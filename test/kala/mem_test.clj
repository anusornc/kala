(ns kala.mem-test
  "kala.mem CLI: client-mode memory calls without an MCP server, the
  --serve loop, one-shot exit codes, and start-transactor argv parsing.
  Boots a local transactor like the MCP suite."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kala.mem :as mem]
            [kala.transactor :as tx])
  (:import [java.io BufferedReader PrintWriter StringReader StringWriter]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "kala-mem-" (java.util.UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- with-mem-state [f]
  (let [t (tx/start! {:storage :file :path (tmp-dir) :port 0})
        state (#'mem/open-state {:conn {:host "127.0.0.1" :port (:port t)}
                                 :session "mem-test"})]
    (try
      (f state t)
      (finally
        (#'mem/close-state! state)
        ((:stop! t))))))

(defn- write-cfg!
  "Write a kala-mem config.edn pointing at the live test transactor."
  [dir port]
  (let [path (str dir "/mcp.edn")]
    (spit path (pr-str {:conn {:host "127.0.0.1" :port port}
                        :session "mem-test"}))
    path))

(defn- capture-run
  "Invoke mem/run with *out*/*err* captured. Returns
  {:exit n :out s :err s}."
  [args]
  (let [out (StringWriter.)
        err (StringWriter.)]
    (binding [*out* out
              *err* err]
      {:exit (mem/run args)
       :out (str out)
       :err (str err)})))

(deftest tool-names-is-the-six-locked-tools
  (is (= ["context" "record" "recall" "read" "history" "turns"]
         mem/tool-names))
  (is (= (set mem/tool-names) @#'mem/tools)))

(deftest call-tool-round-trip
  (with-mem-state
    (fn [state _]
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

(deftest read-history-turns-round-trip
  (with-mem-state
    (fn [state _]
      (let [report (mem/call-tool state "record"
                                  {:tx-data [{:db/id "n" :db/ident :note/text
                                              :db/valueType :db.type/string
                                              :db/cardinality :db.cardinality/one}
                                             {:db/id "e" :note/text "hello"}]})
            eid (get-in report [:tempids "e"])]
        (testing "read pulls the entity"
          (let [doc (mem/call-tool state "read" {:entity eid})]
            (is (= "hello" (:note/text doc)))))
        (testing "history by session scope — the :session-id arg maps to :session"
          (let [{:keys [result]} (mem/call-tool state "history"
                                                {:scope "session" :session-id "mem-test"})]
            (is (seq result))
            (is (some #(= :note/text (:a %)) result))))
        (testing "history by entity scope accepts the keyword spelling too"
          (let [{:keys [result]} (mem/call-tool state "history"
                                                {:scope :entity :entity eid})]
            (is (some #(= :note/text (:a %)) result))))
        (testing "turns lists the session's turn listing"
          (let [{:keys [turns]} (mem/call-tool state "turns" {})]
            (is (= 1 (count turns)))
            (is (= 1 (second (first turns))))))))))

(deftest serve-line-parser
  (is (= ["context" {:with-t true}]
         (#'mem/parse-serve-line "context {:with-t true}")))
  (is (= ["record" {:tx-data [[:db/add 1 :a 2]]}]
         (#'mem/parse-serve-line "{:op \"record\" :args {:tx-data [[:db/add 1 :a 2]]}}")))
  (is (= ["turns" {}]
         (#'mem/parse-serve-line "turns")))
  (is (nil? (#'mem/parse-serve-line ""))))

(deftest serve-stdin-loop
  (with-mem-state
    (fn [state _]
      (let [in (BufferedReader.
                (StringReader.
                 (str "context {:with-t true}\n"
                      "history {:scope \"session\" :session-id \"mem-test\"}\n"
                      "turns\n"
                      "\n")))
            sw (StringWriter.)
            out (PrintWriter. sw true)]
        (binding [*err* (PrintWriter. (StringWriter.) true)]
          (mem/serve! state in out))
        (let [lines (->> (str/split-lines (str sw))
                         (remove str/blank?)
                         (mapv edn/read-string))]
          (is (= 3 (count lines)))
          (is (contains? (first lines) :current))
          (is (map? (second lines)))
          (is (contains? (second lines) :result))
          (is (contains? (nth lines 2) :turns)))))))

(deftest serve-stdin-loop-tool-error-stays-open
  (with-mem-state
    (fn [state _]
      (let [in (BufferedReader.
                (StringReader.
                 (str "read {}\n"
                      "context\n"
                      "\n")))
            sw (StringWriter.)
            out (PrintWriter. sw true)]
        (binding [*err* (PrintWriter. (StringWriter.) true)]
          (mem/serve! state in out))
        (let [lines (->> (str/split-lines (str sw))
                         (remove str/blank?)
                         (mapv edn/read-string))]
          (is (= 2 (count lines)))
          (is (= :mem/bad-argument (:kala/error (first lines))))
          (is (contains? (second lines) :current)))))))

(deftest run-usage-exit-code
  (doseq [args [[] ["--help"] ["help"] ["-h"] ["only-one-arg"]]]
    (let [{:keys [exit err]} (capture-run args)]
      (is (= 2 exit) (str "args=" (pr-str args)))
      (is (re-find #"Usage:" err)))))

(deftest run-one-shot-exit-codes
  (with-mem-state
    (fn [_state t]
      (let [dir (tmp-dir)
            cfg (write-cfg! dir (:port t))]
        (testing "successful one-shot exits 0 and prints EDN on stdout"
          (let [{:keys [exit out]} (capture-run [cfg "context" "{:with-t true}"])
                doc (edn/read-string out)]
            (is (= 0 exit))
            (is (contains? doc :current))))
        (testing "tool error exits 1 with :kala/error on stdout"
          (let [{:keys [exit out]} (capture-run [cfg "read" "{}"])
                doc (edn/read-string out)]
            (is (= 1 exit))
            (is (= :mem/bad-argument (:kala/error doc)))))
        (testing "unknown tool exits 1"
          (let [{:keys [exit err]} (capture-run [cfg "nope" "{}"])]
            (is (= 1 exit))
            (is (re-find #":mem/unknown-tool" err))))
        (testing "session-key drift class — history :session-id reaches the store"
          ;; record under the config session, then history with that id via run
          (is (= 0 (:exit (capture-run
                           [cfg "record"
                            (pr-str {:tx-data [{:db/id "n" :db/ident :note/text
                                                :db/valueType :db.type/string
                                                :db/cardinality :db.cardinality/one}
                                               {:db/id "e" :note/text "via-run"}]})]))))
          (let [{:keys [exit out]} (capture-run
                                    [cfg "history"
                                     (pr-str {:scope "session" :session-id "mem-test"})])
                doc (edn/read-string out)]
            (is (= 0 exit))
            (is (some #(= :note/text (:a %)) (:result doc)))))))))

(deftest start-transactor-foreground-flag-anywhere
  ;; Regression for #56: --foreground used to be honored only as $2, so
  ;; `start-transactor --foreground cfg.edn` silently treated the flag as
  ;; the config path. Argv parsing must accept the flag in any position.
  (let [script (.getAbsolutePath (io/file "bin/start-transactor"))
        missing (str (tmp-dir) "/no-such-transactor.edn")
        run (fn [& args]
              (apply sh/sh (into [script] args)))]
    (testing "--foreground before config still resolves the config path"
      (let [{:keys [exit err]} (run "--foreground" missing)]
        (is (= 2 exit))
        (is (re-find #"no-such-transactor\.edn" err))
        (is (not (re-find #"config not found: .*/--foreground" err)))))
    (testing "--foreground after config still recognized (usage of missing file)"
      (let [{:keys [exit err]} (run missing "--foreground")]
        (is (= 2 exit))
        (is (re-find #"no-such-transactor\.edn" err))))
    (testing "flag alone is usage"
      (let [{:keys [exit err]} (run "--foreground")]
        (is (= 2 exit))
        (is (re-find #"Usage:" err))))))
