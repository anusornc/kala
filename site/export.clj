;; Static-site exporter: turns kala stores into the JSON the GitHub
;; Pages explorer renders — the monitor's graph derivation plus the two
;; drilldowns (a turn's datoms, an entity's correction trail), pre-baked
;; so the site serves with no server (ADR-0012 holds: nothing here ever
;; connects to a live store; fixtures come from backup-log exports, and
;; the showcase store is recorded fresh through kala.memory's own path).
;;
;; Run:   clojure -M:site
;; Out:   site/data/showcase.json, site/data/trial-3b.json,
;;        site/graph-view.js, site/graph-view.css

(require '[clojure.java.io :as io]
         '[kala.api :as api]
         '[kala.backup :as backup]
         '[kala.httpd :as httpd]
         '[kala.memory :as memory]
         '[kala.monitor.graph :as graph])

(defn- export-payload [db]
  (let [g (assoc (graph/graph db {}) :ok true)
        turns-datoms (into {}
                           (for [t (distinct (map :t (:turns g)))]
                             [(str "t" t) (graph/turn-datoms db t)]))
        trails (into {}
                     (for [e (:entities g)
                           :let [eid (parse-long (subs (:id e) 1))]]
                       [(str "e" eid) (graph/entity-trail db eid)]))]
    (assoc g :turns-datoms turns-datoms :trails trails)))

(defn- write-json [path m]
  (io/make-parents path)
  (spit path (httpd/jobj m))
  (println "wrote" path))

;; ---- the showcase store: recorded fresh through kala.memory's own path ----
;; The October trial stores anchor their notes on lookup-ident entities
;; ([:proj/kala ...]), which the monitor's model — correctly — excludes as
;; schema vocabulary, so their graphs are sessions+citations only. This
;; store tells the same story (three sessions, notes corrected, a tag
;; retracted, refs, cross-session citations) on ordinary entities, through
;; the real record path, so every edge kind is on the wall.

(defn- showcase-store! []
  (let [conn (api/connect {:storage :memory})
        _ (api/transact conn
                        [[:db/add "nt" :db/ident :note/title]
                         [:db/add "nt" :db/valueType :db.type/string]
                         [:db/add "nt" :db/cardinality :db.cardinality/one]
                         [:db/add "ntg" :db/ident :note/tag]
                         [:db/add "ntg" :db/valueType :db.type/string]
                         [:db/add "ntg" :db/cardinality :db.cardinality/many]
                         [:db/add "nrf" :db/ident :note/related]
                         [:db/add "nrf" :db/valueType :db.type/ref]
                         [:db/add "nrf" :db/cardinality :db.cardinality/one]
                         [:db/add "tst" :db/ident :task/state]
                         [:db/add "tst" :db/valueType :db.type/keyword]
                         [:db/add "tst" :db/cardinality :db.cardinality/one]
                         [:db/add "ttl" :db/ident :task/title]
                         [:db/add "ttl" :db/valueType :db.type/string]
                         [:db/add "ttl" :db/cardinality :db.cardinality/one]])
        _ (memory/install conn)
        _ (memory/session conn {:id "research"})
        _ (memory/session conn {:id "design"})
        _ (memory/session conn {:id "ops"})
        r1 (memory/record conn "research" [{:db/id "n1" :note/title "wire listen design"
                                            :note/tag ["client" "adr"]}])
        n1 (get-in r1 [:tempids "n1"])
        _ (memory/record conn "research" [{:db/id "n2" :note/title "single-writer holds"
                                           :note/tag "core"}])
        n2 (get-in r1 [:tempids "n2"])
        r3 (memory/record conn "design" [{:db/id "n3" :note/title "monitor layout"
                                          :note/tag "ui"}])
        n3 (get-in r3 [:tempids "n3"])
        ;; a card-one correction: the layout note is retitled
        r4 (memory/record conn "design" [{:db/id n3 :note/title "monitor view modes"}])
        ;; a retraction: the "adr" tag was wrong for n1
        _ (memory/record conn "research" [[:db/retract n1 :note/tag "adr"]])
        ;; refs: the ops runbook relates to the design note; the release
        ;; note relates to the single-writer note
        r6 (memory/record conn "ops" [{:db/id "n4" :note/title "ops runbook"
                                       :note/related n3}])
        n4 (get-in r6 [:tempids "n4"])
        _ (memory/record conn "ops" [{:db/id "n5" :note/title "release checklist"
                                      :note/related n2}])
        ;; a task whose state progresses, then a cross-session citation:
        ;; the design turn cites the research turn it drew from
        _ (memory/record conn "ops" [{:db/id "t1" :task/title "rotate logs"
                                             :task/state :task.state/todo}])
        _ (memory/record conn "ops" [{:db/id "t1" :task/title "rotate logs"
                                             :task/state :task.state/done}])
        _ (memory/record conn "design" [{:db/id "n6" :note/title "view modes spec"
                                         :note/related n1
                                         :memory/source (:tx r1)}])
        _ (memory/record conn "research" [{:db/id "n7" :note/title "listen channels"
                                           :note/tag "wire"
                                           :memory/source (:tx r4)}])]
        conn))

;; ---- the trial store: the real October artifact, exactly as exported ----

(defn- trial-store-rebuilt [path]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "site-export-trial"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        conn0 (api/connect {:storage :file :path dir})]
    (with-open [in (io/reader path)]
      (backup/import-log (api/db-storage (api/db conn0)) in))
    ;; the import writes the storage log directly — rebuild from it
    (api/connect {:storage :file :path dir})))

;; ---- run -----------------------------------------------------------------------

(let [out "site/data"]
  (write-json (str out "/showcase.json")
              (export-payload (api/db (showcase-store!))))
  (write-json (str out "/trial-3b.json")
              (export-payload (api/db (trial-store-rebuilt
                                       "docs/research/multi-agent-trial-2026-10-21/store-3b.edn"))))
  ;; the graph view's shared files: web/shared is the single source — the
  ;; monitor serves it off the classpath, the site gets a copy here, and
  ;; the CI gate fails if either drifts
  (doseq [f ["graph-view.js" "graph-view.css"]]
    (io/copy (io/file "web/shared" f) (io/file "site" f))
    (println "copied" (str "site/" f)))
  (println "done"))
(System/exit 0)
