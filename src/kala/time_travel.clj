(ns kala.time-travel
  "Time travel over the storage log: replay, as-of snapshots, since views,
  history, and speculative with(). Rebuilding a db re-applies every log
  entry through the transaction pipeline's index and schema primitives, so
  a rebuilt db value equals the live one."
  (:require [kala.core :as core]
            [kala.storage :as storage]
            [kala.tx :as tx])
  (:import [kala.core Datom]))

;; tx entity ids pack t into the low 42 bits (partition 2)
(def ^:private t-bits-mask 0x3FFFFFFFFFF)

(defn eid->t
  "Extract the transaction counter from a tx entity id."
  [e]
  (bit-and e t-bits-mask))

(defn- normalize-t [x]
  (cond
    (not (int? x))
    (core/err :db/bad-t "t must be a counter or a tx entity id" {:t x})
    (= 2 (core/eid->partition x)) (eid->t x)
    (= 1 (core/eid->partition x))
    (core/err :db/bad-t "Expected t or a tx entity id, got a user entity id" {:e x})
    :else x))

;; Log entries round-trip through Java serialization, which yields Boolean
;; instances that are NOT reference-identical to the compiled true/false
;; literals — so true?/false? (identity checks) would misclassify them.
;; Compare by value and canonicalize.
(defn- raw->datom [[e a v tx added]]
  (core/->Datom e a v tx (boolean (= added true))))

;; Allocation index (block*1024 + idx) of a user-partition entity id, for
;; rebuilding ::core/next-idx during replay.
(defn- user-alloc-idx [x]
  (when (and (int? x) (pos? x) (= 1 (core/eid->partition x)))
    (inc (+ (* (bit-and (bit-shift-right x 10) 0x3FF) 1024)
            (bit-and x 0x3FF)))))

(defn- restore-next-idx [db datoms]
  (if-some [n (transduce (comp (mapcat (fn [^Datom d] [(.e d) (.v d)]))
                               (keep user-alloc-idx))
                         max 0 datoms)]
    (assoc-in db [::core/next-idx 1] (max n (get-in db [::core/next-idx 1] 0)))
    db))

;; Rebuild a db value from the storage log up to up-to-t. history? keeps
;; retraction entries as visible datoms (added=false) instead of applying
;; them as removals.
(defn- replay [storage up-to-t history?]
  (reduce (fn [db {:keys [t datoms]}]
            (let [datoms (mapv raw->datom datoms)
                  db (reduce (fn [db ^Datom d]
                               (if (or history? (.added d))
                                 (tx/update-indexes db conj d)
                                 (tx/update-indexes db disj d)))
                             db datoms)]
              (-> db
                  (tx/register-new-entities datoms)
                  (restore-next-idx datoms)
                  (assoc ::core/t t))))
          (core/empty-db storage)
          (storage/log-scan storage (inc core/bootstrap-t) up-to-t)))

(defn as-of
  "The db value as of transaction t (a t counter or a tx entity id)."
  [db t]
  (let [t (normalize-t t)]
    (when (< t core/bootstrap-t)
      (core/err :db/bad-t "t is before the bootstrap transaction" {:t t}))
    (if (>= t (::core/t db))
      db
      (replay (::core/storage db) t false))))

(defn since
  "A view of the db exposing only datoms asserted after t (a t counter or a
  tx entity id). Facts from t and earlier are invisible even though they
  remain current. Views are read-only: transact/with reject them."
  [db t]
  (let [t (normalize-t t)]
    (-> (reduce (fn [db index]
                  (let [{:keys [set cmp]} (core/index-specs index)]
                    (assoc db set
                           ;; datoms carry tx entity ids; tx-eid(t) is
                           ;; monotonic in t, so compare in eid space
                           (into (sorted-set-by cmp)
                                 (filter (fn [^Datom d] (> (.tx d) (core/tx-eid t)))
                                         (get db set))))))
                db
                (keys core/index-specs))
        (assoc ::core/view :since))))

(defn history
  "The db value containing every assertion and retraction ever transacted,
  each with its added flag."
  [db]
  (replay (::core/storage db) (::core/t db) true))

(defn db-with
  "Speculative transaction: applies tx-data against a db value and returns
  the report {:db-before :db-after :t :tx-data :tempids} without touching
  storage or the connection."
  [db tx-data]
  (dissoc (tx/transact-report db tx-data) :raw-datoms))

(defn restore-db
  "Rebuild the current db value from the complete storage log. Opening an
  existing database is: open storage, restore-db, continue — nothing is
  lost or applied twice."
  [storage]
  (replay storage Long/MAX_VALUE false))
