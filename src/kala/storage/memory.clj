(ns kala.storage.memory
  "Volatile in-memory Storage adapter for development and testing."
  (:require [kala.storage :as storage]))

(defn empty-storage []
  (let [segments (atom {})
        log (atom (sorted-map))
        ;; the t floor prune-log! raises: the sorted map alone cannot hold
        ;; it once pruning removes the newest entries
        floor (atom 0)]
    (reify storage/Storage
      (put-segment [_ id data]
        (swap! segments assoc id data)
        :ok)
      (get-segment [_ id]
        (get @segments id))
      (list-segments [_]
        (keys @segments))
      (append-log [_ entry]
        (let [t (:t entry)]
          (storage/check-monotonic-t t (max (or (last (keys @log)) 0) @floor))
          (swap! log assoc t entry)
          :ok))
      (log-scan [_ from-t to-t]
        (vals (subseq @log >= from-t <= to-t)))
      (prune-log! [_ up-to-t]
        (swap! log (fn [m] (into (sorted-map) (filter #(> (key %) up-to-t)) m)))
        (swap! floor max up-to-t)
        :ok))))
