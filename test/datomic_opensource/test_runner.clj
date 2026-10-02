(ns datomic-opensource.test-runner
  (:require [clojure.test :as t]

            [datomic-opensource.api-test]
            [datomic-opensource.schema-test]
            [datomic-opensource.storage.memory-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-all-tests #"datomic-opensource\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
