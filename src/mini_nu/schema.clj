(ns mini-nu.schema)

(defn- attr [ident type & {:as more}]
  (merge {:db/ident ident :db/valueType type :db/cardinality :db.cardinality/one} more))

(def schema
  [(attr :account/id :db.type/uuid :db/unique :db.unique/identity)
   (attr :account/owner :db.type/string)
   (attr :account/currency :db.type/string)
   ;; the bank's own accounts (one per currency) may go below zero
   (attr :account/external? :db.type/boolean)
   ;; a credit card is an account allowed to go below zero, down to its limit
   (attr :account/credit-limit :db.type/long)
   (attr :account/closing-day :db.type/long)
   (attr :account/card-of :db.type/ref :db/unique :db.unique/value) ; the checking account that pays the bill

   (attr :transfer/id :db.type/uuid :db/unique :db.unique/identity)
   (attr :transfer/idempotency-key :db.type/string :db/unique :db.unique/value)
   (attr :transfer/from :db.type/ref)
   (attr :transfer/to :db.type/ref)
   (attr :transfer/amount :db.type/long)               ; debited, in the sender's currency
   (attr :transfer/credited :db.type/long)             ; credited, in the receiver's currency
   (attr :transfer/fx-rate :db.type/bigdec)            ; only on cross-currency transfers
   (attr :transfer/reverses :db.type/ref :db/unique :db.unique/value)
   (attr :transfer/schedule :db.type/ref)
   (attr :transfer/memo :db.type/string)                ; the merchant, on card purchases
   (attr :transfer/entries :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true)

   ;; amounts are integer cents: negative = debit, positive = credit
   (attr :entry/account :db.type/ref)
   (attr :entry/amount :db.type/long)

   ;; "USD/BRL" -> 5.40 means 1 USD buys 5.40 BRL. Old rates stay readable through history.
   (attr :fx/pair :db.type/string :db/unique :db.unique/identity)
   (attr :fx/rate :db.type/bigdec)

   (attr :schedule/id :db.type/uuid :db/unique :db.unique/identity)
   (attr :schedule/from :db.type/ref)
   (attr :schedule/to :db.type/ref)
   (attr :schedule/amount :db.type/long)
   (attr :schedule/every :db.type/string)               ; minute | day | week | month
   (attr :schedule/next-run :db.type/instant)
   (attr :schedule/active? :db.type/boolean)
   (attr :schedule/last-result :db.type/string)

   ;; stored on the transaction entity itself, so every write carries who/where
   (attr :audit/request-id :db.type/string)
   (attr :audit/actor :db.type/string)
   (attr :audit/ip :db.type/string)])
