(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot/pod version
  :aot :all
  :description  "Boot pod module–this is included with all pods."
  :url          "http://github.com/boot-clj/boot"
  :scm          {:url "https://github.com/boot-clj/boot.git" :dir "../../"}
  :repositories [["clojars"  {:url "https://clojars.org/repo" :creds :gpg :sign-releases false}]]
  :license      {:name "Eclipse Public License"
                 :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[boot/base                               ~version :scope "provided"]
                 [org.clojure/clojure                     "1.6.0"  :scope "provided"]
                 [org.tcrawley/dynapath                   "0.2.3"  :scope "compile"]
                 [org.projectodd.shimdandy/shimdandy-impl "1.0.1"  :scope "compile"]])
