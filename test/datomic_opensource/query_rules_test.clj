(ns datomic-opensource.query-rules-test
  "Behavioral seam: query expressiveness — predicates and function
  expressions in :where, rules (including recursion) via the % input,
  aggregates in :find with grouping and :with."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic-opensource.api :as api]
            [datomic-opensource.test-util :refer [define! fresh-conn]]))

(defn load-fixture! []
  (let [conn (fresh-conn)]
    (define! conn "n" :person/name :db.type/string)
    (define! conn "a" :person/age :db.type/long)
    (define! conn "p" :person/parent :db.type/ref)
    (define! conn "f" :person/friend :db.type/ref)
    (define! conn "l" :person/like :db.type/string {:cardinality :db.cardinality/many})
    (define! conn "on" :order/number :db.type/long)
    (define! conn "oo" :order/owner :db.type/ref)
    (api/transact conn
                  [[:db/add "alice" :person/name "Alice"]
                   [:db/add "alice" :person/age 30]
                   [:db/add "alice" :person/like "chess"]
                   [:db/add "alice" :person/like "go"]
                   [:db/add "bob" :person/name "Bob"]
                   [:db/add "bob" :person/age 25]
                   [:db/add "bob" :person/like "chess"]
                   [:db/add "carol" :person/name "Carol"]
                   [:db/add "carol" :person/age 30]
                   ;; parent chain: alice -> bob -> carol (alice parent of bob, etc.)
                   [:db/add "bob" :person/parent "alice"]
                   [:db/add "carol" :person/parent "bob"]
                   [:db/add "alice" :person/friend "carol"]
                   [:db/add "o1" :order/number 1]
                   [:db/add "o1" :order/owner "alice"]
                   [:db/add "o2" :order/number 2]
                   [:db/add "o2" :order/owner "bob"]
                   [:db/add "o3" :order/number 3]
                   [:db/add "o3" :order/owner "alice"]])
    conn))

(defn q-one [conn query & inputs]
  (apply api/q query (api/db conn) inputs))

;; ---- predicates and function expressions ------------------------------------

(deftest predicates-filter-solutions
  (let [conn (load-fixture!)]
    (testing "comparison against a constant"
      (is (= #{"Bob"}
             (set (map first (q-one conn '[:find ?n
                                           :where [?p :person/age ?a]
                                                  [(< ?a 30)]
                                                  [?p :person/name ?n]])))))
      (is (= #{"Alice" "Carol"}
             (set (map first (q-one conn '[:find ?n
                                           :where [?p :person/age ?a]
                                                  [(>= ?a 30)]
                                                  [?p :person/name ?n]]))))))
    (testing "equality between a var and a constant"
      (is (= #{"Alice"}
             (set (map first (q-one conn '[:find ?n
                                           :where [?p :person/name ?n]
                                                  [(= ?n "Alice")]]))))))
    (testing "comparison between two vars"
      (is (= #{"Alice" "Carol"}
             (set (map first (q-one conn '[:find ?n1
                                           :where [?p1 :person/age ?a]
                                                  [?p2 :person/age ?a]
                                                  [?p1 :person/name ?n1]
                                                  [?p2 :person/name ?n2]
                                                  [(not= ?n1 ?n2)]]))))))))

(deftest function-expressions-bind-results
  (let [conn (load-fixture!)]
    (testing "arithmetic on a bound input"
      (is (= [35]
             (mapv first (q-one conn '[:find ?y
                                       :in $ ?x
                                       :where [(+ ?x 5) ?y]]
                                30)))))
    (testing "string built-in over bound data"
      (is (= #{"ALICE"}
             (set (map first (q-one conn '[:find ?u
                                           :in $ ?n
                                           :where [?p :person/name ?n]
                                                  [(upper-case ?n) ?u]]
                                    "Alice"))))))))

;; ---- rules ------------------------------------------------------------------

(def ^:private friend-rules
  '[[(friend ?a ?b) [?a :person/friend ?b]]])

(def ^:private ancestor-rules
  '[[(ancestor ?a ?d) [?d :person/parent ?a]]
    [(ancestor ?a ?d) [?d :person/parent ?p] (ancestor ?a ?p)]])

(deftest non-recursive-rules-behave-like-views
  (let [conn (load-fixture!)]
    (is (= #{"Carol"}
           (set (map first (q-one conn '[:find ?fn
                                         :in $ %
                                         :where (friend ?a ?b)
                                                 [?a :person/name "Alice"]
                                                 [?b :person/name ?fn]]
                                  friend-rules)))))))

(deftest recursive-rules-terminate-with-complete-closure
  (let [conn (load-fixture!)]
    (testing "all ancestor pairs over the three-link chain"
      ;; alice->bob, bob->carol, plus transitive alice->carol = 3 pairs
      (is (= 3
             (count (q-one conn '[:find ?a ?d
                                  :in $ %
                                  :where (ancestor ?a ?d)]
                           ancestor-rules)))))
    (testing "transitive descendants of alice"
      (is (= #{"Bob" "Carol"}
             (set (map first (q-one conn '[:find ?dn
                                           :in $ %
                                           :where (ancestor ?a ?d)
                                                  [?a :person/name "Alice"]
                                                  [?d :person/name ?dn]]
                                    ancestor-rules))))))))

;; ---- aggregates ---------------------------------------------------------------

(deftest single-value-aggregates-over-order-numbers
  (let [conn (load-fixture!)]
    (is (= [3] (mapv first (q-one conn '[:find (count ?n) :where [?o :order/number ?n]]))))
    (is (= [1] (mapv first (q-one conn '[:find (min ?n) :where [?o :order/number ?n]]))))
    (is (= [3] (mapv first (q-one conn '[:find (max ?n) :where [?o :order/number ?n]]))))
    (is (= [6] (mapv first (q-one conn '[:find (sum ?n) :where [?o :order/number ?n]]))))
    (is (= [2.0] (mapv first (q-one conn '[:find (avg ?n) :where [?o :order/number ?n]]))))
    (is (= [2] (mapv first (q-one conn '[:find (median ?n) :where [?o :order/number ?n]]))))
    (is (= [0.6666666666666666]
           (mapv first (q-one conn '[:find (variance ?n) :where [?o :order/number ?n]]))))
    (is (= [0.816496580927726]
           (mapv first (q-one conn '[:find (std-dev ?n) :where [?o :order/number ?n]]))))))

(deftest count-distinct-and-with-n-aggregates
  (let [conn (load-fixture!)]
    (is (= [2]
           (mapv first (q-one conn '[:find (count-distinct ?a) :where [?p :person/age ?a]]))))
    (testing "min/max with n return sorted collections"
      (is (= [[25 30]]
             (mapv first (q-one conn '[:find (min 2 ?a) :where [?p :person/age ?a]]))))
      (is (= [[30 25]]
             (mapv first (q-one conn '[:find (max 2 ?a) :where [?p :person/age ?a]])))))
    (testing "rand and sample return n values"
      (is (= 2 (count (ffirst (q-one conn '[:find (rand 2 ?a) :where [?p :person/age ?a]])))))
      (is (= 2 (count (ffirst (q-one conn '[:find (sample 2 ?a) :where [?p :person/age ?a]]))))))))

(deftest aggregates-group-by-plain-find-vars
  (let [conn (load-fixture!)]
    (is (= {"Alice" 2 "Bob" 1}
           (into {} (q-one conn '[:find ?name (count ?o)
                                  :where [?p :person/name ?name]
                                         [?o :order/owner ?p]]))))))

(deftest aggregates-compose-with-with-clause
  (let [conn (load-fixture!)]
    (testing "without :with, the aggregate var dedupes"
      ;; distinct likes across everyone: chess, go
      (is (= [2]
             (mapv first (q-one conn '[:find (count ?like)
                                       :where [?p :person/like ?like]])))))
    (testing "with :with, dedupe covers the with vars too (per-person pairs)"
      ;; (chess alice) (go alice) (chess bob) = 3
      (is (= [3]
             (mapv first (q-one conn '[:find (count ?like) :with ?p
                                       :where [?p :person/like ?like]])))))))

;; ---- typed errors -------------------------------------------------------------

(deftest expressiveness-errors-are-typed
  (let [conn (load-fixture!)]
    (testing "unknown builtin"
      (try (q-one conn '[:find ?y :in $ ?x :where [(frobnicate ?x) ?y]] 1)
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/unknown-builtin (:datomic-opensource/error (ex-data e)))))))
    (testing "unbound var in predicate"
      (try (q-one conn '[:find ?p :where [?p :person/name _] [(< ?never 3)]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/unbound-var (:datomic-opensource/error (ex-data e)))))))
    (testing "unknown rule"
      (try (q-one conn '[:find ?a ?b :in $ % :where (no-such-rule ?a ?b)] friend-rules)
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/unknown-rule (:datomic-opensource/error (ex-data e)))))))
    (testing "unknown aggregate"
      (try (q-one conn '[:find (wibble ?n) :where [?o :order/number ?n]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/unknown-aggregate (:datomic-opensource/error (ex-data e)))))))
    (testing "rand without count argument"
      (try (q-one conn '[:find (rand ?n) :where [?o :order/number ?n]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/bad-aggregate (:datomic-opensource/error (ex-data e)))))))
    (testing "aggregate in scalar find spec"
      (try (q-one conn '[:find [(count ?n) .] :where [?o :order/number ?n]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/bad-find (:datomic-opensource/error (ex-data e)))))))))

(deftest wildcards-are-anonymous-in-rule-calls
  (let [conn (load-fixture!)]
    (testing "(ancestor _ ?d) matches every derived descendant"
      (is (= #{"Bob" "Carol"}
             (set (map first (q-one conn '[:find ?dn
                                           :in $ %
                                           :where (ancestor _ ?d)
                                                  [?d :person/name ?dn]]
                                    ancestor-rules))))))))
