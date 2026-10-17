(ns kala.memory-test
  "Behavioral seam: the memory layer driven through its public fns —
   exactly as a downstream program (or the MCP server) would. Runs in the
   full {peer, client} x {memory, file} matrix, which is what enforces
   access-mode parity for every memory function."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.memory :as memory]
            [kala.test-util :refer [assert-error fresh-conn]]))

(defn install-vocab!
  "A developer's own vocabulary: a cardinality-one string attribute plus a
  note attribute for tx-meta."
  [conn]
  (api/transact conn
                [[:db/add "proj-name" :db/ident :proj/name]
                 [:db/add "proj-name" :db/valueType :db.type/string]
                 [:db/add "proj-name" :db/cardinality :db.cardinality/one]
                 [:db/add "proj-note" :db/ident :proj/note]
                 [:db/add "proj-note" :db/valueType :db.type/string]
                 [:db/add "proj-note" :db/cardinality :db.cardinality/one]]))

(defn install-config!
  "A component attribute for the nested-map guard test."
  [conn]
  (api/transact conn
                [[:db/add "proj-cfg" :db/ident :proj/config]
                 [:db/add "proj-cfg" :db/valueType :db.type/ref]
                 [:db/add "proj-cfg" :db/cardinality :db.cardinality/one]
                 [:db/add "proj-cfg" :db/isComponent true]]))

(defn- ready-conn []
  (let [conn (fresh-conn)]
    (install-vocab! conn)
    (memory/install conn)
    conn))

(defn- session-datom-count [db s-eid]
  (ffirst (api/q '[:find (count ?tx) :in $ ?s :where [?tx :memory/session ?s]]
                 db s-eid)))

;; ---- install -----------------------------------------------------------------

(deftest install-registers-the-spine-and-is-idempotent
  (let [conn (fresh-conn)
        report (memory/install conn)]
    (testing "install registers exactly the three spine idents"
      (is (some? report) "first install returns the tx report")
      (is (pos? (:t report)))
      (let [db (api/db conn)]
        (doseq [ident [:memory/session :memory.session/id :memory/source]]
          (is (pos? (api/entid db ident)) (pr-str ident)))))
    (testing "second install is a no-op returning nil"
      (is (nil? (memory/install conn)))
      (let [db (api/db conn)]
        (doseq [ident [:memory/session :memory.session/id :memory/source]]
          (is (pos? (api/entid db ident)) (pr-str ident)))))))

;; ---- sessions ----------------------------------------------------------------

(deftest session-generates-fresh-and-resumes-by-id
  (let [conn (ready-conn)]
    (testing "generated sessions are fresh entities"
      (let [s1 (memory/session conn)
            s2 (memory/session conn)]
        (is (pos? s1))
        (is (pos? s2))
        (is (not= s1 s2))))
    (testing "re-opening an id returns the same session entity"
      (let [s (memory/session conn {:id "run-42"})]
        (is (= s (memory/session conn {:id "run-42"})))
        (is (not= s (memory/session conn {:id "run-43"})))))
    (testing "a session id must be a non-empty string"
      (assert-error :memory/unknown-session (memory/session conn {:id 42})))))

;; ---- recording ---------------------------------------------------------------

(deftest record-attributes-one-transaction-per-turn
  (let [conn (ready-conn)
        s (memory/session conn {:id "main"})]
    (testing "one record = one transaction, fully attributed"
      (let [r (memory/record conn s [[:db/add "p" :proj/name "kala"]])]
        (is (pos? (:t r)))
        (is (= 1 (:turn r)))
        (is (pos? (:tx r)))
        (is (= 3 (:datoms r)) "the belief, the attribution, and the txInstant stamp")
        (is (= {"p" (get (:tempids r) "p")} (:tempids r))
            "the user's tempids only — the tx-meta tempid is not theirs")
        (is (= 1 (session-datom-count (api/db conn) s))
            "the turn's tx carries :memory/session")))
    (testing "turns land in log order, one t each, ordinals per session"
      (let [s2 (memory/session conn {:id "other"})
            r2 (memory/record conn s [[:db/add "p2" :proj/name "two"]])
            r2b (memory/record conn s2 [[:db/add "q1" :proj/name "interleaved"]])
            r3 (memory/record conn s [[:db/add "p3" :proj/name "three"]])]
        (is (apply < (map :t [r2 r2b r3])) "t is a counter — every turn takes the next one")
        (is (= 2 (:turn r2)))
        (is (= 1 (:turn r2b)) "interleaved sessions count independently")
        (is (= 3 (:turn r3)))))))

(deftest record-resolves-tempids-for-later-reference
  (let [conn (ready-conn)
        s (memory/session conn)
        r (memory/record conn s [[:db/add "p" :proj/name "kala"]])
        eid (get (:tempids r) "p")]
    (is (pos? eid))
    (is (= {:db/id eid :proj/name "kala"}
           (api/pull (api/db conn) '[:proj/name] eid)))))

(deftest record-cites-current-and-prior-turns
  (let [conn (ready-conn)
        s (memory/session conn)]
    (testing "\"datomic.tx\" in a ref position cites the current turn"
      (let [r (memory/record conn s [[:db/add "p" :proj/name "x"]
                                     [:db/add "p" :memory/source "datomic.tx"]])]
        (is (= [(:tx r)] (:memory/source (api/pull (api/db conn) '[:memory/source]
                                                   (get (:tempids r) "p"))))
            "a card-many citation, readable back")))
    (testing "a prior turn is cited by its transaction entity id"
      (let [r1 (memory/record conn s [[:db/add "p" :proj/name "derived-from"]])
            r2 (memory/record conn s [[:db/add "q" :proj/name "y"]
                                      [:db/add "q" :memory/source (:tx r1)]])]
        (is (= [(:tx r1)] (:memory/source (api/pull (api/db conn) '[:memory/source]
                                                    (get (:tempids r2) "q")))))))
    (testing "custom tx-meta rides the turn entity alongside the attribution"
      (let [r (memory/record conn s [{:db/id "datomic.tx" :proj/note "why"}])]
        (is (pos? (:tx r)))
        (is (= "why" (:proj/note (api/pull (api/db conn) '[:proj/note] (:tx r)))))))))

(deftest card-one-correction-keeps-history
  (let [conn (ready-conn)
        s (memory/session conn)
        r1 (memory/record conn s [[:db/add "p" :proj/name "v1"]])
        eid (get (:tempids r1) "p")
        r2 (memory/record conn s [[:db/add eid :proj/name "v2"]])]
    (testing "the belief reads as-of either turn"
      (is (= "v1" (:proj/name (api/pull (api/as-of (api/db conn) (:t r1)) '[:proj/name] eid))))
      (is (= "v2" (:proj/name (api/pull (api/db conn) '[:proj/name] eid)))))
    (testing "the superseded value stays in history with its retraction"
      (let [hist (vec (filter #(= :proj/name (:a %))
                              (api/datoms (api/history (api/db conn)) :eavt [eid])))]
        (is (some #(and (true? (:added %)) (= "v1" (:v %))) hist))
        (is (some #(and (false? (:added %)) (= "v1" (:v %)) (= (:tx r2) (:tx %))) hist)
            "the correction turn is what retracted the old belief")))))

(deftest cas-guards-concurrent-sessions
  (let [conn (ready-conn)
        s1 (memory/session conn {:id "s1"})
        s2 (memory/session conn {:id "s2"})
        r (memory/record conn s1 [[:db/add "p" :proj/name "a"]])
        eid (get (:tempids r) "p")]
    (testing "a mismatched cas lands nothing"
      (assert-error :transact/cas-failed
        (memory/record conn s2 [[:db.fn/cas eid :proj/name "wrong" "b"]]))
      (is (= "a" (:proj/name (api/pull (api/db conn) '[:proj/name] eid)))))
    (testing "the guarded variant succeeds when the expectation holds"
      (memory/record conn s2 [[:db.fn/cas eid :proj/name "a" "b"]])
      (is (= "b" (:proj/name (api/pull (api/db conn) '[:proj/name] eid)))))))

;; ---- guards ------------------------------------------------------------------

(deftest record-without-install-raises-not-installed
  (let [conn (fresh-conn)]
    (install-vocab! conn)
    (assert-error :memory/not-installed
      (memory/record conn (memory/session conn) [[:db/add "p" :proj/name "x"]]))
    (assert-error :memory/not-installed (memory/session conn {:id "x"}))))

(deftest unknown-session-handle-raises
  (let [conn (ready-conn)]
    (assert-error :memory/unknown-session
      (memory/record conn "no-such-session" [[:db/add "p" :proj/name "x"]]))
    (assert-error :memory/unknown-session
      (memory/record conn 999999 [[:db/add "p" :proj/name "x"]]))))

(deftest reserved-attrs-cannot-be-written-through-record
  (let [conn (ready-conn)
        s (memory/session conn {:id "main"})]
    (install-config! conn)
    (testing "an op vector cannot re-attribute the turn"
      (assert-error :memory/reserved-attr
        (memory/record conn s [[:db/add "p" :proj/name "x"]
                               [:db/add "datomic.tx" :memory/session 123]])))
    (testing "a map form cannot forge session identity"
      (assert-error :memory/reserved-attr
        (memory/record conn s [{:db/id "x" :memory.session/id "forged"}])))
    (testing "a nested component map cannot hide the attribution"
      (assert-error :memory/reserved-attr
        (memory/record conn s [{:db/id "p" :proj/name "x"
                                :proj/config {:db/id "c" :memory/session 123}}])))
    (testing "a reverse-ref spelling is refused too"
      (assert-error :memory/reserved-attr
        (memory/record conn s [{:db/id "x" :memory/_session 123}])))
    (testing "nothing from a refused record lands"
      (is (nil? (ffirst (api/q '[:find ?e :in $ :where [?e :memory.session/id "forged"]]
                               (api/db conn))))))))

;; ---- read doors ---------------------------------------------------------------

(deftest query-door-caps-with-visible-truncation
  (let [conn (ready-conn)
        s (memory/session conn)]
    (dotimes [i 5]
      (memory/record conn s [[:db/add (str "e" i) :proj/name (str "n" i)]]))
    (let [db (api/db conn)
          q '[:find ?n :where [?e :proj/name ?n]]]
      (testing "under the cap: result and an honest not-capped marker"
        (let [{:keys [result truncated]} (memory/query db q {})]
          (is (= 5 (count result)))
          (is (= {:capped? false} truncated))))
      (testing "over the cap: capped visibly, values never hinted at"
        (let [{:keys [result truncated]} (memory/query db q {:limit 2})]
          (is (= 2 (count result)))
          (is (= {:capped? true :shown 2 :dropped 3 :limit 2} truncated))))
      (testing ":args binds the rest of :in, in order"
        (let [eid (ffirst (api/q '[:find ?e :where [?e :proj/name "n1"]] db))
              {:keys [result]} (memory/query db '[:find ?v :in $ ?e :where [?e :proj/name ?v]]
                                             {:args [eid]})]
          (is (= [["n1"]] result))))
      (testing ":as-of pins the read to a belief state"
        (let [r1 (memory/record conn s [[:db/add "p" :proj/name "v1"]])
              eid (get (:tempids r1) "p")
              t1 (:t r1)
              _ (memory/record conn s [[:db/add eid :proj/name "v2"]])
              db (api/db conn)
              q1 '[:find ?v :in $ ?e :where [?e :proj/name ?v]]]
          (is (= [["v1"]] (:result (memory/query db q1 {:args [eid] :as-of t1}))))
          (is (= [["v2"]] (:result (memory/query db q1 {:args [eid]}))))))
      (testing "scalar find shapes pass through uncapped"
        (let [{:keys [result truncated]} (memory/query db '[:find [?n] :where [?e :proj/name ?n]] {})]
          (is (string? result))
          (is (= {:capped? false} truncated))))
      (testing "kala error kinds surface verbatim"
        (assert-error :query/bad-query
          (memory/query db '[:where [?e :proj/name ?n]] {}))))))

(deftest pull-door-refuses-oversize-documents
  (let [conn (ready-conn)
        _ (api/transact conn [[:db/add "blob" :db/ident :proj/blob]
                              [:db/add "blob" :db/valueType :db.type/string]
                              [:db/add "blob" :db/cardinality :db.cardinality/one]])
        s (memory/session conn)
        big (apply str (repeat 20000 "x"))
        r (memory/record conn s [[:db/add "p" :proj/name "named"]
                                 [:db/add "p" :proj/blob big]])
        eid (get (:tempids r) "p")
        db (api/db conn)]
    (testing "the whole entity is over the cap: refuse, never truncate"
      (assert-error :memory/result-too-large (memory/pull db eid {})))
    (testing "a narrower pattern fits"
      (is (= {:db/id eid :proj/name "named"}
             (memory/pull db eid {:pattern [:proj/name]}))))
    (testing "a nonexistent entity is nil, not an error"
      (is (nil? (memory/pull db 999999 {}))))
    (testing "kala's own pull errors surface verbatim"
      (assert-error :pull/bad-pattern (memory/pull db eid {:pattern [[:proj/name :bogus-opt 1]]})))))

(deftest history-door-scopes
  (let [conn (ready-conn)
        s1 (memory/session conn {:id "s1"})
        s2 (memory/session conn {:id "s2"})
        r1 (memory/record conn s1 [[:db/add "p" :proj/name "v1"]])
        eid (get (:tempids r1) "p")
        _ (memory/record conn s2 [[:db/add "q" :proj/name "other-session"]])
        r3 (memory/record conn s1 [[:db/add eid :proj/name "v2"]
                                   [:db/add eid :proj/note "derived"]])
        db (api/db conn)]
    (testing "entity scope: the correction trail, in t order"
      (let [{:keys [result truncated]} (memory/history db {:scope :entity :entity eid})]
        (is (= {:capped? false} truncated))
        (is (= [["v1" true (:t r1)] ["v1" false (:t r3)] ["v2" true (:t r3)] ["derived" true (:t r3)]]
               (mapv (juxt :v :added :t) result))
            "t order; within one turn the index's own value order")))
    (testing "entity scope narrows to attributes"
      (let [{:keys [result]} (memory/history db {:scope :entity :entity eid :attrs #{:proj/name}})]
        (is (every? #(= :proj/name (:a %)) result))
        (is (= 3 (count result)))))
    (testing "entity scope honors inclusive t bounds"
      (let [{:keys [result]} (memory/history db {:scope :entity :entity eid :from-t (:t r3)})]
        (is (= 3 (count result)))
        (is (every? #(= (:t r3) (:t %)) result))))
    (testing "session scope: the provenance trail, other sessions excluded"
      (let [{:keys [result]} (memory/history db {:scope :session :session "s1"})
            own-turns #{(:t r1) (:t r3)}]
        (is (contains? own-turns (:t r1)))
        (is (every? #(contains? own-turns (:t %)) result) "only s1's turns")
        (is (some #(= :memory/session (:a %)) result) "the attribution datom rides its own trail")))
    (testing "session scope accepts a session entity id too"
      (let [{:keys [result]} (memory/history db {:scope :session :session s1})]
        (is (pos? (count result)))))
    (testing "bad handles raise memory kinds"
      (assert-error :memory/bad-scope (memory/history db {:scope :everything}))
      (assert-error :memory/unknown-session (memory/history db {:scope :session :session "nope"}))
      (assert-error :memory/bad-option (memory/history db {:scope :entity :entity "p"}))
      (assert-error :memory/bad-option (memory/history db {:scope :entity :entity eid :from-t -1})))))

(deftest turns-door-lists-the-step-index
  (let [conn (ready-conn)
        s (memory/session conn {:id "main"})
        r1 (memory/record conn s [[:db/add "p" :proj/name "one"]])
        r2 (memory/record conn s [[:db/add "p2" :proj/name "two"]])
        db (api/db conn)]
    (testing "one triple per turn, in t order, ordinals 1-based"
      (let [{:keys [session turns truncated]} (memory/turns db "main" {})]
        (is (= "main" session))
        (is (= {:capped? false} truncated))
        (is (= [[(:t r1) 1] [(:t r2) 2]] (mapv (juxt first second) turns)))
        (is (every? #(inst? (nth % 2)) turns) "the wall clock rides the door, honestly")))
    (testing "bounded by :as-of — a pinned belief state never sees its future"
      (let [{:keys [turns]} (memory/turns db "main" {:as-of (:t r1)})]
        (is (= [[(:t r1) 1]] (mapv (juxt first second) turns)))))
    (testing "capped like every list-shaped door"
      (let [{:keys [turns truncated]} (memory/turns db "main" {:limit 1})]
        (is (= 1 (count turns)))
        (is (= {:capped? true :shown 1 :dropped 1 :limit 1} truncated))))
    (testing "handles: id string and entity id alike"
      (let [s-eid (memory/session conn {:id "main"})]
        (is (= 2 (count (:turns (memory/turns db s-eid {})))))))
    (testing "unknown sessions raise"
      (assert-error :memory/unknown-session (memory/turns db "nope" {})))
    (testing "bad options raise"
      (assert-error :memory/bad-option (memory/turns db "main" {:limit 0})))))
