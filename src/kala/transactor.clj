(ns kala.transactor
  "The standalone transactor: a TCP server owning the database's single
  writer. Peers and clients speak the wire protocol (docs/wire-protocol.md);
  writes serialize through one lock into a single total order, then commit
  to durable storage. Reads run against the transactor's db value, pinned
  to a t (as-of), a since view, or history when the request asks."
  (:require [clojure.edn :as edn]
            [kala.api :as api]
            [kala.core :as core]
            [kala.wire :as wire])
  (:import [java.net InetAddress ServerSocket SocketException]
           [java.util.concurrent Executors]))

;; server state: the single-writer connection atom, the set of notification
;; listener output streams, a registry of speculative db values (from
;; :with) addressable by id, since they have no log presence, the
;; optional :connect token — nil means the wire requires no auth — and
;; whether the wire may trigger compaction (:compact true, ADR-0010)
(defrecord TransactorState [conn listeners speculative token compact?])

(def ^:private max-speculative 100)

(defn- remember-speculative [state db]
  (let [id (str "w-" (System/nanoTime))]
    (swap! (:speculative state)
           (fn [entries]
             (let [entries (conj (vec entries) [id db])]
               (if (> (count entries) max-speculative)
                 (subvec entries (- (count entries) max-speculative))
                 entries))))
    id))

(defn- lookup-speculative [state db-ref]
  (some (fn [[id db]] (when (= id db-ref) db))
        @(:speculative state)))

(defn- db-at [state {:keys [t since-t history db-ref]}]
  (or (when db-ref (lookup-speculative state db-ref))
      (let [db (api/db (:conn state))]
        (cond
          history (api/history db)
          since-t (api/since db since-t)
          t (api/as-of db t)
          :else db))))

(defn- ok [body]
  (merge {:status :ok} body))

(defn- fail [kind message extra]
  (merge {:status :error :error kind :message message} extra))

(defn- public-datoms [datoms]
  (mapv #(select-keys % [:e :a :v :tx :added]) datoms))

(defn- notify! [listeners msg]
  (doseq [^java.io.OutputStream out @listeners]
    (try
      (wire/write-msg out msg)
      (catch Exception _
        (swap! listeners disj out)))))

;; One handler per op in the wire table (kala.wire/ops); handle-op dispatches
;; by lookup, and the transactor test pins the two key sets equal — an op
;; added to the table without a handler (or the reverse) fails loudly.
(def handlers
  {:connect
   (fn [{:keys [conn token]} msg]
     ;; the token gates before the version check — an unauthenticated
     ;; probe learns nothing about the server's protocol version
     (if-not (wire/token-ok? token (:token msg))
       (fail :protocol/unauthorized
             "Invalid or missing token" {})
       (if (= wire/protocol-version (:protocol-version msg))
         (ok {:protocol-version wire/protocol-version
              :t (api/db-t (api/db conn))})
         (fail :protocol/version-mismatch
               (str "Protocol version mismatch: server "
                    wire/protocol-version ", client " (:protocol-version msg))
               {:server-version wire/protocol-version
                :client-version (:protocol-version msg)}))))

   :db
   (fn [{:keys [conn]} _msg]
     (ok {:t (api/db-t (api/db conn))}))

   :transact
   (fn [state msg]
     (let [report (api/transact (:conn state) (:tx-data msg))]
       (notify! (:listeners state) {:notification :tx-completed :t (:t report)})
       (ok {:t (:t report)
            :tx-data (public-datoms (:tx-data report))
            :tempids (:tempids report)})))

   :compact
   (fn [{:keys [conn compact?]} _msg]
     ;; ADR-0010: pruning history over the wire is the operator's opt-in;
     ;; the table flags :is-write?, so this runs under the single-writer
     ;; lock and the report's :t is exact. Not a transaction — listeners
     ;; get no notification.
     (if compact?
       (let [{:keys [t datoms pruned]} (api/compact conn)]
         (ok {:t t :datoms datoms :pruned pruned}))
       (fail :compact/disabled
             "Client compaction is not enabled on this transactor (start it with :compact true)"
             {})))

   :with
   (fn [state msg]
     ;; the api dispatch — every sibling handler's shape; a server-side db
     ;; value takes the same peer branch it always did
     (let [report (api/with (db-at state msg) (:tx-data msg))
           db-ref (remember-speculative state (:db-after report))]
       (ok {:t (:t report)
            :db-ref db-ref
            :tx-data (public-datoms (:tx-data report))
            :tempids (:tempids report)})))

   :q
   (fn [state msg]
     (ok {:rows (apply api/q (:query msg) (db-at state msg) (:args msg))}))

   :pull
   (fn [state msg]
     (ok {:doc (api/pull (db-at state msg) (:pattern msg) (:eid msg))}))

   :datoms
   (fn [state msg]
     (ok {:datoms (public-datoms
                   (api/datoms (db-at state msg) (:index msg) (:components msg)))}))

   :listen
   (fn [_state _msg]
     (ok {:listening true}))})

(defn- handle-op [state msg]
  (if-some [h (get handlers (:op msg))]
    (h state msg)
    (fail :transactor/unknown-op "Unknown op" {:op (:op msg)})))

(defn- handle [state msg]
  (try
    (handle-op state msg)
    (catch clojure.lang.ExceptionInfo e
      (let [kind (or (:kala/error (ex-data e)) :transactor/error)]
        (fail kind
              (.getMessage e)
              (select-keys (ex-data e) (wire/error-data-keys-for kind)))))
    (catch Exception e
      (fail :transactor/error (.getMessage e) {}))))

;; a frame that arrived whole but cannot be read as EDN is refused with a
;; structured reply before the connection closes — the client sees the
;; reader error, not a bare EOF (#32). The frame's :op is unknowable (the
;; canonical poison — a record that pr-str's to an unknown tag — embeds
;; #object prints no EDN reader can pass), so the kind carries no data keys.
(defn- bad-frame-reply [e]
  (fail :protocol/bad-frame
        (or (ex-message (ex-cause e)) (ex-message e) "Unparseable request frame")
        {}))

(defn- handle-connection [state lock sock]
  ;; the protocol requires :connect as the first message on every connection;
  ;; a version mismatch gets its structured error and the connection closes.
  ;; :listen turns the socket into a push channel: the ack is written first,
  ;; then the socket joins the listener set and receives tx completions.
  (try
    (with-open [s sock]
      (let [in (.getInputStream s)
            out (.getOutputStream s)]
        (loop [handshaked? false]
          (when-some [msg (try
                            (wire/read-msg in)
                            (catch clojure.lang.ExceptionInfo e
                              (wire/write-msg out (bad-frame-reply e))
                              nil))]
            (let [first-msg? (not handshaked?)
                  reply (cond
                          (and first-msg? (not= :connect (:op msg)))
                          (fail :protocol/connect-required
                                "First message must be :connect" {:op (:op msg)})

                          first-msg?
                          (handle state msg)

                          ;; kala.wire/ops owns the single-writer invariant:
                          ;; whatever it flags :is-write? serializes here
                          (wire/write-op? (:op msg))
                          (do
                            (.lock lock)
                            (try
                              (handle state msg)
                              (finally
                                (.unlock lock))))

                          :else
                          (handle state msg))]
              (wire/write-msg out reply)
              (when (= :listen (:op msg))
                (swap! (:listeners state) conj out))
              ;; the handshake is the trust boundary: on the FIRST message
              ;; any non-ok reply — connect-required, version mismatch, bad
              ;; or missing token, even a handler error from a poisoned
              ;; config — ends the connection. Continuing past a refusal
              ;; would let a client skip :connect and operate unauthenticated
              ;; (the review-found bypass on ac529e5). After the handshake,
              ;; per-op failures reply and the connection lives on.
              (when-not (and first-msg? (not= :ok (:status reply)))
                (recur true)))))))
    (catch Exception e
      ;; one line, no stack trace — the codec-silence posture wire_test
      ;; enforces; parse failures (#32) never reach here
      (println :CONN-ERR (pr-str (ex-data e)) (.getMessage e)))))

(defn start!
  "Start a transactor. Opts: :port (0 picks an ephemeral port), :host,
  :storage (:memory or :file), :path (for file storage), :token (a shared
  secret every :connect must carry — nil, the default, requires no auth;
  a token that is present must be a non-empty string), :compact (true to
  let clients trigger compaction over the wire — the default refuses
  :compact with :compact/disabled; peer-mode api/compact on the
  transactor's own connection is unaffected, ADR-0010). Returns a map with
  :port, :stop!, :acceptor, :conn. The transactor owns one connection;
  concurrent client writes serialize through it."
  [{:keys [host port storage path token compact] :or {port 0 storage :memory compact false}}]
  (when (some? token)
    (when-not (and (string? token) (pos? (.length ^String token)))
      (core/err :connect/bad-token
                "The transactor :token must be a non-empty string" {})))
  (when-not (boolean? compact)
    (core/err :connect/bad-option
              "The transactor :compact option must be true or false"
              {:option :compact}))
  ;; a truthy non-boolean would silently invert operator intent on the one
  ;; irreversible op, so it is refused above; this one only advises
  (when (and compact (not token))
    (println :WARN ":compact true without a :token — any client that reaches "
             "the wire may prune history (ADR-0010)"))
  (let [conn (api/connect (if (= :file storage)
                            {:storage :file :path path}
                            {:storage storage}))
        state (->TransactorState conn (atom #{}) (atom []) token (boolean compact))
        lock (java.util.concurrent.locks.ReentrantLock.)
        server (ServerSocket. port 50 (InetAddress/getByName (or host "127.0.0.1")))
        executor (Executors/newCachedThreadPool
                  (reify java.util.concurrent.ThreadFactory
                    (newThread [_ r]
                      (doto (Thread. r) (.setDaemon true)))))
        acceptor (Thread. (fn []
                            (loop []
                              (when-not (.isClosed server)
                                (try
                                  (let [sock (.accept server)]
                                    (.execute executor #(handle-connection state lock sock)))
                                  (catch SocketException _))
                                (recur)))))]
    (.setDaemon acceptor true)
    (.start acceptor)
    {:port (.getLocalPort server)
     :server server
     :conn conn
     :acceptor acceptor
     :stop! (fn []
              (.close server)
              (.shutdownNow executor))}))

(defn parse-config
  "The transactor config file: EDN map with :host, :port, :storage, :path,
  :token (a shared secret every client must carry; generate one with e.g.
  `openssl rand -hex 32`), and :compact (true to let clients compact the
  store over the wire)."
  [s]
  (edn/read-string s))

(defn -main [config-path & _]
  (let [config (parse-config (slurp config-path))
        {:keys [port acceptor]} (start! config)]
    (println "Transactor listening on" port "storage" (:storage config))
    (.join acceptor)))
