(ns ^:peer datomic-opensource.pull-entity-test
  "Behavioral seam: pull patterns, lazy entity navigation, pull inside
  queries, VAET enumeration, and seek/range scans."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic-opensource.api :as api]
            [datomic-opensource.test-util :refer [define! fresh-conn]]))

(defn load-fixture! []
  (let [conn (fresh-conn)]
    (define! conn "n" :person/name :db.type/string)
    (define! conn "a" :person/age :db.type/long)
    (define! conn "e" :person/email :db.type/string)
    (define! conn "l" :person/like :db.type/string {:cardinality :db.cardinality/many})
    (define! conn "f" :person/friend :db.type/ref)
    (define! conn "on" :order/number :db.type/long)
    (define! conn "oi" :order/items :db.type/ref {:cardinality :db.cardinality/many :component true})
    (define! conn "is" :item/sku :db.type/string)
    (api/transact conn
                  [[:db/add "alice" :person/name "Alice"]
                   [:db/add "alice" :person/age 30]
                   [:db/add "alice" :person/like "chess"]
                   [:db/add "alice" :person/like "go"]
                   [:db/add "bob" :person/name "Bob"]
                   [:db/add "bob" :person/age 25]
                   [:db/add "carol" :person/name "Carol"]
                   [:db/add "alice" :person/friend "bob"]
                   [:db/add "o1" :order/number 7]
                   [:db/add "o1" :order/items "i1"]
                   [:db/add "o1" :order/items "i2"]
                   [:db/add "i1" :item/sku "A"]
                   [:db/add "i2" :item/sku "B"]])
    conn))

(defn fixture-ids [db]
  (let [eids (fn [ident v]
               (mapv :e (api/datoms db :avet [(api/entid db ident) v])))]
    {:alice (first (eids :person/name "Alice"))
     :bob (first (eids :person/name "Bob"))
     :carol (first (eids :person/name "Carol"))
     :o1 (first (eids :order/number 7))}))

;; ---- pull --------------------------------------------------------------------

(deftest pull-basic-attribute-specs
  (let [conn (load-fixture!)
        {:keys [alice]} (fixture-ids (api/db conn))
        doc (api/pull (api/db conn) [:person/name :person/age] alice)]
    (is (= alice (:db/id doc)))
    (is (= "Alice" (:person/name doc)))
    (is (= 30 (:person/age doc)))
    (testing "absent attributes are omitted"
      (is (not (contains? doc :person/email))))))

(deftest pull-wildcard
  (let [conn (load-fixture!)
        {:keys [alice]} (fixture-ids (api/db conn))
        doc (api/pull (api/db conn) '[*] alice)]
    (is (= "Alice" (:person/name doc)))
    (is (= 30 (:person/age doc)))
    (is (= ["chess" "go"] (:person/like doc)))
    (is (contains? doc :db/id))))

(deftest pull-ref-attr-returns-entity-id-without-map-spec
  (let [conn (load-fixture!)
        {:keys [alice bob]} (fixture-ids (api/db conn))
        doc (api/pull (api/db conn) [:person/friend] alice)]
    (is (= bob (:person/friend doc)))))

(deftest pull-nested-map-spec
  (let [conn (load-fixture!)
        {:keys [alice]} (fixture-ids (api/db conn))
        doc (api/pull (api/db conn) '[{:person/friend [:person/name :person/age]}] alice)
        bob-id (:db/id (:person/friend doc))]
    (is (pos? bob-id))
    (is (= {:person/name "Bob" :person/age 25}
           (dissoc (:person/friend doc) :db/id)))))

(deftest pull-reverse-map-spec
  (let [conn (load-fixture!)
        {:keys [bob]} (fixture-ids (api/db conn))
        doc (api/pull (api/db conn) '[{:person/_friend [:person/name]}] bob)]
    (is (= [{:person/name "Alice"}]
           (mapv #(dissoc % :db/id) (:person/_friend doc))))))

(deftest pull-default-and-missing-options
  (let [conn (load-fixture!)
        {:keys [alice]} (fixture-ids (api/db conn))]
    (testing ":default fills absent attributes"
      (is (= "n/a"
             (:person/email (api/pull (api/db conn)
                                      '[[:person/email :default "n/a"]] alice)))))
    (testing ":missing marks absent attributes"
      (is (= ::absent
             (:person/email (api/pull (api/db conn)
                                      '[[:person/email :missing ::absent]] alice)))))
    (testing "present attributes are unaffected"
      (is (= "Alice"
             (:person/name (api/pull (api/db conn)
                                     '[[:person/name :default "n/a"]] alice)))))))

(deftest pull-limit-truncates-cardinality-many
  (let [conn (load-fixture!)
        {:keys [alice]} (fixture-ids (api/db conn))]
    (is (= ["chess"]
           (:person/like (api/pull (api/db conn) '[[:person/like :limit 1]] alice))))
    (is (= ["chess" "go"]
           (:person/like (api/pull (api/db conn) '[:person/like] alice))))))

(deftest pull-unknown-attribute-errors
  (let [conn (load-fixture!)
        {:keys [alice]} (fixture-ids (api/db conn))]
    (try (api/pull (api/db conn) [:person/nope] alice)
         (is false "expected ex-info")
         (catch clojure.lang.ExceptionInfo e
           (is (= :pull/unknown-attribute (:datomic-opensource/error (ex-data e))))))))

;; ---- pull inside queries -----------------------------------------------------

(deftest pull-expressions-inside-queries
  (let [conn (load-fixture!)
        result (api/q '[:find (pull ?p [:person/name])
                        :where [?p :person/age 25]]
                      (api/db conn))]
    (is (= ["Bob"]
           (mapv (comp :person/name first) result)))))

;; ---- entity navigation -------------------------------------------------------

(deftest entity-navigates-lazily
  (let [conn (load-fixture!)
        {:keys [alice bob]} (fixture-ids (api/db conn))
        e (api/entity (api/db conn) alice)]
    (testing "identity"
      (is (= alice (:db/id e))))
    (testing "scalar and cardinality-many attribute access"
      (is (= "Alice" (e :person/name)))
      (is (= "Alice" (:person/name e)))
      (is (= ["chess" "go"] (e :person/like))))
    (testing "ref attrs return entities; navigation chains"
      (is (= bob (:db/id (e :person/friend))))
      (is (= "Bob" ((e :person/friend) :person/name))))
    (testing "reverse references return referrer entities"
      (is (= [alice]
             (mapv :db/id ((api/entity (api/db conn) bob) :person/_friend)))))
    (testing "unknown attributes return nil"
      (is (nil? (e :person/email))))))

(deftest touch-expands-components-to-plain-maps
  (let [conn (load-fixture!)
        {:keys [o1]} (fixture-ids (api/db conn))
        m (api/touch (api/entity (api/db conn) o1))]
    (is (= o1 (:db/id m)))
    (is (= 7 (:order/number m)))
    (is (= #{{:item/sku "A"} {:item/sku "B"}}
           (set (map #(dissoc % :db/id) (:order/items m)))))
    (is (every? number? (map :db/id (:order/items m))))
    (testing "non-component refs stay ids in touch"
      (let [{:keys [alice]} (fixture-ids (api/db conn))
            m (api/touch (api/entity (api/db conn) alice))]
        (is (number? (:person/friend m)))))))

;; ---- index access --------------------------------------------------------------

(deftest vaet-enumerates-incoming-references
  (let [conn (load-fixture!)
        {:keys [bob]} (fixture-ids (api/db conn))
        db (api/db conn)
        datoms (api/datoms db :vaet [bob])]
    (testing "bob is the value of alice's friend datom"
      (is (= [{:e (first (mapv :e (api/datoms db :avet [(api/entid db :person/name) "Alice"])))
               :a :person/friend
               :v bob}]
             (mapv #(select-keys % [:e :a :v]) datoms))))))

(deftest seek-datoms-scan-from-position
  (let [conn (load-fixture!)
        db (api/db conn)
        all (vec (api/seek-datoms db :eavt []))
        last-d (peek all)
        from-last (vec (api/seek-datoms db :eavt
                                       [(:e last-d) (:a last-d) (:v last-d)]))]
    (testing "seek to the final datom yields exactly that datom"
      (is (= [last-d] from-last)))
    (testing "seek with no components scans the whole index"
      (is (= all (vec (api/seek-datoms db :eavt [])))))
    (testing "mid-position scan runs from that datom to the end"
      (let [from-second (vec (api/seek-datoms db :eavt
                                              [(:e (all 1)) (:a (all 1)) (:v (all 1))]))]
        (is (= (subvec all 1) from-second))))))
