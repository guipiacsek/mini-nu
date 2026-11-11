(ns mini-nu.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test]
            [mini-nu.ledger :as ledger]))

(deftest validate-test
  (let [ok {:available 100 :external? false}]
    (is (nil? (ledger/validate {:from :a :to :b :amount 100} ok)))
    (is (= :insufficient-funds (ledger/validate {:from :a :to :b :amount 101} ok)))
    (is (nil? (ledger/validate {:from :a :to :b :amount 101} {:available 0 :external? true})) "external account may go negative")
    (is (= :same-account (ledger/validate {:from :a :to :a :amount 1} ok)))
    (is (= :invalid-amount (ledger/validate {:from :a :to :b :amount 0} ok)))
    (is (= :invalid-amount (ledger/validate {:from :a :to :b :amount nil} ok)))
    (is (= :over-limit (ledger/validate {:from :a :to :b :amount 60} (assoc ok :room 50))))
    (is (nil? (ledger/validate {:from :a :to :b :amount 50} (assoc ok :room 50))))))

(deftest limit-period-test
  (testing "Sao Paulo is UTC-3: 14:00Z is 11:00 there, daytime"
    (is (match? {:period "day" :limit 5000000
                 :start #inst "2026-11-11T09:00:00Z" :end #inst "2026-11-11T23:00:00Z"}
                (ledger/limit-period "BRL" #inst "2026-11-11T14:00:00Z"))))
  (testing "after 20:00 local the night runs into the next morning"
    (is (match? {:period "night" :limit 100000
                 :start #inst "2026-11-11T23:00:00Z" :end #inst "2026-11-12T09:00:00Z"}
                (ledger/limit-period "BRL" #inst "2026-11-12T01:00:00Z"))))
  (testing "before 06:00 local the night started the evening before"
    (is (match? {:period "night" :start #inst "2026-11-11T23:00:00Z" :end #inst "2026-11-12T09:00:00Z"}
                (ledger/limit-period "BRL" #inst "2026-11-12T08:59:59Z"))))
  (testing "New York follows daylight saving: 06:00 local is 10:00Z in summer, 11:00Z in winter"
    (is (match? {:period "day" :start #inst "2026-07-01T10:00:00Z"} (ledger/limit-period "USD" #inst "2026-07-01T12:00:00Z")))
    (is (match? {:period "day" :start #inst "2026-12-01T11:00:00Z"} (ledger/limit-period "USD" #inst "2026-12-01T12:00:00Z")))))

(deftest bill-cycle-test
  (is (= {:closed-at #inst "2026-11-05T03:00:00Z" :due-at #inst "2026-11-12T03:00:00Z" :closes-at #inst "2026-12-05T03:00:00Z"}
         (ledger/bill-cycle "BRL" 5 #inst "2026-11-11T12:00:00Z"))
      "after the 5th, the last bill closed on the 5th of this month")
  (is (= #inst "2026-10-20T03:00:00Z" (:closed-at (ledger/bill-cycle "BRL" 20 #inst "2026-11-11T12:00:00Z")))
      "before the 20th, it closed last month")
  (is (= #inst "2026-12-05T03:00:00Z" (:closed-at (ledger/bill-cycle "BRL" 5 #inst "2026-12-05T03:00:00Z")))
      "exactly at closing time counts as closed"))

(deftest entries-test
  (let [es (ledger/entries {:from :a :to :b :amount 250})]
    (is (match? [{:entry/account :a :entry/amount -250}
                 {:entry/account :b :entry/amount 250}]
                es))
    (is (zero? (reduce + (map :entry/amount es))))))
