(ns kala.schema-test
  "Behavioral seam: schema enforcement — value-type validation, uniqueness and
  upserts, component cascade on entity retraction, compare-and-swap, stored
  transaction functions, and the AVET index."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.test-util :as test-util
             :refer [assert-error define! fresh-conn install-tx-fn!]]))

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
      ;; client markers are records equal by conn+mode+t; peer db values are
      ;; equal by content — either way, failed writes changed nothing
      (is (= db-before (api/db conn))))))

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

;; ---- schema retraction --------------------------------------------------------

(deftest retracting-a-schema-attribute-entity-prunes-the-registry
  (let [conn (fresh-conn)]
    (define! conn "n" :t/name :db.type/string)
    (define! conn "a" :t/age :db.type/long)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "p" :t/name "Alice"]
                                                [:db/add "p" :t/age 30]])
          alice (get tempids "p")
          age-eid (api/entid (api/db conn) :t/age)]
      (api/transact conn [[:db.fn/retractEntity age-eid]])
      (let [db (api/db conn)]
        (testing "writing the retracted attribute raises"
          (assert-error :transact/unknown-attribute
                        (api/transact conn [[:db/add "q" :t/age 31]])))
        (testing "query resolution raises like any unknown attribute"
          (assert-error :query/unknown-attribute
                        (api/q '[:find ?p :where [?p :t/age ?a]] db)))
        (testing "pull resolution raises like any unknown attribute"
          (assert-error :pull/unknown-attribute
                        (api/pull db [:t/age] alice)))
        (testing "entity and ident resolution behave as never-defined"
          (is (nil? (api/entid db :t/age)))
          ;; entity navigation is peer-only; there the dead attribute
          ;; resolves like a never-defined one
          (when (= :peer test-util/*access-mode*)
            (is (nil? ((api/entity db alice) :t/age)))))))))

(deftest schema-retract-shares-a-tx-with-data-retractions
  ;; the registry must stay intact until every op of the pass has applied —
  ;; pruning mid-pass would break the op that follows a schema retraction
  (let [conn (fresh-conn)]
    (define! conn "a" :t/age :db.type/long)
    (define! conn "h" :t/height :db.type/long)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "p" :t/age 30]
                                                [:db/add "q" :t/height 180]])
          p (get tempids "p")
          q (get tempids "q")
          db (api/db conn)
          age-eid (api/entid db :t/age)
          height-eid (api/entid db :t/height)]
      (testing "data retract first, schema retract second"
        (api/transact conn [[:db/retract p :t/age 30]
                            [:db.fn/retractEntity age-eid]])
        (let [db (api/db conn)]
          (is (empty? (api/datoms db :eavt [p])))
          (is (nil? (api/entid db :t/age)))))
      (testing "schema retract first, data retract second"
        (api/transact conn [[:db.fn/retractEntity height-eid]
                            [:db/retract q :t/height 180]])
        (let [db (api/db conn)]
          (is (empty? (api/datoms db :eavt [q])))
          (is (nil? (api/entid db :t/height))))))))

(deftest data-written-before-a-schema-retract-still-reads
  (let [conn (fresh-conn)]
    (define! conn "a" :t/age :db.type/long)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "p" :t/age 30]])
          p (get tempids "p")
          t-data (get tempids :db/current-tx)
          age-eid (api/entid (api/db conn) :t/age)]
      (api/transact conn [[:db.fn/retractEntity age-eid]])
      (let [db-now (api/db conn)]
        (testing "the orphaned data datom survives the registry prune"
          ;; with the ident gone, public-datom falls back to the raw attr id
          (is (= [{:e p :a age-eid :v 30}]
                 (mapv #(select-keys % [:e :a :v]) (api/datoms db-now :eavt [p])))))
        (testing "an as-of before the retract still resolves the attribute"
          (let [db-at (api/as-of db-now t-data)]
            (is (= [30] (mapv :v (api/datoms db-at :eavt [p (api/entid db-at :t/age)]))))))))))

(deftest retracting-an-enum-entity-prunes-its-idents-entry
  ;; an ident-only entity (a :db/ident with no :db/valueType) registers only
  ;; in ::idents, never in the attribute tables — its entry must die with the
  ;; retraction like an attribute's does
  (let [conn (fresh-conn)]
    (api/transact conn [[:db/add "x" :db/ident :t/red]])
    (let [red (api/entid (api/db conn) :t/red)]
      (testing "retractEntity drops the ident resolution"
        (api/transact conn [[:db.fn/retractEntity red]])
        (is (nil? (api/entid (api/db conn) :t/red)))))
    (testing "retracting the ident datom directly drops it too"
      (api/transact conn [[:db/add "y" :db/ident :t/blue]])
      (let [blue (api/entid (api/db conn) :t/blue)]
        (api/transact conn [[:db/retract blue :db/ident :t/blue]])
        (is (nil? (api/entid (api/db conn) :t/blue)))))))

(deftest enum-ident-survives-same-tx-redefinition
  ;; the prune drops only entries the entity's slice no longer justifies: a
  ;; same-tx re-assertion re-registers, and a move re-points the ident at the
  ;; new entity — registration runs before the prune (tx/derive-schema)
  (let [conn (fresh-conn)]
    (api/transact conn [[:db/add "x" :db/ident :t/red]])
    (let [red (api/entid (api/db conn) :t/red)]
      (testing "retract and re-assert on the same entity stays registered"
        (api/transact conn [[:db/retract red :db/ident :t/red]
                            [:db/add red :db/ident :t/red]])
        (is (= red (api/entid (api/db conn) :t/red))))
      (testing "retract here, re-define elsewhere points at the new entity"
        (let [{:keys [tempids]} (api/transact conn [[:db/retract red :db/ident :t/red]
                                                    [:db/add "z" :db/ident :t/red]])]
          (is (= (get tempids "z") (api/entid (api/db conn) :t/red)))
          (is (not= red (api/entid (api/db conn) :t/red))))))))

(deftest same-tx-attribute-redefinition-follows-net-state
  ;; ADR-0003: the prune drops what the net datoms no longer justify — a
  ;; retract + re-assert of the ident in one tx keeps the attribute, a rename
  ;; leaves only the new ident resolvable, with no stale entries either way
  (let [conn (fresh-conn)]
    (define! conn "n" :t/name :db.type/string)
    (define! conn "a" :t/age :db.type/long)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "p" :t/name "Alice"]
                                                [:db/add "p" :t/age 30]])
          p (get tempids "p")
          age-eid (api/entid (api/db conn) :t/age)]
      (testing "retract and re-assert the ident: the attribute survives"
        (api/transact conn [[:db/retract age-eid :db/ident :t/age]
                            [:db/add age-eid :db/ident :t/age]])
        (let [db (api/db conn)]
          (is (= age-eid (api/entid db :t/age)))
          (is (= [30] (mapv :v (api/datoms db :aevt [(api/entid db :t/age)]))))
          (api/transact conn [[:db/add "q" :t/age 31]])
          (is (= [30 31] (mapv :v (api/datoms (api/db conn) :aevt [age-eid]))))))
      (testing "retract here, define elsewhere: the new entity owns the ident"
        (let [{:keys [tempids]} (api/transact conn [[:db/retract age-eid :db/ident :t/age]
                                                    [:db/add "z" :db/ident :t/age]])]
          (is (= (get tempids "z") (api/entid (api/db conn) :t/age)))
          (is (not= age-eid (api/entid (api/db conn) :t/age)))))
      (testing "rename on the same entity: old ident dies, new ident resolves"
        (define! conn "w" :t/weight :db.type/long)
        (api/transact conn [[:db/add "p" :t/weight 80]])
        (let [wt-eid (api/entid (api/db conn) :t/weight)]
          (api/transact conn [[:db/retract wt-eid :db/ident :t/weight]
                              [:db/add wt-eid :db/ident :t/mass]])
          (let [db (api/db conn)]
            (is (nil? (api/entid db :t/weight)))
            (is (= wt-eid (api/entid db :t/mass)))
            ;; the data survives under the renamed attribute
            (is (= [80] (mapv :v (api/datoms db :aevt [(api/entid db :t/mass)]))))
            (assert-error :transact/unknown-attribute
                          (api/transact conn [[:db/add "r" :t/weight 32]]))))))))

;; ---- cardinality ------------------------------------------------------------

(deftest cardinality-many-duplicate-add-is-a-noop
  (let [conn (fresh-conn)]
    (define! conn "a" :t/alias :db.type/string {:cardinality :db.cardinality/many})
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/alias "A"]])
          eid (get tempids "e")
          {:keys [tx-data]} (api/transact conn [[:db/add eid :t/alias "A"]])]
      (is (= [:db/txInstant] (mapv :a tx-data)))
      (is (= ["A"] (mapv :v (api/datoms (api/db conn) :eavt [eid])))))))

;; ---- uniqueness and upserts -------------------------------------------------

(defn install-email-schema! [conn]
  (define! conn "email" :t/email :db.type/string {:unique :db.unique/identity})
  (define! conn "name" :t/name :db.type/string))

;; ---- compare-and-swap --------------------------------------------------------

(deftest cas-asserts-only-when-old-matches
  (let [conn (fresh-conn)]
    (define! conn "a" :t/age :db.type/long)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/age 30]])
          eid (get tempids "e")]
      (testing "matching old value applies the new one"
        (api/transact conn [[:db.fn/cas eid :t/age 30 31]])
        (is (= [31] (mapv :v (api/datoms (api/db conn) :eavt [eid])))))
      (testing "nil old expects the attribute to be absent"
        (define! conn "n" :t/nick :db.type/string)
        (api/transact conn [[:db.fn/cas eid :t/nick nil "Ace"]])
        (is (= ["Ace"] (mapv :v (api/datoms (api/db conn) :eavt [eid :t/nick])))))
      (testing "current already equal to new is a no-op"
        (let [{:keys [tx-data]} (api/transact conn [[:db.fn/cas eid :t/age 31 31]])]
          (is (= [:db/txInstant] (mapv :a tx-data)))
          (is (= [31] (mapv :v (api/datoms (api/db conn) :eavt [eid :t/age])))))))))

(deftest cas-failure-is-structured-and-leaves-db-unchanged
  (let [conn (fresh-conn)]
    (define! conn "a" :t/age :db.type/long)
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/age 30]])
          eid (get tempids "e")]
      (assert-error :transact/cas-failed
        (api/transact conn [[:db.fn/cas eid :t/age 99 100]]))
      (testing "the failed write left nothing behind"
        (is (= [30] (mapv :v (api/datoms (api/db conn) :eavt [eid])))))
      (testing "the error carries op, old, new, and actual"
        (try (api/transact conn [[:db.fn/cas eid :t/age 99 100]])
             (is false "expected ex-info")
             (catch clojure.lang.ExceptionInfo e
               (let [d (ex-data e)]
                 (is (= [:db.fn/cas eid :t/age 99 100] (:op d)))
                 (is (= 99 (:old d)))
                 (is (= 100 (:new d)))
                 (is (= 30 (:actual d)))))))
      (testing "a retried CAS with the right old value succeeds"
        (api/transact conn [[:db.fn/cas eid :t/age 30 100]])
        (is (= [100] (mapv :v (api/datoms (api/db conn) :eavt [eid]))))))))

(deftest cas-rejects-invalid-uses
  (let [conn (fresh-conn)]
    (define! conn "a" :t/age :db.type/long)
    (define! conn "tags" :t/tag :db.type/string {:cardinality :db.cardinality/many})
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/age 30]
                                                [:db/add "e" :t/tag "x"]])
          eid (get tempids "e")]
      (testing "cardinality-many attributes cannot be CASed"
        (assert-error :transact/bad-op
          (api/transact conn [[:db.fn/cas eid :t/tag "x" "y"]])))
      (testing "unknown attributes fail like any write"
        (assert-error :transact/unknown-attribute
          (api/transact conn [[:db.fn/cas eid :t/nope nil 1]])))
      (testing "tempids cannot address a CAS (nothing to compare)"
        (assert-error :transact/bad-entity
          (api/transact conn [[:db.fn/cas "brand-new" :t/age nil 1]])))
      (testing "a unique new value conflicting with another entity fails"
        (install-email-schema! conn)
        (api/transact conn [[:db/add "u1" :t/email "taken@x"]])
        (assert-error :transact/unique-conflict
          (api/transact conn [[:db.fn/cas eid :t/email nil "taken@x"]])))
      (testing "CAS onto the holder's own unique value is a no-op"
        (api/transact conn [[:db.fn/cas eid :t/email nil "mine@x"]])
        (api/transact conn [[:db.fn/cas eid :t/email "mine@x" "mine@x"]])
        (is (= ["mine@x"] (mapv :v (api/datoms (api/db conn) :eavt [eid :t/email])))))
      (testing "a matching CAS moves the unique slot, the entity keeps its identity"
        (api/transact conn [[:db.fn/cas eid :t/email "mine@x" "moved@x"]])
        (let [db (api/db conn)]
          (is (= eid (ffirst (api/q '[:find ?e :where [?e :t/email "moved@x"]] db))))
          (is (empty? (api/q '[:find ?e :where [?e :t/email "mine@x"]] db)))))))


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
      (is (= [:db/txInstant] (mapv :a tx-data))))))

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
          (is (= [:db/txInstant] (mapv :a tx-data))))))))

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
        ;; order: number + items; item: sku + part; part: id; referrer's
        ;; incoming ref; plus the transactor's :db/txInstant stamp
        (is (= 7 (count tx-data)))
        (is (= 6 (count (filter #(false? (:added %)) tx-data)))))
      (testing "the whole component tree is gone"
        (is (empty? (api/datoms db :eavt [order])))
        (is (empty? (api/datoms db :eavt [item])))
        (is (empty? (api/datoms db :eavt [part]))))
      (testing "the referrer survives; its incoming reference is retracted"
        (is (empty? (api/datoms db :eavt [friend (api/entid db :person/friend)])))
        (is (seq (api/datoms db :eavt [friend]))))
      (testing "retracting a second time is a noop (only the stamp)"
        (let [{:keys [tx-data]} (api/transact conn [[:db.fn/retractEntity order]])]
          (is (= [:db/txInstant] (mapv :a tx-data)))))))))

;; ---- transaction functions (story 17, ADR-0004) -------------------------------

(defn install-count-schema! [conn]
  (define! conn "n" :t/count :db.type/long))

(def set-five-src "(fn [db e] [[:db/add e :t/count 5]])")

(deftest transaction-functions-are-stored-data-and-splice-at-call
  (let [conn (fresh-conn)]
    (install-count-schema! conn)
    (install-tx-fn! conn :t/set-five set-five-src)
    (testing "the fn is ordinary data: an enum plus a :db/fn string, queryable"
      (let [db (api/db conn)
            e (api/entid db :t/set-five)]
        (is (pos? e))
        (is (= [set-five-src] (mapv :v (api/datoms db :eavt [e (api/entid db :db/fn)]))))
        (is (= [:t/set-five]
               (map first (api/q '[:find ?i :in $ ?src
                                   :where [?e :db/fn ?src] [?e :db/ident ?i]]
                                 db set-five-src))))))
    (testing "bare-ident invocation; a tempid arg resolves in the expansion"
      (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/count 0]
                                                  [:t/set-five "e"]])
            e (get tempids "e")]
        (is (= [5] (mapv :v (api/datoms (api/db conn) :eavt [e :t/count]))))))
    (testing ":db.fn/call form splices at the call position — the next op
              sees the fn's effect (:db.fn/cas matches only then)"
      (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/count 0]])
            e (get tempids "e")]
        (api/transact conn [[:db.fn/call :t/set-five e]
                            [:db.fn/cas e :t/count 5 6]])
        (is (= [6] (mapv :v (api/datoms (api/db conn) :eavt [e :t/count]))))))))

(deftest transaction-function-receives-the-accumulated-db
  (let [conn (fresh-conn)]
    (install-count-schema! conn)
    ;; claims the count only when none is visible: the query reads the db as
    ;; of the call position — prior ops of the same tx applied
    (install-tx-fn! conn :t/claim
                    "(fn [db e] (if (empty? (kala.api/q '[:find ?c :in $ ?e :where [?e :t/count ?c]] db (kala.api/entid db e))) [[:db/add e :t/count 99]] nil))")
    (let [{:keys [tempids]} (api/transact conn [[:db/add "e" :t/count 0]])
          e (get tempids "e")]
      (testing "a count written earlier in the SAME tx suppresses the claim —
                a stale db would claim 99 over it"
        (api/transact conn [[:db/add e :t/count 7] [:t/claim e]])
        (is (= [7] (mapv :v (api/datoms (api/db conn) :eavt [e :t/count]))))))
    (testing "nothing visible: the fn's write splices in"
      (let [{:keys [tempids]} (api/transact conn [[:t/claim "other"]])
            e (get tempids "other")]
        (is (= [99] (mapv :v (api/datoms (api/db conn) :eavt [e :t/count]))))))))

(deftest transaction-function-return-shapes
  (let [conn (fresh-conn)]
    (install-count-schema! conn)
    (install-tx-fn! conn :t/noop "(fn [db] nil)")
    (testing "nil return: the transaction is the stamp only"
      (let [{:keys [tx-data]} (api/transact conn [[:t/noop]])]
        (is (= [:db/txInstant] (mapv :a tx-data)))))
    (install-tx-fn! conn :t/emit-map "(fn [db e] {:db/id e :t/count 5})")
    (testing "map-form return expands like a map-form entity"
      (let [{:keys [tempids]} (api/transact conn [[:t/emit-map "m"]])
            e (get tempids "m")]
        (is (pos? e))
        (is (= [5] (mapv :v (api/datoms (api/db conn) :eavt [e :t/count]))))))
    (install-tx-fn! conn :t/emit-seq
                    "(fn [db x] [[:db/add x :t/count 1] [:db/add (str x \"-child\") :t/count 2]])")
    (testing "sequential return may create entities; its tempids resolve in
              the report"
      (let [{:keys [tempids]} (api/transact conn [[:t/emit-seq "root"]])]
        (is (pos? (get tempids "root")))
        (is (pos? (get tempids "root-child")))))
    (testing "speculative with() runs fns against the db value"
      (let [db (api/db conn)
            {:keys [tx-data]} (api/with db [[:t/emit-map "w"]])]
        (is (some #(= :t/count (:a %)) tx-data))
        (is (= (api/db-t db) (api/db-t (api/db conn))) "nothing committed")))))

(deftest transaction-function-sources-validate-at-store-time
  (let [conn (fresh-conn)]
    (testing "unreadable source carries op and source"
      (try (api/transact conn [[:db/add "f" :db/ident :t/bad]
                               [:db/add "f" :db/fn "(not a form"]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (let [d (ex-data e)]
               (is (= :transact/bad-tx-fn (:kala/error d)))
               (is (= [:db/add "f" :db/fn "(not a form"] (:op d)))
               (is (= "(not a form" (:source d)))))))
    (testing "source that evals to a non-function"
      (assert-error :transact/bad-tx-fn
        (api/transact conn [[:db/add "f" :db/ident :t/bad]
                            [:db/add "f" :db/fn "(+ 1 1)"]])))
    (testing "unresolved symbols fail compilation, never store — Clojure
              compiles fn bodies eagerly, so nothing broken is ever stored"
      (assert-error :transact/bad-tx-fn
        (api/transact conn [[:db/add "f" :db/ident :t/bad]
                            [:db/add "f" :db/fn "(fn [db] nope-symbol)"]])))
    (testing "the fn-env namespace sees kala.api, not kala.tx privates"
      (assert-error :transact/bad-tx-fn
        (api/transact conn [[:db/add "f" :db/ident :t/bad]
                            [:db/add "f" :db/fn "(fn [db] (derive-schema db))"]])))
    (testing "every failed definition left nothing behind"
      (is (empty? (api/q '[:find ?e :in $ ?i :where [?e :db/ident ?i]]
                          (api/db conn) :t/bad))))))

(deftest transaction-function-call-failures-are-structured
  (let [conn (fresh-conn)]
    (install-count-schema! conn)
    (define! conn "x" :t/plain :db.type/string)
    (testing "an ident naming no entity"
      (try (api/transact conn [[:t/no-such-fn]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (let [d (ex-data e)]
               (is (= :transact/unknown-tx-fn (:kala/error d)))
               (is (= [:t/no-such-fn] (:op d)))
               (is (= :t/no-such-fn (:ident d)))))))
    (testing "an ident whose entity carries no :db/fn"
      (assert-error :transact/unknown-tx-fn
        (api/transact conn [[:t/plain]])))
    (install-tx-fn! conn :t/boom "(fn [db] (throw (ex-info \"boom\" {})))")
    (testing "a throwing fn wraps into tx-fn-failed; the tx consumed no t"
      (let [t-before (:t (api/transact conn [[:db/add "m1" :t/plain "a"]]))]
        (try (api/transact conn [[:t/boom]])
             (is false "expected ex-info")
             (catch clojure.lang.ExceptionInfo e
               (let [d (ex-data e)]
                 (is (= :transact/tx-fn-failed (:kala/error d)))
                 (is (= :t/boom (:fn d)))
                 (is (= "boom" (:message d))))))
        (is (= (+ t-before 1)
               (:t (api/transact conn [[:db/add "m2" :t/plain "b"]]))))))
    (testing "a return that is neither nil, sequential, nor a map"
      (install-tx-fn! conn :t/garbage "(fn [db] 42)")
      (assert-error :transact/bad-tx-fn
        (api/transact conn [[:t/garbage]])))))

(deftest transaction-function-recursion-is-fueled
  (let [conn (fresh-conn)]
    (install-count-schema! conn)
    (define! conn "x" :t/plain :db.type/string)
    (install-tx-fn! conn :t/spin "(fn [db] [[:t/spin]])")
    (let [t-before (:t (api/transact conn [[:db/add "m1" :t/plain "a"]]))]
      (try (api/transact conn [[:t/spin]])
           (is false "expected ex-info")
           (catch clojure.lang.ExceptionInfo e
             (let [d (ex-data e)]
               (is (= :transact/tx-fn-overflow (:kala/error d)))
               (is (= 1000 (:iterations d))))))
      (testing "the runaway transaction committed nothing"
        (is (= (+ t-before 1)
               (:t (api/transact conn [[:db/add "m2" :t/plain "b"]]))))))))

;; ---- ADR-0007: schema ops apply first within a transaction ---------------------

(deftest fresh-vocabulary-is-usable-in-the-same-transaction
  (let [conn (fresh-conn)]
    (testing "an attribute registers and applies in one turn (list form)"
      (let [{:keys [tempids]} 
            (api/transact conn
                          [[:db/add "prio" :db/ident :task/priority]
                           [:db/add "prio" :db/valueType :db.type/long]
                           [:db/add "prio" :db/cardinality :db.cardinality/one]
                           [:db/add "t" :task/priority 3]])
            eid (get tempids "t")]
        (is (pos? eid))
        (is (= 3 (:v (first (api/datoms (api/db conn) :eavt [eid :task/priority])))))))
    (testing "map form too — even with the data entity written FIRST"
      (let [{:keys [tempids]}
            (api/transact conn
                          [{:db/id "t" :task/tag "ops"}
                           {:db/id "tag" :db/ident :task/tag}
                           {:db/id "tag" :db/valueType :db.type/string}
                           {:db/id "tag" :db/cardinality :db.cardinality/one}])
            eid (get tempids "t")]
        (is (pos? eid))
        (is (= "ops" (:v (first (api/datoms (api/db conn) :eavt [eid :task/tag])))))))
    (testing "the registry survives reopen (replay re-derives in phase order)"
      (is (pos? (api/entid (api/db conn) :task/priority)))
      (is (pos? (api/entid (api/db conn) :task/tag))))))
