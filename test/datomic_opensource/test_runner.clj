(ns datomic-opensource.test-runner
  "Runs the behavioral suites (everything using test-util/fresh-conn) once
  per storage backend — memory and file — which is what enforces
  storage-backend parity, then the backend-specific suites (recovery and
  the per-adapter storage seams) exactly once."
  (:require [clojure.test :as t]

            [datomic-opensource.api-test]
            [datomic-opensource.map-tx-test]
            [datomic-opensource.pull-entity-test]
            [datomic-opensource.query-rules-test]
            [datomic-opensource.query-test]
            [datomic-opensource.schema-test]
            [datomic-opensource.time-travel-test]

            [datomic-opensource.file-recovery-test]
            [datomic-opensource.transactor-test]
            [datomic-opensource.storage.memory-test]
            [datomic-opensource.storage.file-test]))

(def ^:private behavioral-ns-symbols
  ['datomic-opensource.api-test
   'datomic-opensource.map-tx-test
   'datomic-opensource.pull-entity-test
   'datomic-opensource.query-rules-test
   'datomic-opensource.query-test
   'datomic-opensource.schema-test
   'datomic-opensource.time-travel-test])

(def ^:private backend-specific-ns-symbols
  ['datomic-opensource.file-recovery-test
   'datomic-opensource.transactor-test
   'datomic-opensource.storage.memory-test
   'datomic-opensource.storage.file-test])

(def ^:private storage-bindings
  [{:storage :memory}
   {:storage :file :path (str (java.nio.file.Files/createTempDirectory
                               (str "dos-matrix-" (java.util.UUID/randomUUID))
                               (make-array java.nio.file.attribute.FileAttribute 0)))}])

(defn -main [& _]
  (let [results
        (into
         (mapv (fn [opts]
                 (binding [datomic-opensource.test-util/*storage-opts* opts]
                   (println "\n=== matrix:" (:storage opts) "===")
                   (apply t/run-tests behavioral-ns-symbols)))
               storage-bindings)
         [(apply t/run-tests backend-specific-ns-symbols)])
        fail (reduce + (map :fail results))
        err (reduce + (map :error results))]
    (println (str "\nmatrix total: " fail " failures, " err " errors"))
    (System/exit (if (zero? (+ fail err)) 0 1))))
