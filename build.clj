(ns build
  "clojure -T:build uber  ->  target/mini-nu.jar, runnable with java -jar"
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/mini-nu.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis @basis :ns-compile '[mini-nu.http] :class-dir class-dir})
  (b/uber {:class-dir class-dir :uber-file uber-file :basis @basis :main 'mini-nu.http})
  (println "Built" uber-file))
