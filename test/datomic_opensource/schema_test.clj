(ns datomic-opensource.schema-test
  "Behavioral seam: schema enforcement — value-type validation, uniqueness and
  upserts, component cascade on entity retraction, and the AVET index."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic-opensource.api :as api]
            [datomic-opensource.core :as core]))

(defn fresh-conn []
  (api/connect {:storage :memory :name "test"}))

(defn define! [conn tempid ident value-type & [opts]]
  (api/transact conn
                (cond-> [[:db/add tempid :db/ident ident]
                         [:db/add tempid :db/valueType value-type]
                         [:db/add tempid :db/cardinality (or (:cardinality opts) :db.cardinality/one)]]
                  (:unique opts)    (conj [:db/add tempid :db/unique (:unique opts)])
                  (:component opts) (conj [:db/add tempid :db/isComponent true]))))

(defmacro assert-error [kind & body]
  `(try ~@body
        (is false ~(str "expected ex-info " kind))
        (catch clojure.lang.ExceptionInfo e#
          (is (= ~kind (:datomic-opensource/error (ex-data e#)))))))

;; ---- value-type validation --------------------------------------------------

(deftest value-types-accept-correct-and-reject-wrong-values
  (let [conn (fresh-conn)
            cases [[:db.type/string "s" 1]
                   [:db.type/keyword :k "s"]
                   [:db.type/symbol 'sym "s"]
                   [:db.type/long 1 "s"]
                   [:db.type/bigint (bigint 5) 1]
                   [:db.type/float (float 0.5) 0.5]
                   [:db.type/double 0.5 (float 0.5)]
                   [:db.type/boolean true 1]
                   [:db.type/instant (java.util.Date. 0) 1]
                   [:db.type/uuid (java.util.UUID/randomUUID) "s"]
                   [:db.type/uri (java.net.URI. "https://example.com") "s"]
                   [:db.type/bytes (byte-array (map byte [1 2 3])) "s"]]]
    (doseq [[value-type good bad] cases]
      (testing (name value-type)
        (let [ident (keyword "t" (name value-type))]
          (define! conn "attr" ident value-type)
          (api/transact conn [[:db/add "e" ident good]])
          (assert-error :transact/bad-value-type
                        (api/transact conn [[:db/add "other" ident bad]])))))))

(deftest ref-attr-accepts-entity-ids-and-idents
  (let [conn (fresh-conn)]
    (define! conn "fr" :t/friend :db.type/ref)
    (define! conn "n" :t/name :db.type/string)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "p" :t/name "Alice"]])
          pid (get tempids "p")]
      (api/transact conn [[:db/add "p2" :t/name "Bob"]
                          [:db/add "p2" :t/friend pid]])
      (api/transact conn [[:db/add "p3" :t/name "Carol"]
                          [:db/add "p3" :t/friend :t/name]])
      (is (= 2 (count (api/datoms (api/db conn) :aevt [(api/entid (api/db conn) :t/friend)]))))
      (assert-error :transact/bad-entity
                    (api/transact conn [[:db/add "p4" :t/friend 0]])))))

(deftest retracting-with-wrong-typed-value-also-errors
  (let [conn (fresh-conn)]
    (define! conn "n" :t/name :db.type/string)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/name "A"]])
          eid (get tempids "e")]
      (assert-error :transact/bad-value-type
                    (api/transact conn [[:db/retract eid :t/name 1]])))))

(deftest invalid-write-leaves-db-completely-unchanged
  (let [conn (fresh-conn)]
    (define! conn "n" :t/name :db.type/string)
    (api/transact conn [[:db/add "e" :t/name "A"]])
    (let [db-before (api/db conn)]
      (assert-error :transact/bad-value-type
                    (api/transact conn [[:db/add "e" :t/name 1]]))
      (assert-error :transact/unknown-attribute
                    (api/transact conn [[:db/add "e" :t/nope 1]]))
      (is (identical? db-before (api/db conn)))
      (is (= (::core/t db-before) (::core/t (api/db conn)))))))

(deftest unknown-value-type-is-rejected-at-definition
  (let [conn (fresh-conn)
        ;; point :db/valueType at a non-value-type entity directly
        card-one (api/entid (api/db conn) :db.cardinality/one)]
    (assert-error :transact/unknown-value-type
                  (api/transact conn [[:db/add "x" :db/ident :t/weird]
                                      [:db/add "x" :db/valueType card-one]]))))

(deftest invalid-schema-combinations-are-rejected
  (let [conn (fresh-conn)]
    (testing "byte values cannot be unique"
      (assert-error :transact/unique-not-supported
                    (define! conn "b1" :t/binary :db.type/bytes {:unique :db.unique/value})))
    (testing "unique requires cardinality one"
      (assert-error :transact/unique-not-supported
                    (define! conn "t1" :t/tags :db.type/string
                             {:cardinality :db.cardinality/many :unique :db.unique/value})))))

;; ---- cardinality ------------------------------------------------------------

(deftest cardinality-many-duplicate-add-is-a-noop
  (let [conn (fresh-conn)]
    (define! conn "a" :t/alias :db.type/string {:cardinality :db.cardinality/many})
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/alias "A"]])
          eid (get tempids "e")
          {:keys [tx-data]} (api/transact conn [[:db/add eid :t/alias "A"]])]
      (is (empty? tx-data))
      (is (= ["A"] (mapv :v (api/datoms (api/db conn) :eavt [eid])))))))

;; ---- uniqueness and upserts -------------------------------------------------

(defn install-email-schema! [conn]
  (define! conn "email" :t/email :db.type/string {:unique :db.unique/identity})
  (define! conn "name" :t/name :db.type/string))

(deftest unique-identity-upserts-onto-the-existing-entity
  (let [conn (fresh-conn)]
    (install-email-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "a" :t/email "x@y.io"]])
          eid1 (get tempids "a")
          {:keys [tempids]} (api/transact conn [[:db/add "b" :t/email "x@y.io"]
                                               [:db/add "b" :t/name "New"]])]
      (testing "the new tempid resolves to the existing entity"
        (is (= eid1 (get tempids "b"))))
      (testing "no second entity was created"
        (is (= 1 (count (api/datoms (api/db conn) :avet [(api/entid (api/db conn) :t/email)]))))
        (is (= ["New"] (mapv :v (api/datoms (api/db conn) :eavt [eid1 (api/entid (api/db conn) :t/name)]))))))))

(deftest unique-identity-reassert-is-a-noop
  (let [conn (fresh-conn)]
    (install-email-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "a" :t/email "x@y.io"]])
          eid1 (get tempids "a")
          {:keys [tx-data]} (api/transact conn [[:db/add eid1 :t/email "x@y.io"]])]
      (is (empty? tx-data)))))

(deftest concrete-entity-cannot-claim-another-identity
  (let [conn (fresh-conn)]
    (install-email-schema! conn)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "a" :t/email "x@y.io"]])
          eid1 (get tempids "a")
          {:keys [tempids]} (api/transact conn [[:db/add "c" :t/name "C"]])
          eid2 (get tempids "c")]
      (assert-error :transact/unique-conflict
                    (api/transact conn [[:db/add eid2 :t/email "x@y.io"]]))
      (testing "and both entities survive untouched"
        (is (= 1 (count (api/datoms (api/db conn) :eavt [eid1]))))
        (is (= 1 (count (api/datoms (api/db conn) :eavt [eid2]))))))))

(deftest two-tempids-upserting-the-same-value-conflict
  (let [conn (fresh-conn)]
    (install-email-schema! conn)
    (api/transact conn [[:db/add "a" :t/email "x@y.io"]])
    (assert-error :transact/conflicting-upserts
                  (api/transact conn [[:db/add "c" :t/email "x@y.io"]
                                      [:db/add "d" :t/email "x@y.io"]]))))

(deftest unique-value-rejects-duplicate-writes
  (let [conn (fresh-conn)]
    (define! conn "sku" :t/sku :db.type/string {:unique :db.unique/value})
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e1" :t/sku "W-1"]])
          eid1 (get tempids "e1")]
      (assert-error :transact/unique-conflict
                    (api/transact conn [[:db/add "e2" :t/sku "W-1"]]))
      (testing "the holder may re-assert its own value"
        (let [{:keys [tx-data]} (api/transact conn [[:db/add eid1 :t/sku "W-1"]])]
          (is (empty? tx-data)))))))

;; ---- component cascade ------------------------------------------------------

(defn install-order-schema! [conn]
  (define! conn "onum" :order/number :db.type/long)
  (define! conn "oitems" :order/items :db.type/ref {:cardinality :db.cardinality/many :component true})
  (define! conn "isku" :item/sku :db.type/string)
  (define! conn "ipart" :item/part :db.type/ref {:component true})
  (define! conn "pid" :part/id :db.type/string)
  (define! conn "pname" :person/name :db.type/string)
  (define! conn "pfr" :person/friend :db.type/ref))

(deftest retracting-an-entity-cascades-transitively-through-components
  (let [conn (fresh-conn)]
    (install-order-schema! conn)
    (let [{:keys [tempids]} (api/transact conn
                                          [[:db/add "o" :order/number 1]
                                           [:db/add "i" :item/sku "A"]
                                           [:db/add "o" :order/items "i"]
                                           [:db/add "p" :part/id "P"]
                                           [:db/add "i" :item/part "p"]
                                           [:db/add "friend" :person/name "Fred"]
                                           [:db/add "friend" :person/friend "i"]])
          order (get tempids "o")
          item (get tempids "i")
          part (get tempids "p")
          friend (get tempids "friend")
          {:keys [tx-data]} (api/transact conn [[:db.fn/retractEntity order]])
          db (api/db conn)]
      (testing "every datom of the tree plus incoming refs is retracted"
        ;; order: number + items; item: sku + part; part: id; referrer's incoming ref
        (is (= 6 (count tx-data)))
        (is (every? #(false? (:added %)) tx-data)))
      (testing "the whole component tree is gone"
        (is (empty? (api/datoms db :eavt [order])))
        (is (empty? (api/datoms db :eavt [item])))
        (is (empty? (api/datoms db :eavt [part]))))
      (testing "the referrer survives; its incoming reference is retracted"
        (is (empty? (api/datoms db :eavt [friend (api/entid db :person/friend)])))
        (is (seq (api/datoms db :eavt [friend]))))
      (testing "retracting a second time is a noop"
        (let [{:keys [tx-data]} (api/transact conn [[:db.fn/retractEntity order]])]
          (is (empty? tx-data)))))))
