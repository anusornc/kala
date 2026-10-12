(ns kala.eid
  "Entity ids: one positive long packing a partition, a block, and an index.
  Partition 0 holds the bootstrap schema (attributes and their enums),
  partition 1 holds transacted entities, partition 2 holds transaction
  entities, which pack t into the payload bits.

  This namespace is a pure-arithmetic leaf: it requires nothing and never
  sees a db value. Callers own the allocation counter — kala.tx keeps
  ::core/next-idx in the db value and advances it through alloc-user-eid,
  and replay restores that counter with user-alloc-index. tx entity ids are
  monotonic in t: kala.time-travel's since compares them as packed longs
  and relies on it.")

(def ^:private partition-bits 42)

(def ^:private partitions
  {:db.part/db 0
   :db.part/user 1
   :db.part/tx 2})

(def ^:private payload-mask
  (dec (bit-shift-left 1 partition-bits)))

(def ^:private block-bits 10)
(def ^:private block-count (bit-shift-left 1 block-bits))

(defn- eid [partition block idx]
  (bit-or (bit-shift-left partition partition-bits)
          (bit-shift-left block block-bits)
          idx))

(defn- eid->partition [e]
  (bit-shift-right e partition-bits))

(defn- partition-of? [p x]
  (and (int? x) (pos? x) (= p (eid->partition x))))

(defn bootstrap-eid
  "The entity id of bootstrap-schema placement idx: block 0 of partition 0."
  [idx]
  (eid (:db.part/db partitions) 0 idx))

(defn bootstrap-eid?
  "Is x a partition-0 (bootstrap schema) entity id?"
  [x]
  (partition-of? 0 x))

(defn alloc-user-eid
  "The user entity id of allocation counter n: block (quot n block-count),
  index (rem n block-count). Total and injective in n."
  [n]
  (eid (:db.part/user partitions) (quot n block-count) (rem n block-count)))

(defn user-alloc-index
  "The allocation counter one past user entity id x — the inverse of
  alloc-user-eid — or nil if x is not a user entity id. Replay restores
  ::core/next-idx by taking the max of this over every datom's entity slot."
  [x]
  (when (partition-of? (:db.part/user partitions) x)
    (inc (+ (* (bit-and (bit-shift-right x block-bits) (dec block-count))
               block-count)
            (bit-and x (dec block-count))))))

(defn user-eid?
  "Is x a partition-1 (transacted entity) id?"
  [x]
  (partition-of? (:db.part/user partitions) x))

(defn tx-eid
  "The transaction entity id of t. Monotonic in t — the since view compares
  tx entity ids as packed longs and relies on it."
  [t]
  (eid (:db.part/tx partitions) 0 t))

(defn tx-eid?
  "Is x a partition-2 (transaction entity) id?"
  [x]
  (partition-of? (:db.part/tx partitions) x))

(defn eid->t
  "Extract the transaction counter from a tx entity id."
  [e]
  (bit-and e payload-mask))
