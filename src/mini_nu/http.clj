(ns mini-nu.http
  (:require [datomic.client.api :as d]
            [mini-nu.db :as db]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty])
  (:import (java.time Instant)
           (java.util Date)))

(def ^:private error-status
  {:invalid-amount 422
   :same-account 422
   :insufficient-funds 422
   :unknown-account 404
   :idempotency-conflict 409})

(defn- ->uuid [s] (when (string? s) (parse-uuid s)))
(defn- ->amount [n] (when (int? n) (long n)))
(defn- ->date [s] (try (Date/from (Instant/parse s)) (catch Exception _ nil)))
(defn- bad-request [msg] {:status 400 :body {:error msg}})

(defn- db-as-of
  "Datomic refuses a time after its latest transaction; the present db is the answer then."
  [db date]
  (try (d/as-of db date)
       (catch clojure.lang.ExceptionInfo e
         (if (= :cognitect.anomalies/unavailable (:cognitect.anomalies/category (ex-data e))) db (throw e)))))

(defn- money-move [conn move! {:keys [body-params headers]} req]
  (let [k (get headers "idempotency-key")]
    (cond
      (nil? k) (bad-request "Idempotency-Key header is required")
      (some nil? (vals req)) (bad-request "missing or malformed field")
      :else (let [{:keys [ok error]} (move! conn (assoc req :idempotency-key k :amount (->amount (:amount body-params))))]
              (if error
                {:status (error-status error 400) :body {:error error}}
                {:status 201 :body ok})))))

(defn- with-account [conn {{:keys [id]} :path-params} f]
  (let [id (->uuid id)
        db (d/db conn)]
    (if (and id (db/account? db id))
      (f db id)
      {:status 404 :body {:error :unknown-account}})))

(defn app [conn]
  (ring/ring-handler
   (ring/router
    [["/accounts"
      {:post (fn [{{:keys [owner]} :body-params}]
               (if (string? owner)
                 {:status 201 :body {:id (db/open-account! conn owner)}}
                 (bad-request "owner is required")))}]
     ["/accounts/:id/balance"
      {:get (fn [{{:strs [as-of]} :query-params :as req}]
              (with-account conn req
                (fn [db id]
                  (if-let [db (if as-of (some->> (->date as-of) (db-as-of db)) db)]
                    {:status 200 :body {:account id :balance (db/balance db id)}}
                    (bad-request "as-of must be an ISO instant, e.g. 2026-12-01T00:00:00Z")))))}]
     ["/accounts/:id/statement"
      {:get (fn [req]
              (with-account conn req
                (fn [db id] {:status 200 :body (db/statement db id)})))}]
     ["/deposits"
      {:post (fn [{p :body-params :as req}]
               (money-move conn db/deposit! req {:to (->uuid (:to p))}))}]
     ["/transfers"
      {:post (fn [{p :body-params :as req}]
               (money-move conn db/transfer! req {:from (->uuid (:from p)) :to (->uuid (:to p))}))}]]
    {:data {:muuntaja m/instance
            :middleware [parameters/parameters-middleware
                         muuntaja/format-middleware]}})
   (ring/create-default-handler)))

(defn -main [& _]
  (println "mini-nu listening on http://localhost:3000")
  (jetty/run-jetty (app (db/connect! "mini-nu")) {:port 3000 :join? true}))
