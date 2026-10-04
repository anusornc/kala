(ns kala.map-tx-test
  "Behavioral seam: map-form transactions, tempid sharing across maps, upserts
  via unique identity, and transaction metadata (:db/txInstant, custom)."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.test-util :refer [assert-error define! fresh-conn]]))

(defn install-schema! [conn]
  (define! conn "n" :person/name :db.type/string)
  (define! conn "al" :person/alias :db.type/string {:cardinality :db.cardinality/many})
  (define! conn "fr" :person/friend :db.type/ref)
  (define! conn "on" :order/number :db.type/long)
  (define! conn "oi" :order/items :db.type/ref {:cardinality :db.cardinality/many :component true})
  (define! conn "is" :item/sku :db.type/string)
  (define! conn "em" :person/email :db.type/string {:unique :db.unique/identity})
  (define! conn "tn" :tx/note :db.type/string))

;; ---- map form ---------------------------------------------------------------

(deftest map-form-transacts-entities
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "p" :person/name "Alice"}])
          eid (get tempids "p")]
      (is (pos? eid))
      (is (= ["Alice"] (mapv :v (api/datoms (api/db conn) :eavt [eid])))))))

(deftest map-form-without-db-id-generates-an-entity
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tx-data]} (api/transact conn [{:person/name "Bob"}])]
      (is (= 2 (count tx-data)))                       ; name + txInstant stamp
      (is (pos? (:e (first tx-data)))))))

(deftest same-tempid-across-two-maps-lands-on-one-entity
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "p" :person/name "Alice"}
                                                {:db/id "p" :person/alias "Wonder"}])
          eid (get tempids "p")]
      (is (= 2 (count (api/datoms (api/db conn) :eavt [eid])))))))

(deftest map-form-expands-cardinality-many-collections
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "p" :person/alias ["A" "B" "C"]}])
          eid (get tempids "p")]
      (is (= #{"A" "B" "C"} (set (mapv :v (api/datoms (api/db conn) :eavt [eid]))))))))

(deftest map-form-nests-component-children
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn
                                          [{:db/id "o"
                                            :order/number 7
                                            :order/items [{:item/sku "A"} {:item/sku "B"}]}])
          order (get tempids "o")
          db (api/db conn)
          item-ids (mapv :v (api/datoms db :eavt [order (api/entid db :order/items)]))]
      (testing "both component children created and referenced"
        (is (= 2 (count item-ids)))
        (is (every? pos? item-ids))
        (is (= #{"A" "B"} (set (mapcat #(mapv :v (api/datoms db :eavt [% (api/entid db :item/sku)]))
                                       item-ids)))))
      (testing "children are retracted with the parent (components)"
        (api/transact conn [[:db.fn/retractEntity order]])
        (let [db' (api/db conn)]
          (is (every? #(empty? (api/datoms db' :eavt [%])) item-ids)))))))

(deftest map-form-nested-non-component-ref-is-rejected
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (assert-error :transact/nested-map-requires-component
                  (api/transact conn [{:person/name "A"
                                       :person/friend {:person/name "B"}}]))))

(deftest map-form-upserts-via-unique-identity
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "a" :person/email "x@y.io"}])
          eid1 (get tempids "a")
          {:keys [tempids]} (api/transact conn [{:db/id "b" :person/email "x@y.io"
                                                 :person/name "Named"}])]
      (is (= eid1 (get tempids "b")))
      (is (= ["Named"] (mapv :v (api/datoms (api/db conn)
                                            :eavt [eid1 (api/entid (api/db conn) :person/name)])))))))

(deftest ref-position-tempids-in-map-form-resolve
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "alice" :person/name "Alice"
                                                 :person/friend "bob"}
                                                {:db/id "bob" :person/name "Bob"}])
          alice (get tempids "alice")
          bob (get tempids "bob")
          db (api/db conn)]
      (is (pos? bob))
      (is (= bob (:v (first (api/datoms db :eavt [alice (api/entid db :person/friend)]))))))))

(deftest map-and-list-forms-can-be-mixed
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "p" :person/name "Alice"}
                                                [:db/add "p" :person/alias "Wonder"]])
          eid (get tempids "p")]
      (is (= 2 (count (api/datoms (api/db conn) :eavt [eid])))))))

(deftest map-form-with-explicit-entity-id-extends-that-entity
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "p" :person/name "Alice"}])
          eid (get tempids "p")
          _ (api/transact conn [{:db/id eid :person/alias "Grr"}])]
      (is (= ["Grr"] (mapv :v (api/datoms (api/db conn) :eavt [eid (api/entid (api/db conn) :person/alias)])))))))

;; ---- transaction metadata ---------------------------------------------------

(deftest every-transaction-carries-db-txInstant
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:person/name "Alice"}])
          tx (get tempids :db/current-tx)
          insts (mapv :v (api/datoms (api/db conn)
                                     :eavt [tx (api/entid (api/db conn) :db/txInstant)]))]
      (is (= 1 (count insts)))
      (is (instance? java.util.Date (first insts))))))

(deftest caller-supplied-db-txInstant-is-respected
  (let [conn (fresh-conn)
        then (java.util.Date. 946684800000)]        ; 2000-01-01T00:00:00Z
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "datomic.tx" :db/txInstant then}
                                                {:person/name "Alice"}])
          tx (get tempids :db/current-tx)
          insts (mapv :v (api/datoms (api/db conn)
                                     :eavt [tx (api/entid (api/db conn) :db/txInstant)]))]
      (is (= [then] insts)))))

(deftest custom-tx-metadata-lands-on-the-tx-entity
  (let [conn (fresh-conn)]
    (install-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [{:db/id "datomic.tx" :tx/note "why"}
                                                {:person/name "Alice"}])
          tx (get tempids :db/current-tx)
          notes (mapv :v (api/datoms (api/db conn) :eavt [tx (api/entid (api/db conn) :tx/note)]))]
      (is (= ["why"] notes))
      (is (pos? tx)))))
