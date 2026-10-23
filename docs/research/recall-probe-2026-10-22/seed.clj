;; Recall-probe seeder: a small ops store whose facts are findable only by
;; lexical recall — an agent that half-remembers "paging threshold" must
;; filter note values with includes?. Run from the kala repo root:
;;   clojure -M /tmp/kala-probe/seed.clj
(require '[kala.api :as api]
         '[kala.memory :as memory])

(def ag "/tmp/kala-probe")
(def conn (api/connect {:storage :file :path (str ag "/store")}))
(memory/install conn)

(api/transact conn
              [[:db/add "svc" :db/ident :service/note]
               [:db/add "svc" :db/valueType :db.type/string]
               [:db/add "svc" :db/cardinality :db.cardinality/one]
               [:db/add "own" :db/ident :service/owner]
               [:db/add "own" :db/valueType :db.type/string]
               [:db/add "own" :db/cardinality :db.cardinality/one]
               [:db/add "b" :db/ident :service/billing]
               [:db/add "i" :db/ident :service/ingest]
               [:db/add "a" :db/ident :service/auth]])

(def s (memory/session conn {:id "ops-keeper"}))
(def r (memory/record
         conn s
         [[:db/add :service/billing :service/note
           "invoices regenerate nightly at 02:10 UTC; retries every 15 min"]
          [:db/add :service/ingest :service/note
           "kafka consumer lag pages the on-call at 3 min backlog"]
          [:db/add :service/auth :service/note
           "JWT rotation runs hourly; old tokens stay valid 5 min"]
          [:db/add :service/ingest :service/owner "platform team"]]))
(println "SEEDED turn" (:turn r) "at t" (:t r))

(doseq [a ["claude" "agy" "grok" "qwen"]]
  (spit (str ag "/" a ".edn")
        (pr-str {:conn {:host "127.0.0.1" :port 4334} :session a})))
(println "client configs written")
