(ns kala.query-test
  "Behavioral seam: Datalog queries — pattern clauses, joins incl. reverse
  refs, :in bindings (scalar/tuple/collection/relation/db), find specs,
  EDN string and data forms."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.test-util :refer [define! fresh-conn]]))

(defn load-fixture! []
  (let [conn (fresh-conn)]
    (define! conn "n" :person/name :db.type/string)
    (define! conn "a" :person/age :db.type/long)
    (define! conn "e" :person/email :db.type/string {:unique :db.unique/identity})
    (define! conn "f" :person/friend :db.type/ref)
    (define! conn "on" :order/number :db.type/long)
    (define! conn "oo" :order/owner :db.type/ref)
    (api/transact conn
                  [[:db/add "alice" :person/name "Alice"]
                   [:db/add "alice" :person/age 30]
                   [:db/add "alice" :person/email "a@x.io"]
                   [:db/add "bob" :person/name "Bob"]
                   [:db/add "bob" :person/age 25]
                   [:db/add "bob" :person/email "b@x.io"]
                   [:db/add "carol" :person/name "Carol"]
                   [:db/add "carol" :person/age 30]
                   [:db/add "carol" :person/email "c@x.io"]
                   [:db/add "alice" :person/friend "bob"]
                   [:db/add "o1" :order/number 1]
                   [:db/add "o1" :order/owner "alice"]
                   [:db/add "o2" :order/number 2]
                   [:db/add "o2" :order/owner "bob"]])
    conn))

(defn q-one [conn query & inputs]
  (apply api/q query (api/db conn) inputs))

(deftest basic-pattern-relation
  (let [conn (load-fixture!)]
    (is (= #{"Alice" "Bob" "Carol"}
           (set (map first (q-one conn '[:find ?n :where [_ :person/name ?n]])))))))

(deftest entity-reference-in-pattern-e-slot
  ;; #34: a keyword constant in a forward pattern's :e slot is the same
  ;; entity reference form datoms/pull/entity take
  (let [conn (load-fixture!)]
    (api/transact conn [[:db/add "ai" :db/ident :t/alice]
                        [:db/add "ai" :person/name "Alice Ident"]])
    (let [db (api/db conn)]
      (testing "a keyword entity ref resolves"
        (is (= #{["Alice Ident"]}
               (set (api/q '[:find ?n :where [:t/alice :person/name ?n]] db)))))
      (testing "an unresolvable one raises :query/unknown-ident — the :v slot's rule"
        (try (api/q '[:find ?n :where [:t/typo :person/name ?n]] db)
             (is false "expected ex-info")
             (catch clojure.lang.ExceptionInfo e
               (is (= :query/unknown-ident (:kala/error (ex-data e))))
               (is (= [:ident] (sort (disj (into #{} (keys (ex-data e))) :kala/error)))))))
      (testing "a keyword bound through :in stays a value, not a reference form — parity with the references, which do not resolve query arguments either"
        (is (= [] (api/q '[:find ?n :in $ ?e :where [?e :person/name ?n]] db :t/alice)))))))

(deftest joins-across-ref-attributes
  (let [conn (load-fixture!)]
    (testing "two-hop join through a ref"
      (is (= ["Bob"]
             (mapv first (q-one conn '[:find ?fn
                                       :in $ ?name
                                       :where [?p :person/name ?name]
                                              [?p :person/friend ?f]
                                              [?f :person/name ?fn]]
                                "Alice")))))
    (testing "join in both directions of the same attribute"
      (is (= #{"Alice"}
             (set (map first (q-one conn '[:find ?fn
                                           :in $ ?name
                                           :where [?p :person/name ?name]
                                                  [?f :person/friend ?p]
                                                  [?f :person/name ?fn]]
                                    "Bob"))))))))

(deftest reverse-ref-syntax
  (let [conn (load-fixture!)]
    (testing ":person/_friend finds who references the entity"
      (is (= #{"Alice"}
             (set (map first (q-one conn '[:find ?who
                                           :in $ ?name
                                           :where [?p :person/name ?name]
                                                  [?p :person/_friend ?who2]
                                                  [?who2 :person/name ?who]]
                                    "Bob"))))))))

(deftest constants-constrain-patterns
  (let [conn (load-fixture!)]
    (is (= #{"Alice" "Carol"}
           (set (map first (q-one conn '[:find ?n :where [?p :person/age 30]
                                                    [?p :person/name ?n]])))))))

(deftest ground-clause-constrains-solutions
  (let [conn (load-fixture!)
        {:keys [tempids]} (api/transact conn [[:db/add "dave" :person/name "Dave"]
                                              [:db/add "dave" :person/age 40]])
        dave (get tempids "dave")]
    ;; the ground clause pins dave's name; only matching solutions survive
    (is (= [40] (mapv first (q-one conn '[:find ?a
                                          :in $ ?p
                                          :where [?p :person/name "Dave"]
                                                 [?p :person/age ?a]]
                                   dave))))
    (is (empty? (q-one conn '[:find ?a
                              :in $ ?p
                              :where [?p :person/name "Nobody"]
                                     [?p :person/age ?a]]
                       dave)))))

(deftest avet-style-value-lookup
  (let [conn (load-fixture!)]
    (is (= 1 (count (q-one conn '[:find ?p :where [?p :person/email "b@x.io"]]))))))

(deftest scalar-tuple-collection-and-relation-inputs
  (let [conn (load-fixture!)]
    (testing "tuple binding"
      (is (= ["Alice"]
             (mapv first (q-one conn '[:find ?name
                                       :in $ [?name ?age]
                                       :where [?p :person/name ?name]
                                              [?p :person/age ?age]]
                                ["Alice" 30])))))
    (testing "collection binding"
      (is (= #{"Alice" "Bob"}
             (set (map first (q-one conn '[:find ?name
                                           :in $ [?name ...]
                                           :where [?p :person/name ?name]]
                                    ["Alice" "Bob"]))))))
    (testing "relation binding"
      (is (= #{"Alice" "Carol"}
             (set (map first (q-one conn '[:find ?name
                                           :in $ [[?name ?age]]
                                           :where [?p :person/name ?name]
                                                  [?p :person/age ?age]]
                                    [["Alice" 30] ["Carol" 30]]))))))))

;; ADR-0001: clauses execute in analyzed order; the result set is the
;; contract, row order is unspecified.
(deftest clauses-run-in-analyzed-order
  (let [conn (load-fixture!)]
    (testing "a function expression may consume a variable bound by a later-written clause"
      (is (= #{["Alice" 31] ["Bob" 26] ["Carol" 31]}
             (set (q-one conn '[:find ?n ?d
                                :where [(inc ?a) ?d]
                                       [?p :person/age ?a]
                                       [?p :person/name ?n]])))))
    (testing "an argument bound by no clause still raises :query/unbound-var"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Argument not bound"
                            (q-one conn '[:find ?n :where [(> ?a 3)] [_ :person/name ?n]]))))
    (testing "written order does not change the result set"
      (is (= (set (q-one conn '[:find ?fn :where [?p :person/name "Alice"]
                                               [?p :person/friend ?f]
                                               [?f :person/name ?fn]]))
             (set (q-one conn '[:find ?fn :where [?f :person/name ?fn]
                                                [?p :person/friend ?f]
                                                [?p :person/name "Alice"]]))))
      (is (= (set (q-one conn '[:find ?n :where [?p :person/age 30]
                                                [?p :person/name ?n]]))
             (set (q-one conn '[:find ?n :where [?p :person/name ?n]
                                                [?p :person/age 30]])))))
    (testing "reverse-attribute patterns reorder"
      (is (= (set (q-one conn '[:find ?who :where [?p :person/name "Bob"]
                                                  [?p :person/_friend ?who]]))
             (set (q-one conn '[:find ?who :where [?p :person/_friend ?who]
                                                  [?p :person/name "Bob"]])))))
    (testing "aggregates with :with reorder"
      (is (= (q-one conn '[:find (sum ?age) :with ?p :where [?p :person/age ?age]
                                                            [?p :person/email ?e]])
             (q-one conn '[:find (sum ?age) :with ?p :where [?p :person/email ?e]
                                                            [?p :person/age ?age]]))))
    (testing "a leading predicate over an :in-bound variable"
      (is (= #{["Alice"] ["Carol"]}
             (set (q-one conn '[:find ?n :in $ ?age :where [(< 0 ?age)]
                                                              [?p :person/age ?age]
                                                              [?p :person/name ?n]]
                         30))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No rule defined"
                            (q-one conn '[:find ?x :in $ % :where [?p :person/age 30] (missing ?x)]
                                   '[[(other ?x) [_ :person/age ?x]]]))))))

(deftest lexical-builtins-recall
  ;; includes?/starts-with?/ends-with?: the lexical-recall slice of the
  ;; data-only allowlist — regex stays out (agent-written patterns would
  ;; be a ReDoS surface on the transactor)
  (let [conn (load-fixture!)]
    (testing "includes? matches a fragment"
      (is (= #{"Alice"}
             (set (map first (q-one conn '[:find ?n :where [?p :person/name ?n]
                                                      [(includes? ?n "li")]]))))))
    (testing "starts-with? and ends-with? anchor the two ends"
      (is (= #{"Bob"}
             (set (map first (q-one conn '[:find ?n :where [?p :person/name ?n]
                                                      [(starts-with? ?n "B")]])))))
      (is (= #{"Alice"}
             (set (map first (q-one conn '[:find ?n :where [?p :person/name ?n]
                                                      [(ends-with? ?n "ce")]]))))))
    (testing "composed with lower-case for case-insensitive recall"
      (is (= #{"Alice" "Carol"}
             (set (map first (q-one conn '[:find ?n :where [?p :person/name ?n]
                                                      [(lower-case ?n) ?lc]
                                                      [(includes? ?lc "a")]]))))))))

(deftest find-specs-shape-the-result
  (let [conn (load-fixture!)
        names-relation (q-one conn '[:find ?n :where [_ :person/name ?n]])]
    (testing "collection spec"
      (is (sequential? (q-one conn '[:find [?n ...] :where [_ :person/name ?n]])))
      (is (= 3 (count (q-one conn '[:find [?n ...] :where [_ :person/name ?n]])))))
    (testing "scalar spec"
      (is (string? (q-one conn '[:find [?n] :where [_ :person/name ?n]])))
      (is (nil? (q-one conn '[:find [?n] :where [_ :person/name "Nobody"]]))))
    (testing "string spec joins with spaces"
      (is (string? (q-one conn '[:find [(str ?n)] :where [_ :person/name ?n]])))
      (is (= 3 (count (clojure.string/split
                        (q-one conn '[:find [(str ?n)] :where [_ :person/name ?n]])
                        #" ")))))
    (testing "relation spec is the default"
      (is (= 1 (count (first names-relation)))))))

(deftest with-clause-includes-but-does-not-project
  (let [conn (load-fixture!)]
    (is (= #{25 30}
           (set (q-one conn '[:find [?age ...] :with ?p
                              :where [?p :person/age ?age]]))))))

(deftest with-clause-affects-cardinality
  (let [conn (load-fixture!)]
    (api/transact conn [[:db/add "sam1" :person/name "Sam"]
                        [:db/add "sam2" :person/name "Sam"]])
    (testing "without :with, duplicate find values collapse"
      (is (= 4 (count (q-one conn '[:find ?name
                                    :where [?p :person/name ?name]])))))
    (testing "with :with, dedupe happens on find+with, then with columns drop"
      (is (= 5 (count (q-one conn '[:find ?name :with ?p
                                    :where [?p :person/name ?name]])))))
    (testing "collection spec keeps duplicates across distinct with-values"
      (let [names (q-one conn '[:find [?name ...] :with ?p
                                :where [?p :person/name ?name]])]
        (is (= 5 (count names)))
        (is (= 2 (count (filter #{"Sam"} names))))))))

(deftest edn-string-queries-match-data-form
  (let [conn (load-fixture!)
        via-data (q-one conn '[:find ?n :where [_ :person/name ?n]])
        via-string (q-one conn "[:find ?n :where [_ :person/name ?n]]")]
    (is (= via-data via-string))))

(deftest identical-results-when-rerun
  (let [conn (load-fixture!)
        q '[:find ?n ?a :where [?p :person/name ?n] [?p :person/age ?a]]]
    (is (= (q-one conn q) (q-one conn q)))))

(deftest structured-query-errors
  (let [conn (load-fixture!)]
    (testing "unknown attribute in pattern"
      (try (q-one conn '[:find ?e :where [?e :person/nope ?x]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/unknown-attribute (:kala/error (ex-data e)))))))
    (testing "wrong number of inputs for :in"
      (try (q-one conn '[:find ?n :in $ ?name :where [?p :person/name ?name]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :query/arity (:kala/error (ex-data e)))))))))

(deftest unknown-ident-as-constant-still-errors-in-query
  (let [conn (load-fixture!)]
    (try (q-one conn '[:find ?x :where [?p :person/friend :no/such]])
         (is false "expected ex-info")
         (catch clojure.lang.ExceptionInfo e
           (is (= :query/unknown-ident (:kala/error (ex-data e))))))))
