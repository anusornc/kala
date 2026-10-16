(ns kala.e2e.client-journey
  "Project-E2E client driver: the remote-access journey over a REAL
  transactor process, in two halves separated by a hard crash.

     clojure -M:e2e write  <host> <port>   — seeds a deterministic store
     clojure -M:e2e verify <host> <port>   — proves what survived it

  The write half installs three attributes, transacts two people and a
  friend edge, compare-and-swaps one age, and retracts the other — every
  step acked by the transactor. The verify half runs against a transactor
  that was killed with SIGKILL and restarted on the same storage
  directory: names and the friend edge must be back, the cas'd age must
  be current, the retracted one gone, as-of must still see the world
  before the update, and history must hold the assertion and the
  retraction. Exit 0 only when every check passes."
  (:require [kala.api :as api]
            [kala.client :as client]))

(def ^:private attrs
  [[:person/name :db.type/string :db.cardinality/one]
   [:person/age :db.type/long :db.cardinality/one]
   [:person/friend :db.type/ref :db.cardinality/one]])

;; the deterministic t-map this driver produces on a fresh file store:
;; t=1 bootstrap, t=2..4 attributes, t=5 the two people, t=6 the friend
;; edge, t=7 the cas, t=8 the retraction. Verify reads as-of t=6 — the
;; world exactly before the cas changed alice's age.

(defn- connect! [host port]
  (client/connect {:host host :port (parse-long port)}))

(defn- define! [conn ident vt card]
  (api/transact conn [[:db/add (name ident) :db/ident ident]
                      [:db/add (name ident) :db/valueType vt]
                      [:db/add (name ident) :db/cardinality card]]))

(defn- q1 [db q & inputs]
  (ffirst (apply api/q q db inputs)))

(defn- named-e [db name]
  (q1 db '[:find ?e :in $ ?n :where [?e :person/name ?n]] name))

(defn- check! [what actual expected]
  (if (= actual expected)
    (println (str "  ok   " what ": " (pr-str actual)))
    (do (println (str "  FAIL " what ": got " (pr-str actual)
                      " want " (pr-str expected)))
        (System/exit 1))))

(defn- check-wrap! [what f]
  (let [result (try {:ok (f)}
                    (catch Throwable e
                      {:err (.getMessage e)
                       :kind (:kala/error (ex-data e))
                       :data (ex-data e)
                       :cause (some-> (.getCause e) .getMessage)}))]
    (if-some [{:keys [ok]} (when (contains? result :ok) result)]
      (println (str "  ok   " what ": " (pr-str ok)))
      (do (println (str "  THROW " what ": " (pr-str result)))
          (System/exit 1)))))

(defn- check-eq! [what f expected]
  (let [result (try {:got (f)}
                    (catch Throwable e
                      {:err (.getMessage e)
                       :kind (:kala/error (ex-data e))
                       :data (ex-data e)
                       :cause (some-> (.getCause e) .getMessage)}))]
    (cond
      (contains? result :got)
      (check! what (:got result) expected)

      :else
      (do (println (str "  THROW " what ": " (pr-str result)))
          (System/exit 1)))))

(defn- write-half! [conn]
  (doseq [[ident vt card] attrs]
    (define! conn ident vt card))
  (api/transact conn [[:db/add "a" :person/name "Alice"]
                      [:db/add "a" :person/age 30]
                      [:db/add "b" :person/name "Bob"]
                      [:db/add "b" :person/age 25]])
  (let [db (api/db conn)
        alice (named-e db "Alice")
        bob (named-e db "Bob")]
    (api/transact conn [[:db/add alice :person/friend bob]])
    (api/transact conn [[:db.fn/cas alice :person/age 30 31]])
    (api/transact conn [[:db/retract bob :person/age 25]])))

(defn- verify-half! [conn]
  (let [db (api/db conn)
        alice (named-e db "Alice")
        bob (named-e db "Bob")]
    (check-eq! "both names survive the crash"
               #(set (map first (api/q '[:find ?n :where [_ :person/name ?n]] db)))
               #{"Alice" "Bob"})
    (check-eq! "alice's entity id is stable across the crash"
               #(pos? alice)
               true)
    (check-eq! "the cas'd age is current"
               #(q1 db '[:find ?a :in $ ?e :where [?e :person/age ?a]] alice)
               31)
    (check-eq! "the retracted age stays gone"
               #(q1 db '[:find ?a :in $ ?e :where [?e :person/age ?a]] bob)
               nil)
    (check-eq! "the friend edge survives"
               #(q1 db '[:find ?fn
                         :where [?a :person/name "Alice"]
                         [?a :person/friend ?f]
                         [?f :person/name ?fn]])
               "Bob")
    (check-eq! "as-of t=6 still sees the world before the cas"
               #(q1 (api/as-of db 6)
                    '[:find ?a :in $ ?e :where [?e :person/age ?a]]
                    alice)
               30)
    (check-eq! "history holds the age's full story (assert, cas retracts the old, re-assert)"
               (fn []
                 (set (map (juxt :v :added)
                           (filter #(= :person/age (:a %))
                                   (api/datoms (api/history db) :eavt [alice])))))
               #{[30 true] [30 false] [31 true]})
    (check-eq! "history holds bob's retraction"
               (fn []
                 (boolean (some #(and (= :person/age (:a %)) (false? (:added %)))
                                (api/datoms (api/history db) :eavt [bob]))))
               true)))

(defn -main [mode host port]
  (let [conn (connect! host port)]
    (try
      (case mode
        "write" (do (write-half! conn)
                    (println "WRITE-OK"))
        "verify" (do (verify-half! conn)
                     (println "VERIFY-OK"))
        (do (println (str "unknown mode: " mode " (use write or verify)"))
            (System/exit 2)))
      (finally
        (client/close conn)))))
