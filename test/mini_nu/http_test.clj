(ns mini-nu.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jsonista.core :as j]
            [matcher-combinators.matchers]
            [matcher-combinators.test]
            [mini-nu.db :as db]
            [mini-nu.http :as http]))

(defn- call [app method uri & [body headers]]
  (let [[path query] (str/split uri #"\?" 2)
        resp (app {:request-method method :uri path :query-string query :remote-addr "127.0.0.1"
                   :headers (merge {"content-type" "application/json"} headers)
                   :body (when body (java.io.ByteArrayInputStream. (.getBytes (j/write-value-as-string body))))})]
    (cond-> resp
      (some-> (get-in resp [:headers "Content-Type"]) (str/includes? "json"))
      (assoc :json (j/read-value (slurp (:body resp)) j/keyword-keys-object-mapper)))))

(deftest http-test
  (let [app (http/app (db/connect! (str (random-uuid))))
        gui (:id (:json (call app :post "/accounts" {:owner "Gui"})))
        mom (:id (:json (call app :post "/accounts" {:owner "Mom" :currency "BRL"})))]
    (is (= 200 (:status (call app :get "/"))) "serves the demo page")
    (call app :post "/deposits" {:to gui :amount 10000} {"idempotency-key" "d1"})
    (let [t (call app :post "/transfers" {:from gui :to mom :amount 1000} {"idempotency-key" "t1" "x-actor" "demo"})]
      (is (match? {:status 201 :json {:credited 5400 :fx-rate 5.4}} t))
      (is (some? (get-in t [:headers "X-Request-Id"])))
      (is (match? {:status 200 :json {:audit {:actor "demo" :ip "127.0.0.1"
                                              :request-id (get-in t [:headers "X-Request-Id"])}}}
                  (call app :get (str "/transfers/" (get-in t [:json :id])))))
      (is (match? {:status 201 :json {:kind "reversal"}}
                  (call app :post (str "/transfers/" (get-in t [:json :id]) "/reversal")))))
    (is (match? {:status 200 :json [{:owner "Gui" :currency "USD" :balance 10000}
                                    {:owner "Mom" :currency "BRL" :balance 0}]}
                (call app :get "/accounts")))
    (is (match? {:status 200 :json {(keyword "USD/BRL") 5.0 (keyword "BRL/USD") 0.2}}
                (call app :put "/fx-rates" {:from "USD" :to "BRL" :rate 5.0})))
    (is (match? {:status 201 :json {:every "week" :active? true}}
                (call app :post "/schedules" {:from gui :to mom :amount 100 :every "week"})))
    (is (= 400 (:status (call app :get (str "/accounts/" gui "/statement?cursor=abc")))))
    (is (match? {:status 200 :json {:balance 0}} (call app :get (str "/accounts/" gui "/balance?as-of=2000-01-01T00:00:00Z")))
        "a past balance")
    (is (match? {:status 200 :json {:balance 10000}} (call app :get (str "/accounts/" gui "/balance?as-of=2999-01-01T00:00:00Z")))
        "a time after the latest transaction answers with today's balance")
    (is (= 400 (:status (app {:request-method :post :uri "/transfers" :headers {"content-type" "application/json" "idempotency-key" "x"}
                              :body (java.io.ByteArrayInputStream. (.getBytes "{oops"))})))
        "malformed JSON is a client error, not a crash")
    (is (match? {:status 200 :json {:transfer-outcomes {:ok #(>= % 3)} ; counters are process-wide
                                    :ledger {:accounts 2 :active-schedules 1}
                                    :latency-ms {:p50 number?}}}
                (call app :get "/metrics")))))

(deftest schema-test
  (let [app (http/app (db/connect! (str (random-uuid))))
        gui (:id (:json (call app :post "/accounts" {:owner "Gui"})))]
    (is (match? {:status 400 :json {:error "malformed-request" :details {:to string? :amount string?}}}
                (call app :post "/transfers" {:from gui :to "not-a-uuid" :amount 1.5} {"idempotency-key" "k"}))
        "the schema names each bad field")
    (is (match? {:status 400 :json {:details {:currency string?}}} (call app :post "/accounts" {:owner "x" :currency "EUR"})))
    (is (= 400 (:status (call app :post "/schedules" {:from gui :to gui :amount 1 :every "hourly"}))))
    (is (= 400 (:status (call app :get "/accounts/123/balance"))) "ids in the path are checked too")
    (is (match? {:status 422 :json {:error "invalid-amount"}}
                (call app :post "/deposits" {:to gui :amount -5} {"idempotency-key" "k2"}))
        "well-formed but against the rules is a 422")))

(deftest cards-and-limits-test
  (let [app (http/app (db/connect! (str (random-uuid))))
        gui (:id (:json (call app :post "/accounts" {:owner "Gui"})))
        _ (call app :post "/deposits" {:to gui :amount 100000} {"idempotency-key" "d1"})
        card (call app :post (str "/accounts/" gui "/card") {:credit-limit 50000 :closing-day 10})
        card-id (get-in card [:json :id])]
    (is (match? {:status 201 :json {:kind "card" :credit-limit 50000 :balance 0}} card))
    (is (= 409 (:status (call app :post (str "/accounts/" gui "/card") {:credit-limit 1 :closing-day 10}))))
    (is (match? {:status 201 :json {:kind "purchase" :memo "Starbucks"}}
                (call app :post (str "/cards/" card-id "/purchases") {:amount 650 :merchant "Starbucks"} {"idempotency-key" "p1"})))
    (is (= 400 (:status (call app :post (str "/cards/" card-id "/purchases") {:amount 650 :merchant "x"}))) "needs a key")
    (is (match? {:status 201 :json {:kind "card-payment"}}
                (call app :post (str "/cards/" card-id "/payments") {:amount 650} {"idempotency-key" "pay1"})))
    (is (match? {:status 200 :json {:owed 0 :available 50000 :current {:spent 650}}} (call app :get (str "/cards/" card-id "/bill"))))
    (is (= 404 (:status (call app :get (str "/cards/" gui "/bill")))) "a checking account has no bill")
    (is (match? {:status 200 :json {:period #(#{"day" "night"} %) :used 650 :remaining number?}}
                (call app :get (str "/accounts/" gui "/limit"))))))


(deftest api-docs-test
  (let [app (http/app (db/connect! (str (random-uuid))))
        spec (call app :get "/swagger.json")]
    (is (= 200 (:status spec)))
    (is (match? {:paths {(keyword "/transfers") {:post {:summary string?
                                                        :parameters (matcher-combinators.matchers/embeds
                                                                     [{:in "header" :name "idempotency-key" :required true}])}}}}
                (:json spec))
        "the docs come from the same schemas that validate requests")
    (is (= 200 (:status (call app :get "/api-docs/index.html"))))))
