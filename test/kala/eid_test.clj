(ns kala.eid-test
  "Unit seam: the entity-id packing — partition/block/index packed into one
  positive long. Pure arithmetic on purpose: no db values, no storage, no
  matrix. The pins here guard what the rest of the system leans on —
  allocation round-trips through restore, tx entity ids stay monotonic in t,
  and the partition predicates classify cleanly."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.eid :as eid]))

(deftest allocation-round-trips-through-restore
  (testing "user-alloc-index inverts alloc-user-eid, across the block boundary"
    (doseq [n [0 1 1023 1024 1025 4095 4096]]
      (is (= (inc n) (eid/user-alloc-index (eid/alloc-user-eid n))))))
  (testing "replay restores a counter that continues allocation without collision"
    (let [n 5000
          restored (dec (eid/user-alloc-index (eid/alloc-user-eid n)))]
      (is (= n restored))
      (is (not= (eid/alloc-user-eid (inc n)) (eid/alloc-user-eid n))))))

(deftest allocation-is-total-and-injective-over-a-range
  (let [eids (mapv eid/alloc-user-eid (range 0 3000))]
    (is (every? pos? eids))
    (is (apply distinct? eids))
    (is (= (mapv inc (range 0 3000))
           (mapv eid/user-alloc-index eids)))))

(deftest tx-eids-are-monotonic-in-t
  ;; the since view compares tx entity ids as packed longs and relies on this
  (let [ts (vec (range 1 2000))
        es (mapv eid/tx-eid ts)]
    (is (apply < es))
    (is (= ts (mapv eid/eid->t es)))))

(deftest partition-predicates-classify
  (let [user (eid/alloc-user-eid 0)
        tx (eid/tx-eid 1)
        boot (eid/bootstrap-eid 1)]
    (is (eid/user-eid? user))
    (is (eid/tx-eid? tx))
    (is (eid/bootstrap-eid? boot))
    (is (not (eid/user-eid? tx)))
    (is (not (eid/tx-eid? user)))
    (is (not (eid/bootstrap-eid? user))))
  (testing "non-ids classify as nothing"
    (doseq [x [nil "e" :kw 0 -5 true 2.5]]
      (is (and (not (eid/user-eid? x))
               (not (eid/tx-eid? x))
               (not (eid/bootstrap-eid? x)))))))

(deftest bootstrap-placement-is-deterministic
  (is (= (eid/bootstrap-eid 1) (eid/bootstrap-eid 1)))
  (is (not= (eid/bootstrap-eid 1) (eid/bootstrap-eid 2)))
  (is (eid/bootstrap-eid? (eid/bootstrap-eid 42))))
