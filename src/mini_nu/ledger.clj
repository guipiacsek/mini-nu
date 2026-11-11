(ns mini-nu.ledger
  "Pure ledger rules. No database in here, so they are trivial to test."
  (:import (java.math RoundingMode)
           (java.time LocalDate YearMonth ZoneId ZoneOffset ZonedDateTime)
           (java.util Date)))

(defn validate
  "Returns nil when the transfer is allowed, otherwise an error keyword.
  available is the balance plus any credit line; room is what is left of the
  time-of-day limit (nil when no limit applies)."
  [{:keys [from to amount]} {:keys [available external? room]}]
  (cond
    (not (pos-int? amount)) :invalid-amount
    (= from to) :same-account
    external? nil
    (< available amount) :insufficient-funds
    (and room (< room amount)) :over-limit))

(def zones
  "Limits and card bills follow the customer's local time."
  {"USD" (ZoneId/of "America/New_York")
   "BRL" (ZoneId/of "America/Sao_Paulo")})

(def limits
  "Most a customer can send per period, in cents. Night (20:00-06:00 local) is
  much lower, like Brazil's Pix night limit: a phone stolen at 2am can't empty
  the account."
  {"USD" {"day" 1000000 "night" 100000}    ; $10,000 / $1,000
   "BRL" {"day" 5000000 "night" 100000}})  ; R$50,000 / R$1,000

(defn- ->date [^ZonedDateTime t] (Date/from (.toInstant t)))

(defn limit-period
  "The limit period containing `at`: {:period \"day\"|\"night\" :start :end :limit}."
  [currency ^Date at]
  (let [zone (zones currency)
        t (.atZone (.toInstant at) zone)
        d (.toLocalDate t)
        h (.getHour t)
        at-hour (fn [^LocalDate day hour] (->date (.atZone (.atTime day (int hour) 0) zone)))
        [period start end] (cond (< h 6) ["night" (at-hour (.minusDays d 1) 20) (at-hour d 6)]
                                 (< h 20) ["day" (at-hour d 6) (at-hour d 20)]
                                 :else ["night" (at-hour d 20) (at-hour (.plusDays d 1) 6)])]
    {:period period :start start :end end :limit (get-in limits [currency period])}))

(defn bill-cycle
  "A card closes on closing-day (1-28) at midnight local time and is due 7 days
  later. Returns the last closing at or before `now`, its due date, and the next closing."
  [currency closing-day ^Date now]
  (let [zone (zones currency)
        t (.atZone (.toInstant now) zone)
        close (fn [^YearMonth ym] (.atStartOfDay (.atDay ym (int closing-day)) zone))
        this-month (close (YearMonth/from t))
        prev (if (.isAfter this-month t) (close (.minusMonths (YearMonth/from t) 1)) this-month)]
    {:closed-at (->date prev)
     :due-at (->date (.plusDays prev 7))
     :closes-at (->date (.plusMonths prev 1))}))

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
