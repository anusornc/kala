(ns kala.compaction
  "Compaction: snapshot the live state, prune the log it covers.

  The RAM-bound ceiling (ADR-0005) is that a file store replays its whole
  log at boot — every correction a store ever absorbed costs boot RAM
  forever. Compaction bounds that replay to the live set: the current
  datoms as of one t go into a snapshot SEGMENT (the protocol's segment
  half, exercised at last), and the log entries the snapshot covers are
  pruned. Boot then replays snapshot + tail.

  The order is the whole correctness story. The snapshot put is durable
  BEFORE any pruning starts, and replay starts after the snapshot's base,
  so every crash window replays identically:

    - crash before the put: nothing happened;
    - crash after the put, before the prune: snapshot + full log — replay
      reads snapshot, skips entries up to the base, ignores nothing new;
    - crash mid-prune: leftover pre-base entries are skipped by range.

  What pruning costs is history below the base: :as-of t for t < base
  raises :db/pruned-t, and history shows surviving datoms plus the full
  post-compaction story. Corrections before the base stay corrected — the
  snapshot holds their outcome — but the trail to them is gone. That is
  the trade ADR-0008 records; compaction is therefore an explicit,
  opt-in call (kala.api/compact), never a background process.

  Snapshot datoms keep their original tx coordinates, so time-travel
  reads above the base are exact, and the snapshot carries the user
  entity-id allocation counter explicitly — a live-state-only max could
  not recover the ids of entities whose every datom was retracted."
  (:require [kala.core :as core]
            [kala.storage :as storage])
  (:import [kala.core Datom]))

(defn live-raw-datoms
  "The db value's live state as raw storage tuples — [e attr-id v tx true]
  in EAVT order, attr ids raw, each datom's original tx kept."
  [db]
  (mapv (fn [^Datom d] [(.e d) (.a d) (.v d) (.tx d) true])
        (core/index-slice db :eavt [])))

(defn snapshot
  "The snapshot document for a db value: its t, the user allocation
  counter, and the live datoms."
  [db]
  {:kala/snapshot true
   :t (core/db-t db)
   :next-idx (get-in db [::core/next-idx 1])
   :datoms (live-raw-datoms db)})

(defn snapshot-base
  "The t of storage's current snapshot, or nil when the store has never
  been compacted."
  [storage]
  (let [s (storage/get-segment storage storage/snapshot-segment-id)]
    (when (and (map? s) (:kala/snapshot s) (int? (:t s)))
      (:t s))))

(defn compact!
  "Compact a peer connection's store: write the snapshot segment for the
  connection's current t, then prune the log entries at or below it.
  Returns {:t the-base :datoms snapshotted :pruned pruned}.

  Crash-safe by ordering (see the namespace): the put acks durable
  before the prune starts, and both halves are idempotent, so re-running
  after an interruption finishes the job. Concurrent transacts are safe
  to interleave — they land above the base and never touch the pruned
  range — and the connection is re-annotated atomically so later as-of
  reads know where history now begins."
  [conn]
  (let [db @conn
        storage (core/db-storage db)
        snap (snapshot db)
        base (:t snap)
        pruned (count (storage/log-scan storage 0 base))]
    (storage/put-segment storage storage/snapshot-segment-id snap)
    (storage/prune-log! storage base)
    ;; swap!, not reset!: a transact landing between the prune and this
    ;; line keeps its db-after, annotated with the base it now sits above
    (swap! conn assoc ::core/base-t base)
    {:t base :datoms (count (:datoms snap)) :pruned pruned}))
