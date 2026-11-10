(ns mini-nu.features-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [matcher-combinators.test]
            [mini-nu.db :as db]
            [mini-nu.db-test :refer [fresh-conn k balance funded-account]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest fx-test
  (let [conn (fresh-conn)
        gui (funded-account conn 10000 "USD")
        mom (db/open-account! conn "mom" "BRL" {})]
    (testing "USD -> BRL uses the current rate and stores it on the transfer"
      (is (match? {:ok {:amount 10000 :credited 54000 :fx-rate 5.40M :from-currency "USD" :to-currency "BRL"}}
                  (db/transfer! conn {:from gui :to mom :amount 10000 :idempotency-key (k)})))
      (is (= 0 (balance conn gui)))
      (is (= 54000 (balance conn mom))))
    (testing "a new rate applies to new transfers only"
      (db/set-rate! conn "USD" "BRL" 5.00M {})
      (let [t (db/transfer! conn {:from mom :to gui :amount 5000 :idempotency-key (k)})]
        (is (match? {:ok {:credited 1000 :fx-rate 0.2M}} t)))
      (is (match? {:items [{:fx-rate 0.2M} {:fx-rate 5.40M}]} (db/statement (d/db conn) gui {:limit 2}))))
    (testing "each currency still sums to zero across all accounts, bank included"
      (doseq [cur ["USD" "BRL"]]
        (is (zero? (->> (d/q '[:find ?id :in $ ?c :where [?a :account/currency ?c] [?a :account/id ?id]] (d/db conn) cur)
                        (map (comp #(balance conn %) first))
                        (reduce +))))))))

(deftest reversal-test
  (let [conn (fresh-conn)
        alice (funded-account conn 1000)
        bob (db/open-account! conn "bob")
        t (:id (:ok (db/transfer! conn {:from alice :to bob :amount 300 :idempotency-key (k)})))
        r (db/reverse! conn t)]
    (is (match? {:ok {:kind "reversal" :reverses t :from bob :to alice :amount 300}} r))
    (is (= 1000 (balance conn alice)))
    (is (= 0 (balance conn bob)))
    (is (= r (db/reverse! conn t)) "reversing again returns the same reversal")
    (is (= 1000 (balance conn alice)) "and moves nothing")
    (is (= {:error :cannot-reverse-reversal} (db/reverse! conn (:id (:ok r)))))
    (is (= {:error :unknown-transfer} (db/reverse! conn (random-uuid))))
    (is (match? {:reversed-by (:id (:ok r))} (db/get-transfer (d/db conn) t)))
    (testing "cannot claw back money the recipient already spent"
      (let [t2 (:id (:ok (db/transfer! conn {:from alice :to bob :amount 500 :idempotency-key (k)})))]
        (db/transfer! conn {:from bob :to alice :amount 400 :idempotency-key (k)})
        (is (= {:error :insufficient-funds} (db/reverse! conn t2)))))))

(deftest fx-reversal-test
  (let [conn (fresh-conn)
        gui (funded-account conn 10000 "USD")
        mom (db/open-account! conn "mom" "BRL" {})
        t (:id (:ok (db/transfer! conn {:from gui :to mom :amount 10000 :idempotency-key (k)})))]
    (db/set-rate! conn "USD" "BRL" 6.00M {})
    (db/reverse! conn t)
    (is (= 10000 (balance conn gui)) "a reversal uses the original amounts, not today's rate")
    (is (= 0 (balance conn mom)))))

(deftest schedule-test
  (let [conn (fresh-conn)
        alice (funded-account conn 1000)
        bob (db/open-account! conn "bob")
        start #inst "2026-12-01T09:00:00Z"
        s (:id (:ok (db/create-schedule! conn {:from alice :to bob :amount 100 :every "month" :start-at start} {})))
        run! #(db/run-due-schedules! conn %)]
    (run! #inst "2026-11-30T00:00:00Z")
    (is (= 0 (balance conn bob)) "not due yet")
    (run! #inst "2026-12-01T09:00:00Z")
    (is (= 100 (balance conn bob)))
    (is (match? {:next-run "2027-01-01T09:00:00Z" :last-result "ok"} (db/get-schedule (d/db conn) s)))
    (run! #inst "2026-12-15T00:00:00Z")
    (is (= 100 (balance conn bob)) "running again before the next date pays nothing")
    (testing "a crash between paying and moving the schedule on cannot pay twice"
      (db/transfer! conn {:from alice :to bob :amount 100 :schedule s
                          :idempotency-key (str "schedule:" s ":2027-01-01T09:00:00Z")})
      (run! #inst "2027-01-01T09:00:00Z")
      (is (= 200 (balance conn bob))))
    (is (match? [{:kind "scheduled"} {:kind "scheduled"}] (:items (db/statement (d/db conn) bob {}))))
    (testing "a failed run is recorded and the schedule moves on"
      (db/transfer! conn {:from alice :to bob :amount 800 :idempotency-key (k)})
      (run! #inst "2027-02-01T09:00:00Z")
      (is (match? {:last-result "insufficient-funds" :next-run "2027-03-01T09:00:00Z"} (db/get-schedule (d/db conn) s))))
    (testing "cancelled schedules stop"
      (db/cancel-schedule! conn s {})
      (db/deposit! conn {:to alice :amount 1000 :idempotency-key (k)})
      (run! #inst "2027-03-01T09:00:00Z")
      (is (match? {:active? false :next-run "2027-03-01T09:00:00Z"} (db/get-schedule (d/db conn) s))))
    (is (= {:error :invalid-schedule}
           (db/create-schedule! conn {:from alice :to bob :amount 1 :every "hourly"} {})))))

(deftest audit-test
  (let [conn (fresh-conn)
        alice (funded-account conn 1000)
        bob (db/open-account! conn "bob")
        t (:id (:ok (db/transfer! conn {:from alice :to bob :amount 1 :idempotency-key (k)}
                                  {:audit/actor "gui" :audit/request-id "req-1" :audit/ip "10.0.0.1"})))]
    (is (match? {:audit {:actor "gui" :request-id "req-1" :ip "10.0.0.1" :at string?}}
                (db/get-transfer (d/db conn) t)))))

(deftest statement-paging-test
  (let [conn (fresh-conn)
        alice (db/open-account! conn "alice")
        _ (dotimes [i 5] (db/deposit! conn {:to alice :amount (inc i) :idempotency-key (k)}))
        p1 (db/statement (d/db conn) alice {:limit 2})
        p2 (db/statement (d/db conn) alice {:limit 2 :cursor (parse-long (:next-cursor p1))})
        p3 (db/statement (d/db conn) alice {:limit 2 :cursor (parse-long (:next-cursor p2))})]
    (is (= [5 4] (map :amount (:items p1))))
    (is (= [3 2] (map :amount (:items p2))))
    (is (match? {:items [{:amount 1}] :next-cursor nil} p3))
    (testing "date filters"
      (is (empty? (:items (db/statement (d/db conn) alice {:to #inst "2000-01-01"}))))
      (is (= 5 (count (:items (db/statement (d/db conn) alice {:from #inst "2000-01-01"}))))))))

(deftest persistence-test
  (let [dir (str (Files/createTempDirectory "mini-nu" (make-array FileAttribute 0)))
        conn (db/connect! "persist" dir)
        alice (funded-account conn 700)
        again (db/connect! "persist" dir)]
    (is (= 700 (balance again alice)) "a second connection to the same directory sees the data")
    (is (= 5.40M (db/fx-rate (d/db again) "USD" "BRL")) "and setup does not reset the rate")))
