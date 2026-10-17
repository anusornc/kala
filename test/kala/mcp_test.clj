(ns kala.mcp-test
  "The protocol seam: every test drives kala.mcp/handle — one JSON-RPC
  request map in, one reply map out — over an in-memory store with the
  server's own boot path (make-state). The stdio loop is not under test;
  the wire to kala is (a real file-backed server journey lives in the
  e2e script). Runs once, outside the matrix."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.mcp :as mcp]))

(def ^:private vocab
  [[:db/add "dc" :db/ident :proj/deploy-command]
   [:db/add "dc" :db/valueType :db.type/string]
   [:db/add "dc" :db/cardinality :db.cardinality/one]
   [:db/add "proj" :db/ident :proj/kala]])

(defn- state! []
  ;; the protocol seam runs against an in-memory peer directly — the
  ;; access-mode matrix is kala.memory-test's job; the server's client
  ;; posture is the e2e journey's
  (let [conn (api/connect {:storage :memory})]
    (api/transact conn vocab)
    (mcp/make-state conn "kala-main-test")))

(defn- call! [state name args]
  (mcp/handle state {:jsonrpc "2.0" :id 1 :method "tools/call"
                     :params {:name name :arguments args}}))

(defn- result-text [reply]
  (-> reply :result :content first :text))

(defn- read-edn [reply]
  (edn/read-string (result-text reply)))

;; ---- protocol ----------------------------------------------------------------

(deftest initialize-negotiates-and-describes-the-server
  (let [state (state!)]
    (testing "a known version echoes back"
      (let [reply (mcp/handle state {:jsonrpc "2.0" :id 0 :method "initialize"
                                     :params {:protocolVersion "2024-11-05"}})]
        (is (= "2024-11-05" (-> reply :result :protocolVersion)))
        (is (= {:tools {}} (-> reply :result :capabilities)))
        (is (= "kala-mcp" (-> reply :result :serverInfo :name)))))
    (testing "an unknown version gets ours"
      (is (= mcp/protocol-version
             (-> (mcp/handle state {:jsonrpc "2.0" :id 0 :method "initialize"
                                    :params {:protocolVersion "1999-01-01"}})
                 :result :protocolVersion))))))

(deftest protocol-plumbing
  (let [state (state!)]
    (testing "notifications say nothing"
      (is (nil? (mcp/handle state {:jsonrpc "2.0" :method "notifications/initialized"}))))
    (testing "a request without a method is invalid, notifications without one say nothing"
      (is (= -32600 (-> (mcp/handle state {:jsonrpc "2.0" :id 1}) :error :code)))
      (is (nil? (mcp/handle state {:jsonrpc "2.0"}))))
    (testing "unknown methods are -32601"
      (is (= -32601 (-> (mcp/handle state {:jsonrpc "2.0" :id 2 :method "resources/list"})
                        :error :code))))
    (testing "ping pongs"
      (is (= {} (-> (mcp/handle state {:jsonrpc "2.0" :id 3 :method "ping"}) :result))))
    (testing "malformed json is -32700 with a null id"
      (is (= {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}}
             (mcp/handle-line state "{not json"))))))

(deftest tools-list-declares-the-six-locked-tools
  (let [reply (mcp/handle (state!) {:jsonrpc "2.0" :id 1 :method "tools/list"})
        tools (-> reply :result :tools)
        names (mapv :name tools)]
    (is (= ["context" "record" "recall" "read" "history" "turns"] names))
    (doseq [t tools]
      (is (string? (:description t)) (pr-str (:name t)))
      (is (= "object" (-> t :inputSchema :type)) (pr-str (:name t))))
    (testing "the required arguments are declared"
      (is (= ["tx-data"] (-> (nth tools 1) :inputSchema :required)))
      (is (= ["query"] (-> (nth tools 2) :inputSchema :required)))
      (is (= ["entity"] (-> (nth tools 3) :inputSchema :required)))
      (is (= ["scope"] (-> (nth tools 4) :inputSchema :required)))
      (is (= [] (-> (nth tools 0) :inputSchema :required))
          "context is the default read — no required arguments"))))

;; ---- the tools over a live store ----------------------------------------------

(deftest record-then-context-is-the-round-trip
  (let [state (state!)
        proj (api/entid (api/db (:conn @state)) :proj/kala)
        record (call! state "record"
                      {:tx-data (pr-str [[:db/add proj :proj/deploy-command "make deploy"]])})]
    (testing "record returns the turn's coordinates"
      (let [r (read-edn record)]
        (is (nil? (-> record :result :isError)) "success carries no isError")
        (is (pos? (:t r)))
        (is (= 1 (:turn r)))
        (is (pos? (:tx r)))
        (is (= {} (:tempids r)) "the tx-meta tempid is not the agent's")))
    (testing "context reflects the turn, attributed to the server's session"
      (let [doc (read-edn (call! state "context" {:cap 10}))]
        (is (= [[:proj/kala :proj/deploy-command "make deploy"]] (:current doc)))
        (is (= {"kala-main-test" [[(:t (read-edn record)) 1]]} (:turns doc)))))
    (testing "turns resolves the step"
      (let [{:keys [turns]} (read-edn (call! state "turns" {}))]
        (is (= 1 (count turns)))
        (is (= 1 (second (first turns))))))))

(deftest recall-and-read-and-history-work-over-the-tools
  (let [state (state!)
        proj (api/entid (api/db (:conn @state)) :proj/kala)
        r (read-edn (call! state "record"
                           {:tx-data (pr-str [[:db/add proj :proj/deploy-command "cmd v1"]])}))]
    (testing "recall runs Datalog with EDN-string args and as-of"
      (is (= [["cmd v1"]]
             (-> (call! state "recall"
                        {:query "[:find ?v :in $ ?e :where [?e :proj/deploy-command ?v]]"
                         :args (pr-str [proj])
                         :as-of (:t r)})
                 read-edn :result))
          "args bind values — entity references bind as eids, an ident goes in the query text")
      (is (= [["cmd v1"]] (-> (call! state "recall"
                                     {:query "[:find ?v :in $ ?e :where [?e :proj/deploy-command ?v]]"
                                      :args (pr-str [proj])})
                              read-edn :result))))
    (testing "read takes the eid or the ident string"
      (is (contains? (read-edn (call! state "read" {:entity proj})) :proj/deploy-command))
      (is (contains? (read-edn (call! state "read" {:entity ":proj/kala"})) :proj/deploy-command)))
    (testing "history scopes over the tool edge"
      (let [{:keys [result truncated]} (read-edn (call! state "history" {:scope ":entity" :entity proj}))]
        (is (= {:capped? false} truncated))
        (is (pos? (count result)))
        (is (every? #(contains? % :t) result)))
      (let [{:keys [result]} (read-edn (call! state "history" {:scope ":session" :session-id "kala-main-test"}))]
        (is (pos? (count result)))))))

;; ---- errors at the edge --------------------------------------------------------

(deftest errors-surface-verbatim-under-isError
  (let [state (state!)]
    (testing "malformed EDN names the argument"
      (let [reply (call! state "record" {:tx-data "[:db/add"})
            err (read-edn reply)]
        (is (true? (-> reply :result :isError)))
        (is (= :mcp/bad-edn (:kala/error err)))
        (is (= "tx-data" (:argument err)))))
    (testing "a non-string structure arg is :mcp/bad-edn too"
      (let [err (read-edn (call! state "recall" {:query 42}))]
        (is (= :mcp/bad-edn (:kala/error err)))))
    (testing "a missing required argument is :mcp/bad-argument"
      (let [err (read-edn (call! state "record" {}))]
        (is (= :mcp/bad-argument (:kala/error err)))))
    (testing "kala's own errors ride through verbatim"
      (let [reply (call! state "record"
                          {:tx-data (pr-str [[:db/add "x" :nope/never 1]])})]
        (is (true? (-> reply :result :isError)))
        (is (= :transact/unknown-attribute (:kala/error (read-edn reply))))))
    (testing "an unknown tool is -32602, not a tool result"
      (is (= -32602 (-> (call! state "forget" {}) :error :code))))))

(deftest the-session-is-the-servers-to-own
  (let [state (state!)
        proj (api/entid (api/db (:conn @state)) :proj/kala)]
    ;; record carries no session argument anywhere in its schema; a second
    ;; make-state with the same id must land on the same session entity:
    (let [s1 (:session-eid @state)
          s2 (:session-eid @(mcp/make-state (:conn @state) "kala-main-test"))]
      (is (= s1 s2) "same-id resume is an upsert"))
    (is (some? (read-edn (call! state "record"
                                {:tx-data (pr-str [[:db/add proj :proj/deploy-command "x"]])})))))
)