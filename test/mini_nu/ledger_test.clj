(ns mini-nu.ledger-test
  (:require [clojure.test :refer [deftest is]]
            [matcher-combinators.test]
            [mini-nu.ledger :as ledger]))

(deftest validate-test
  (is (nil? (ledger/validate {:from :a :to :b :amount 100} 100 false)))
  (is (= :insufficient-funds (ledger/validate {:from :a :to :b :amount 101} 100 false)))
  (is (nil? (ledger/validate {:from :a :to :b :amount 101} 0 true)) "external account may go negative")
  (is (= :same-account (ledger/validate {:from :a :to :a :amount 1} 100 false)))
  (is (= :invalid-amount (ledger/validate {:from :a :to :b :amount 0} 100 false)))
  (is (= :invalid-amount (ledger/validate {:from :a :to :b :amount nil} 100 false))))

(deftest entries-test
  (let [es (ledger/entries {:from :a :to :b :amount 250})]
    (is (match? [{:entry/account :a :entry/amount -250}
                 {:entry/account :b :entry/amount 250}]
                es))
    (is (zero? (reduce + (map :entry/amount es))))))
