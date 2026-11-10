(ns mini-nu.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jsonista.core :as j]
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
    (is (= 400 (:status (app {:request-method :post :uri "/transfers" :headers {"content-type" "application/json" "idempotency-key" "x"}
                              :body (java.io.ByteArrayInputStream. (.getBytes "{oops"))})))
        "malformed JSON is a client error, not a crash")
    (is (match? {:status 200 :json {:transfer-outcomes {:ok 3}
                                    :ledger {:accounts 2 :active-schedules 1}
                                    :latency-ms {:p50 number?}}}
                (call app :get "/metrics")))))
