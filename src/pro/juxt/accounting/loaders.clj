;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; This file is part of JUXT Accounting.
;;
;; JUXT Accounting is free software: you can redistribute it and/or modify it under the
;; terms of the GNU Affero General Public License as published by the Free
;; Software Foundation, either version 3 of the License, or (at your option) any
;; later version.
;;
;; JUXT Accounting is distributed in the hope that it will be useful but WITHOUT ANY
;; WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
;; A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
;; details.
;;
;; Please see the LICENSE file for a copy of the GNU Affero General Public License.
;;
(ns pro.juxt.accounting.loaders
  (:require
   [clojure.tools
    [trace :refer (deftrace)]]
   [pro.juxt.accounting.database :as db]
   [clojurewerkz.money.amounts :as ma :refer (amount-of)]
   [clojurewerkz.money.currencies :as mc :refer (GBP EUR)])
  (:import (org.joda.money Money CurrencyUnit)))

(defn account-of [db m]
  {:post [(not (nil? %))]}
  (let [acct (db/find-account db m)]
    (when (nil? acct) (throw (ex-info "No such account: " m)))
    (:db/id acct)))

(defn training [db context billing]
  {:date (:date billing)
   :amount (amount-of GBP (:amount-ex-vat billing))
   :debit-account (account-of db (:debit-account context))
   :credit-account (account-of db (:credit-account context))
   :description (:description billing)})

(defn expenses [db context billing]
  (let [amount (ma/parse (:amount billing))]
    (let [m
          {:date (:date billing)
           :debit-account (:debit-account context)
           :credit-account (account-of db (:credit-account context))
           :amount amount
           :description (:description billing)
           ;; TODO: Get expense type/code, or infer it from description
           }]
      (if-let [entity (:client billing)]
        (assoc m
          :debit-account (account-of db (:credit-account context))
          :credit-account (account-of db
                                      {:entity entity
                                       :type :pro.juxt.accounting/expenses}))
        m))))

(defn daily-rate-billing [db context billing]
  {:date (:date billing)
   :amount (amount-of GBP (:rate billing))
   :debit-account (account-of db (:debit-account context))
   :credit-account (account-of db (:credit-account context))
   :description (:description billing)})

(def descriptions {:full-day-with-travel "Full day (with travel)"
                   :full-day-from-home "Full day (working remotely)"
                   :half-day-from-home "Half day (working remotely)"
                   :work-from-home-hourly "Support"
                   })

(defn variable-rate-billing [db context {:keys [date type description hours] :as billing}]
  (let [amt (or
             (-> context :rates type)
             (when (= type :work-from-home-hourly) (* hours (-> context :rates :per-hour))))
        _ (assert (not (nil? amt)) (str billing))
        amount (amount-of GBP amt java.math.RoundingMode/DOWN)]
    {:date date
     :debit-account (account-of db (:debit-account context))
     :credit-account (account-of db (:credit-account context))
     :amount amount
     :description (str (get descriptions type)
                                           (when description (str " - " description))
                                           (when hours
                                             (format " - %s hours worked"
                                                     (.format (java.text.DecimalFormat. "#.##")
                                                              (float hours)))))}))


(defn natwest-tsv [db context record]
  (let [credit (cond (= "-" (.trim (:credit record))) 0
                     :otherwise (.parse (java.text.DecimalFormat.) (:credit record)))
        debit (cond (= "-" (.trim (:debit record))) 0
                    :otherwise (.parse (java.text.DecimalFormat.) (:debit record)))
        fields {:date (.parse (java.text.SimpleDateFormat. "dd MMM y z") (str (:date record) " UTC"))
                :description (:description record)}]

    (when-let [fields2
               (cond
                (and (pos? debit) (re-matches #".*LIKELY LTD.*" (:description record)))
                (assoc {}
                  :credit-account (account-of db {:entity :likely :type :pro.juxt.accounting/invoiced})
                  :debit-account (account-of db {:entity :congreve :type :pro.juxt.accounting/current-account})
                  :amount (amount-of GBP debit))

                (and (pos? credit) (re-matches #".*HMRC VAT.*" (:description record)))
                (assoc {}
                  :credit-account (account-of db {:entity :congreve :type :pro.juxt.accounting/current-account})
                  :debit-account (account-of db {:entity :congreve :type :pro.juxt.accounting/vat-owing})
                  :amount (amount-of GBP credit)))]
      (merge fields fields2))))
