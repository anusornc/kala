(ns kala.demo
  "End-to-end proof walkthrough of Kala's public surface, one runnable file.
  Every claim prints its actual value and is asserted — a mismatch aborts
  with a nonzero exit, so a run that prints ALL PROOFS PASSED means the
  behavior is real. Run: clojure -M:demo

  Covers: peer connect (memory + file), schema, list- and map-form
  transactions, Datalog (bound inputs, joins, recursive rules, aggregates),
  pull (nested patterns), entity navigation (forward and reverse refs),
  compare-and-swap with its structured failure, stored transaction
  functions, time travel (as-of, history, with), durable file storage
  across a reopen, and the remote client over the transactor wire —
  including the peer-only boundary in client mode."
  (:require [kala.api :as api]
            [kala.client :as client]
            [kala.transactor :as tx])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]))

;; ---- harness -----------------------------------------------------------------

(defn- section [s]
  (println (str "\n==== " s " ====")))

(defn- verify
  "Assert (= actual expected) and print the value; a mismatch aborts the
  demo via ex-info, which -main turns into a nonzero exit."
  [claim actual expected]
  (if (= actual expected)
    (println (str "  ok   " claim ": " (pr-str actual)))
    (throw (ex-info (str "proof failed: " claim)
                    {:demo/claim claim :demo/expected expected :demo/actual actual}))))

(defn- tmp-dir! [label]
  (str (Files/createTempDirectory
        (str "kala-demo-" label "-" (UUID/randomUUID))
        (make-array FileAttribute 0))))

(defn- define!
  "One schema attribute: ident + valueType + cardinality."
  [conn ident vt card]
  (api/transact conn [[:db/add (name ident) :db/ident ident]
                      [:db/add (name ident) :db/valueType vt]
                      [:db/add (name ident) :db/cardinality card]]))

(defn- all-names [db]
  (set (map first (api/q '[:find ?n :where [_ :person/name ?n]] db))))

(defn- age-of [db e]
  (ffirst (api/q '[:find ?a :in $ ?e :where [?e :person/age ?a]] db e)))

(defn- tags-of [db e]
  (set (map :v (api/datoms db :eavt [e :person/tag]))))

(def ancestor-rules
  '[[(ancestor ?a ?d) [?d :person/parent ?a]]
    [(ancestor ?a ?d) [?d :person/parent ?p] (ancestor ?a ?p)]])

(def promote-src
  "(fn [db e] (let [a (ffirst (kala.api/q '[:find ?a :in $ ?e :where [?e :person/age ?a]] db (kala.api/entid db e)))] (when (and a (< a 35)) [[:db/add e :person/age 35]])))")

;; ---- sections 1-2: peer, schema, both transaction forms ------------------------

(defn- seed-memory-db!
  "Sections 1 and 2. Returns {:alice :bob :carol :dave :t0} for the
  sections that follow."
  [conn]
  (section "1. Peer connect, schema, transactions with string tempids")
  (define! conn :person/name :db.type/string :db.cardinality/one)
  (define! conn :person/age :db.type/long :db.cardinality/one)
  (define! conn :person/friend :db.type/ref :db.cardinality/one)
  (define! conn :person/tag :db.type/string :db.cardinality/many)
  (define! conn :person/parent :db.type/ref :db.cardinality/many)
  (let [{:keys [tempids]} (api/transact conn
                            [[:db/add "alice" :person/name "Alice"]
                             [:db/add "alice" :person/age 30]
                             [:db/add "bob" :person/name "Bob"]
                             [:db/add "bob" :person/age 25]
                             [:db/add "carol" :person/name "Carol"]
                             [:db/add "carol" :person/age 35]
                             [:db/add "dora" :person/name "Dora"]
                             [:db/add "dora" :person/age 60]])
        alice (get tempids "alice")
        bob (get tempids "bob")
        carol (get tempids "carol")
        dora (get tempids "dora")]
    (println "  the report resolved the string tempid \"alice\" ->" alice)
    (api/transact conn
      [[:db/add alice :person/friend bob]
       [:db/add carol :person/parent alice]
       [:db/add carol :person/parent bob]
       [:db/add dora :person/parent carol]])
    (verify "four people transacted" (all-names (api/db conn))
            #{"Alice" "Bob" "Carol" "Dora"})
    (section "2. Map-form transactions, cardinality-many")
    (let [{:keys [tempids]} (api/transact conn
                              [{:db/id "dave" :person/name "Dave"
                                :person/age 28
                                :person/tag ["clojure" "datalog"]}])
          dave (get tempids "dave")]
      (verify "map-form tempid resolved" (pos? dave) true)
      (verify "many-cardinality tags" (tags-of (api/db conn) dave)
              #{"clojure" "datalog"})
      {:alice alice :bob bob :carol carol :dave dave
       :t0 (api/db-t (api/db conn))})))

;; ---- sections 3-10 -------------------------------------------------------------

(defn- run-all! []
  (let [conn (api/connect {:storage :memory :name "demo"})
        {:keys [alice bob carol dave t0]} (seed-memory-db! conn)
        db (api/db conn)]

    (section "3. Datalog: predicates, joins, recursive rules, aggregates")
    (verify "predicate: everyone aged 30 or over"
            (set (map first (api/q '[:find ?n
                                     :where [?p :person/age ?a]
                                     [(>= ?a 30)]
                                     [?p :person/name ?n]]
                                   db)))
            #{"Alice" "Carol" "Dora"})
    (verify "join: Alice's friend's name"
            (set (map first (api/q '[:find ?fn
                                     :where [?a :person/name "Alice"]
                                     [?a :person/friend ?f]
                                     [?f :person/name ?fn]]
                                   db)))
            #{"Bob"})
    (verify "recursive rule: Dora's ancestors (transitive)"
            (set (map first (api/q '[:find ?an :in $ % ?dn
                                     :where [?d :person/name ?dn]
                                     (ancestor ?a ?d)
                                     [?a :person/name ?an]]
                                   db ancestor-rules "Dora")))
            #{"Alice" "Bob" "Carol"})
    (verify "aggregate: people count"
            (ffirst (api/q '[:find (count ?p) :where [?p :person/name]] db))
            5)

    (section "4. Pull: wildcard and nested spec")
    (let [doc (api/pull db '[*] alice)]
      (println "  pull [*]:" (pr-str doc))
      (verify "nested pull walks the ref"
              (get-in (api/pull db '[{:person/friend [:person/name]}] alice)
                      [:person/friend :person/name])
              "Bob"))

    (section "5. Entity navigation, forward and reverse refs")
    (let [e (api/entity db alice)]
      (verify "keyword read" (:person/name e) "Alice")
      (verify "ref attr returns an entity" (:person/name (:person/friend e)) "Bob")
      (verify "reverse ref navigates back (a seq of referrers)"
              (mapv :person/name (:person/_friend (:person/friend e)))
              ["Alice"])
      (println "  touch:" (pr-str (select-keys (api/touch e) [:person/name :person/age]))))

    (section "6. Compare-and-swap, and its structured failure")
    (api/transact conn [[:db.fn/cas alice :person/age 30 31]])
    (verify "matching old applies the new value" (age-of (api/db conn) alice) 31)
    (let [err (try (api/transact conn [[:db.fn/cas alice :person/age 30 99]])
                   "no error!"
                   (catch clojure.lang.ExceptionInfo e e))]
      (verify "stale CAS rejected with kind" (:kala/error (ex-data err))
              :transact/cas-failed)
      (println "  error data:" (pr-str (select-keys (ex-data err) [:old :new :actual])))
      (verify ":actual reports the live value" (:actual (ex-data err)) 31)
      (verify "the failed CAS left nothing behind" (age-of (api/db conn) alice) 31))
    (api/transact conn [[:db.fn/cas alice :person/age 31 40]])
    (verify "retry with the fresh old value" (age-of (api/db conn) alice) 40)

    (section "7. Stored transaction functions (the source is data)")
    (api/transact conn [[:db/add "fn" :db/ident :demo/promote]
                        [:db/add "fn" :db/fn promote-src]])
    (verify "the fn is queryable data"
            (set (map first (api/q '[:find ?i :in $ ?src
                                     :where [?e :db/fn ?src]
                                     [?e :db/ident ?i]]
                                   (api/db conn) promote-src)))
            #{:demo/promote})
    (println "  ages before: bob" (age-of db bob) "dave" (age-of db dave))
    (api/transact conn [[:demo/promote bob] [:demo/promote dave]])
    (verify "the fn lifted bob to 35" (age-of (api/db conn) bob) 35)
    (verify "the fn lifted dave to 35" (age-of (api/db conn) dave) 35)

    (section "8. Time travel: as-of, history, with")
    (let [past (api/as-of (api/db conn) t0)]
      (verify "as-of t0 sees the original age" (age-of past alice) 30)
      (verify "as-of t0 predates the tx-fn lift" (age-of past bob) 25))
    (api/transact conn [[:db/retract dave :person/tag "datalog"]])
    (verify "current tags after the retract" (tags-of (api/db conn) dave) #{"clojure"})
    (verify "history still holds the retraction"
            (vec (map :v (filter #(and (false? (:added %)) (= :person/tag (:a %)))
                                 (api/datoms (api/history (api/db conn)) :eavt [dave]))))
            ["datalog"])
    (let [spec (api/with (api/db conn) [[:db/add carol :person/age 99]])]
      (verify "speculative db-after has the write" (age-of (:db-after spec) carol) 99)
      (verify "the real db is untouched" (age-of (api/db conn) carol) 35))

    (section "9. Durable file storage: write, reopen, recover")
    (let [dir (tmp-dir! "file")
          fconn (api/connect {:storage :file :path dir})]
      (define! fconn :person/name :db.type/string :db.cardinality/one)
      (api/transact fconn [[:db/add "kiwi" :person/name "Kiwi"]])
      (let [logs (vec (sort (map #(.getName ^File %)
                                 (.listFiles (File. (str dir "/log"))))))]
        (println "  on disk:" (pr-str logs))
        (verify "one durable log file per transaction" (count logs) 2))
      (let [t-before (api/db-t (api/db fconn))
            revived (api/connect {:storage :file :path dir})]
        (verify "data survived the reopen" (all-names (api/db revived)) #{"Kiwi"})
        (verify "t counter rebuilt to the last acked t"
                (api/db-t (api/db revived)) t-before)))

    (section "10. Remote client over the transactor wire")
    (let [t (tx/start! {:storage :file :path (tmp-dir! "srv") :port 0})
          rc (client/connect {:host "127.0.0.1" :port (:port t) :name "demo"})]
      (define! rc :person/name :db.type/string :db.cardinality/one)
      (api/transact rc [[:db/add "zoe" :person/name "Zoe"]])
      (let [rdb (api/db rc)
            zoe (ffirst (api/q '[:find ?e :where [?e :person/name "Zoe"]] rdb))]
        (verify "query over the wire" (all-names rdb) #{"Zoe"})
        (println "  pull over the wire:" (pr-str (api/pull rdb '[*] zoe)))
        (let [err (try (api/entity rdb zoe)
                       "no error!"
                       (catch clojure.lang.ExceptionInfo e e))]
          (verify "peer-only ops are rejected in client mode"
                  (:kala/error (ex-data err)) :client/peer-only)))
      (client/close rc)
      ((:stop! t))
      (println "  client closed, transactor stopped"))))

;; ---- entry ---------------------------------------------------------------------

(defn -main [& _]
  (println "Kala demo — every claim below is asserted against real behavior.\n")
  (try
    (run-all!)
    (println "\n==== ALL PROOFS PASSED ====")
    (catch Throwable e
      (println "\n==== DEMO FAILED ====")
      (println (.getMessage e))
      (when (instance? clojure.lang.ExceptionInfo e)
        (println (pr-str (ex-data e))))
      (System/exit 1)))
  (System/exit 0))
