(ns mini-nu.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datomic.client.api :as d]
            [matcher-combinators.test]
            [mini-nu.db :as db]))

(defn fresh-conn [] (db/connect! (str (random-uuid))))
(defn k [] (str (random-uuid)))
(defn balance [conn id] (db/balance (d/db conn) id))

(defn funded-account
  ([conn cents] (funded-account conn cents "USD"))
  ([conn cents currency]
   (let [id (db/open-account! conn "test" currency {})]
     (db/deposit! conn {:to id :amount cents :idempotency-key (k)})
     id)))

(deftest transfer-test
  (let [conn (fresh-conn)
        alice (funded-account conn 1000)
        bob (db/open-account! conn "bob")]
    (testing "moves money"
      (is (match? {:ok {:from alice :to bob :amount 300 :credited 300 :kind "transfer"}}
                  (db/transfer! conn {:from alice :to bob :amount 300 :idempotency-key (k)})))
      (is (= 700 (balance conn alice)))
      (is (= 300 (balance conn bob))))
    (testing "rejects overdraft and leaves balances alone"
      (is (= {:error :insufficient-funds}
             (db/transfer! conn {:from alice :to bob :amount 701 :idempotency-key (k)})))
      (is (= 700 (balance conn alice))))
    (testing "rejects unknown accounts"
      (is (= {:error :unknown-account}
             (db/transfer! conn {:from alice :to (random-uuid) :amount 1 :idempotency-key (k)}))))
    (testing "statement lists every entry, newest first"
      (is (match? {:items [{:amount -300 :kind "transfer" :counterparty "bob"}
                           {:amount 1000 :kind "deposit" :currency "USD"}]
                   :next-cursor nil}
                  (db/statement (d/db conn) alice {}))))))

(deftest idempotency-test
  (let [conn (fresh-conn)
        alice (funded-account conn 1000)
        bob (db/open-account! conn "bob")
        req {:from alice :to bob :amount 100 :idempotency-key "retry-me"}
        first-try (db/transfer! conn req)]
    (is (= first-try (db/transfer! conn req)) "a retry returns the original transfer")
    (is (= 900 (balance conn alice)) "and does not debit twice")
    (is (= {:error :idempotency-conflict}
           (db/transfer! conn (assoc req :amount 999)))
        "same key with different data is refused")))

(deftest concurrency-test
  (let [conn (fresh-conn)
        alice (funded-account conn 500)
        bob (db/open-account! conn "bob")
        results (->> (range 100)
                     (mapv (fn [_] (future (db/transfer! conn {:from alice :to bob :amount 10 :idempotency-key (k)}))))
                     (mapv deref))]
    (is (= 50 (count (filter :ok results))) "only what the balance covers goes through")
    (is (zero? (balance conn alice)))
    (is (= 500 (balance conn bob)))))

(deftest as-of-test
  (let [conn (fresh-conn)
        alice (funded-account conn 1000)
        bob (db/open-account! conn "bob")
        before (java.util.Date.)]
    (Thread/sleep 5) ; txInstant has millisecond resolution
    (db/transfer! conn {:from alice :to bob :amount 400 :idempotency-key (k)})
    (is (= 1000 (db/balance (d/as-of (d/db conn) before) alice)) "the past is still there")
    (is (= 600 (balance conn alice)))))

(defn- currency-total
  "Every account in a currency, including the bank's own. Double entry keeps this at zero."
  [conn currency]
  (->> (d/q '[:find ?id :in $ ?c :where [?a :account/currency ?c] [?a :account/id ?id]] (d/db conn) currency)
       (map (comp #(balance conn %) first))
       (reduce +)))

(def ^:private gen-transfers
  (gen/vector (gen/tuple (gen/choose 0 3) (gen/choose 0 3) (gen/choose 1 400)) 1 30))

(defspec money-is-never-created-or-destroyed 25
  (prop/for-all [transfers gen-transfers]
    (let [conn (fresh-conn)
          accounts [(funded-account conn 500 "USD") (funded-account conn 500 "USD")
                    (funded-account conn 2500 "BRL") (funded-account conn 2500 "BRL")]]
      (doseq [[from to amount] transfers]
        (db/transfer! conn {:from (accounts from) :to (accounts to) :amount amount :idempotency-key (k)}))
      (and (zero? (currency-total conn "USD"))
           (zero? (currency-total conn "BRL"))
           (every? #(not (neg? (balance conn %))) accounts)))))
