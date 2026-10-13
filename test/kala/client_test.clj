(ns kala.client-test
  "Client-mode coverage the peer-only suites cannot provide: the full
  time-travel surface over the wire, transaction-completion notifications,
  the explicit peer-only boundary, and error-data parity across the wire."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.client :as client]
            [kala.storage :as storage]
            [kala.storage.memory :as memory]
            [kala.transactor :as tx]
            [kala.test-util :refer [define! install-tx-fn!]]
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

;; ---- error-data parity (#26, #28) ----------------------------------------------

(defn- err-keys [f]
  (try (f) nil
    (catch clojure.lang.ExceptionInfo e
      (sort (disj (into #{} (keys (ex-data e))) :kala/error)))))

(defn- load-parity-fixture! [conn]
  (define! conn "n" :person/name :db.type/string)
  (define! conn "a" :person/age :db.type/long)
  (define! conn "f" :person/friend :db.type/ref)
  (define! conn "em" :person/email :db.type/string {:unique :db.unique/value})
  (define! conn "lg" :person/login :db.type/string {:unique :db.unique/identity})
  (api/transact conn [[:db/add "alice" :person/name "Alice"]
                      [:db/add "h1" :person/login "va"]
                      [:db/add "h2" :person/login "vb"]]))

;; Kinds whose raise sites all carry the same keys: the trigger's keys are
;; exactly the whitelist.
(def exact-triggers
  [[:db/view-not-writable #(api/with (api/since (api/db %) 1) [[:db/add "x" :person/name "y"]])]
   [:transact/unknown-attribute #(api/transact % [[:db/add "x" :person/nope 1]])]
   [:transact/unknown-ident #(api/transact % [[:db/add "alice" :person/friend :no/such]])]
   [:transact/bad-value-type #(api/transact % [[:db/add "x" :person/age :oops]])]
   [:transact/unknown-value-type
    #(api/transact % [[:db/add "x" :db/ident :t/wv]
                      [:db/add "x" :db/valueType (api/entid (api/db %) :db.cardinality/one)]])]
   [:transact/unknown-cardinality
    #(api/transact % [[:db/add "x" :db/ident :t/uc]
                      [:db/add "x" :db/valueType :db.type/string]
                      [:db/add "x" :db/cardinality :db.type/long]])]
   [:transact/unique-not-supported
    #(api/transact % [[:db/add "x" :db/ident :t/un]
                      [:db/add "x" :db/valueType :db.type/bytes]
                      [:db/add "x" :db/unique :db.unique/value]])]
   [:transact/unique-conflict
    #(do (api/transact % [[:db/add "x" :person/email "a@x"]])
         (api/transact % [[:db/add "y" :person/email "a@x"]]))]
   [:transact/conflicting-upserts
    #(api/transact % [[:db/add "t1" :person/login "va"] [:db/add "t1" :person/login "vb"]])]
   [:transact/cas-failed
    #(let [{:keys [tempids]} (api/transact % [[:db/add "x" :person/age 30]])]
       (api/transact % [[:db.fn/cas (get tempids "x") :person/age 99 100]]))]
   [:transact/unknown-tx-fn #(api/transact % [[:t/no-such-tx-fn]])]
   ;; definition and bad source ride one tx, so the abort leaves nothing behind
   [:transact/bad-tx-fn
    #(api/transact % [[:db/add "fn" :db/ident :t/bad-tx-fn]
                      [:db/add "fn" :db/fn "(fn [db] nope-symbol)"]])]
   [:transact/tx-fn-failed
    #(do (install-tx-fn! % :t/boom-tx-fn "(fn [db] (throw (ex-info \"boom\" {})))")
         (api/transact % [[:t/boom-tx-fn]]))]
   [:transact/tx-fn-overflow
    #(do (install-tx-fn! % :t/spin-tx-fn "(fn [db] [[:t/spin-tx-fn]])")
         (api/transact % [[:t/spin-tx-fn]]))]
   [:transact/nested-map-requires-component
    #(api/transact % [{:db/id "alice" :person/friend {:db/id "nested" :person/name "N"}}])]
   [:datoms/unknown-index #(api/datoms (api/db %) :nope [])]
   [:datoms/bad-components #(api/datoms (api/db %) :eavt [1 2 3 4])]
   [:pull/unknown-attribute #(api/pull (api/db %) [:person/nope] 1)]
   [:query/bad-query #(api/q '[:find ?x] (api/db %))]
   [:query/arity #(api/q '[:find ?x :in $ $ :where [?x :person/name _]] (api/db %))]
   [:query/bad-clause #(api/q '[:find ?x :where 5] (api/db %))]
   [:query/bad-find #(api/q '[:find 5 :where [?x :person/name _]] (api/db %))]
   [:query/bad-attribute #(api/q '[:find ?x :where [?x 5 ?v]] (api/db %))]
   [:query/unknown-attribute #(api/q '[:find ?x :where [?x :person/nope 1]] (api/db %))]
   [:query/unknown-ident #(api/q '[:find ?x :where [?x :person/friend :no/such]] (api/db %))]
   [:query/unknown-aggregate #(api/q '[:find (nope ?x) :where [?x :person/name _]] (api/db %))]
   [:query/unknown-builtin #(api/q '[:find ?x :where [?x :person/name ?n] [(nope ?n)]] (api/db %))]
   [:query/unbound-var #(api/q '[:find ?x :where [?x :person/name ?n] [(+ ?missing)]] (api/db %))]
   [:query/unknown-rule
    (fn [conn] (api/q '[:find ?x :in $ % :where (r ?x)] (api/db conn)
                      '[[(real ?x) [?x :person/name _]]]))]
   ;; bodies that synthesize ever-fresh values never reach the fixed point:
   ;; a counting rule walks the integers past the fuel guard (see the
   ;; solve-rules doc comment in kala.query)
   [:query/rule-overflow
    (fn [conn]
      (let [rules [[(list 'r '?y) [(list '+ 1 1) '?y]]
                   [(list 'r '?y) (list 'r '?x) [(list '+ '?x 1) '?y]]]]
        (api/q '[:find ?y :in $ % :where (r ?y)] (api/db conn) rules)))]])

;; Kinds whose raise sites carry differing key subsets: the whitelist is the
;; union (see wire/error-data-keys), so the trigger pins one site's subset and
;; the client must receive exactly what the peer carried.
(def union-triggers
  [[:db/bad-t
    ;; the client's as-of is a lazy db pin: the t validation surfaces on the
    ;; first read through it
    #(api/q '[:find ?x :where [?x :person/name _]] (api/as-of (api/db %) "nope"))]
   [:transact/bad-op #(api/transact % [[:db/add "x" :person/name]])]
   [:transact/bad-entity #(api/transact % [[:db/retract "t1" :person/login "va"]])]
   [:query/bad-aggregate #(api/q '[:find (sum) :where [?x :person/name _]] (api/db %))]
   [:query/bad-binding #(api/q '[:find ?x :where [?x :person/name ?n] [(+ _)]] (api/db %))]
   [:query/bad-rules (fn [conn] (api/q '[:find ?x :in $ % :where (r ?x)] (api/db conn) 5))]
   [:pull/bad-pattern #(api/pull (api/db %) 5 1)]])

(def pinned-kinds
  "Kinds that never ride a transactor reply, pinned with the reason."
  {:query/no-db "the transactor's :q handler always binds $ — the kind is only reachable in-process on peer reads"
   :connect/missing-path "peer-side connect error — clients send no storage options"
   :connect/unknown-storage "peer-side connect error — same"
   :client/peer-only "client-boundary error — the peer runs these ops in-process and never raises it"
   :client/error "fallback kind for a reply frame without :error; the transactor's fail frames always carry one"
   :storage/non-monotonic-t "peer mode triggers it through the public path below (a lying adapter); client mode cannot carry an adapter — the transactor config is EDN keywords"
   :storage/rename-failed "needs filesystem fault injection between temp write and rename; keys enumerated for the reply path"})

(def raw-frame-kinds
  "Reply-level protocol kinds: raised by the wire layer itself, so they are
  asserted on raw frames rather than through the client."
  {:protocol/version-mismatch [:server-version :client-version]
   :protocol/connect-required [:op]
   :transactor/unknown-op [:op]})

(deftest error-data-parity-between-peer-and-client
  (let [t (start-transactor!)
        pconn (api/connect {:storage :memory :name "peer"})
        cconn (client/connect {:host "127.0.0.1" :port (:port t) :name "test"})]
    (try
      (load-parity-fixture! pconn)
      (load-parity-fixture! cconn)
      (testing "every whitelisted kind is triggered or pinned with a reason"
        (is (= (set (keys wire/error-data-keys))
               (set (concat (map first exact-triggers)
                            (map first union-triggers)
                            (keys raw-frame-kinds)
                            (keys pinned-kinds))))))
      (testing "single-site kinds ship exactly their whitelisted keys in both modes"
        (doseq [[kind trigger] exact-triggers]
          (let [peer-keys (err-keys #(trigger pconn))
                client-keys (err-keys #(trigger cconn))]
            (is (= (sort (wire/error-data-keys-for kind)) peer-keys)
                (str kind " carries exactly its whitelisted keys in peer mode"))
            (is (= peer-keys client-keys)
                (str kind " client mode receives what peer mode carries")))))
      (testing "union kinds: the site's keys ride the whitelist and parity holds"
        (doseq [[kind trigger] union-triggers]
          (let [peer-keys (err-keys #(trigger pconn))
                client-keys (err-keys #(trigger cconn))]
            (is (every? (set (wire/error-data-keys-for kind)) peer-keys)
                (str kind " peer keys are within the whitelist"))
            (is (= peer-keys client-keys)
                (str kind " client mode receives what peer mode carries")))))
      (finally
        ((:stop! t))
        (when-some [p (:socket pconn)] (client/close p))))))

(defn- raw-socket! [t]
  (let [sock (java.net.Socket. "127.0.0.1" (int (:port t)))]
    {:sock sock
     :in (java.io.BufferedInputStream. (.getInputStream sock))
     :out (java.io.BufferedOutputStream. (.getOutputStream sock))}))

(defn- frame-keys [reply]
  (sort (disj (into #{} (keys reply)) :status :error :message)))

(deftest protocol-error-kinds-carry-their-keys-on-raw-frames
  (let [t (start-transactor!)]
    (try
      (testing "the first message on a connection must be :connect"
        (let [{:keys [sock in out]} (raw-socket! t)]
          (try
            (wire/write-msg out {:op :datoms :index :eavt :components []})
            (let [reply (wire/read-msg in)]
              (is (= :protocol/connect-required (:error reply)))
              (is (= [:op] (frame-keys reply))))
            (finally (.close sock)))))
      (testing "a version mismatch is a structured error and closes the connection"
        (let [{:keys [sock in out]} (raw-socket! t)]
          (try
            (wire/write-msg out {:op :connect :protocol-version (inc wire/protocol-version)})
            (let [reply (wire/read-msg in)]
              (is (= :protocol/version-mismatch (:error reply)))
              (is (= [:client-version :server-version] (frame-keys reply))))
            (finally (.close sock)))))
      (testing "an op the wire table does not know"
        (let [{:keys [sock in out]} (raw-socket! t)]
          (try
            (wire/write-msg out {:op :connect :protocol-version wire/protocol-version})
            (is (= :ok (:status (wire/read-msg in))))
            (wire/write-msg out {:op :no/such})
            (let [reply (wire/read-msg in)]
              (is (= :transactor/unknown-op (:error reply)))
              (is (= [:op] (frame-keys reply))))
            (finally (.close sock)))))
      (finally
        ((:stop! t))))))

(deftest pinned-kinds-carry-their-keys-where-they-are-constructible
  (testing "peer-side connect kinds never ride a reply"
    (let [ex (try (api/connect {:storage :file :name "x"}) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :connect/missing-path (:kala/error (ex-data ex))))
      (is (= [] (sort (disj (into #{} (keys (ex-data ex))) :kala/error)))))
    (let [ex (try (api/connect {:storage :nope}) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :connect/unknown-storage (:kala/error (ex-data ex))))
      (is (= [:storage] (sort (disj (into #{} (keys (ex-data ex))) :kala/error))))))
  (testing "the non-monotonic guard raises at the storage seam both modes share"
    (let [ms (memory/empty-storage)]
      (storage/append-log ms {:t 5 :datoms []})
      (let [ex (try (storage/append-log ms {:t 3 :datoms []}) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :storage/non-monotonic-t (:kala/error (ex-data ex))))
        (is (= [:last-t :t] (sort (disj (into #{} (keys (ex-data ex))) :kala/error)))))))
  (testing "the non-monotonic guard raises through the public path with a lying adapter"
    ;; an adapter whose log-scan hides its high last-t: the rebuilt conn
    ;; replays a low t, so its first append violates the backend's guard
    (let [inner (memory/empty-storage)]
      (storage/append-log inner {:t 100 :datoms []})
      (let [lying (reify storage/Storage
                    (put-segment [_ id data] (storage/put-segment inner id data))
                    (get-segment [_ id] (storage/get-segment inner id))
                    (list-segments [_] (storage/list-segments inner))
                    (append-log [_ entry] (storage/append-log inner entry))
                    (log-scan [_ from-t to-t]
                      (filter #(< (:t %) 100) (storage/log-scan inner from-t to-t))))
            conn (api/connect {:storage lying :name "stale"})
            ex (try (api/transact conn [[:db/add "x" :db/ident :t/x]]) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :storage/non-monotonic-t (:kala/error (ex-data ex))))
        (is (= [:last-t :t] (sort (disj (into #{} (keys (ex-data ex))) :kala/error)))))))
  (testing "the client-boundary kind is :client/peer-only carrying its op"
    (let [t (start-transactor!)
          conn (client/connect {:host "127.0.0.1" :port (:port t) :name "t"})]
      (try
        (api/entity (api/db conn) 1)
        (is false "expected ex-info")
        (catch clojure.lang.ExceptionInfo e
          (is (= :client/peer-only (:kala/error (ex-data e))))
          (is (= [:op] (sort (disj (into #{} (keys (ex-data e))) :kala/error)))))
        (finally
          ((:stop! t)))))))
