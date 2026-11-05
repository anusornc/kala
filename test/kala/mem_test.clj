(ns kala.mem-test
  "kala.mem CLI: client-mode memory calls without an MCP server, plus the
  --serve line parser. Boots a local transactor like the MCP suite."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.mem :as mem]
            [kala.transactor :as tx]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "kala-mem-" (java.util.UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- with-mem-state [f]
  (let [t (tx/start! {:storage :file :path (tmp-dir) :port 0})
        state (#'mem/open-state {:conn {:host "127.0.0.1" :port (:port t)}
                                 :session "mem-test"})]
    (try
      (f state)
      (finally
        (#'mem/close-state! state)
        ((:stop! t))))))

(deftest call-tool-round-trip
  (with-mem-state
    (fn [state]
      (testing "context before any content"
        (let [doc (mem/call-tool state "context" {})]
          (is (map? doc))
          (is (contains? doc :current))))
      (testing "record registers and uses vocabulary in one turn (ADR-0007)"
        (let [report (mem/call-tool state "record"
                                    {:tx-data [{:db/id "n" :db/ident :note/text
                                                :db/valueType :db.type/string
                                                :db/cardinality :db.cardinality/one}
                                               {:db/id "e" :note/text "hello"}]})]
          (is (pos? (:t report)))
          (is (= 1 (:turn report)))))
      (testing "recall finds the note"
        (let [{:keys [result]} (mem/call-tool state "recall"
                                              {:query '[:find ?t :where [?e :note/text ?t]]})]
          (is (= #{["hello"]} (set result)))))
      (testing "unknown attribute carries the declaration hint"
        (try
          (mem/call-tool state "record" {:tx-data [[:db/add "e" :nope/nothing 1]]})
          (is false "expected ex-info")
          (catch clojure.lang.ExceptionInfo e
            (is (= :transact/unknown-attribute (:kala/error (ex-data e))))
            (is (string? (:hint (ex-data e))))
            (is (re-find #"declare" (.getMessage e)))))))))

(deftest serve-line-parser
  (is (= ["context" {:with-t true}]
         (#'mem/parse-serve-line "context {:with-t true}")))
  (is (= ["record" {:tx-data [[:db/add 1 :a 2]]}]
         (#'mem/parse-serve-line "{:op \"record\" :args {:tx-data [[:db/add 1 :a 2]]}}")))
  (is (= ["turns" {}]
         (#'mem/parse-serve-line "turns")))
  (is (nil? (#'mem/parse-serve-line ""))))
