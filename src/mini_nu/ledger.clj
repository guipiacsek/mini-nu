(ns mini-nu.ledger
  "Pure ledger rules. No database in here, so they are trivial to test.")

(defn validate
  "Returns nil when the transfer is allowed, otherwise an error keyword."
  [{:keys [from to amount]} from-balance from-external?]
  (cond
    (not (pos-int? amount)) :invalid-amount
    (= from to) :same-account
    (and (not from-external?) (< from-balance amount)) :insufficient-funds))

(defn entries
  "Double entry: one debit and one credit that always sum to zero."
  [{:keys [from to amount]}]
  [{:entry/account from :entry/amount (- amount)}
   {:entry/account to :entry/amount amount}])
