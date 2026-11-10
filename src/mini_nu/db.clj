(ns mini-nu.db
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [mini-nu.ledger :as ledger]
            [mini-nu.schema :refer [schema]]))

(def external-id
  "The bank's funding account. Deposits are transfers out of it."
  #uuid "00000000-0000-0000-0000-000000000000")

(defn connect!
  "Creates an in-memory database. Data is gone when the process stops."
  [db-name]
  (let [client (d/client {:server-type :datomic-local :system "mini-nu" :storage-dir :mem})
        _ (d/create-database client {:db-name db-name})
        conn (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data schema})
    (d/transact conn {:tx-data [{:account/id external-id :account/owner "external" :account/external? true}]})
    conn))

;; ---------- reads ----------

(defn- account [db id]
  (ffirst (d/q '[:find (pull ?a [:db/id :account/external?])
                 :in $ ?id
                 :where [?a :account/id ?id]]
               db id)))

(defn account? [db id]
  (some? (account db id)))

(defn balance
  "Never stored: always the sum of the account's entries."
  [db account-id]
  (or (ffirst (d/q '[:find (sum ?amount)
                     :with ?entry
                     :in $ ?id
                     :where [?a :account/id ?id]
                            [?entry :entry/account ?a]
                            [?entry :entry/amount ?amount]]
                   db account-id))
      0))

(defn find-transfer [db idempotency-key]
  (when-let [t (ffirst (d/q '[:find (pull ?t [:transfer/id :transfer/amount
                                              {:transfer/from [:account/id]}
                                              {:transfer/to [:account/id]}])
                              :in $ ?k
                              :where [?t :transfer/idempotency-key ?k]]
                            db idempotency-key))]
    {:id (:transfer/id t)
     :from (get-in t [:transfer/from :account/id])
     :to (get-in t [:transfer/to :account/id])
     :amount (:transfer/amount t)}))

(defn statement [db account-id]
  (->> (d/q '[:find ?tid ?amount ?inst
              :in $ ?id
              :where [?a :account/id ?id]
                     [?entry :entry/account ?a]
                     [?entry :entry/amount ?amount ?tx]
                     [?t :transfer/entries ?entry]
                     [?t :transfer/id ?tid]
                     [?tx :db/txInstant ?inst]]
            db account-id)
       (sort-by last)
       (mapv (fn [[tid amount inst]]
               {:transfer-id tid :amount amount :at (str (.toInstant ^java.util.Date inst))}))))

;; ---------- writes ----------

(def ^:private errors
  #{:invalid-amount :same-account :insufficient-funds :unknown-account :idempotency-conflict})

(defn- fail! [err]
  (throw (ex-info (name err) {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                              :mini-nu/error err})))

(defn transfer-tx
  "Transaction function: runs inside the Datomic transaction, so the balance
  check and the write are atomic. Concurrent transfers cannot overdraw."
  [db {:keys [id idempotency-key from to amount]}]
  (if-let [prev (find-transfer db idempotency-key)]
    (if (= [from to amount] ((juxt :from :to :amount) prev))
      [] ; a retry: the money already moved
      (fail! :idempotency-conflict))
    (let [from-acc (account db from)
          to-acc (account db to)]
      (when-not (and from-acc to-acc) (fail! :unknown-account))
      (some-> (ledger/validate {:from from :to to :amount amount}
                               (balance db from)
                               (:account/external? from-acc))
              fail!)
      [{:transfer/id id
        :transfer/idempotency-key idempotency-key
        :transfer/from (:db/id from-acc)
        :transfer/to (:db/id to-acc)
        :transfer/amount amount
        :transfer/entries (ledger/entries {:from (:db/id from-acc) :to (:db/id to-acc) :amount amount})}])))

(defn- tx-error [e]
  ;; shortcut: also matches on the message in case Datomic drops our ex-data on the way out
  (some (fn [ex]
          (or (:mini-nu/error (ex-data ex))
              (some #(when (str/includes? (str (ex-message ex)) (name %)) %) errors)))
        (take-while some? (iterate ex-cause e))))

(defn transfer!
  "Returns {:ok transfer} or {:error keyword}.
  Retrying with the same idempotency key never moves money twice."
  [conn {:keys [idempotency-key] :as req}]
  (try
    (let [{:keys [db-after]} (d/transact conn {:tx-data [['mini-nu.db/transfer-tx (assoc req :id (random-uuid))]]})]
      {:ok (find-transfer db-after idempotency-key)})
    (catch clojure.lang.ExceptionInfo e
      (if-let [err (tx-error e)] {:error err} (throw e)))))

(defn deposit! [conn req]
  (transfer! conn (assoc req :from external-id)))

(defn open-account! [conn owner]
  (let [id (random-uuid)]
    (d/transact conn {:tx-data [{:account/id id :account/owner owner}]})
    id))
