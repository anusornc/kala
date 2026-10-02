(ns datomic-opensource.storage.memory-test
  "Storage seam: the memory adapter must satisfy the Storage protocol contract
  independently of the database above it."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic-opensource.storage :as storage]
            [datomic-opensource.storage.memory :as memory]))

(defn- entry [t] {:t t :datoms [[1 2 (str "v" t) t true]]})

(deftest segment-round-trip
  (let [s (memory/empty-storage)]
    (storage/put-segment s :seg/a {:payload 1})
    (is (= {:payload 1} (storage/get-segment s :seg/a)))
    (is (nil? (storage/get-segment s :seg/missing)))))

(deftest segment-put-is-idempotent
  (let [s (memory/empty-storage)]
    (storage/put-segment s :seg/a {:n 1})
    (storage/put-segment s :seg/a {:n 1})
    (is (= {:n 1} (storage/get-segment s :seg/a)))
    (is (= [:seg/a] (sort (storage/list-segments s))))))

(deftest list-segments-returns-all
  (let [s (memory/empty-storage)]
    (storage/put-segment s :seg/a 1)
    (storage/put-segment s :seg/b 2)
    (is (= [:seg/a :seg/b] (sort (storage/list-segments s))))))

(deftest log-scan-returns-entries-in-t-order
  (let [s (memory/empty-storage)]
    (doseq [t [2 5 9]] (storage/append-log s (entry t)))
    (is (= [2 5 9] (mapv :t (storage/log-scan s 0 Long/MAX_VALUE))))))

(deftest log-append-requires-strictly-increasing-t
  (let [s (memory/empty-storage)]
    (storage/append-log s (entry 5))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strictly increasing"
                          (storage/append-log s (entry 5))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strictly increasing"
                          (storage/append-log s (entry 4))))
    (is (= [5] (mapv :t (storage/log-scan s 0 Long/MAX_VALUE))))))

(deftest log-scan-respects-range
  (let [s (memory/empty-storage)]
    (doseq [t [2 5 9]] (storage/append-log s (entry t)))
    (is (= [5] (mapv :t (storage/log-scan s 5 5))))
    (is (= [5 9] (mapv :t (storage/log-scan s 5 Long/MAX_VALUE))))
    (is (= [] (mapv :t (storage/log-scan s 10 Long/MAX_VALUE))))))

(deftest log-entries-round-trip-content
  (let [s (memory/empty-storage)
        e {:t 3 :datoms [[7 8 9 3 true]]}]
    (is (= :ok (storage/append-log s e)))
    (is (= [e] (storage/log-scan s 3 3)))))
