(ns kala.time-travel
  "Time travel over the storage log: replay, as-of snapshots, since views,
  history, and speculative with(). Rebuilding a db re-applies every log
  entry through the transaction pipeline's index and schema primitives, so
  a rebuilt db value equals the live one."
  (:require [kala.core :as core]
            [kala.eid :as eid]
            [kala.storage :as storage]
            [kala.tx :as tx]
            [kala.weak-cache :as weak-cache])
  (:import [kala.core Datom]))

(defn- normalize-t [x]
  (cond
    (not (int? x))
    (core/err :db/bad-t "t must be a counter or a tx entity id" {:t x})
    (eid/tx-eid? x) (eid/eid->t x)
    (eid/user-eid? x)
    (core/err :db/bad-t "Expected t or a tx entity id, got a user entity id" {:e x})
    :else x))

;; Log entries round-trip through Java serialization, which yields Boolean
;; instances that are NOT reference-identical to the compiled true/false
;; literals — so true?/false? (identity checks) would misclassify them.
;; Compare by value and canonicalize.
(defn- raw->datom [[e a v tx added]]
  (core/->Datom e a v tx (boolean (= added true))))

;; Restore the user allocation counter by taking the max over every datom's
;; entity slot: an allocated eid always appears in the e-slot of the entry
;; that created it, so a whole-log max cannot miss one. kala.eid's
;; user-alloc-index is the packing's own inverse; value slots are skipped.
(defn- restore-next-idx [db datoms]
  (if-some [n (transduce (comp (map (fn [^Datom d] (.e d)))
                               (keep eid/user-alloc-index))
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
                                 (tx/retract-from-indexes db d)))
                             db datoms)]
              (-> db
                  (tx/derive-schema datoms)
                  (restore-next-idx datoms)
                  (assoc ::core/t t))))
          (core/empty-db storage)
          (storage/log-scan storage (inc core/bootstrap-t) up-to-t)))

;; ---- replay cache ----------------------------------------------------------------
;; The log is append-only, so a replay up to a fixed t is a pure function of
;; entries that can never change: as-of and history are cacheable with no
;; invalidation. The cache is a kala.weak-cache keyed by the storage handle —
;; both storage backends hand out fresh reify handles whose equality is
;; identity, so equal-but-distinct handles over one directory simply hold
;; separate entries. Entries store the replayed db stripped of ::storage per
;; the module's no-self-reference invariant, and every fetch re-assocs the
;; live handle, restoring a db equal to a fresh replay.

(def ^:private replay-caches (weak-cache/make))

(defn- cached-replay [storage up-to-t history?]
  (assoc (weak-cache/fetch replay-caches storage [up-to-t history?]
                           #(dissoc (replay storage up-to-t history?) ::core/storage))
         ::core/storage storage))

(defn as-of
  "The db value as of transaction t (a t counter or a tx entity id)."
  [db t]
  (let [t (normalize-t t)]
    (when (< t core/bootstrap-t)
      (core/err :db/bad-t "t is before the bootstrap transaction" {:t t}))
    (if (>= t (::core/t db))
      db
      (cached-replay (::core/storage db) t false))))

(defn since
  "A view of the db exposing only datoms asserted after t (a t counter or a
  tx entity id). Facts from t and earlier are invisible even though they
  remain current. Views are read-only: transact/with reject them."
  [db t]
  (let [t (normalize-t t)]
    (-> (reduce (fn [db index]
                  (let [{:keys [set cmp]} (core/index-specs index)]
                    (assoc db set
                           ;; datoms carry tx entity ids; tx-eid is
                           ;; monotonic in t (see kala.eid/tx-eid), so
                           ;; the packed longs compare directly
                           (into (sorted-set-by cmp)
                                 (filter (fn [^Datom d] (> (.tx d) (eid/tx-eid t)))
                                         (get db set))))))
                db
                (keys core/index-specs))
        (assoc ::core/view :since))))

(defn history
  "The db value containing every assertion and retraction ever transacted,
  each with its added flag."
  [db]
  (cached-replay (::core/storage db) (::core/t db) true))

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
  ;; deliberately uncached: its up-to-t is the complete CURRENT log, which
  ;; grows — a fixed-t cache entry would serve stale entries. as-of/history
  ;; keys are pinned by their caller's db t, so their content is frozen.
  [storage]
  (replay storage Long/MAX_VALUE false))
