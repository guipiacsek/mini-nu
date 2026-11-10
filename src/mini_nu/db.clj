(ns mini-nu.db
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [mini-nu.ledger :as ledger]
            [mini-nu.schema :refer [schema]]))

(def currencies #{"USD" "BRL"})
(def schedule-frequencies #{"minute" "day" "week" "month"})

(def external-ids
  "The bank's own account in each currency. Deposits come out of it and
  currency exchange goes through it."
  {"USD" #uuid "00000000-0000-0000-0000-000000000001"
   "BRL" #uuid "00000000-0000-0000-0000-000000000002"})

(defn- iso [^java.util.Date d] (some-> d .toInstant str))

(defn- with-audit
  "Attaches who/where to the transaction entity, so it is stored with the data it explains."
  [tx-data audit]
  (let [meta (into {} (remove (comp nil? val)) audit)]
    (cond-> (vec tx-data) (seq meta) (conj (assoc meta :db/id "datomic.tx")))))

(defn set-rate!
  "Sets from->to and its inverse. Past transfers keep the rate they used."
  [conn from to rate audit]
  (d/transact conn {:tx-data (with-audit [{:fx/pair (str from "/" to) :fx/rate rate}
                                          {:fx/pair (str to "/" from) :fx/rate (with-precision 10 (/ 1M rate))}]
                               audit)}))

(defn connect!
  "storage is :mem (gone when the process stops) or a directory (survives restarts)."
  ([db-name] (connect! db-name :mem))
  ([db-name storage]
   (let [client (d/client {:server-type :datomic-local
                           :system "mini-nu"
                           :storage-dir (if (= :mem storage) :mem (.getAbsolutePath (io/file storage)))})
         _ (d/create-database client {:db-name db-name})
         conn (d/connect client {:db-name db-name})]
     (d/transact conn {:tx-data schema})
     (d/transact conn {:tx-data (for [[cur id] external-ids]
                                  {:account/id id :account/owner (str "Bank " cur)
                                   :account/currency cur :account/external? true})})
     (when-not (seq (d/q '[:find ?p :where [_ :fx/pair ?p]] (d/db conn)))
       (set-rate! conn "USD" "BRL" 5.40M {:audit/actor "setup"}))
     conn)))

;; ---------- reads ----------

(defn- account [db id]
  (when id
    (ffirst (d/q '[:find (pull ?a [:db/id :account/id :account/owner :account/currency :account/external?])
                   :in $ ?id
                   :where [?a :account/id ?id]]
                 db id))))

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

(defn accounts [db]
  (->> (d/q '[:find (pull ?a [:account/id :account/owner :account/currency])
              :where [?a :account/id]
                     (not [?a :account/external? true])]
            db)
       (map (fn [[a]] {:id (:account/id a)
                       :owner (:account/owner a)
                       :currency (:account/currency a)
                       :balance (balance db (:account/id a))}))
       (sort-by :owner)))

(defn fx-rate [db from to]
  (ffirst (d/q '[:find ?r :in $ ?p :where [?f :fx/pair ?p] [?f :fx/rate ?r]]
               db (str from "/" to))))

(defn fx-rates [db]
  (into (sorted-map) (d/q '[:find ?p ?r :where [?f :fx/pair ?p] [?f :fx/rate ?r]] db)))

(def ^:private account-pattern [:account/id :account/owner :account/currency :account/external?])

(def ^:private transfer-pattern
  [:transfer/id :transfer/amount :transfer/credited :transfer/fx-rate
   {:transfer/from account-pattern}
   {:transfer/to account-pattern}
   {:transfer/reverses [:transfer/id]}
   {:transfer/_reverses [:transfer/id]}
   {:transfer/schedule [:schedule/id]}])

(defn- ->transfer [t]
  (when (:transfer/id t)
    (let [{from :transfer/from to :transfer/to} t]
      (cond-> {:id (:transfer/id t)
               :kind (cond (:transfer/reverses t) "reversal"
                           (:transfer/schedule t) "scheduled"
                           (:account/external? from) "deposit"
                           :else "transfer")
               :from (:account/id from) :from-owner (:account/owner from) :from-currency (:account/currency from)
               :to (:account/id to) :to-owner (:account/owner to) :to-currency (:account/currency to)
               :amount (:transfer/amount t)
               :credited (:transfer/credited t)}
        (:transfer/fx-rate t) (assoc :fx-rate (:transfer/fx-rate t))
        (:transfer/reverses t) (assoc :reverses (get-in t [:transfer/reverses :transfer/id]))
        (seq (:transfer/_reverses t)) (assoc :reversed-by (-> t :transfer/_reverses first :transfer/id))
        (:transfer/schedule t) (assoc :schedule (get-in t [:transfer/schedule :schedule/id]))))))

(defn find-transfer [db idempotency-key]
  (->transfer (ffirst (d/q '[:find (pull ?t pattern)
                             :in $ pattern ?k
                             :where [?t :transfer/idempotency-key ?k]]
                           db transfer-pattern idempotency-key))))

(defn get-transfer
  "The transfer plus the audit record of the transaction that created it."
  [db id]
  (when-let [[t tx] (first (d/q '[:find (pull ?t pattern) (pull ?tx [:db/txInstant :audit/request-id :audit/actor :audit/ip])
                                  :in $ pattern ?id
                                  :where [?t :transfer/id ?id ?tx]]
                                db transfer-pattern id))]
    (assoc (->transfer t)
           :audit {:at (iso (:db/txInstant tx))
                   :request-id (:audit/request-id tx)
                   :actor (:audit/actor tx)
                   :ip (:audit/ip tx)})))

(defn statement
  "Newest first. Pass the previous page's :next-cursor to get the next page.
  from/to are java.util.Dates (from inclusive, to exclusive)."
  [db account-id {:keys [from to limit cursor]}]
  (let [limit (or limit 50)
        rows (->> (d/q '[:find ?entry ?amount ?tx ?inst ?t
                         :in $ ?id
                         :where [?a :account/id ?id]
                                [?entry :entry/account ?a]
                                [?entry :entry/amount ?amount ?tx]
                                [?t :transfer/entries ?entry]
                                [?tx :db/txInstant ?inst]]
                       db account-id)
                  ;; shortcut: filters and pages in memory; fine for thousands of entries per account,
                  ;; move the range into the query (index-range on txInstant) beyond that
                  (filter (fn [[_ _ tx ^java.util.Date inst]]
                            (and (or (nil? from) (not (.before inst from)))
                                 (or (nil? to) (.before inst to))
                                 (or (nil? cursor) (< tx cursor)))))
                  (sort-by #(nth % 2) >))
        page (take limit rows)]
    {:items (mapv (fn [[_ amount tx inst t]]
                    (let [tr (->transfer (d/pull db transfer-pattern t))
                          incoming? (pos? amount)]
                      (merge (select-keys tr [:kind :fx-rate :reverses :reversed-by :schedule])
                             {:transfer-id (:id tr)
                              :amount amount
                              :currency (if incoming? (:to-currency tr) (:from-currency tr))
                              :counterparty (if incoming? (:from-owner tr) (:to-owner tr))
                              :at (iso inst)
                              :cursor (str tx)})))
                  page)
     :next-cursor (when (> (count rows) limit) (str (nth (last page) 2)))}))

(def ^:private schedule-pattern
  [:schedule/id :schedule/amount :schedule/every :schedule/next-run :schedule/active? :schedule/last-result
   {:schedule/from account-pattern}
   {:schedule/to account-pattern}])

(defn- ->schedule [s]
  {:id (:schedule/id s)
   :from (get-in s [:schedule/from :account/id]) :from-owner (get-in s [:schedule/from :account/owner])
   :to (get-in s [:schedule/to :account/id]) :to-owner (get-in s [:schedule/to :account/owner])
   :currency (get-in s [:schedule/from :account/currency])
   :amount (:schedule/amount s)
   :every (:schedule/every s)
   :next-run (iso (:schedule/next-run s))
   :active? (:schedule/active? s)
   :last-result (:schedule/last-result s)})

(defn get-schedule [db id]
  (some-> (ffirst (d/q '[:find (pull ?s pattern) :in $ pattern ?id :where [?s :schedule/id ?id]]
                       db schedule-pattern id))
          ->schedule))

(defn schedules [db account-id]
  (->> (d/q '[:find (pull ?s pattern)
              :in $ pattern ?id
              :where [?a :account/id ?id]
                     (or [?s :schedule/from ?a] [?s :schedule/to ?a])]
            db schedule-pattern account-id)
       (map (comp ->schedule first))
       (sort-by (juxt (comp not :active?) :next-run))))

(defn stats [db]
  {:accounts (count (accounts db))
   :transfers (or (ffirst (d/q '[:find (count ?t) :where [?t :transfer/id]] db)) 0)
   :active-schedules (or (ffirst (d/q '[:find (count ?s) :where [?s :schedule/active? true]] db)) 0)})

;; ---------- transaction functions (run inside Datomic, one at a time) ----------

(def ^:private errors
  #{:invalid-amount :same-account :insufficient-funds :unknown-account :idempotency-conflict
    :no-fx-rate :unknown-transfer :cannot-reverse-reversal})

(defn- fail! [err]
  (throw (ex-info (name err) {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                              :mini-nu/error err})))

(defn- pool [db currency]
  (:db/id (account db (external-ids currency))))

(defn transfer-tx
  "Runs inside the Datomic transaction, so the balance check and the write
  are atomic. Concurrent transfers cannot overdraw."
  [db {:keys [id idempotency-key from to amount schedule]}]
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
      (let [from-cur (:account/currency from-acc)
            to-cur (:account/currency to-acc)
            fx? (not= from-cur to-cur)
            rate (when fx? (or (fx-rate db from-cur to-cur) (fail! :no-fx-rate)))
            credited (if fx? (ledger/convert amount rate) amount)]
        (when-not (pos? credited) (fail! :invalid-amount))
        [(cond-> {:transfer/id id
                  :transfer/idempotency-key idempotency-key
                  :transfer/from (:db/id from-acc)
                  :transfer/to (:db/id to-acc)
                  :transfer/amount amount
                  :transfer/credited credited
                  :transfer/entries (ledger/entries
                                     (cond-> {:from (:db/id from-acc) :to (:db/id to-acc)
                                              :amount amount :credited credited}
                                       fx? (assoc :from-pool (pool db from-cur) :to-pool (pool db to-cur))))}
           rate (assoc :transfer/fx-rate rate)
           schedule (assoc :transfer/schedule [:schedule/id schedule]))]))))

(defn reverse-tx
  "Undoes a transfer by adding its exact opposite. Nothing is deleted, and a
  transfer can be reversed only once."
  [db {:keys [id original]}]
  (let [k (str "reversal:" original)]
    (if (find-transfer db k)
      []
      (let [t (ffirst (d/q '[:find (pull ?t [:db/id :transfer/amount :transfer/credited
                                             {:transfer/reverses [:db/id]}
                                             {:transfer/from [:db/id :account/id :account/external?]}
                                             {:transfer/to [:db/id :account/id :account/external?]}
                                             {:transfer/entries [{:entry/account [:db/id]} :entry/amount]}])
                             :in $ ?id
                             :where [?t :transfer/id ?id]]
                           db original))
            {from :transfer/from to :transfer/to} t]
        (when-not t (fail! :unknown-transfer))
        (when (:transfer/reverses t) (fail! :cannot-reverse-reversal))
        ;; the recipient must still hold what they received
        (when (and (not (:account/external? to))
                   (< (balance db (:account/id to)) (:transfer/credited t)))
          (fail! :insufficient-funds))
        [{:transfer/id id
          :transfer/idempotency-key k
          :transfer/from (:db/id to)
          :transfer/to (:db/id from)
          :transfer/amount (:transfer/credited t)
          :transfer/credited (:transfer/amount t)
          :transfer/reverses (:db/id t)
          :transfer/entries (ledger/reversal-entries
                             (for [e (:transfer/entries t)]
                               {:entry/account (get-in e [:entry/account :db/id])
                                :entry/amount (:entry/amount e)}))}]))))

;; ---------- writes ----------

(defn- tx-error [e]
  ;; shortcut: also matches on the message in case Datomic drops our ex-data on the way out
  (some (fn [ex]
          (or (:mini-nu/error (ex-data ex))
              (some #(when (str/includes? (str (ex-message ex)) (name %)) %) errors)))
        (take-while some? (iterate ex-cause e))))

(defn- run-tx!
  "Returns {:ok transfer} or {:error keyword}."
  [conn tx-data audit idempotency-key]
  (try
    (let [{:keys [db-after]} (d/transact conn {:tx-data (with-audit tx-data audit)})]
      {:ok (find-transfer db-after idempotency-key)})
    (catch clojure.lang.ExceptionInfo e
      (if-let [err (tx-error e)] {:error err} (throw e)))))

(defn transfer!
  "Retrying with the same idempotency key never moves money twice."
  ([conn req] (transfer! conn req {}))
  ([conn {:keys [idempotency-key] :as req} audit]
   (run-tx! conn [['mini-nu.db/transfer-tx (assoc req :id (random-uuid))]] audit idempotency-key)))

(defn deposit!
  "A transfer from the bank's account in the receiver's currency."
  ([conn req] (deposit! conn req {}))
  ([conn {:keys [to] :as req} audit]
   (if-let [cur (:account/currency (account (d/db conn) to))]
     (transfer! conn (assoc req :from (external-ids cur)) audit)
     {:error :unknown-account})))

(defn reverse!
  "Reversing twice returns the first reversal."
  ([conn transfer-id] (reverse! conn transfer-id {}))
  ([conn transfer-id audit]
   (run-tx! conn [['mini-nu.db/reverse-tx {:id (random-uuid) :original transfer-id}]]
            audit (str "reversal:" transfer-id))))

(defn open-account!
  ([conn owner] (open-account! conn owner "USD" {}))
  ([conn owner currency audit]
   (let [id (random-uuid)]
     (d/transact conn {:tx-data (with-audit [{:account/id id :account/owner owner :account/currency currency}] audit)})
     id)))

(defn create-schedule!
  "Returns {:ok schedule} or {:error keyword}. start-at defaults to now."
  [conn {:keys [from to amount every start-at]} audit]
  (let [db (d/db conn)
        f (account db from)
        t (account db to)
        id (random-uuid)]
    (cond
      (not (and f t)) {:error :unknown-account}
      (not (schedule-frequencies every)) {:error :invalid-schedule}
      (not (pos-int? amount)) {:error :invalid-amount}
      (= from to) {:error :same-account}
      :else (do (d/transact conn {:tx-data (with-audit [{:schedule/id id
                                                         :schedule/from (:db/id f)
                                                         :schedule/to (:db/id t)
                                                         :schedule/amount amount
                                                         :schedule/every every
                                                         :schedule/next-run (or start-at (java.util.Date.))
                                                         :schedule/active? true}]
                                                       audit)})
                {:ok (get-schedule (d/db conn) id)}))))

(defn cancel-schedule! [conn id audit]
  (if (get-schedule (d/db conn) id)
    (do (d/transact conn {:tx-data (with-audit [[:db/add [:schedule/id id] :schedule/active? false]] audit)})
        {:ok (get-schedule (d/db conn) id)})
    {:error :unknown-schedule}))

(defn run-due-schedules!
  "Fires every active schedule whose time has come. The idempotency key is the
  schedule plus its run time, so a crash or a second runner can't pay twice."
  [conn ^java.util.Date now]
  (let [audit {:audit/actor "scheduler"}]
    (doseq [[id run-at every from to amount]
            (d/q '[:find ?id ?next ?every ?from ?to ?amount
                   :in $ ?now
                   :where [?s :schedule/active? true]
                          [?s :schedule/next-run ?next]
                          [(<= ?next ?now)]
                          [?s :schedule/id ?id]
                          [?s :schedule/every ?every]
                          [?s :schedule/amount ?amount]
                          [?s :schedule/from ?f] [?f :account/id ?from]
                          [?s :schedule/to ?t] [?t :account/id ?to]]
                 (d/db conn) now)]
      (let [result (transfer! conn {:from from :to to :amount amount :schedule id
                                    :idempotency-key (str "schedule:" id ":" (iso run-at))}
                              audit)]
        (try
          (d/transact conn {:tx-data (with-audit [[:db/cas [:schedule/id id] :schedule/next-run run-at (ledger/next-run run-at every)]
                                                  [:db/add [:schedule/id id] :schedule/last-result
                                                   (if (:ok result) "ok" (name (:error result)))]]
                                                 audit)})
          (catch clojure.lang.ExceptionInfo _ nil)))))) ; cas lost: another runner already moved it on
