(ns mini-nu.http
  (:require [datomic.client.api :as d]
            [mini-nu.db :as db]
            [mini-nu.metrics :as metrics]
            [muuntaja.core :as m]
            [reitit.coercion.schema :as schema-coercion]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.util.response :as response]
            [schema.core :as s])
  (:import (java.util Date)))

(def ^:private error-status
  {:invalid-amount 422
   :same-account 422
   :insufficient-funds 422
   :over-limit 422
   :card-not-allowed 422
   :no-fx-rate 422
   :cannot-reverse-reversal 422
   :invalid-schedule 422
   :invalid-closing-day 422
   :unknown-account 404
   :unknown-card 404
   :unknown-transfer 404
   :unknown-schedule 404
   :idempotency-conflict 409
   :card-exists 409})

;; Schemas check the shape of a request (a 400 when wrong). Business rules, like
;; "amount must be positive", stay in the ledger and answer 422.
(def ^:private Currency (s/enum "USD" "BRL"))
(def ^:private IdPath {:id s/Uuid})
;; money moves are retried safely by sending the same key again
(def ^:private KeyHeader {:idempotency-key s/Str})

(defn- result->response [{:keys [ok error]} status]
  (if error
    {:status (error-status error 400) :body {:error error}}
    {:status status :body ok}))

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
      (when-not (#{"/" "/metrics" "/swagger.json" nil} route)
        (metrics/record-request! route (:status resp) (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)))
      resp)))

(defn- malformed [e _]
  (let [errors (:errors (ex-data e))]
    {:status 400 :body {:error :malformed-request
                        :details (if (map? errors) (update-vals errors pr-str) (pr-str errors))}}))

(defn- money-move
  "Writes that move money; the schema already made sure the Idempotency-Key header is there."
  [move! {{{k :idempotency-key} :header} :parameters audit :audit} req]
  (let [result (move! (assoc req :idempotency-key k) audit)]
    (metrics/record-outcome! (if (:ok result) :ok (:error result)))
    (result->response result 201)))

(defn- with-account [conn id f]
  (let [db (d/db conn)]
    (if (db/account? db id)
      (f db)
      {:status 404 :body {:error :unknown-account}})))

(defn app [conn]
  (ring/ring-handler
   (ring/router
    [["/"
      {:no-doc true
       :get (fn [_] (-> (response/resource-response "public/index.html")
                        (response/content-type "text/html; charset=utf-8")))}]
     ["/accounts"
      {:swagger {:tags ["accounts"]}
       :get {:summary "List accounts and cards" :handler (fn [_] {:status 200 :body (db/accounts (d/db conn))})}
       :post {:summary "Open a checking account" :parameters {:body {:owner s/Str (s/optional-key :currency) Currency}}
              :handler (fn [{{{:keys [owner currency] :or {currency "USD"}} :body} :parameters audit :audit}]
                         {:status 201 :body {:id (db/open-account! conn owner currency audit)}})}}]
     ["/accounts/:id/balance"
      {:swagger {:tags ["accounts"]}
       :get {:summary "Balance now, or at any past instant" :parameters {:path IdPath :query {(s/optional-key :as-of) s/Inst}}
             :handler (fn [{{{:keys [id]} :path {:keys [as-of]} :query} :parameters}]
                        (with-account conn id
                          (fn [db] {:status 200 :body {:account id :balance (db/balance (cond-> db as-of (db/as-of as-of)) id)}})))}}]
     ["/accounts/:id/statement"
      {:swagger {:tags ["accounts"]}
       :get {:summary "Entries, newest first; pass next-cursor for more" :parameters {:path IdPath
                          :query {(s/optional-key :from) s/Inst (s/optional-key :to) s/Inst
                                  (s/optional-key :limit) s/Int (s/optional-key :cursor) s/Int}}
             :handler (fn [{{{:keys [id]} :path q :query} :parameters}]
                        (with-account conn id
                          (fn [db] {:status 200 :body (db/statement db id (update q :limit #(some-> % (max 1) (min 200))))})))}}]
     ["/accounts/:id/schedules"
      {:swagger {:tags ["schedules"]}
       :get {:summary "Schedules from or to this account" :parameters {:path IdPath}
             :handler (fn [{{{:keys [id]} :path} :parameters}]
                        (with-account conn id (fn [db] {:status 200 :body (db/schedules db id)})))}}]
     ["/accounts/:id/limit"
      {:swagger {:tags ["accounts"]}
       :get {:summary "The day or night limit in force right now" :parameters {:path IdPath}
             :handler (fn [{{{:keys [id]} :path} :parameters}]
                        (if-let [l (db/limit (d/db conn) id (Date.))]
                          {:status 200 :body l}
                          {:status 404 :body {:error :unknown-account}}))}}]
     ["/accounts/:id/card"
      {:swagger {:tags ["cards"]}
       :post {:summary "Open a credit card paid from this account" :parameters {:path IdPath :body {:credit-limit s/Int :closing-day s/Int}}
              :handler (fn [{{{:keys [id]} :path body :body} :parameters audit :audit}]
                         (result->response (db/open-card! conn id body audit) 201))}}]
     ["/cards/:id/bill"
      {:swagger {:tags ["cards"]}
       :get {:summary "Owed, available credit, last bill and this cycle" :parameters {:path IdPath}
             :handler (fn [{{{:keys [id]} :path} :parameters}]
                        (if-let [b (db/bill (d/db conn) id (Date.))]
                          {:status 200 :body b}
                          {:status 404 :body {:error :unknown-card}}))}}]
     ["/cards/:id/purchases"
      {:swagger {:tags ["cards"]}
       :post {:summary "Charge the card" :parameters {:path IdPath :header KeyHeader :body {:amount s/Int :merchant s/Str}}
              :handler (fn [{{{:keys [id]} :path body :body} :parameters :as req}]
                         (money-move #(db/purchase! conn %1 %2) req (assoc body :card-id id)))}}]
     ["/cards/:id/payments"
      {:swagger {:tags ["cards"]}
       :post {:summary "Pay the card from its checking account" :parameters {:path IdPath :header KeyHeader :body {:amount s/Int}}
              :handler (fn [{{{:keys [id]} :path body :body} :parameters :as req}]
                         (money-move #(db/pay-bill! conn %1 %2) req (assoc body :card-id id)))}}]
     ["/deposits"
      {:swagger {:tags ["transfers"]}
       :post {:summary "Add money from the bank" :parameters {:header KeyHeader :body {:to s/Uuid :amount s/Int}}
              :handler (fn [{{:keys [body]} :parameters :as req}]
                         (money-move #(db/deposit! conn %1 %2) req body))}}]
     ["/transfers"
      {:swagger {:tags ["transfers"]}
       :post {:summary "Send money, converting currency if needed" :parameters {:header KeyHeader :body {:from s/Uuid :to s/Uuid :amount s/Int}}
              :handler (fn [{{:keys [body]} :parameters :as req}]
                         (money-move #(db/transfer! conn %1 %2) req body))}}]
     ["/transfers/:id"
      {:swagger {:tags ["transfers"]}
       :get {:summary "A transfer and its audit record" :parameters {:path IdPath}
             :handler (fn [{{{:keys [id]} :path} :parameters}]
                        (if-let [t (db/get-transfer (d/db conn) id)]
                          {:status 200 :body t}
                          {:status 404 :body {:error :unknown-transfer}}))}}]
     ["/transfers/:id/reversal"
      {:swagger {:tags ["transfers"]}
       :post {:summary "Undo a transfer by adding its opposite (once)" :parameters {:path IdPath}
              :handler (fn [{{{:keys [id]} :path} :parameters audit :audit}]
                         (let [result (db/reverse! conn id audit)]
                           (metrics/record-outcome! (if (:ok result) :ok (:error result)))
                           (result->response result 201)))}}]
     ["/fx-rates"
      {:swagger {:tags ["fx"]}
       :get {:summary "Current exchange rates" :handler (fn [_] {:status 200 :body (db/fx-rates (d/db conn))})}
       :put {:summary "Set a rate and its inverse" :parameters {:body {:from Currency :to Currency :rate (s/constrained s/Num pos? 'positive)}}
             :handler (fn [{{{:keys [from to rate]} :body} :parameters audit :audit}]
                        (if (= from to)
                          {:status 400 :body {:error "from and to must be different currencies"}}
                          (do (db/set-rate! conn from to (BigDecimal. (str rate)) audit)
                              {:status 200 :body (db/fx-rates (d/db conn))})))}}]
     ["/schedules"
      {:swagger {:tags ["schedules"]}
       :post {:summary "Repeat a transfer every minute, day, week or month" :parameters {:body {:from s/Uuid :to s/Uuid :amount s/Int
                                  :every (apply s/enum db/schedule-frequencies)
                                  (s/optional-key :start-at) s/Inst}}
              :handler (fn [{{:keys [body]} :parameters audit :audit}]
                         (result->response (db/create-schedule! conn body audit) 201))}}]
     ["/schedules/:id"
      {:swagger {:tags ["schedules"]}
       :delete {:summary "Cancel a schedule" :parameters {:path IdPath}
                :handler (fn [{{{:keys [id]} :path} :parameters audit :audit}]
                           (result->response (db/cancel-schedule! conn id audit) 200))}}]
     ["/metrics"
      {:swagger {:tags ["ops"]}
       :get {:summary "Request counts, outcomes, latency, ledger totals"
             :handler (fn [_] {:status 200 :body (assoc (metrics/snapshot) :ledger (db/stats (d/db conn)))})}}]
     ["/swagger.json"
      {:get {:no-doc true
             :swagger {:info {:title "mini-nu"
                              :description "A double-entry bank ledger. Amounts are integer cents in the sender's currency. Money moves need an Idempotency-Key header."}}
             :handler (swagger/create-swagger-handler)}}]]
    {:data {:muuntaja m/instance
            :coercion schema-coercion/coercion
            :middleware [swagger/swagger-feature
                         wrap-metrics
                         wrap-audit
                         parameters/parameters-middleware
                         muuntaja/format-negotiate-middleware
                         muuntaja/format-response-middleware
                         ;; malformed JSON and requests that fail their schema answer 400, not 500
                         (exception/create-exception-middleware
                          (assoc exception/default-handlers :reitit.coercion/request-coercion malformed))
                         muuntaja/format-request-middleware
                         coercion/coerce-request-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler {:path "/api-docs" :url "/swagger.json"})
    (ring/create-default-handler))))
