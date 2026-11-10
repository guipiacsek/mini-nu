(ns mini-nu.http
  (:require [datomic.client.api :as d]
            [mini-nu.db :as db]
            [mini-nu.metrics :as metrics]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response])
  (:import (java.time Instant)
           (java.util Date))
  (:gen-class))

(def ^:private error-status
  {:invalid-amount 422
   :same-account 422
   :insufficient-funds 422
   :no-fx-rate 422
   :cannot-reverse-reversal 422
   :invalid-schedule 422
   :unknown-account 404
   :unknown-transfer 404
   :unknown-schedule 404
   :idempotency-conflict 409})

(defn- ->uuid [s] (when (string? s) (parse-uuid s)))
(defn- ->amount [n] (when (int? n) (long n)))
(defn- ->long [s] (when (string? s) (parse-long s)))
(defn- ->date [s] (when (string? s) (try (Date/from (Instant/parse s)) (catch Exception _ nil))))
(defn- ->rate [n] (when (number? n) (let [r (BigDecimal. (str n))] (when (pos? r) r))))
(defn- bad-request [msg] {:status 400 :body {:error msg}})

(defn- result->response [{:keys [ok error]} status]
  (if error
    {:status (error-status error 400) :body {:error error}}
    {:status status :body ok}))

(defn- db-as-of
  "Datomic refuses a time after its latest transaction; the present db is the answer then."
  [db date]
  (try (d/as-of db date)
       (catch clojure.lang.ExceptionInfo e
         (if (= :cognitect.anomalies/unavailable (:cognitect.anomalies/category (ex-data e))) db (throw e)))))

(defn- wrap-audit
  "Gives every request an id (echoed in X-Request-Id) and the audit record that writes carry."
  [handler]
  (fn [req]
    (let [rid (or (get-in req [:headers "x-request-id"]) (str (random-uuid)))
          audit {:audit/request-id rid
                 :audit/actor (get-in req [:headers "x-actor"] "anonymous")
                 :audit/ip (:remote-addr req)}]
      (some-> (handler (assoc req :audit audit))
              (response/header "X-Request-Id" rid)))))

(defn- wrap-metrics [handler]
  (fn [req]
    (let [t0 (System/nanoTime)
          resp (handler req)
          route (:template (ring/get-match req))]
      (when-not (#{"/" "/metrics"} route)
        (metrics/record-request! route (:status resp) (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)))
      resp)))

(defn- money-move [conn move! {:keys [body-params headers audit]} req]
  (let [k (get headers "idempotency-key")]
    (cond
      (nil? k) (bad-request "Idempotency-Key header is required")
      (some nil? (vals req)) (bad-request "missing or malformed field")
      :else (let [result (move! conn (assoc req :idempotency-key k :amount (->amount (:amount body-params))) audit)]
              (metrics/record-outcome! (if (:ok result) :ok (:error result)))
              (result->response result 201)))))

(defn- with-account [conn {{:keys [id]} :path-params} f]
  (let [id (->uuid id)
        db (d/db conn)]
    (if (and id (db/account? db id))
      (f db id)
      {:status 404 :body {:error :unknown-account}})))

(defn- statement-handler [conn {{:strs [from to limit cursor]} :query-params :as req}]
  (with-account conn req
    (fn [db id]
      (let [opts {:from (->date from) :to (->date to) :cursor (->long cursor)
                  :limit (some-> (->long limit) (max 1) (min 200))}]
        (if (or (and from (nil? (:from opts))) (and to (nil? (:to opts))) (and cursor (nil? (:cursor opts))))
          (bad-request "from/to must be ISO instants and cursor a :next-cursor value")
          {:status 200 :body (db/statement db id opts)})))))

(defn app [conn]
  (ring/ring-handler
   (ring/router
    [["/"
      {:get (fn [_] (-> (response/resource-response "public/index.html")
                        (response/content-type "text/html; charset=utf-8")))}]
     ["/accounts"
      {:get (fn [_] {:status 200 :body (db/accounts (d/db conn))})
       :post (fn [{{:keys [owner currency] :or {currency "USD"}} :body-params audit :audit}]
               (if (and (string? owner) (db/currencies currency))
                 {:status 201 :body {:id (db/open-account! conn owner currency audit)}}
                 (bad-request "owner is required and currency must be USD or BRL")))}]
     ["/accounts/:id/balance"
      {:get (fn [{{:strs [as-of]} :query-params :as req}]
              (with-account conn req
                (fn [db id]
                  (if-let [db (if as-of (some->> (->date as-of) (db-as-of db)) db)]
                    {:status 200 :body {:account id :balance (db/balance db id)}}
                    (bad-request "as-of must be an ISO instant, e.g. 2026-12-01T00:00:00Z")))))}]
     ["/accounts/:id/statement" {:get #(statement-handler conn %)}]
     ["/accounts/:id/schedules"
      {:get (fn [req] (with-account conn req (fn [db id] {:status 200 :body (db/schedules db id)})))}]
     ["/deposits"
      {:post (fn [{p :body-params :as req}]
               (money-move conn db/deposit! req {:to (->uuid (:to p))}))}]
     ["/transfers"
      {:post (fn [{p :body-params :as req}]
               (money-move conn db/transfer! req {:from (->uuid (:from p)) :to (->uuid (:to p))}))}]
     ["/transfers/:id"
      {:get (fn [{{:keys [id]} :path-params}]
              (if-let [t (some->> (->uuid id) (db/get-transfer (d/db conn)))]
                {:status 200 :body t}
                {:status 404 :body {:error :unknown-transfer}}))}]
     ["/transfers/:id/reversal"
      {:post (fn [{{:keys [id]} :path-params audit :audit}]
               (if-let [id (->uuid id)]
                 (let [result (db/reverse! conn id audit)]
                   (metrics/record-outcome! (if (:ok result) :ok (:error result)))
                   (result->response result 201))
                 (bad-request "transfer id must be a UUID")))}]
     ["/fx-rates"
      {:get (fn [_] {:status 200 :body (db/fx-rates (d/db conn))})
       :put (fn [{{:keys [from to rate]} :body-params audit :audit}]
              (if-let [rate (and (db/currencies from) (db/currencies to) (not= from to) (->rate rate))]
                (do (db/set-rate! conn from to rate audit)
                    {:status 200 :body (db/fx-rates (d/db conn))})
                (bad-request "from/to must be different currencies (USD, BRL) and rate a positive number")))}]
     ["/schedules"
      {:post (fn [{p :body-params audit :audit}]
               (if (and (:start-at p) (nil? (->date (:start-at p))))
                 (bad-request "start-at must be an ISO instant")
                 (result->response (db/create-schedule! conn {:from (->uuid (:from p)) :to (->uuid (:to p))
                                                              :amount (->amount (:amount p)) :every (:every p)
                                                              :start-at (->date (:start-at p))}
                                                        audit)
                                   201)))}]
     ["/schedules/:id"
      {:delete (fn [{{:keys [id]} :path-params audit :audit}]
                 (if-let [id (->uuid id)]
                   (result->response (db/cancel-schedule! conn id audit) 200)
                   (bad-request "schedule id must be a UUID")))}]
     ["/metrics"
      {:get (fn [_] {:status 200 :body (assoc (metrics/snapshot) :ledger (db/stats (d/db conn)))})}]]
    {:data {:muuntaja m/instance
            :middleware [wrap-metrics
                         wrap-audit
                         parameters/parameters-middleware
                         muuntaja/format-middleware]}})
   (ring/create-default-handler)))

(defn- start-scheduler!
  "Checks for due scheduled transfers every few seconds."
  [conn]
  (doto (Thread. (fn []
                   (loop []
                     (Thread/sleep 5000)
                     (try (db/run-due-schedules! conn (Date.))
                          (catch Exception e (println "scheduler:" (ex-message e))))
                     (recur))))
    (.setDaemon true)
    (.start)))

(defn -main [& _]
  (let [port (parse-long (or (System/getenv "PORT") "3000"))
        storage (let [s (or (System/getenv "MINI_NU_DATA") "data")] (if (= "mem" s) :mem s))
        conn (db/connect! "mini-nu" storage)
        server (jetty/run-jetty (app conn) {:port port :join? false})]
    (start-scheduler! conn)
    (println (str "mini-nu listening on http://localhost:" port
                  (if (= :mem storage) " (in memory)" (str " (data in " storage "/)"))))
    (.join server)))
