(ns datomic-opensource.test-util
  "Shared helpers for the behavioral-seam test suites."
  (:require [clojure.test :refer [is]]
            [datomic-opensource.api :as api]))

(defn fresh-conn []
  (api/connect {:storage :memory :name "test"}))

(defn define!
  ([conn tempid ident value-type]
   (define! conn tempid ident value-type nil))
  ([conn tempid ident value-type opts]
   (api/transact conn
                 (cond-> [[:db/add tempid :db/ident ident]
                          [:db/add tempid :db/valueType value-type]
                          [:db/add tempid :db/cardinality (or (:cardinality opts) :db.cardinality/one)]]
                   (:unique opts)    (conj [:db/add tempid :db/unique (:unique opts)])
                   (:component opts) (conj [:db/add tempid :db/isComponent true])))))

(defmacro assert-error [kind & body]
  `(try ~@body
        (is false ~(str "expected ex-info " kind))
        (catch clojure.lang.ExceptionInfo e#
          (is (= ~kind (:datomic-opensource/error (ex-data e#)))))))
