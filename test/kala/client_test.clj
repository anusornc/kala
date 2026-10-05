(ns kala.client-test
  "Client-mode coverage the peer-only suites cannot provide: the full
  time-travel surface over the wire, transaction-completion notifications,
  and the explicit peer-only boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.client :as client]
            [kala.transactor :as tx]
            [kala.test-util :refer [define!]]
            [kala.wire :as wire]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "kala-client-" (java.util.UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- start-transactor! []
  (tx/start! {:storage :file :path (tmp-dir) :port 0}))

(defn- q-set [db q & inputs]
  (set (map first (apply api/q q db inputs))))

(deftest time-travel-over-the-wire
  (let [t (start-transactor!)
        conn (client/connect {:host "127.0.0.1" :port (:port t) :name "test"})]
    (try
      (define! conn "n" :person/name :db.type/string)
      (define! conn "a" :person/age :db.type/long)
      (let [r1 (api/transact conn [[:db/add "alice" :person/name "Alice"]
                                   [:db/add "alice" :person/age 30]])
            alice (get (:tempids r1) "alice")
            tx2 (:t r1)
            r2 (api/transact conn [[:db/add "bob" :person/name "Bob"]
                                   [:db/add "bob" :person/age 25]])
            tx3 (:t r2)
            _ (api/transact conn [[:db/retract alice :person/age 30]])
            db-now (api/db conn)]
        (testing "as-of pins q, pull, and datoms to the past"
          (let [db2 (api/as-of db-now tx2)]
            (is (= #{30} (q-set db2 '[:find ?a :in $ ?p :where [?p :person/age ?a]] alice)))
            (is (= {:person/name "Alice" :person/age 30}
                   (dissoc (api/pull db2 [:person/name :person/age] alice) :db/id)))
            (is (= [30] (mapv :v (api/datoms db2 :eavt [alice (api/entid db2 :person/age)]))))))
        (testing "history shows assertion and retraction with status"
          (let [ages (filter #(= :person/age (:a %))
                             (api/datoms (api/history db-now) :eavt [alice]))]
            (is (= [true false] (mapv :added ages)))))
        (testing "with() runs server-side without storage effect"
          (let [report (api/with db-now [[:db/add "ghost" :person/name "Ghost"]])]
            (is (= 1 (count (q-set (:db-after report)
                                   '[:find ?p :where [?p :person/name "Ghost"]]))))
            (is (= #{} (q-set db-now '[:find ?p :where [?p :person/name "Ghost"]])))))
        (testing "since hides facts from t and earlier"
          (let [db-since (api/since db-now tx2)]
            (is (= 1 (count (q-set db-since '[:find ?p :where [?p :person/name "Bob"]]))))
            (is (= #{} (q-set db-since '[:find ?n :in $ ?p :where [?p :person/name ?n]] alice))))))
      (finally ((:stop! t))))))

(deftest notifications-push-tx-completions
  (let [t (start-transactor!)
        conn (client/connect {:host "127.0.0.1" :port (:port t) :name "test"})]
    ;; schema goes in before the listener subscribes, so the expected push
    ;; set is deterministic
    (define! conn "n" :person/name :db.type/string)
    (let [received (atom [])
          listener (client/listen conn (fn [msg] (swap! received conj msg)))]
      (try
        (testing "the subscription is active before we write"
          (Thread/sleep 300))
        (let [{:keys [t]} (api/transact conn [[:db/add "alice" :person/name "Alice"]])]
          (loop [n 0]
            (when (and (empty? @received) (< n 50))
              (Thread/sleep 100)
              (recur (inc n))))
          (is (= [{:notification :tx-completed :t t}] @received)))
        (finally
          ((:stop! listener))
          ((:stop! t)))))))

(deftest peer-only-operations-are-rejected-explicitly-in-client-mode
  (let [t (start-transactor!)
        conn (client/connect {:host "127.0.0.1" :port (:port t) :name "test"})]
    (try
      (define! conn "n" :person/name :db.type/string)
      (api/transact conn [[:db/add "a" :person/name "Alice"]])
      (let [db (api/db conn)
            eid (api/entid db :person/name)]
        (testing "entity navigation"
          (try (api/entity db eid)
               (is false "expected ex-info")
               (catch clojure.lang.ExceptionInfo e
                 (is (= :client/peer-only (:kala/error (ex-data e)))))))
        (testing "seek-datoms"
          (try (api/seek-datoms db :eavt [])
               (is false "expected ex-info")
               (catch clojure.lang.ExceptionInfo e
                 (is (= :client/peer-only (:kala/error (ex-data e))))))))
      (finally ((:stop! t))))))

;; ---- error-data parity (#26) ---------------------------------------------------

(defn- err-keys [f]
  (try (f) nil
    (catch clojure.lang.ExceptionInfo e
      (sort (disj (into #{} (keys (ex-data e))) :kala/error)))))

(defn- load-parity-fixture! [conn]
  (define! conn "n" :person/name :db.type/string)
  (define! conn "a" :person/age :db.type/long)
  (define! conn "f" :person/friend :db.type/ref)
  (define! conn "em" :person/email :db.type/string {:unique :db.unique/value})
  (api/transact conn [[:db/add "alice" :person/name "Alice"]]))

(deftest error-data-parity-between-peer-and-client
  (let [t (start-transactor!)
        pconn (api/connect {:storage :memory :name "peer"})
        cconn (client/connect {:host "127.0.0.1" :port (:port t) :name "test"})
        ;; each trigger takes a connection and raises exactly one kind
        triggers [[:pull/unknown-attribute
                   #(api/pull (api/db %) [:person/nope] 1)]
                  [:transact/unknown-attribute
                   #(api/transact % [[:db/add "x" :person/nope 1]])]
                  [:transact/unknown-ident
                   #(api/transact % [[:db/add "alice" :person/friend :no/such]])]
                  [:transact/bad-value-type
                   #(api/transact % [[:db/add "x" :person/age :oops]])]
                  [:transact/unique-conflict
                   #(do (api/transact % [[:db/add "x" :person/email "a@x"]])
                        (api/transact % [[:db/add "y" :person/email "a@x"]]))]
                  [:query/unknown-attribute
                   #(api/q '[:find ?x :where [?x :person/nope 1]] (api/db %))]
                  [:query/bad-clause
                   #(api/q '[:find ?x :where 5] (api/db %))]
                  [:datoms/unknown-index
                   #(api/datoms (api/db %) :nope [])]
                  [:db/view-not-writable
                   #(api/with (api/since (api/db %) 1) [[:db/add "x" :person/name "y"]])]]]
    (try
      (load-parity-fixture! pconn)
      (load-parity-fixture! cconn)
      (testing "each error kind ships the same data keys in both modes"
        (doseq [[kind trigger] triggers]
          (let [peer-keys (err-keys #(trigger pconn))
                client-keys (err-keys #(trigger cconn))]
            (is (= (sort (wire/error-data-keys-for kind)) peer-keys)
                (str kind " carries exactly its whitelisted keys in peer mode"))
            (is (= peer-keys client-keys)
                (str kind " client mode receives what peer mode carries")))))
      (finally
        ((:stop! t))
        (when-some [p (:socket pconn)] (client/close p))))))
