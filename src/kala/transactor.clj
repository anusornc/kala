(ns kala.transactor
  "The standalone transactor: a TCP server owning the database's single
  writer. Peers and clients speak the wire protocol (docs/wire-protocol.md);
  writes serialize through one lock into a single total order, then commit
  to durable storage. Reads run against the transactor's db value, pinned
  to a t (as-of), a since view, or history when the request asks."
  (:require [clojure.edn :as edn]
            [kala.api :as api]
            [kala.time-travel :as time-travel]
            [kala.wire :as wire])
  (:import [java.net InetAddress ServerSocket SocketException]
           [java.util.concurrent Executors]))

;; server state: the single-writer connection atom, the set of notification
;; listener output streams, and a registry of speculative db values (from
;; :with) addressable by id, since they have no log presence
(defrecord TransactorState [conn listeners speculative])

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

(defn- handle-op [state msg]
  (condp = (:op msg)
    :connect
    (if (= wire/protocol-version (:protocol-version msg))
      (ok {:protocol-version wire/protocol-version
           :t (api/db-t (api/db (:conn state)))})
      (fail :protocol/version-mismatch
            (str "Protocol version mismatch: server "
                 wire/protocol-version ", client " (:protocol-version msg))
            {:server-version wire/protocol-version
             :client-version (:protocol-version msg)}))

    :db
    (ok {:t (api/db-t (api/db (:conn state)))})

    :transact
    (let [report (api/transact (:conn state) (:tx-data msg))]
      (notify! (:listeners state) {:notification :tx-completed :t (:t report)})
      (ok {:t (:t report)
           :tx-data (public-datoms (:tx-data report))
           :tempids (:tempids report)}))

    :with
    (let [report (time-travel/db-with (db-at state msg) (:tx-data msg))
          db-ref (remember-speculative state (:db-after report))]
      (ok {:t (:t report)
           :db-ref db-ref
           :tx-data (public-datoms (:tx-data report))
           :tempids (:tempids report)}))

    :q
    (ok {:rows (apply api/q (:query msg) (db-at state msg) (:args msg))})

    :pull
    (ok {:doc (api/pull (db-at state msg) (:pattern msg) (:eid msg))})

    :datoms
    (ok {:datoms (public-datoms
                  (api/datoms (db-at state msg) (:index msg) (:components msg)))})

    :listen
    (ok {:listening true})

    (fail :transactor/unknown-op "Unknown op" {:op (:op msg)})))

(defn- handle [state msg]
  (try
    (handle-op state msg)
    (catch clojure.lang.ExceptionInfo e
      (fail (or (:kala/error (ex-data e)) :transactor/error)
            (.getMessage e)
            (select-keys (ex-data e) [:attribute :e :t :index :op])))
    (catch Exception e
      (fail :transactor/error (.getMessage e) {}))))

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
          (when-some [msg (wire/read-msg in)]
            (let [first-msg? (not handshaked?)
                  reply (cond
                          (and first-msg? (not= :connect (:op msg)))
                          (fail :protocol/connect-required
                                "First message must be :connect" {:op (:op msg)})

                          first-msg?
                          (handle state msg)

                          (= :transact (:op msg))
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
              (when-not (and first-msg?
                             (= :protocol/version-mismatch (:error reply)))
                (recur true)))))))
    (catch Exception e
      (println :CONN-ERR (pr-str (ex-data e)) (.getMessage e))
      (doseq [^StackTraceElement st (.getStackTrace e)]
        (println "   " (.toString st))))))

(defn start!
  "Start a transactor. Opts: :port (0 picks an ephemeral port), :host,
  :storage (:memory or :file), :path (for file storage). Returns a map with
  :port, :stop!, :acceptor, :conn. The transactor owns one connection;
  concurrent client writes serialize through it."
  [{:keys [host port storage path] :or {port 0 storage :memory}}]
  (let [conn (api/connect (if (= :file storage)
                            {:storage :file :path path}
                            {:storage storage}))
        state (->TransactorState conn (atom #{}) (atom []))
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
  "The transactor config file: EDN map with :host, :port, :storage, :path."
  [s]
  (edn/read-string s))

(defn -main [config-path & _]
  (let [config (parse-config (slurp config-path))
        {:keys [port acceptor]} (start! config)]
    (println "Transactor listening on" port "storage" (:storage config))
    (.join acceptor)))
