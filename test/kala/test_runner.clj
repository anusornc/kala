(ns kala.test-runner
  "Runs the behavioral suites in the full mode x storage matrix —
  {peer, client} x {memory, file} — which is what enforces access-mode and
  storage-backend parity end to end. Client-mode runs skip ^:peer
  namespaces (entity navigation and storage introspection are peer-only).
  Suites that run once, outside the matrix — the client's own coverage,
  recovery, the per-adapter storage seams, the transactor, and the
  unit suites (kala.eid-test, kala.weak-cache-test) — are listed below."
  (:require [clojure.test :as t]

            [kala.api-test]
            [kala.map-tx-test]
            [kala.pull-entity-test]
            [kala.query-rules-test]
            [kala.query-test]
            [kala.schema-test]
            [kala.time-travel-test]

            [kala.client-test]
            [kala.eid-test]
            [kala.file-recovery-test]
            [kala.transactor-test]
            [kala.wire-test]
            [kala.storage.memory-test]
            [kala.storage.file-test]
            [kala.weak-cache-test]))

(def ^:private behavioral-ns-symbols
  ['kala.api-test
   'kala.map-tx-test
   'kala.pull-entity-test
   'kala.query-rules-test
   'kala.query-test
   'kala.schema-test
   'kala.time-travel-test])

(def ^:private peer-only? (into {} (map (fn [s] [s (boolean (:peer (meta (find-ns s))))])
                                        behavioral-ns-symbols)))

(def ^:private once-ns-symbols
  ['kala.client-test
   'kala.eid-test
   'kala.file-recovery-test
   'kala.transactor-test
   'kala.weak-cache-test
   'kala.wire-test
   'kala.storage.memory-test
   'kala.storage.file-test])

(def ^:private matrix
  (for [mode [:peer :client]
        storage [:memory :file]]
    {:mode mode
     :storage storage
     :path (when (= :file storage)
             (str (java.nio.file.Files/createTempDirectory
                   (str "kala-matrix-" (name mode) "-" (java.util.UUID/randomUUID))
                   (make-array java.nio.file.attribute.FileAttribute 0))))}))

(def ^:private tmp-root
  (java.nio.file.Files/createTempDirectory
   (str "kala-runner-" (java.util.UUID/randomUUID))
   (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- run-counting [run-fn]
  (let [{:keys [fail error]} (run-fn)]
    [(long (or fail 0)) (long (or error 0))]))

(defn- merge-counts [a b]
  [(+ (a 0) (b 0)) (+ (a 1) (b 1))])

(defn -main [& _]
  (let [matrix-counts
        (mapv (fn [{:keys [mode storage path]}]
                (binding [kala.test-util/*access-mode* mode
                          kala.test-util/*storage-opts*
                          {:storage storage :path (or path (str tmp-root "/" (java.util.UUID/randomUUID)))}]
                  (println "\n=== matrix:" mode "x" storage "===")
                  (reduce merge-counts [0 0]
                          (remove nil?
                                  (mapv (fn [ns-sym]
                                          (cond
                                            (and (= mode :client) (peer-only? ns-sym))
                                            (do (println "(skipping peer-only:" ns-sym ")") nil)

                                            :else
                                            (run-counting #(t/run-tests ns-sym))))
                                        behavioral-ns-symbols)))))
              matrix)
        [mf me] (reduce merge-counts [0 0] matrix-counts)
        [bf be] (reduce merge-counts [0 0]
                        (mapv (fn [ns-sym]
                                (println "\n=== backend:" ns-sym "===")
                                (run-counting #(t/run-tests ns-sym)))
                              once-ns-symbols))]
    (println (str "\nmatrix total: " (+ mf bf) " failures, " (+ me be) " errors"))
    (System/exit (if (zero? (+ mf bf me be)) 0 1))))