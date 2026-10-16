(ns kala.storage.memory
  "Volatile in-memory Storage adapter for development and testing."
  (:require [kala.storage :as storage]))

(defn empty-storage []
  (let [segments (atom {})
        log (atom (sorted-map))]
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
          (storage/check-monotonic-t t (last (keys @log)))
          (swap! log assoc t entry)
          :ok))
      (log-scan [_ from-t to-t]
        (vals (subseq @log >= from-t <= to-t))))))
