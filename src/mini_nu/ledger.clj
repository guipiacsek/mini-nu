(ns mini-nu.ledger
  "Pure ledger rules. No database in here, so they are trivial to test."
  (:import (java.math RoundingMode)
           (java.time ZoneOffset)))

(defn validate
  "Returns nil when the transfer is allowed, otherwise an error keyword."
  [{:keys [from to amount]} from-balance from-external?]
  (cond
    (not (pos-int? amount)) :invalid-amount
    (= from to) :same-account
    (and (not from-external?) (< from-balance amount)) :insufficient-funds))

(defn convert
  "Cents in one currency to cents in another. Banker's rounding avoids a
  systematic bias over many conversions."
  [amount rate]
  (-> (bigdec amount) (* rate) (.setScale 0 RoundingMode/HALF_EVEN) long))

(defn entries
  "Double entry. Same currency: one debit, one credit. Cross currency: money
  goes through the bank's pool in each currency, so every currency still
  sums to zero."
  [{:keys [from to amount credited from-pool to-pool]}]
  (if from-pool
    [{:entry/account from :entry/amount (- amount)}
     {:entry/account from-pool :entry/amount amount}
     {:entry/account to-pool :entry/amount (- credited)}
     {:entry/account to :entry/amount credited}]
    [{:entry/account from :entry/amount (- amount)}
     {:entry/account to :entry/amount amount}]))

(defn reversal-entries
  "A reversal never deletes: it adds the exact opposite of every original entry."
  [entries]
  (mapv #(update % :entry/amount -) entries))

(defn next-run
  "When a schedule fires next, in UTC."
  [^java.util.Date last every]
  (let [t (.atZone (.toInstant last) ZoneOffset/UTC)]
    (java.util.Date/from
     (.toInstant (case every
                   "minute" (.plusMinutes t 1)
                   "day" (.plusDays t 1)
                   "week" (.plusWeeks t 1)
                   "month" (.plusMonths t 1))))))
