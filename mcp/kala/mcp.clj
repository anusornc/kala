(ns kala.mcp
  "The MCP server: kala.memory exposed as six tools over stdio.

  The surface is the #43 lock: context / record / recall / read / history /
  turns, bare-named, one per memory op. The server owns the session — it
  installs the spine at boot and opens (or resumes) the configured
  session, so an agent's record needs no session argument and write
  provenance holds by construction. EDN at both edges: structure-bearing
  arguments arrive as EDN strings and every result is a readable-EDN
  string; kala and memory errors surface verbatim under the MCP isError
  flag. The transport is newline-delimited JSON-RPC 2.0 over stdio —
  a deliberate hand-rolled subset (initialize, tools/list, tools/call,
  ping), not the Java SDK: the subset is small and the artifact stays
  one-dependency light.

  The protocol is a pure seam: handle takes a request map and returns the
  reply map (nil for notifications); serve! is the only line-reading
  loop. Nothing but protocol traffic ever reaches stdout — the server
  mutters to stderr or not at all.

  gen-class exists for the standalone jar (build/mcp-uberjar): java -jar
  needs a real main class, the -M path never touches it."
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [kala.api :as api]
            [kala.client :as client]
            [kala.core :as core]
            [kala.memory :as memory])
  (:import [java.io StringReader PushbackReader]))

;; ---- json-rpc -----------------------------------------------------------------

(defn- rpc-result
  [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- rpc-error
  [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- error-data
  "The ex-info's data as the client should see it: the :kala/error map
  verbatim, :message filled from the throwable when absent."
  [e]
  (cond-> (ex-data e)
    (not (:message (ex-data e))) (assoc :message (ex-message e))))

;; ---- the tool surface ----------------------------------------------------------

(defn- conn [state] (:conn @state))
(defn- db [state] (api/db (conn state)))

(defn- bad-arg!
  [tool arg why]
  (core/err :mcp/bad-argument
            (str tool ": " (name arg) " " why)
            {:tool tool :argument (name arg)}))

(defn- edn-arg!
  "A structure-bearing argument arrives as an EDN string and is parsed at
  the edge — keywords and op vectors survive the JSON boundary intact."
  [tool arg v]
  (when (nil? v) (bad-arg! tool arg "is required (an EDN string)"))
  (when-not (string? v)
    (core/err :mcp/bad-edn
              (str tool ": " (name arg) " must be an EDN string")
              {:tool tool :argument (name arg) :got v}))
  (try (edn/read-string v)
       (catch Exception e
         (core/err :mcp/bad-edn
                   (str tool ": " (name arg) " is not readable EDN")
                   {:tool tool :argument (name arg)
                    :message (.getMessage e)}))))

(defn- tool-context
  "The args→opts mapping is kala.memory's own (memory/context-opts, the
  builder the mem CLI shares); this edge only parses what crossed JSON."
  [state args]
  (memory/context (db state) (memory/context-opts args)))

(defn- tx-data-arg!
  "record's tx-data: an EDN string holding ONE vector of ops. Agents write
  the ops as several top-level forms more often than anything else —
  `edn/read-string` would silently take the first and drop the rest, so
  read them all: multiple op vectors wrap into the one vector `record`
  wants; anything else that fails to be a single vector raises with the
  expected shape spelled out (the trial's `:transact/bad-op` wrinkle)."
  [v]
  (when (nil? v) (bad-arg! "record" :tx-data "is required (an EDN string)"))
  (when-not (string? v)
    (core/err :mcp/bad-edn
              "record: tx-data must be an EDN string"
              {:tool "record" :argument "tx-data" :got v}))
  (let [forms (try
                (loop [r (PushbackReader. (StringReader. v))
                       out []]
                  (let [form (edn/read {:eof nil} r)]
                    (if (nil? form) out (recur r (conj out form)))))
                (catch Exception e
                  (core/err :mcp/bad-edn
                            "record: tx-data is not readable EDN"
                            {:tool "record" :argument "tx-data"
                             :message (.getMessage e)})))]
    (cond
      (and (= 1 (count forms)) (vector? (first forms)))
      (first forms)

      (and (pos? (count forms)) (every? vector? forms))
      (vec forms)

      :else
      (core/err :mcp/bad-edn
                "record tx-data must be ONE vector of ops, e.g. \"[[:db/add ...] [:db/retract ...]]\""
                {:tool "record" :argument "tx-data" :forms (count forms)}))))

(defn- tool-record
  [state {:keys [tx-data]}]
  (memory/record (conn state) (:session-eid @state)
                 (tx-data-arg! tx-data)))

(defn- tool-recall
  [state {:keys [query args as-of limit]}]
  (memory/query (db state)
                (edn-arg! "recall" :query query)
                (memory/recall-opts {:args (if args (edn-arg! "recall" :args args) [])
                                     :as-of as-of
                                     :limit limit})))

(defn- tool-read
  [state {:keys [entity pattern as-of]}]
  (when (nil? entity) (bad-arg! "read" :entity "is required (an eid, or an EDN ident string)"))
  (let [eid (if (int? entity) (long entity) (edn-arg! "read" :entity entity))]
    (memory/pull (db state) eid
                 (memory/read-opts {:pattern (if pattern (edn-arg! "read" :pattern pattern) nil)
                                    :as-of as-of}))))

(defn- tool-history
  [state {:keys [scope entity session-id attrs from-t to-t as-of limit]}]
  (when (nil? scope) (bad-arg! "history" :scope "is required (:entity or :session)"))
  (when-not (string? scope) (bad-arg! "history" :scope "must be a string"))
  (memory/history (db state)
                  (memory/history-opts {:scope scope
                                        :entity entity
                                        :session-id session-id
                                        :attrs (if attrs (edn-arg! "history" :attrs attrs) nil)
                                        :from-t from-t
                                        :to-t to-t
                                        :as-of as-of
                                        :limit limit})))

(defn- tool-turns
  [state {:keys [session-id as-of limit]}]
  (memory/turns (db state)
                (or session-id (:session-id @state))
                (memory/turns-opts {:as-of as-of :limit limit})))

(defn- schema
  "A minimal JSON-Schema object shape for one tool's arguments."
  [{:keys [required props]}]
  (cond-> {:type "object"
           :properties (into (sorted-map)
                             (map (fn [[k spec]] [k spec]))
                             props)}
    required (assoc :required (mapv name required))))

(defn- tools
  "The #43 lock, verbatim: six bare-named tools, one per memory op. The
  descriptions steer the agent — context first, record writes one turn."
  []
   [{:name "context"
    :description "The belief-state document: everything memory holds (or held as-of t), capped, correction-annotated. Call this first."
    :inputSchema (schema {:required []
                          :props {"as-of" {:type "integer" :description "read the belief state as of this t"}
                                  "cap" {:type "integer" :description "max facts in :current (default 50)"}
                                  "corrections" {:type "boolean" :description "include the corrections trail (default true)"}
                                  "with-t" {:type "boolean" :description "stamp each :current tuple with its asserting t ([e a v t])"}
                                  "min-t" {:type "integer" :description "keep only facts asserted at or after this t, before the cap"}}})}
   {:name "record"
    :description "Record one turn of memory: tx-data becomes exactly one transaction attributed to this session. Returns the turn's t and ordinal plus resolved tempids. To cite an earlier turn use its :tx value (a tx entity id) — never its t. New vocabulary registers and applies in the same transaction; to address an existing entity by a unique attribute, register-and-upsert via a tempid carrying the unique value, or resolve its eid with the read tool first."
    :inputSchema (schema {:required [:tx-data]
                          :props {"tx-data" {:type "string" :description "EDN vector of transaction ops, e.g. \"[:db/add :proj/kala :proj/note \\\"hello\\\"]\" — an ident in the :e slot is that entity; the string \"datomic.tx\" in a ref position cites this turn"}}})}
   {:name "recall"
    :description "Run a Datalog query over memory — joins included. List results are capped and any cap is reported."
    :inputSchema (schema {:required [:query]
                          :props {"query" {:type "string" :description "EDN query, e.g. \"[:find ?v :in $ ?e :where [?e :proj/deploy-command ?v]]\""}
                                  "args" {:type "string" :description "EDN vector binding the :in symbols after $ — entity references bind as eids, e.g. \"[12345]\""}
                                  "as-of" {:type "integer" :description "t to read against"}
                                  "limit" {:type "integer" :description "max solutions (default 100, ceiling 1000)"}}})}
   {:name "read"
    :description "Read one entity: its attributes as a document, current or as-of t. All or nothing — an over-size read refuses with a narrower-pattern hint."
    :inputSchema (schema {:required [:entity]
                          :props {"entity" {:type ["integer" "string"] :description "entity id, or an EDN ident string like \":proj/kala\""}
                                  "pattern" {:type "string" :description "EDN pull pattern (default whole entity)"}
                                  "as-of" {:type "integer" :description "t to read against"}}})}
   {:name "history"
    :description "The provenance trail: every assertion and retraction of one entity (the correction trail), or every datom one session wrote."
    :inputSchema (schema {:required [:scope]
                          :props {"scope" {:type "string" :description "EDN keyword: :entity or :session"}
                                  "entity" {:type "integer" :description "entity id (for :entity scope)"}
                                  "session-id" {:type "string" :description "session id (required for :session scope)"}
                                  "attrs" {:type "string" :description "EDN collection narrowing :entity scope to attributes"}
                                  "from-t" {:type "integer" :description "inclusive lower t bound"}
                                  "to-t" {:type "integer" :description "inclusive upper t bound"}
                                  "as-of" {:type "integer" :description "t to read against"}
                                  "limit" {:type "integer" :description "max entries"}}})}
   {:name "turns"
    :description "The step index of a session: [t, turn ordinal, txInstant] triples. How \"step 12\" becomes a t."
    :inputSchema (schema {:required []
                          :props {"session-id" {:type "string" :description "session id (default this server's session)"}
                                  "as-of" {:type "integer" :description "bound the listing at this t"}
                                  "limit" {:type "integer" :description "max triples"}}})}])

(def ^:private tool-table
  {"context" tool-context
   "record" tool-record
   "recall" tool-recall
   "read" tool-read
   "history" tool-history
   "turns" tool-turns})

;; ---- the protocol seam ---------------------------------------------------------

(def ^:private known-versions #{"2024-11-05" "2025-03-26" "2025-06-18"})
(def protocol-version "2025-06-18")

(defn- initialize-result
  [{:keys [protocolVersion] :as _params}]
  {:protocolVersion (or (when (contains? known-versions protocolVersion) protocolVersion)
                        protocol-version)
   :capabilities {:tools {}}
   :serverInfo {:name "kala-mcp" :version "0.5.0"}})

(defn handle
  "One JSON-RPC request map in, the reply map out — nil for notifications.
  The whole server's behavior is testable here, with no stdio attached."
  [state {:keys [id method params]}]
  (cond
    (nil? method)
    (when id (rpc-error id -32600 "Invalid Request"))

    (nil? id)
    nil

    :else
    (case method
      "initialize" (rpc-result id (initialize-result params))
      "ping" (rpc-result id {})
      "tools/list" (rpc-result id {:tools (vec (tools))})
      "tools/call"
      (let [{:keys [name arguments]} params]
        (if-some [tool (get tool-table name)]
          (let [{:keys [isError content]}
              (try
                {:content [{:type "text" :text (pr-str (tool state (or arguments {})))}]}
                (catch clojure.lang.ExceptionInfo e
                  {:isError true
                   :content [{:type "text" :text (pr-str (error-data e))}]})
                ;; anything else — a bad scalar type, a slip in the server —
                ;; is a tool failure, never a reason for the server to die
                (catch Throwable t
                  {:isError true
                   :content [{:type "text"
                              :text (pr-str {:kala/error :mcp/error
                                             :message (.getMessage t)})}]}))]
            (rpc-result id (cond-> {:content content} isError (assoc :isError true))))
          (rpc-error id -32602 (str "Unknown tool: " name))))
      (rpc-error id -32601 "Method not found"))))

(defn handle-line
  "One stdio line in, one reply line out (nil says nothing). Malformed
  JSON is a -32700 parse error — the id, if any was ever known, is null."
  [state line]
  (let [req (try (json/read-str line :key-fn keyword :eof-error? false)
                 (catch Exception _ ::bad-json))]
    (if (= ::bad-json req)
      (rpc-error nil -32700 "Parse error")
      (handle state req))))

;; ---- boot ----------------------------------------------------------------------

(defn make-state
  "Boot the memory side of the server: install the spine, open or resume
  the session. Public for the protocol-seam tests."
  [conn session-id]
  (memory/install conn)
  (let [s (memory/session conn {:id session-id})]
    (atom {:conn conn :session-id session-id :session-eid s})))

(defn- open-conn
  [{:keys [host] :as opts}]
  (if host
    (client/connect opts)
    (api/connect (or opts {:storage :memory}))))

(defn- serve!
  [state]
  (let [in (java.io.BufferedReader.
            (java.io.InputStreamReader. System/in "UTF-8"))
        out (java.io.PrintWriter.
             (java.io.OutputStreamWriter. System/out "UTF-8") true)]
    (loop []
      (when-some [line (.readLine in)]
        (when-some [reply (handle-line state line)]
          (.println out (json/write-str reply)))
        (recur)))))

(defn -main
  "clojure -M:mcp [config.edn]

  Config (all optional): {:conn {...}} names the connection — peer
  storage opts (e.g. {:storage :file :path \"...\"}) or client opts
  {:host :port :token} (ADR-0006: the shared token rides :connect); and
  {:session \"id\"} names the session to open or resume. No config means
  an in-memory store and a fresh session."
  [& [config-file]]
  (let [cfg (if config-file (edn/read-string (slurp config-file)) {})
        sid (or (:session cfg) (str (java.util.UUID/randomUUID)))
        state (make-state (open-conn (:conn cfg)) sid)]
    (binding [*out* *err*]
      (println "[kala-mcp] serving session" sid))
    (serve! state)))
