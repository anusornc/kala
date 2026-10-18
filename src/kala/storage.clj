(ns kala.storage
  "The storage protocol (spec seam 2): content-addressed segments plus an
  ordered, append-only transaction log keyed by t. Implementations must make
  a log append durable before acknowledging it; adapters beyond memory
  (local file in TT8) implement this same contract. The log's monotonicity
  guard lives here too, so every backend raises the same error shape.")

(defprotocol Storage
  (put-segment [storage id data]
    "Store data under a unique id. Idempotent for a given id.")
  (get-segment [storage id]
    "The data stored under id, or nil when absent.")
  (list-segments [storage]
    "A seq of all stored segment ids.")
  (append-log [storage entry]
    "Append one log entry, a map holding :t and :datoms. Returns :ok once the
     entry is durable. Entries must carry strictly increasing :t.")
  (log-scan [storage from-t to-t]
    "All log entries with from-t <= t <= to-t, ordered by t ascending.")
  (prune-log! [storage up-to-t]
    "Remove every log entry with t <= up-to-t — compaction's second half.
     The pair is ordered: put the snapshot segment FIRST (its put is
     durable), prune after, so a crash anywhere between leaves a store
     that replays identically (replay starts after the snapshot's base
     and simply ignores leftover pre-base entries). After pruning, appends
     must exceed up-to-t even when the pruned range held the newest
     entries."))

(def snapshot-segment-id
  "The segment id under which compaction stores the snapshot of live
  state (see kala.compaction). One fixed id, overwritten per compaction:
  snapshots never accumulate, and a crash mid-compaction leaves either
  the previous or the new snapshot — both replayable with whatever log
  remains."
  "kala.snapshot/current")

(defn check-monotonic-t
  "The log's one invariant, enforced at the append seam: t must be strictly
  greater than the backend's last acked t, or :storage/non-monotonic-t
  raises with {:t :last-t}. Adapters call this before appending — a new
  backend inherits the check instead of re-deriving it."
  [t last-t]
  (when (and (some? last-t) (<= t last-t))
    (throw (ex-info "Log :t must be strictly increasing"
                    {:kala/error :storage/non-monotonic-t
                     :t t :last-t last-t}))))
