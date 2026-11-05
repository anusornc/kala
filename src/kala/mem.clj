(ns kala.mem
  "Agent-ops CLI: call kala.memory against an already-running transactor
  without booting an MCP server per invocation.

  One-shot:
    clojure -M:mem config.edn <tool> [edn-args-map]

  Persistent (one JVM, one client connection; newline EDN in / EDN out):
    clojure -M:mem config.edn --serve
    ;; then send lines like: {:op \"context\" :args {:with-t true}}
    ;;                    or: context {:with-t true}

  Config matches the MCP server's shape:
    {:conn {:host \"127.0.0.1\" :port 4334 :token \"...\"}
     :session \"my-agent\"}

  Tools: see `tool-names` — the same six as the MCP surface. Args are an
  EDN map; structure-bearing values (tx-data, query, pattern, attrs,
  scope) are EDN data here, not JSON strings — the CLI is for agents and
  shells that already speak EDN.

  Cold-start still pays one JVM boot. For tight loops keep --serve open,
  or point a long-lived MCP harness at the same transactor (ADR-0011:
  MCP remains the universal SDK; this CLI is the ops shortcut)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kala.api :as api]
            [kala.client :as client]
            [kala.core :as core]
            [kala.memory :as memory])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter
                    PrintWriter Writer]))

(def tool-names
  "Ordered memory tool surface shared with the MCP server (#43 lock).
  Single source of truth for names printed in usage and unknown-tool errors."
  ["context" "record" "recall" "read" "history" "turns"])

(def ^:private tools (set tool-names))

(defn- tool-names-joined
  []
  (str/join " | " tool-names))

(defn- parse-config
  [path]
  (let [cfg (edn/read-string (slurp path))]
    (when-not (map? cfg)
      (core/err :mem/bad-config "Config must be an EDN map" {:path path}))
    (when-not (string? (:session cfg))
      (core/err :mem/bad-config "Config requires :session (a string id)" {:path path}))
    (when-not (map? (:conn cfg))
      (core/err :mem/bad-config
                "Config requires :conn — client opts {:host :port :token} (a running transactor)"
                {:path path}))
    (when-not (:host (:conn cfg))
      (core/err :mem/bad-config
                "kala-mem connects as a client; :conn needs :host (and usually :port :token)"
                {:conn (:conn cfg)}))
    cfg))

(defn- open-state
  "Install the spine, open/resume the session — same boot the MCP server
  does, against a live transactor."
  [{:keys [conn session]}]
  (let [c (client/connect conn)]
    (memory/install c)
    {:conn c
     :session-id session
     :session-eid (memory/session c {:id session})}))

(defn- close-state!
  [state]
  (when-some [c (:conn state)]
    (client/close c)))

(defn call-tool
  "Dispatch one memory tool against a boot state. Public for tests. The
  args→opts mapping is kala.memory's own (the builders the MCP surface
  shares) — required-argument checks are all this edge adds."
  [{:keys [conn session-id session-eid]} tool args]
  (let [args (or args {})
        db (api/db conn)]
    (case tool
      "context"
      (memory/context db (memory/context-opts args))

      "record"
      (let [tx-data (:tx-data args)]
        (when-not (vector? tx-data)
          (core/err :mem/bad-argument
                    "record :tx-data must be a vector of ops"
                    {:got (type tx-data)}))
        (memory/record conn session-eid tx-data))

      "recall"
      (let [q (:query args)]
        (when (nil? q)
          (core/err :mem/bad-argument "recall :query is required" {}))
        (memory/query db q (memory/recall-opts args)))

      "read"
      (let [entity (:entity args)]
        (when (nil? entity)
          (core/err :mem/bad-argument "read :entity is required" {}))
        (memory/pull db entity (memory/read-opts args)))

      "history"
      (let [scope (:scope args)]
        (when (nil? scope)
          (core/err :mem/bad-argument "history :scope is required (:entity or :session)" {}))
        (memory/history db (memory/history-opts args)))

      "turns"
      (memory/turns db
                    (or (:session-id args) session-id)
                    (memory/turns-opts args))

      (core/err :mem/unknown-tool
                (str "Unknown tool: " tool " (" (tool-names-joined) ")")
                {:tool tool}))))

(defn- error-data
  [e]
  (cond-> (ex-data e)
    (not (:message (ex-data e))) (assoc :message (ex-message e))))

(defn- print-result!
  "Write one EDN value as a single line. `out` may be a PrintWriter
  (serve mode) or Clojure's *out* (one-shot — an OutputStreamWriter)."
  [out value]
  (let [s (str (pr-str value) \newline)]
    (.write ^Writer out s)
    (.flush ^Writer out)))

(defn- with-tool-errors
  "Run `f`, printing a :kala/error map to `out` on failure. Returns the
  exit code (0 ok / 1 error). Shared by one-shot and --serve so the
  ExceptionInfo/Throwable pair is not duplicated at each edge."
  [out f]
  (try
    (f)
    0
    (catch clojure.lang.ExceptionInfo e
      (print-result! out (error-data e))
      1)
    (catch Throwable t
      (print-result! out {:kala/error :mem/error :message (.getMessage t)})
      1)))

(defn- run-one!
  [state tool args out]
  (with-tool-errors out
    (fn []
      (print-result! out (call-tool state tool args)))))

(defn- parse-serve-line
  "A serve line is either {:op \"context\" :args {...}} or
  context {...} (two EDN forms)."
  [line]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. line))
        first (edn/read {:eof ::eof} rdr)]
    (cond
      (= ::eof first)
      nil

      (map? first)
      (let [op (or (:op first) (:tool first))
            args (or (:args first) (dissoc first :op :tool))]
        (when-not (string? op)
          (core/err :mem/bad-argument "serve map needs :op (tool name string)" {:got first}))
        [op args])

      (or (symbol? first) (string? first) (keyword? first))
      ;; `tool` bare, or `tool {args}` — the sentinel distinguishes a
      ;; missing args form from an explicit {}
      (let [args (edn/read {:eof ::eof} rdr)]
        [(name first) (if (= ::eof args) {} args)])

      :else
      (core/err :mem/bad-argument
                "serve line must be {:op tool :args map} or `tool {args}`"
                {:got first}))))

(defn serve!
  "Read newline-delimited EDN tool calls from `in`, write one EDN result
  line per call to `out`. Blank line or EOF ends the loop. Public for
  tests — production uses System/in and System/out."
  ([state]
   (serve! state
           (BufferedReader. (InputStreamReader. System/in "UTF-8"))
           (PrintWriter. (OutputStreamWriter. System/out "UTF-8") true)))
  ([state ^BufferedReader in ^Writer out]
   (binding [*out* *err*]
     (println "[kala-mem] serving session" (:session-id state)
              "; send EDN lines (empty line quits)"))
   (loop []
     (when-some [line (.readLine in)]
       (if (str/blank? line)
         nil
         (do
           (with-tool-errors out
             (fn []
               (when-let [[tool args] (parse-serve-line line)]
                 (print-result! out (call-tool state tool args))
                 nil)))
           (recur)))))))

(defn- usage!
  []
  (binding [*out* *err*]
    (println (str "Usage:
  kala-mem <config.edn> <tool> [edn-args-map]   # one-shot client call
  kala-mem <config.edn> --serve                 # persistent stdin REPL

Config: {:conn {:host \"127.0.0.1\" :port 4334 :token \"...\"} :session \"id\"}
Tools: " (tool-names-joined) "
Args are an EDN map (not JSON). Example:
  kala-mem mcp.edn context '{:with-t true}'
  kala-mem mcp.edn record '{:tx-data [{:db/ident :note/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one} {:note/text \"hi\"}]}'")))
  2)

(defn run
  "CLI entry that returns an exit code (0 ok, 1 tool/config error, 2 usage).
  Public for tests — `-main` is the thin System/exit wrapper."
  [args]
  (try
    (cond
      (or (empty? args) (#{"-h" "--help" "help"} (first args)))
      (usage!)

      (and (>= (count args) 2) (= "--serve" (second args)))
      (let [cfg (parse-config (first args))
            state (open-state cfg)]
        (try
          (serve! state)
          0
          (finally (close-state! state))))

      (>= (count args) 2)
      (let [[config-path tool & more] args
            args-map (if (seq more)
                       (edn/read-string (str/join " " more))
                       {})
            _ (when-not (contains? tools tool)
                (core/err :mem/unknown-tool
                          (str "Unknown tool: " tool " (" (tool-names-joined) ")")
                          {:tool tool}))
            _ (when-not (map? args-map)
                (core/err :mem/bad-argument "Args must be an EDN map" {:got args-map}))
            cfg (parse-config config-path)
            state (open-state cfg)]
        (try
          (run-one! state tool args-map *out*)
          (finally (close-state! state))))

      :else
      (usage!))
    (catch clojure.lang.ExceptionInfo e
      (print-result! *err* (error-data e))
      1)
    (catch Throwable t
      (binding [*out* *err*] (println (.getMessage t)))
      1)))

(defn -main
  [& args]
  (System/exit (long (run args))))
