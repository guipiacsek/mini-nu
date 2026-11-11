(ns mini-nu.system-test
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [mini-nu.system :as system]))

(deftest starts-and-stops
  (let [sys (component/start (system/new-system {:port 0 :storage :mem :scheduler-ms 50}))
        url (str "http://localhost:" (system/port sys) "/accounts")]
    (try
      (is (= "[]" (slurp url)) "serves requests once started")
      (finally (component/stop sys)))
    (is (thrown? java.net.ConnectException (slurp url)) "and releases the port when stopped")))
