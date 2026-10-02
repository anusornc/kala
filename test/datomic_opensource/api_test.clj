(ns datomic-opensource.api-test
  "Behavioral seam: every test drives the public API only —
  connect -> transact -> datoms/entid reads — exactly as a downstream program would."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic-opensource.api :as api]
            [datomic-opensource.core :as core]))

(defn fresh-conn []
  (api/connect {:storage :memory :name "test"}))

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
      (is (= 0 (core/eid->partition ident-eid))))))

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
        (is (= 1 (core/eid->partition eid))))
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
             (is (= :transact/unknown-attribute (:datomic-opensource/error (ex-data e)))))))
    (testing "unknown ref ident"
      (install-friend-schema! conn)
      (try (api/transact conn [[:db/add "e1" :person/friend :person/ghost]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :transact/unknown-ident (:datomic-opensource/error (ex-data e)))))))
    (testing "bad op shape"
      (try (api/transact conn [[:db/frobnicate "e1" :person/name "x"]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (is (= :transact/bad-op (:datomic-opensource/error (ex-data e)))))))))

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
         (is (= :datoms/unknown-index (:datomic-opensource/error (ex-data e)))))))
