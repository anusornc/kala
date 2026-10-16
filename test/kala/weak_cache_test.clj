(ns kala.weak-cache-test
  "Unit seam: the value-keyed weak cache — the pattern ADR-0002 mandates
  (caches live outside immutable db values, in weak maps) as one tested
  module instead of hand-rolled copies. Unit only: no db values, no
  storage, no matrix. The eviction pin leans on explicit gc — HotSpot
  honors it, but that is the one environment-sensitive assertion here."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.weak-cache :as wc]))

(deftest fetch-builds-once-and-shares-per-key
  (let [cache (wc/make)
        k {:db "value"}
        calls (atom 0)
        build (fn [] (swap! calls inc) (Object.))
        v1 (wc/fetch cache k [:views :a] build)
        v2 (wc/fetch cache k [:views :a] build)]
    (is (= 1 @calls))
    (is (identical? v1 v2))
    (is (not (identical? v1 (wc/fetch cache k [:views :b] build)))
        "different entry keys in one db value hold separate entries")))

(deftest equal-keys-share-and-distinct-keys-isolate
  (let [cache (wc/make)
        build (fn [] (Object.))
        v1 (wc/fetch cache {:t 1} [:x] build)
        v2 (wc/fetch cache {:t 1} [:x] build)
        v3 (wc/fetch cache {:t 2} [:x] build)]
    (is (identical? v1 v2) "equal db values legitimately share views")
    (is (not (identical? v1 v3)))))

(deftest stored-value-replacement-builds-at-most-once-per-key
  ;; the replay-cache pattern: store the value stripped of its back-reference,
  ;; re-assoc on the way out — fetch returns whatever build returned
  (let [cache (wc/make)
        storage (Object.)
        calls (atom 0)
        build (fn [] (swap! calls inc) {:payload :db :back-ref :stripped})
        fetch (fn [] (assoc (wc/fetch cache storage [:replay 1 false] build)
                            :back-ref storage))]
    (let [_ (fetch) _ (fetch)]
      (is (= 1 @calls))
      (is (= {:payload :db :back-ref storage} (fetch))))))

(deftest a-falsy-build-result-raises-instead-of-rebuilding-forever
  ;; the contract the docstring used to state without enforcing: a stored
  ;; nil or false would rebuild on every fetch, silently
  (let [cache (wc/make)]
    (is (thrown? clojure.lang.ExceptionInfo (wc/fetch cache :k :e #(do :nothing nil))))
    (is (thrown? clojure.lang.ExceptionInfo (wc/fetch cache :k :e (fn [] false))))
    (is (= :ok (wc/fetch cache :k :e (fn [] :ok))) "a truthy value still caches")))

(deftest entries-die-with-their-key
  ;; weak eviction is the module's reason to exist: the entry atom must be
  ;; reclaimable once the last strong reference to the key is dropped. GC is
  ;; not scheduled, so poll with an explicit gc and fresh table traffic.
  (let [cache (wc/make)
        k (Object.)
        ^java.lang.ref.WeakReference ref
        (java.lang.ref.WeakReference. (wc/per-key cache k))
        _ (System/gc)
        collected (loop [i 0]
                    (or (nil? (.get ref))
                        (when (< i 50)
                          (System/gc)
                          (byte-array (* 8 1024 1024)) ; churn to nudge the collector
                          (wc/fetch cache (Object.) [:traffic] #(Object.))
                          (recur (inc i)))))]
    (is collected "the per-key atom dies with its key (50 gc rounds should do it)")
    (is (some? (wc/per-key cache (Object.))) "the table still serves fresh keys")))
