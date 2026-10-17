(ns kala.api-test
  "Behavioral seam: every test drives the public API only —
  connect -> transact -> datoms/entid reads — exactly as a downstream program would."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.eid :as eid]
            [kala.storage.memory :as memory]
            [kala.test-util :refer [assert-error fresh-conn]]))

(defn install-person-schema! [conn]
  (api/transact conn
                [[:db/add "name-attr" :db/ident :person/name]
                 [:db/add "name-attr" :db/valueType :db.type/string]
                 [:db/add "name-attr" :db/cardinality :db.cardinality/one]
                 [:db/add "alias-attr" :db/ident :person/alias]
                 [:db/add "alias-attr" :db/valueType :db.type/string]
                 [:db/add "alias-attr" :db/cardinality :db.cardinality/many]]))

(defn install-friend-schema! [conn]
  (api/transact conn [[:db/add "fr-attr" :db/ident :person/friend]
                      [:db/add "fr-attr" :db/valueType :db.type/ref]
                      [:db/add "fr-attr" :db/cardinality :db.cardinality/one]]))

;; ---- bootstrap schema -------------------------------------------------------

(deftest fresh-db-installs-bootstrap-schema
  (let [db (api/db (fresh-conn))
        ident-eid (api/entid db :db/ident)]
    (testing "meta-attributes exist as entities"
      (is (pos? ident-eid))
      (is (pos? (api/entid db :db/cardinality)))
      (is (pos? (api/entid db :db/valueType)))
      (is (pos? (api/entid db :db/unique)))
      (is (pos? (api/entid db :db/isComponent))))
    (testing ":db/ident describes itself"
      (is (some #(= :db/ident (:v %)) (api/datoms db :eavt [ident-eid]))))
    (testing "enum entities resolve"
      (is (pos? (api/entid db :db.cardinality/one)))
      (is (pos? (api/entid db :db.cardinality/many)))
      (is (pos? (api/entid db :db.type/ref)))
      (is (pos? (api/entid db :db.unique/identity))))
    (testing "entity ids use the partition/block/index long layout"
      (is (pos? ident-eid))
      (is (eid/bootstrap-eid? ident-eid)))))

(deftest entid-of-unknown-ident-is-nil
  (is (nil? (api/entid (api/db (fresh-conn)) :nope/never))))

;; ---- transactions -----------------------------------------------------------

(deftest transact-adds-fact-and-returns-complete-report
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids tx-data db-after]} (api/transact conn [[:db/add "e1" :person/name "Alice"]])
          eid (get tempids "e1")]
      (testing "tempid resolved to a fresh user-partition entity"
        (is (pos? eid))
        (is (eid/user-eid? eid)))
      (testing "the report exposes the tx entity"
        (is (pos? (get tempids :db/current-tx)))
        (is (every? #(= (get tempids :db/current-tx) (:tx %)) tx-data)))
      (testing "tx report carries the produced datom (plus the transactor's :db/txInstant stamp)"
        (is (= 2 (count tx-data)))
        (let [d (first tx-data)]
          (is (= eid (:e d)))
          (is (= :person/name (:a d)))
          (is (= "Alice" (:v d)))
          (is (true? (:added d)))))
      (testing "the fact is readable back"
        (is (= ["Alice"] (mapv :v (api/datoms db-after :eavt [eid]))))))))

(deftest tempid-shared-across-ops-lands-on-one-entity
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :person/name "Alice"]
                                                [:db/add "e1" :person/alias "Wonder"]])
          eid (get tempids "e1")]
      (is (= 2 (count (api/datoms (api/db conn) :eavt [eid])))))))

(deftest cardinality-one-add-replaces-previous-value
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :person/name "A"]])
          eid (get tempids "e1")
          {:keys [tx-data]} (api/transact conn [[:db/add eid :person/name "B"]])]
      (testing "replace emits retract-then-add (plus the txInstant stamp)"
        (is (= [false true true] (mapv :added tx-data))))
      (testing "only the latest value is current"
        (is (= ["B"] (mapv :v (api/datoms (api/db conn) :eavt [eid]))))))))

(deftest re-adding-identical-fact-is-a-noop
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :person/name "A"]])
          eid (get tempids "e1")
          {:keys [tx-data]} (api/transact conn [[:db/add eid :person/name "A"]])]
      (testing "no user datoms; only the txInstant stamp"
        (is (= [:db/txInstant] (mapv :a tx-data)))))))

(deftest retract-removes-fact
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :person/name "Alice"]])
          eid (get tempids "e1")]
      (let [{:keys [tx-data]} (api/transact conn [[:db/retract eid :person/name "Alice"]])]
        (is (= 2 (count tx-data)))
        (is (false? (:added (first tx-data))))
        (is (empty? (api/datoms (api/db conn) :eavt [eid]))))
      (testing "retracting a non-existent fact is a noop (only the stamp remains)"
        (let [{:keys [tx-data]} (api/transact conn [[:db/retract eid :person/name "Ghost"]])]
          (is (= [:db/txInstant] (mapv :a tx-data))))))))

(deftest cardinality-many-accumulates-and-retracts-selectively
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :person/alias "A"]
                                                [:db/add "e1" :person/alias "B"]])
          eid (get tempids "e1")
          db (api/db conn)]
      (is (= ["A" "B"] (mapv :v (api/datoms db :eavt [eid]))))
      (api/transact conn [[:db/retract eid :person/alias "A"]])
      (is (= ["B"] (mapv :v (api/datoms (api/db conn) :eavt [eid])))))))

(deftest db-values-are-immutable-across-transactions
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :person/name "A"]])
          eid (get tempids "e1")
          db1 (api/db conn)]
      (api/transact conn [[:db/add eid :person/name "B"]])
      (is (= ["A"] (mapv :v (api/datoms db1 :eavt [eid]))))
      (is (= ["B"] (mapv :v (api/datoms (api/db conn) :eavt [eid])))))))

(deftest transactions-get-monotonically-increasing-t
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{d1 :tx-data} (api/transact conn [[:db/add "e1" :person/name "A"]])
          {d2 :tx-data} (api/transact conn [[:db/add "e2" :person/name "B"]])]
      (is (pos? (:tx (first d1))))
      (is (< (:tx (first d1)) (:tx (first d2)))))))

(deftest invalid-transactions-raise-structured-errors
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (testing "unknown attribute"
      (try (api/transact conn [[:db/add "e1" :nope/nothing 1]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :transact/unknown-attribute (:kala/error (ex-data e)))))))
    (testing "unknown ref ident"
      (install-friend-schema! conn)
      (try (api/transact conn [[:db/add "e1" :person/friend :person/ghost]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :transact/unknown-ident (:kala/error (ex-data e)))))))
    (testing "a keyword op head that names no stored fn"
      (try (api/transact conn [[:db/frobnicate "e1" :person/name "x"]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             ;; a keyword op head names a stored transaction function (ADR-0004):
             ;; one nothing defines resolves as unknown-tx-fn, not bad-op
             (is (= :transact/unknown-tx-fn (:kala/error (ex-data e)))))))
    (testing "genuinely malformed ops"
      (try (api/transact conn [[:db/add "e1" :person/name]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :transact/bad-op (:kala/error (ex-data e)))))))
    (testing "the reserved call head without a target"
      (try (api/transact conn [[:db.fn/call]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :transact/bad-op (:kala/error (ex-data e)))))))))

(deftest ref-values-resolve-idents-to-entity-ids
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (install-friend-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "alice" :person/name "Alice"]
                                                [:db/add "bob" :person/name "Bob"]
                                                [:db/add "alice" :person/friend "bob"]])
          alice (get tempids "alice")
          bob (get tempids "bob")
          friend-v (:v (first (api/datoms (api/db conn) :eavt [alice (api/entid (api/db conn) :person/friend)])))]
      (is (= bob friend-v)))))

;; ---- index reads ------------------------------------------------------------

(deftest datoms-supports-prefix-component-filtering
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :person/name "Alice"]
                                                [:db/add "e1" :person/alias "Wonder"]])
          eid (get tempids "e1")
          db (api/db conn)
          name-aid (api/entid db :person/name)]
      (is (= 2 (count (api/datoms db :eavt [eid]))))
      (is (= 1 (count (api/datoms db :eavt [eid name-aid]))))
      (is (= 1 (count (api/datoms db :eavt [eid name-aid "Alice"])))))))

(deftest datoms-supports-aevt-index
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (api/transact conn [[:db/add "e1" :person/name "Alice"]
                        [:db/add "e2" :person/name "Bob"]])
    (let [db (api/db conn)
          name-aid (api/entid db :person/name)]
      (is (= ["Alice" "Bob"] (mapv :v (api/datoms db :aevt [name-aid])))))))

(deftest datoms-reject-unknown-index
  (try (api/datoms (api/db (fresh-conn)) :zvt [])
       (is false "expected ex-info")
       (catch clojure.lang.ExceptionInfo e
         (is (= :datoms/unknown-index (:kala/error (ex-data e)))))))

(deftest connect-accepts-a-storage-adapter
  ;; the Storage protocol is the declared pluggable seam: an adapter satisfies
  ;; it at the connect seam; the keywords stay as the built-in adapters
  (let [s (memory/empty-storage)
        conn (api/connect {:storage s})]
    (testing "the adapter is wired in as-is, not re-wrapped"
      (is (identical? s (api/db-storage (api/db conn)))))
    (api/transact conn [[:db/add "n" :db/ident :person/name]
                        [:db/add "n" :db/valueType :db.type/string]])
    (api/transact conn [[:db/add "a" :person/name "Alice"]])
    (testing "the connection reads and writes through the adapter"
      (is (= ["Alice"] (mapv :v (api/datoms (api/db conn)
                                            :aevt [(api/entid (api/db conn) :person/name)])))))
    (testing "reopening the same adapter replays its log"
      (let [conn2 (api/connect {:storage s})]
        (is (= ["Alice"] (mapv :v (api/datoms (api/db conn2)
                                              :aevt [(api/entid (api/db conn2) :person/name)]))))))))

;; ---- lookup refs -------------------------------------------------------------

(deftest lookup-refs-resolve-and-refuse
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (api/transact conn [[:db/add "fr" :db/ident :person/email]
                        [:db/add "fr" :db/valueType :db.type/string]
                        [:db/add "fr" :db/cardinality :db.cardinality/one]
                        [:db/add "fr" :db/unique :db.unique/identity]])
    (testing "a lookup ref to an existing entity addresses it"
      ;; creation goes through a tempid + the unique value (upsert);
      ;; lookup refs ADDRESS, they never silently create
      (api/transact conn [[:db/add "p" :person/email "a@x"]
                          [:db/add "p" :person/name "Alice"]])
      (api/transact conn [[:db/add [:person/email "a@x"] :person/alias "Al"]])
      (let [found (ffirst (api/q '[:find ?e :in $ ?v :where [?e :person/email ?v]]
                                  (api/db conn) "a@x"))]
        (is (pos? found))
        (is (= {:person/name "Alice" :person/alias ["Al"]}
               (select-keys (api/pull (api/db conn) '[:person/name :person/alias] found)
                            [:person/name :person/alias])))
        (testing "a second tx through the same ref lands on the same entity"
          (api/transact conn [[:db/add [:person/email "a@x"] :person/name "Alice Prime"]])
          (is (= "Alice Prime"
                 (:v (first (api/datoms (api/db conn) :eavt [found :person/name]))))))))
    (testing "retraction through a resolved lookup ref works"
      (api/transact conn [[:db/retract [:person/email "a@x"] :person/alias "Al"]])
      (is (empty? (api/q '[:find ?a :in $ :where [?e :person/alias ?a]] (api/db conn))))))
  (let [conn (fresh-conn)]
    (install-person-schema! conn)
    (api/transact conn [[:db/add "em" :db/ident :person/email]
                        [:db/add "em" :db/valueType :db.type/string]
                        [:db/add "em" :db/cardinality :db.cardinality/one]
                        [:db/add "em" :db/unique :db.unique/identity]])
    (testing "unresolvable / malformed / non-unique lookup refs refuse loudly"
      (assert-error :transact/bad-lookup-ref
        (api/transact conn [[:db/add [:person/email "ghost@x"] :person/name "G"]]))
      (assert-error :transact/bad-lookup-ref
        (api/transact conn [[:db/add [:person/name "not-unique"] :person/name "G"]]))
      (assert-error :transact/bad-lookup-ref
        (api/transact conn [[:db/add [:nope/never "x"] :person/name "G"]]))
      (assert-error :transact/bad-lookup-ref
        (api/transact conn [[:db/add [:person/email] :person/name "G"]])))
    (testing "nothing from a refused lookup ref lands"
      (is (empty? (api/q '[:find ?n :where [?e :person/name ?n]] (api/db conn)))))))
