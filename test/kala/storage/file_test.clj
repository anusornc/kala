(ns kala.storage.file-test
  "Storage seam: the file adapter must satisfy the Storage protocol contract
  independently of the database above it — including durability-before-ack,
  observed as visibility to a freshly opened adapter over the same directory."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.storage :as storage]
            [kala.storage.file :as file])
  (:import [java.nio.file Files]
           [java.util UUID]))

(defn- tmp-dir []
  (str (Files/createTempDirectory
        (str "dos-file-test-" (UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private root (tmp-dir))

(defn- fresh-storage [] (file/open-storage (str root "/" (UUID/randomUUID))))

(defn- entry [t] {:t t :datoms [[1 2 (str "v" t) t true]]})

(deftest segment-round-trip
  (let [s (fresh-storage)]
    (storage/put-segment s "seg/a" {:payload 1})
    (is (= {:payload 1} (storage/get-segment s "seg/a")))
    (is (nil? (storage/get-segment s "seg/missing")))))

(deftest segment-put-is-idempotent
  (let [s (fresh-storage)]
    (storage/put-segment s "seg/a" {:n 1})
    (storage/put-segment s "seg/a" {:n 1})
    (is (= {:n 1} (storage/get-segment s "seg/a")))
    (is (= ["seg/a"] (storage/list-segments s)))))

(deftest list-segments-returns-all
  (let [s (fresh-storage)]
    (storage/put-segment s "seg/a" 1)
    (storage/put-segment s "seg/b" 2)
    (is (= #{"seg/a" "seg/b"} (set (storage/list-segments s))))))

(deftest log-scan-returns-entries-in-t-order
  (let [s (fresh-storage)]
    (doseq [t [2 5 9]] (storage/append-log s (entry t)))
    (is (= [2 5 9] (mapv :t (storage/log-scan s 0 Long/MAX_VALUE))))))

(deftest log-scan-respects-range
  (let [s (fresh-storage)]
    (doseq [t [2 5 9]] (storage/append-log s (entry t)))
    (is (= [5] (mapv :t (storage/log-scan s 5 5))))
    (is (= [5 9] (mapv :t (storage/log-scan s 5 Long/MAX_VALUE))))
    (is (= [] (mapv :t (storage/log-scan s 10 Long/MAX_VALUE))))))

(deftest log-append-requires-strictly-increasing-t
  (let [s (fresh-storage)]
    (storage/append-log s (entry 5))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strictly increasing"
                          (storage/append-log s (entry 5))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strictly increasing"
                          (storage/append-log s (entry 4))))
    (is (= [5] (mapv :t (storage/log-scan s 0 Long/MAX_VALUE))))))

(deftest log-entries-round-trip-content
  (let [s (fresh-storage)
        e {:t 3 :datoms [[7 8 9 3 true]]}]
    (is (= :ok (storage/append-log s e)))
    (is (= [e] (storage/log-scan s 3 3)))))

(deftest acked-appends-are-durable-across-fresh-open
  (testing "durability-before-ack: a new adapter over the same directory
            sees every acked entry — the crash-recovery contract"
    (let [dir (str root "/" (UUID/randomUUID))
          s1 (file/open-storage dir)]
      (storage/append-log s1 (entry 2))
      (storage/append-log s1 (entry 3))
      (storage/put-segment s1 "seg/x" {:v 42})
      (let [s2 (file/open-storage dir)]
        (is (= [2 3] (mapv :t (storage/log-scan s2 0 Long/MAX_VALUE))))
        (is (= {:v 42} (storage/get-segment s2 "seg/x")))))))
