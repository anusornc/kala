(ns datomic-opensource.bench
  "Benchmark harness: internal baselines plus a DataScript comparison, run
  against identical seed data and identical workloads. Before timing, both
  engines answer the same queries and the result sets are cross-checked —
  the harness doubles as an engine-correctness cross-check.

  Run:         clojure -M:bench            (200 and 2000 entities)
  Custom size: clojure -M:bench 5000

  Engines are pluggable maps ({:name :setup :transact-batch :q
  :datoms-count}); an original-Datomic adapter can be added without
  redesign. Dependencies (criterium, datascript) live under the :bench
  alias only — the release jar is unchanged."
  (:require [criterium.core :as crit]
            [datascript.core :as d]
            [datomic-opensource.api :as api]))

(def ^:private batch-size 200)
(def ^:private friend-batch-size 500)

(def ^:private schema-tx
  [[:db/add "schema-name" :db/ident :person/name]
   [:db/add "schema-name" :db/valueType :db.type/string]
   [:db/add "schema-age" :db/ident :person/age]
   [:db/add "schema-age" :db/valueType :db.type/long]
   [:db/add "schema-friend" :db/ident :person/friend]
   [:db/add "schema-friend" :db/valueType :db.type/ref]
   [:db/add "schema-friend" :db/cardinality :db.cardinality/one]])

(def ^:private q-all '[:find ?n :where [_ :person/name ?n]])
(def ^:private q-filtered '[:find ?p :in $ ?age :where [?p :person/age ?age]])
(def ^:private q-join '[:find ?n :where [?p :person/friend ?f] [?f :person/name ?n]])

;; ---- engines -----------------------------------------------------------------

(defn- os-engine []
  {:name "datomic-opensource (peer, memory)"
   :setup (fn []
            (let [conn (api/connect {:storage :memory :name "bench"})]
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

;; ---- runner ---------------------------------------------------------------------

(defn run-bench
  "Run the full suite for n entities across both engines; prints a results
  table."
  [n]
  (println (str "\n=== benchmark: " n " entities, both engines, memory storage ===\n"))
  (let [engines [(os-engine) (ds-engine)]
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
                          {:engine engine :conn conn :ingest-secs ingest-secs}))
                      engines)
        checks (mapv (fn [{:keys [engine conn]}] (cross-check engine conn)) results)]
    (println (str "\n  cross-check: simple/join value sets and filtered count "
                  (if (= (checks 0) (checks 1)) "MATCH across engines" "DIVERGE — timings below are meaningless")))
    (println "\n| benchmark | engine | mean / total |")
    (println "|---|---|---|")
    (doseq [{:keys [engine conn ingest-secs]} results]
      (println (str "| bulk ingest | " (:name engine) " | " (fmt-time ingest-secs) " |"))
      (doseq [[label query args] [["simple pattern" q-all []]
                                  ["filtered + input" q-filtered [30]]
                                  ["join (friend -> name)" q-join []]]]
        (let [stats (crit/quick-benchmark (doall (apply (:q engine) conn query args)) {})
              mean (first (:mean stats))]
          (println (str "| " label " | " (:name engine) " | " (fmt-time mean) " |")))))))

(defn- parse-sizes [args]
  (if (seq args)
    (mapv (fn [arg]
            (or (parse-long arg)
                (throw (ex-info (str "Not an entity count: " arg)
                                {:bench/bad-arg arg}))))
          args)
    [200 2000]))

(defn -main [& args]
  (doseq [n (parse-sizes args)]
    (run-bench n))
  (System/exit 0))

