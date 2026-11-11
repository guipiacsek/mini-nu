(ns mini-nu.system
  "Wires the pieces with Component: each one starts after what it depends on
  and stops in reverse order."
  (:require [com.stuartsierra.component :as component]
            [mini-nu.db :as db]
            [mini-nu.http :as http]
            [ring.adapter.jetty :as jetty])
  (:import (java.util Date)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (org.eclipse.jetty.server Server ServerConnector))
  (:gen-class))

(defrecord Database [storage conn]
  component/Lifecycle
  (start [this] (assoc this :conn (db/connect! "mini-nu" storage)))
  ;; Datomic Local has nothing to close
  (stop [this] (assoc this :conn nil)))

(defrecord Scheduler [every-ms database ^ScheduledExecutorService executor]
  component/Lifecycle
  (start [this]
    (let [ex (Executors/newSingleThreadScheduledExecutor)]
      (.scheduleWithFixedDelay ex
                               #(try (db/run-due-schedules! (:conn database) (Date.))
                                     (catch Exception e (println "scheduler:" (ex-message e))))
                               every-ms every-ms TimeUnit/MILLISECONDS)
      (assoc this :executor ex)))
  (stop [this]
    (some-> executor .shutdownNow)
    (assoc this :executor nil)))

(defrecord WebServer [port database ^Server server]
  component/Lifecycle
  (start [this]
    (assoc this :server (jetty/run-jetty (http/app (:conn database)) {:port port :join? false})))
  (stop [this]
    (some-> server .stop)
    (assoc this :server nil)))

(defn port
  "The port the server actually bound, useful when started with port 0."
  [system]
  (.getLocalPort ^ServerConnector (first (.getConnectors ^Server (get-in system [:web :server])))))

(defn new-system [{:keys [port storage scheduler-ms] :or {scheduler-ms 5000}}]
  (component/system-map
   :database (map->Database {:storage storage})
   :scheduler (component/using (map->Scheduler {:every-ms scheduler-ms}) [:database])
   :web (component/using (map->WebServer {:port port}) [:database])))

(defn -main [& _]
  (let [port (parse-long (or (System/getenv "PORT") "3000"))
        storage (let [s (or (System/getenv "MINI_NU_DATA") "data")] (if (= "mem" s) :mem s))
        system (component/start (new-system {:port port :storage storage}))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(component/stop system)))
    (println (str "mini-nu listening on http://localhost:" port
                  (if (= :mem storage) " (in memory)" (str " (data in " storage "/)"))))
    (.join ^Server (get-in system [:web :server]))))
