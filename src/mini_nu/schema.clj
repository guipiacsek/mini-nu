(ns mini-nu.schema)

(defn- attr [ident type & {:as more}]
  (merge {:db/ident ident :db/valueType type :db/cardinality :db.cardinality/one} more))

(def schema
  [(attr :account/id :db.type/uuid :db/unique :db.unique/identity)
   (attr :account/owner :db.type/string)
   ;; only the bank's external funding account may go below zero
   (attr :account/external? :db.type/boolean)

   (attr :transfer/id :db.type/uuid :db/unique :db.unique/identity)
   (attr :transfer/idempotency-key :db.type/string :db/unique :db.unique/value)
   (attr :transfer/from :db.type/ref)
   (attr :transfer/to :db.type/ref)
   (attr :transfer/amount :db.type/long)
   (attr :transfer/entries :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true)

   ;; amounts are integer cents: negative = debit, positive = credit
   (attr :entry/account :db.type/ref)
   (attr :entry/amount :db.type/long)])
