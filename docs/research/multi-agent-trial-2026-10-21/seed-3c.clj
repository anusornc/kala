;; Trial seeder, variant 3c: identical to seed.clj except :proj/note is
;; registered cardinality-many (finding 2 fixed at the schema — every
;; session's note is kept). Run from the kala repo root:
;;   clojure -M /tmp/kala-agents2/seed-3c.clj
(require '[kala.api :as api]
         '[kala.memory :as memory]
         '[clojure.java.io :as io])

(def ag "/tmp/kala-agents2")
(def conn (api/connect {:storage :file :path (str ag "/store")}))
(memory/install conn)

;; developer vocabulary (registered before use — the registry derives per tx)
(api/transact conn
  (into []
        (mapcat (fn [t]
                  [[:db/add (str t) :db/ident t]
                   [:db/add (str t) :db/valueType :db.type/string]
                   [:db/add (str t) :db/cardinality
                    (if (= t :proj/note)
                      :db.cardinality/many
                      :db.cardinality/one)]]))
        [:proj/deploy-command :proj/test-command :proj/deploy-target
         :proj/note :proj/runbook]))

;; the runbook entity the beliefs hang on
(api/transact conn [[:db/add "rb" :db/ident :proj/runbook]])
(def rb (api/entid (api/db conn) :proj/runbook))

;; the teammate's session: what they believed when they last touched this
(def s (memory/session conn {:id "runbook-keeper"}))
(def r1 (memory/record
          conn s
          [[:db/add rb :proj/test-command "bash scripts/test.sh"]
           [:db/add rb (keyword "proj" "deploy-command")
            (str "./deploy" ".sh staging-old")]
           [:db/add rb :proj/deploy-target "staging-old"]
           [:db/add rb :proj/note "run deploy from the repo root"]]))
(println "SEEDED turn" (:turn r1) "at t" (:t r1) "runbook eid" rb)

;; exec bits on the ops scripts (no shell needed)
(doseq [f ["deploy.sh" "scripts/test.sh"]]
  (.setExecutable (io/file (str ag "/ops-repo/" f)) true))
(println "exec bits set")

;; per-agent server configs — same store, one session each
(doseq [a ["claude" "agy" "grok"]]
  (spit (str ag "/" a ".edn")
        (pr-str {:conn {:storage :file :path (str ag "/store")}
                 :session a})))
(println "configs written")
