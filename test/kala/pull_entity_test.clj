(ns ^:peer kala.pull-entity-test
  "Behavioral seam: pull patterns, lazy entity navigation, pull inside
  queries, VAET enumeration, and seek/range scans."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.test-util :refer [define! fresh-conn]]))

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
           (is (= :pull/unknown-attribute (:kala/error (ex-data e))))))))

(deftest pull-reverse-limit-and-referrer-order
  (let [conn (load-fixture!)
        {:keys [alice bob]} (fixture-ids (api/db conn))]
    ;; reference bob by its resolved eid — "bob" as a tempid here would
    ;; mint a fresh entity (:person/name carries no upsert)
    (api/transact conn [[:db/add "dave" :person/name "Dave"]
                        [:db/add "dave" :person/friend bob]])
    (let [db (api/db conn)
          dave (first (mapv :e (api/datoms db :avet [(api/entid db :person/name) "Dave"])))]
      (testing "referrers come back in vaet (entity id) order"
        (is (= ["Alice" "Dave"]
               (mapv :person/name (:person/_friend
                                   (api/pull db '[{:person/_friend [:person/name]}] bob))))))
      (testing ":limit truncates referrers (entity ids without a map spec)"
        (is (= [alice]
               (:person/_friend (api/pull db '[[:person/_friend :limit 1]] bob)))))
      (testing "entity reverse navigation follows the same order"
        (is (= [alice dave]
               (mapv :db/id ((api/entity db bob) :person/_friend))))))))

(deftest pull-with-resolvable-keyword-eid
  (let [conn (load-fixture!)]
    ;; :person/name resolves through ::idents to the schema entity; the doc's
    ;; :db/id stays the keyword as passed
    (is (= {:db/id :person/name}
           (api/pull (api/db conn) '[:person/name] :person/name)))))

(deftest pull-with-unresolvable-keyword-eid
  (let [conn (load-fixture!)]
    ;; pinned: the slice entry check meets an unresolved nil eid in the
    ;; index comparator — accidental, but preserved by the bridge switch
    (is (thrown? NullPointerException
                 (api/pull (api/db conn) '[*] :nope/unknown)))))

(deftest entity-reverse-nav-unresolvable-keyword-eid-errors
  (let [conn (load-fixture!)
        {:keys [bob]} (fixture-ids (api/db conn))]
    ;; pinned: on VAET the sought entity rides the ref-typed :v slot, so the
    ;; keyword must resolve (core/ref-entid — the rule resolve-components
    ;; applies); pull never reaches this rule, its entry check NPEs first
    (try (:person/_friend (api/entity (api/db conn) :nope/unknown))
         (is false "expected ex-info")
         (catch clojure.lang.ExceptionInfo e
           (is (= :transact/unknown-ident (:kala/error (ex-data e))))
           (is (= "No entity with ident" (.getMessage e)))
           (is (= :nope/unknown (get (ex-data e) :ident)))))))

(deftest touch-with-unresolvable-keyword-eid
  (let [conn (load-fixture!)]
    (is (thrown? NullPointerException
                 (api/touch (api/entity (api/db conn) :nope/unknown))))))

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

(deftest entity-reverse-nav-unknown-attribute-errors
  (let [conn (load-fixture!)
        {:keys [bob]} (fixture-ids (api/db conn))]
    ;; pinned: resolve-components' error shape — the reverse arm resolves the
    ;; attribute eagerly and the error carries the forward form
    (try (:person/_nope (api/entity (api/db conn) bob))
         (is false "expected ex-info")
         (catch clojure.lang.ExceptionInfo e
           (is (= :transact/unknown-attribute (:kala/error (ex-data e))))
           (is (= "Unknown attribute" (.getMessage e)))
           (is (= :person/nope (get (ex-data e) :attribute)))))))

(deftest entity-point-read-with-unresolvable-keyword-eid-is-absent
  (let [conn (load-fixture!)
        e (api/entity (api/db conn) :nope/unknown)]
    ;; :db/id passes the keyword through; point reads through the bridge's
    ;; hash views return absent (the old slow path NPE'd in the comparator)
    (is (= :nope/unknown (:db/id e)))
    (is (nil? (:person/name e)))))

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
