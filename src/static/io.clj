(ns static.io
  (:use [clojure.tools logging]
        [clojure.java.shell :only [sh]]
        [cssgen]
        [hiccup core]
        [stringtemplate-clj core]
        [clojure.core.memoize :only [memo]])
  (:use static.config :reload-all)
  (:import (org.pegdown PegDownProcessor)
           (java.io File)
           (java.io InputStreamReader OutputStreamWriter)
           (org.apache.commons.io FileUtils FilenameUtils)))

(defn- split-file [content]
  (let [idx (.indexOf content "---" 4)]
    [(.substring content 4 idx) (.substring content (+ 3 idx))]))

(defn- prepare-metadata [metadata]
  (reduce (fn [h [_ k v]]
            (let [key (keyword (.toLowerCase k))]
              (if (not (h key))
                (assoc h key v)
                h)))
          {} (re-seq #"([^:#\+]+): (.+)(\n|$)" metadata)))

(defn- read-markdown [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (:encoding (config))))]
    [(prepare-metadata metadata)
     (delay (.markdownToHtml
              (PegDownProcessor.
                (bit-or org.pegdown.Extensions/FENCED_CODE_BLOCKS org.pegdown.Extensions/TABLES)
                (long 300000.0))
              content))]))

(defn- read-html [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (:encoding (config))))]
    [(prepare-metadata metadata) (delay content)]))

(defn- read-org [file]
  (if (not (:emacs (config)))
    (do (error "Path to Emacs is required for org files.")
        (System/exit 0)))
  (let [metadata (prepare-metadata
                  (apply str
                         (take 500 (slurp file :encoding (:encoding (config))))))
        content (delay
                 (:out (sh (:emacs (config))
                           "-batch" "-eval"
                           (str
                            "(progn "
                            (apply str (map second (:emacs-eval (config))))
                            " (find-file \"" (.getAbsolutePath file) "\") "
                            (:org-export-command (config))
                            ")"))))]
    [metadata content]))

(defn- read-clj [file]
  (let [[metadata & content] (read-string
                              (str \( (slurp file :encoding (:encoding (config))) \)))]
    [metadata (delay (binding [*ns* (the-ns 'static.core)]
                       (->> content 
                            (map eval)
                            last 
                            html)))]))

(defn- read-cssgen [file]
  (let [metadata {:extension "css" :template :none}
        content (read-string
                 (slurp file :encoding (:encoding (config))))
        to-css  #(clojure.string/join "\n" (doall (map css %)))]
    [metadata (delay (binding [*ns* (the-ns 'static.core)] (-> content eval to-css)))]))

(defn read-doc [f]
  (let [extension (FilenameUtils/getExtension (str f))]
    (cond (or (= extension "markdown") (= extension "md"))
          (read-markdown f)
          (= extension "md") (read-markdown f)
          (= extension "org") (read-org f)
          (= extension "html") (read-html f)
          (= extension "clj") (read-clj f)
          (= extension "cssgen") (read-cssgen f)
          :default (throw (Exception. "Unknown Extension.")))))

(defn dir-path [dir]
  (cond (= dir :templates) (str (:in-dir (config)) "templates/")
        (= dir :public) (str (:in-dir (config)) "public/")
        (= dir :site) (str (:in-dir (config)) "site/")
        (= dir :posts) (str (:in-dir (config)) "posts/")
        :default (throw (Exception. "Unknown Directory."))))

(defn list-files [d]
  (let [d (File. (dir-path d))]
    (if (.isDirectory d)
      (sort
       (FileUtils/listFiles d (into-array ["markdown"
                                           "md"
                                           "clj"
                                           "cssgen"
                                           "org"
                                           "html"]) true)) [] )))

(def read-template
  (memo
   (fn [template]
     (let [extension (FilenameUtils/getExtension (str template))]
       (cond (= extension "clj")
             [:clj
              (-> (str (dir-path :templates) template)
                  (File.)
                  (#(str \( (slurp % :encoding (:encoding (config))) \) ))
                  read-string)]
             :default
             [:html
              (load-template (dir-path :templates) template)])))))

(defn write-out-dir [file str]
  (FileUtils/writeStringToFile
   (File. (:out-dir (config)) file) str (:encoding (config))))

(defn deploy-rsync [rsync out-dir host user deploy-dir]
  (let [cmd [rsync "-avz" "--delete" "--checksum" "-e" "ssh"
             out-dir (str user "@" host ":" deploy-dir)]]
    (info (:out (apply sh cmd)))))
