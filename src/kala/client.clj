(ns kala.client
  "Remote client library: the same core operation surface as the embedded
  peer — db, transact, q, pull, datoms, and the time-travel reads — served
  through a running transactor over the wire protocol (docs/wire-protocol.md).

  A client connection holds one socket; operations are serialized on it.
  (api/db conn) returns a RemoteDb marker pinned to the transactor's current
  t; as-of/since/history derive further markers; transact reports carry
  :db-before/:db-after markers at the surrounding ts. Entity navigation is
  a peer-only feature and is rejected explicitly in client mode."
  (:require [kala.core :as core]
            [kala.wire :as wire])
  (:import [java.io BufferedInputStream BufferedOutputStream]
           [java.net Socket]))

(defrecord ClientConn [socket in out lock token])

;; A reference to a server-side db value: :current, :as-of, :since, or
;; :history. t pins the as-of point (t counter or tx entity id).
(defrecord RemoteDb [conn mode t spec])

(defn- db-extras [{:keys [mode t spec]}]
  (cond
    spec {:db-ref spec}
    (= mode :as-of) {:t t}
    (= mode :since) {:since-t t}
    (= mode :history) {:history true}
    ;; a :current marker stands for one db value: pin the t it was taken at
    t {:t t}
    :else {}))

(defn- request [^ClientConn c msg]
  (locking (:lock c)
    (wire/write-msg (:out c) msg)
    (wire/read-msg (:in c))))

;; db-marker fields (:t / :since-t / :history / :db-ref) ride only the ops
;; the wire table flags :db-reference? — the table is the one place that
;; knows which ops address a named db value
(defn- request-db [^RemoteDb db op base]
  (request (:conn db)
           (cond-> base
             (:db-reference? (wire/op-spec op))
             (merge (db-extras db)))))

(defn- raise-from-reply
  "The one reply→exception shape: the frame's kind (:client/error when
  absent), the frame's message, ex-data carrying the kind's whitelisted
  keys (:message re-inserted when the kind whitelists it — fail frames
  merge data over :message). connect, check, and listen all raise through
  this, so one failure class has one ex-data contract."
  [reply]
  (let [kind (or (:error reply) :client/error)
        msg (or (:message reply) "Client request failed")
        data (cond-> (dissoc reply :status :error :message)
               (some #{:message} (wire/error-data-keys-for kind))
               (assoc :message (:message reply)))]
    (core/err kind msg data)))

(defn- refuse!
  "Close the socket and raise through the one reply→exception shape."
  [^Socket sock reply]
  (.close sock)
  (raise-from-reply reply))

(defn- check [reply]
  (if (= :ok (:status reply))
    reply
    (raise-from-reply reply)))

(defn- handshake!
  "The protocol handshake every fresh socket runs: :connect (carrying the
  connection's token when it has one), then the hello reply — or
  close-and-raise through refuse!. connect and listen used to be two
  handshakes, this one raising and the listener's silent."
  [^Socket sock in out token]
  (wire/write-msg out (cond-> {:op :connect :protocol-version wire/protocol-version}
                        token (assoc :token token)))
  (let [hello (wire/read-msg in)]
    (if (= :ok (:status hello))
      hello
      (refuse! sock hello))))

(defn connect
  "Connect to a running transactor:
  {:host \"127.0.0.1\" :port 4334 :token \"...\"} — :token must match the
  transactor's when it was started with one. Performs the protocol
  handshake; a refusal (wrong token, version mismatch) throws."
  [{:keys [host port token] :or {host "127.0.0.1" port 4334}}]
  (let [sock (Socket. ^String host (int ^long port))
        c (->ClientConn sock
                        (BufferedInputStream. (.getInputStream sock))
                        (BufferedOutputStream. (.getOutputStream sock))
                        (Object.)
                        token)]
    (try
      (locking (:lock c)
        (handshake! sock (:in c) (:out c) token))
      (catch Exception e
        (.close sock)
        (throw e)))
    c))

(defn db
  "A marker for the transactor's current db value."
  [^ClientConn c]
  (let [{:keys [t]} (check (request c {:op :db}))]
    (->RemoteDb c :current t nil)))

(defn transact
  "Submit tx-data through the transactor. The report carries :t, :tx-data,
  :tempids, and :db-before/:db-after RemoteDb markers (the db values
  themselves live server-side)."
  [^ClientConn c tx-data]
  (let [{:keys [t tx-data tempids]} (check (request c {:op :transact :tx-data tx-data}))]
    {:db-before (->RemoteDb c :current (dec t) nil)
     :db-after (->RemoteDb c :current t nil)
     :t t
     :tx-data tx-data
     :tempids tempids}))

(defn compact
  "Ask the transactor to checkpoint-and-prune its own store (ADR-0008,
  ADR-0010). The reply carries the peer's {:t :datoms :pruned} report;
  the transactor refuses with :compact/disabled unless it was started
  with :compact true."
  [^ClientConn c]
  (let [{:keys [t datoms pruned]} (check (request c {:op :compact}))]
    {:t t :datoms datoms :pruned pruned}))

(defn q
  "Run a Datalog query server-side against the remote db; extra :in inputs
  (beyond $) travel in :args."
  [query ^RemoteDb db & inputs]
  (let [reply (check (request-db db :q
                                {:op :q
                                 :query query
                                 :args (vec inputs)}))]
    (:rows reply)))

(defn pull
  "Pull a document server-side."
  [^RemoteDb db pattern eid]
  (let [reply (check (request-db db :pull
                                {:op :pull :pattern pattern :eid eid}))]
    (:doc reply)))

(defn datoms
  "Enumerate datoms server-side. Datoms arrive as plain maps with
  :e :a :v :tx :added (attribute idents as :a)."
  [^RemoteDb db index components]
  (let [reply (check (request-db db :datoms
                                {:op :datoms :index index :components components}))]
    (:datoms reply)))

(defn entid
  "Resolve an ident to its entity id via a server-side lookup; entity ids
  pass through."
  [^RemoteDb db ident]
  (if (int? ident)
    ident
    (let [rows (:rows (check (request-db db :q
                                        {:op :q
                                         :query '[:find ?e :in $ ?i :where [?e :db/ident ?i]]
                                         :args [ident]})))]
      (ffirst rows))))

(defn as-of
  "A marker for the server-side db value as of t (a t counter or tx entity id)."
  [^RemoteDb db t]
  (->RemoteDb (:conn db) :as-of t nil))

(defn since
  "A marker for a server-side since view: only facts asserted after t."
  [^RemoteDb db t]
  (->RemoteDb (:conn db) :since t nil))

(defn history
  "A marker for the server-side history value (every assertion and
  retraction, each with its added flag)."
  [^RemoteDb db]
  (->RemoteDb (:conn db) :history nil nil))

(defn with
  "Speculative transaction executed server-side against the remote db (or
  its as-of view). No storage effect. :db-after addresses the speculative
  value held by the transactor (:db-ref); it is readable with q/pull/datoms
  until the transactor drops it."
  [^RemoteDb db tx-data]
  (let [{:keys [t db-ref tx-data tempids]}
        (check (request-db db :with
                           {:op :with :tx-data tx-data}))]
    {:db-before (->RemoteDb (:conn db) :current (dec t) nil)
     :db-after (->RemoteDb (:conn db) :spec nil db-ref)
     :t t
     :tx-data tx-data
     :tempids tempids}))

(defn listen
  "Open a notification socket to the same transactor and invoke (callback
  msg) for every pushed transaction completion ({:notification
  :tx-completed :t t}). The handshake and the :listen ack run here, so a
  refusal throws — connection refused, version mismatch, a failed ack —
  and the subscription is active when listen returns. A connection that
  drops later ends the reader thread quietly (a crash is not observable
  on a push channel). Returns {:stop! f}. Callbacks run on the reader
  thread — return fast."
  [^ClientConn c callback]
  (let [host (.. c socket getInetAddress getHostAddress)
        port (.. c socket getPort)
        sock (Socket. ^String host (int ^long port))
        in (BufferedInputStream. (.getInputStream sock))
        out (BufferedOutputStream. (.getOutputStream sock))]
    (try
      (handshake! sock in out (:token c))
      (wire/write-msg out {:op :listen})
      (let [ack (wire/read-msg in)]
        (when (not= :ok (:status ack))
          (refuse! sock ack))
        (let [stop-flag (atom false)
              thread (Thread. (fn []
                                ;; no catch: a stop reads nil through
                                ;; read-msg's EOF/SocketException rule, and
                                ;; a throwing callback surfaces on the
                                ;; thread instead of dying silently
                                (loop []
                                  (when-not @stop-flag
                                    (when-some [msg (wire/read-msg in)]
                                      (callback msg)
                                      (recur))))))]
          (.setDaemon thread true)
          (.start thread)
          {:stop! (fn []
                    (reset! stop-flag true)
                    (.close sock))}))
      (catch Exception e
        (.close sock)
        (throw e)))))

(defn close
  "Close the client's socket."
  [^ClientConn c]
  (.close (:socket c)))
