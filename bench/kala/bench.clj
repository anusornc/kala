(ns kala.bench
  "Benchmark harness: internal baselines plus DataScript and original-Datomic
  comparisons, run against identical seed data and identical workloads.
  Before timing, all engines answer the same queries and the result sets are
  cross-checked — the harness doubles as an engine-correctness cross-check.
  Latency rows report the mean plus p50/p95/p99 of per-operation times
  (warmup, then every call timed with nanoTime — GC pauses included, which
  is honest for a database); an update workload (100 single-entity
  transactions) and a point read sit beside the three query shapes; and a
  final section times the same peer engine on durable file storage against
  memory — the durability cost.

  Run:              clojure -M:bench              (core suite: kala, DataScript, Datahike)
  Datomic suite:    clojure -M:bench --suite=datomic  (kala, DataScript, original Datomic)
  Custom size:      clojure -M:bench 5000 20000

  Engines are pluggable maps ({:name :setup :transact-batch :q
  :datoms-count}); engine namespaces load lazily per suite because
  Datahike's and com.datomic/peer's data_readers.clj both claim the
  db/id and db/fn tags — the two jars cannot share a classpath (the JVM
  refuses to boot). Dependencies live under the :bench and
  :bench-datomic aliases only — the release jar is unchanged."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [kala.api :as api]))

(def ^:private batch-size 200)
(def ^:private friend-batch-size 500)

(def ^:private schema-tx
  ;; every attribute carries :db/cardinality — original Datomic requires it
  ;; on all schema attributes, and one is what the workloads actually do
  [[:db/add "schema-name" :db/ident :person/name]
   [:db/add "schema-name" :db/valueType :db.type/string]
   [:db/add "schema-name" :db/cardinality :db.cardinality/one]
   [:db/add "schema-age" :db/ident :person/age]
   [:db/add "schema-age" :db/valueType :db.type/long]
   [:db/add "schema-age" :db/cardinality :db.cardinality/one]
   [:db/add "schema-friend" :db/ident :person/friend]
   [:db/add "schema-friend" :db/valueType :db.type/ref]
   [:db/add "schema-friend" :db/cardinality :db.cardinality/one]])

(def ^:private q-all '[:find ?n :where [_ :person/name ?n]])
(def ^:private q-filtered '[:find ?p :in $ ?age :where [?p :person/age ?age]])
(def ^:private q-join '[:find ?n :where [?p :person/friend ?f] [?f :person/name ?n]])
(def ^:private q-point '[:find ?a :in $ ?e :where [?e :person/age ?a]])

;; ---- engines -----------------------------------------------------------------

(defn- os-engine []
  {:name "kala (peer, memory)"
   :setup (fn []
            (let [conn (api/connect {:storage :memory})]
              (api/transact conn schema-tx)
              conn))
   :transact-batch (fn [conn batch] (api/transact conn batch))
   :q (fn [conn query & args] (apply api/q query (api/db conn) args))
   :datoms-count (fn [conn] (count (api/datoms (api/db conn) :eavt [])))})

(defn- file-engine []
  {:name "kala (peer, file)"
   :dir (str (java.nio.file.Files/createTempDirectory
              (str "kala-bench-file-" (java.util.UUID/randomUUID))
              (make-array java.nio.file.attribute.FileAttribute 0)))
   :setup (fn [dir]
            (let [conn (api/connect {:storage :file :path dir})]
              (api/transact conn schema-tx)
              conn))
   :transact-batch (fn [conn batch] (api/transact conn batch))
   :q (fn [conn query & args] (apply api/q query (api/db conn) args))
   :datoms-count (fn [conn] (count (api/datoms (api/db conn) :eavt [])))})

(defn- ds-engine []
  {:name "DataScript (memory)"
   :setup (fn [] (d/create-conn {}))
   :transact-batch (fn [conn batch] (d/transact! conn batch))
   :q (fn [conn query & args] (apply d/q query @conn args))
   :datoms-count (fn [conn] (count (d/datoms @conn :eavt)))})

;; ---- seed data ------------------------------------------------------------------

(defn- person-batches
  "n entities in batches of batch-size, using string tempids so both
  engines resolve the same way."
  [n]
  (mapv vec
        (partition-all
         batch-size
         (for [i (range n)
               op [[:db/add (str "e" i) :person/name (str "Person " i)]
                   [:db/add (str "e" i) :person/age (mod i 80)]]]
           op))))

(defn- friend-batches
  "friend links from each even entity to the next, using the resolved
  entity ids from the ingest reports."
  [n eids]
  (mapv vec
        (partition-all
         friend-batch-size
         (for [i (range 0 (dec n) 2)]
           [:db/add (get eids (str "e" i)) :person/friend (get eids (str "e" (inc i)))]))))

;; ---- timing ---------------------------------------------------------------------

(defn- timed-seconds [f]
  (let [t0 (System/nanoTime)
        _ (f)]
    (/ (- (System/nanoTime) t0) 1e9)))

;; Single unit contract: everything handed to fmt-time is SECONDS, and all
;; scaling happens here.
(defn- fmt-time [s]
  (cond
    (>= s 1) (format "%.2f s" s)
    (>= s 0.001) (format "%.1f ms" (* 1000.0 s))
    :else (format "%.1f µs" (* 1e6 s))))

(defn- collect-tempids [eids tempids]
  (reduce (fn [acc [tempid eid]]
            (if (string? tempid)
              (assoc acc tempid eid)
              acc))
          eids
          tempids))

;; ---- cross-check ----------------------------------------------------------------

;; Entity ids differ between engines (independent allocation), so the
;; cross-check compares value sets and result counts.
(defn- cross-check [engine conn]
  (let [q (:q engine)]
    {:all (set (q conn q-all))
     :age-count (count (q conn q-filtered 30))
     :join (set (q conn q-join))}))

;; ---- latency and update workloads -------------------------------------------------

(defn- percentile
  "The p-quantile of pre-sorted samples (seconds)."
  [sorted p]
  (nth sorted (min (dec (count sorted))
                   (long (* p (count sorted))))))

(defn- wall-row
  "One markdown row for a single-pass wall-clock figure (ingest, updates):
  no distribution, so the percentile columns print dashes."
  [label engine-name secs]
  (println (str "| " label " | " engine-name " | " (fmt-time secs) " | - | - | - |")))

(def ^:private query-warmup 50)
(def ^:private query-iterations 300)

(defn- latency-row
  "One markdown latency row from a per-op sample distribution in seconds."
  [label engine-name {:keys [mean samples]}]
  (let [p (fn [q] (if (seq samples) (fmt-time (percentile samples q)) "-"))]
    (println (str "| " label " | " engine-name " | " (fmt-time mean)
                  " | " (p 0.50) " | " (p 0.95) " | " (p 0.99) " |"))))

(defn- bench-query
  "Per-operation latency distribution for one query shape: warm up, then
  time every call with nanoTime; returns the sorted samples (seconds) and
  their mean — honest percentiles, garbage-collection pauses included."
  [engine conn query args]
  (dotimes [_ query-warmup]
    (apply (:q engine) conn query args))
  (let [samples (sort (map (fn [_]
                             (let [t0 (System/nanoTime)]
                               (doall (apply (:q engine) conn query args))
                               (/ (- (System/nanoTime) t0) 1e9)))
                           (range query-iterations)))]
    {:mean (/ (reduce + samples) (count samples))
     :samples samples}))

(defn- update-workload
  "100 single-entity transactions through the engine's own transact path;
  wall clock. e0's age ends at 100 — identical across engines, so the
  workload must run AFTER any cross-check that counts age values."
  [engine conn eids]
  (let [target (get eids "e0")]
    (timed-seconds
     (fn []
       (dotimes [i 100]
         ((:transact-batch engine) conn [[:db/add target :person/age (inc i)]]))))))

;; ---- runner ---------------------------------------------------------------------

(defn run-bench
  "Run the full suite for n entities across all engines; prints a results
  table."
  [engines n]
  (println (str "\n=== benchmark: " n " entities, all engines, memory storage ===\n"))
  (let [engines engines
        results (mapv (fn [engine]
                        (println (str "- " (:name engine)))
                        (let [conn ((:setup engine))
                              batches (person-batches n)
                              eids-atom (atom {})
                              ;; timed: the transact+commit of every batch
                              ;; only; seed construction happens outside
                              ingest-secs (timed-seconds
                                         (fn []
                                           (loop [[batch & more] (seq batches)]
                                             (when batch
                                               (let [report ((:transact-batch engine) conn batch)]
                                                 (swap! eids-atom collect-tempids (:tempids report))
                                                 (recur more))))))
                              eids @eids-atom]
                          (doseq [batch (friend-batches n eids)]
                            ((:transact-batch engine) conn batch))
                          (println (str "  datoms in store: " ((:datoms-count engine) conn)))
                          {:engine engine :conn conn :ingest-secs ingest-secs :eids eids}))
                      engines)
        checks (mapv (fn [{:keys [engine conn]}] (cross-check engine conn)) results)
        ;; the first engine is the reference; any other engine whose
        ;; value sets diverge is named so the mismatch is actionable
        reference (first checks)
        diverging (for [[engine check] (map vector engines checks)
                        :when (not= check reference)]
                    (:name engine))]
    (println (str "\n  cross-check: simple/join value sets and filtered count "
                  (if (seq diverging)
                    (str "DIVERGE for " (str/join ", " diverging) " — timings below are meaningless")
                    (str "MATCH across " (count engines)
                         " engines (reference: " (:name (first engines)) ")"))))
    (println "\n| benchmark | engine | mean / total | p50 | p95 | p99 |")
    (println "|---|---|---|---|---|---|")
    (doseq [{:keys [engine conn ingest-secs eids]} results]
      (wall-row "bulk ingest" (:name engine) ingest-secs)
      (wall-row "100 updates (single-entity txs)" (:name engine)
                   (update-workload engine conn eids))
      (let [target (get eids "e0")]
        (doseq [[label query args] [["simple pattern" q-all []]
                                    ["filtered + input" q-filtered [30]]
                                    ["join (friend -> name)" q-join []]
                                    ["point read (entity -> age)" q-point [target]]]]
          (latency-row label (:name engine) (bench-query engine conn query args)))))

    ;; ---- storage cost: the same peer engine on durable file storage -------
    (println (str "\n=== storage cost: " n " entities, :file vs :memory ===\n"))
    (let [file (file-engine)
          _ (println (str "- " (:name file)))
          conn ((:setup file) (:dir file))
          eids-atom (atom {})
          ingest-secs (timed-seconds
                       (fn []
                         (loop [[batch & more] (seq (person-batches n))]
                           (when batch
                             (swap! eids-atom collect-tempids
                                    (:tempids ((:transact-batch file) conn batch)))
                             (recur more)))))
          eids @eids-atom
          _ (doseq [batch (friend-batches n eids)]
              ((:transact-batch file) conn batch))
          ;; parity BEFORE the update workload mutates e0's age
          check (cross-check file conn)]
      (println (str "  datoms in store: " ((:datoms-count file) conn)))
      (println (str "  parity vs the memory engine: "
                    (if (= check reference)
                      "same results"
                      "DIVERGE — see the engine cross-check above")))
      (println "")
      (println "| benchmark | engine | mean / total | p50 | p95 | p99 |")
      (println "|---|---|---|---|---|---|")
      (wall-row "bulk ingest" (:name file) ingest-secs)
      (wall-row "100 updates (single-entity txs)" (:name file)
                   (update-workload file conn eids))
      (let [target (get eids "e0")]
        (doseq [[label query args] [["simple pattern" q-all []]
                                    ["filtered + input" q-filtered [30]]
                                    ["join (friend -> name)" q-join []]
                                    ["point read (entity -> age)" q-point [target]]]]
          (latency-row label (:name file) (bench-query file conn query args))))
      (println ""))))

(defn- parse-sizes [args]
  (if (seq args)
    (mapv (fn [arg]
            (or (parse-long arg)
                (throw (ex-info (str "Not an entity count: " arg)
                                {:bench/bad-arg arg}))))
          args)
    [200 2000]))

;; Two suites, never one classpath: Datahike's and com.datomic/peer's
;; data_readers.clj both claim the db/id and db/fn tags, so the JVM refuses
;; to boot with both jars present. Each suite resolves its third engine
;; lazily, so only that suite's dependency is ever loaded.
(def ^:private suites
  {"core"    (fn []
               [(os-engine)
                (ds-engine)
                ((requiring-resolve 'kala.bench.datahike-engine/engine))])
   "datomic" (fn []
               [(os-engine)
                (ds-engine)
                ((requiring-resolve 'kala.bench.datomic-peer/engine) schema-tx)])})

(defn -main [& args]
  (let [suite (or (some #(second (re-matches #"--suite=(.+)" %)) args) "core")
        build (or (get suites suite)
                  (throw (ex-info (str "Unknown suite: " suite)
                                  {:bench/unknown-suite suite
                                   :bench/suites (vec (keys suites))})))
        sizes (parse-sizes (remove #(re-matches #"--suite=.*" %) args))]
    (doseq [n sizes]
      (run-bench (build) n)))
  (System/exit 0))

