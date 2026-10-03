(ns kala.storage
  "The storage protocol (spec seam 2): content-addressed segments plus an
  ordered, append-only transaction log keyed by t. Implementations must make
  a log append durable before acknowledging it; adapters beyond memory
  (local file in TT8) implement this same contract.")

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
    "All log entries with from-t <= t <= to-t, ordered by t ascending."))
