(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/worker version
  :aot :all
  :description  "Boot worker module–this is the worker pod for built-in tasks."
  :url          "http://github.com/boot-clj/boot"
  :scm          {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories [["clojars"  {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]]
  :license      {:name "Eclipse Public License"
                 :url "http://www.eclipse.org/legal/epl-v10.html"}
  :java-source-paths ["third_party/barbarywatchservice/src"]
  :javac-options ["-target" "1.7" "-source" "1.7"]
  :dependencies [[org.clojure/clojure         "1.6.0"  :scope "provided"]
                 [boot/base                   ~version :scope "provided"]
                 [boot/aether                 ~version :scope "compile"]
                 [reply                       "0.3.5"  :scope "compile"]
                 [cheshire                    "5.3.1"  :scope "compile"]
                 [clj-jgit                    "0.8.0"  :scope "compile"]
                 [clj-yaml                    "0.4.0"  :scope "compile"]
                 [javazoom/jlayer             "1.0.1"  :scope "compile"]
                 [mvxcvi/clj-pgp              "0.5.4"  :scope "compile"]
                 [net.java.dev.jna/jna        "4.1.0"  :scope "compile"]
                 [alandipert/desiderata       "1.0.2"  :scope "compile"]
                 [org.clojure/data.xml        "0.0.7"  :scope "compile"]
                 [org.clojure/data.zip        "0.1.1"  :scope "compile"]
                 [org.clojure/tools.namespace "0.2.7"  :scope "compile"]])
