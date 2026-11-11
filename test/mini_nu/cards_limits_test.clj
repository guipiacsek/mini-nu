(ns mini-nu.cards-limits-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [matcher-combinators.test]
            [mini-nu.db :as db]
            [mini-nu.db-test :refer [fresh-conn k balance funded-account]]
            [mini-nu.ledger :as ledger])
  (:import (java.util Date)))

(deftest time-of-day-limit-test
  ;; whichever period it is right now, the limit in force is the one enforced
  (let [conn (fresh-conn)
        {:keys [period limit]} (ledger/limit-period "BRL" (Date.))
        mom (funded-account conn (* 3 limit) "BRL")
        dad (db/open-account! conn "dad" "BRL" {})
        half (quot limit 2)]
    (is (match? {:period period :limit limit :used 0 :remaining limit} (db/limit (d/db conn) mom (Date.))))
    (is (:ok (db/transfer! conn {:from mom :to dad :amount half :idempotency-key "first"})))
    (is (:ok (db/transfer! conn {:from mom :to dad :amount half :idempotency-key (k)})))
    (is (= {:error :over-limit} (db/transfer! conn {:from mom :to dad :amount 1 :idempotency-key (k)}))
        "the period's total is used up, even though the balance would cover it")
    (is (match? {:used limit :remaining 0} (db/limit (d/db conn) mom (Date.))))
    (testing "deposits come from the bank and are never limited"
      (is (:ok (db/deposit! conn {:to mom :amount (* 2 limit) :idempotency-key (k)}))))
    (testing "reversing a transfer moves money back without using the recipient's limit"
      (let [t (:id (:ok (db/transfer! conn {:from dad :to mom :amount half :idempotency-key (k)})))]
        (is (:ok (db/reverse! conn t)))
        (is (match? {:used half} (db/limit (d/db conn) dad (Date.))))))
    (testing "a retry of a transfer made before the limit was reached still returns it"
      (is (match? {:ok {:amount half}} (db/transfer! conn {:from mom :to dad :amount half :idempotency-key "first"}))))
    (testing "concurrent transfers can't jointly exceed the limit"
      (let [conn (fresh-conn)
            a (funded-account conn (* 2 limit) "BRL")
            b (db/open-account! conn "b" "BRL" {})
            results (->> (range 40)
                         (mapv (fn [_] (future (db/transfer! conn {:from a :to b :amount (quot limit 10) :idempotency-key (k)}))))
                         (mapv deref))]
        (is (= 10 (count (filter :ok results))))
        (is (= limit (balance conn b)))))))

(deftest credit-card-test
  (let [conn (fresh-conn)
        gui (funded-account conn 50000)
        mom (db/open-account! conn "mom" "BRL" {})
        card (:id (:ok (db/open-card! conn gui {:credit-limit 100000 :closing-day 5} {})))
        buy! (fn [amount] (db/purchase! conn {:card-id card :amount amount :merchant "Publix" :idempotency-key (k)} {}))]
    (is (uuid? card))
    (is (= {:error :card-exists} (db/open-card! conn gui {:credit-limit 1 :closing-day 5} {})))
    (is (= {:error :invalid-closing-day} (db/open-card! conn mom {:credit-limit 1 :closing-day 31} {})))
    (testing "purchases spend credit; the balance goes negative"
      (is (match? {:ok {:kind "purchase" :memo "Publix" :amount 30000}} (buy! 30000)))
      (is (= -30000 (balance conn card)))
      (is (= 50000 (balance conn gui)) "checking is untouched until the bill is paid")
      (is (match? {:items [{:kind "purchase" :counterparty "Publix" :amount -30000}]} (db/statement (d/db conn) card {}))))
    (testing "the credit limit is the most that can be owed"
      (is (= {:error :insufficient-funds} (buy! 70001)))
      (is (:ok (buy! 70000)))
      (is (= {:error :insufficient-funds} (buy! 1))))
    (testing "a card can't send money to people"
      (is (= {:error :card-not-allowed} (db/transfer! conn {:from card :to mom :amount 1 :idempotency-key (k)}))))
    (testing "the open bill shows this cycle's purchases"
      (is (match? {:owed 100000 :available 0 :current {:spent 100000}}
                  (db/bill (d/db conn) card (Date.)))))
    (testing "after closing, the owed amount becomes the bill, read with as-of"
      ;; an hour after the next closing, so the bill is closed but not yet due
      (let [later (Date. (+ (.getTime ^Date (:closes-at (ledger/bill-cycle "USD" 5 (Date.)))) 3600000))]
        (is (match? {:closed {:amount 100000 :remaining 100000 :status "open"}} (db/bill (d/db conn) card later)))
        (is (:ok (db/pay-bill! conn {:card-id card :amount 40000 :idempotency-key (k)} {})))
        (is (= 10000 (balance conn gui)) "payment comes from checking")
        (is (match? {:owed 60000 :available 40000} (db/bill (d/db conn) card (Date.))))))
    (testing "refunding a purchase gives the credit back"
      (let [t (:id (:ok (buy! 5000)))]
        (is (:ok (db/reverse! conn t)))
        (is (match? {:owed 60000} (db/bill (d/db conn) card (Date.))))))
    (testing "money is still conserved"
      (is (zero? (->> (d/q '[:find ?id :where [?a :account/currency "USD"] [?a :account/id ?id]] (d/db conn))
                      (map (comp #(balance conn %) first))
                      (reduce +)))))))
