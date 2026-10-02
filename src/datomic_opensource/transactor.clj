(ns datomic-opensource.transactor
  "The standalone transactor: a TCP server owning the database's single
  writer. Peers and clients speak the wire protocol (docs/wire-protocol.md);
  writes serialize through one lock into a single total order, then commit
  to durable storage. Reads run against the transactor's db value, pinned
  to a t when the request supplies one."
  (:require [clojure.edn :as edn]
            [datomic-opensource.api :as api]
            [datomic-opensource.core :as core]
            [datomic-opensource.wire :as wire])
  (:import [java.net InetAddress ServerSocket SocketException]
           [java.util.concurrent Executors]))

(defn- db-at [conn {:keys [t history]}]
  (let [db (api/db conn)]
    (cond
      history (api/history db)
      t (api/as-of db t)
      :else db)))

(defn- ok [body]
  (merge {:status :ok} body))

(defn- fail [kind message extra]
  (merge {:status :error :error kind :message message} extra))

(defn- public-datoms [datoms]
  (mapv #(select-keys % [:e :a :v :tx :added]) datoms))

(defn- handle-op [conn msg]
  (condp = (:op msg)
    :connect
    (if (= wire/protocol-version (:protocol-version msg))
      (ok {:protocol-version wire/protocol-version
           :t (::core/t (api/db conn))})
      (fail :protocol/version-mismatch
            (str "Protocol version mismatch: server "
                 wire/protocol-version ", client " (:protocol-version msg))
            {:server-version wire/protocol-version
             :client-version (:protocol-version msg)}))

    :db
    (ok {:t (::core/t (api/db conn))})

    :transact
    (let [report (api/transact conn (:tx-data msg))]
      (ok {:t (:t report)
           :tx-data (public-datoms (:tx-data report))
           :tempids (:tempids report)}))

    :q
    (ok {:rows (apply api/q (:query msg) (db-at conn msg) (:args msg))})

    :pull
    (ok {:doc (api/pull (db-at conn msg) (:pattern msg) (:eid msg))})

    :datoms
    (ok {:datoms (public-datoms
                  (api/datoms (db-at conn msg) (:index msg) (:components msg)))})

    (fail :transactor/unknown-op "Unknown op" {:op (:op msg)})))

(defn- handle [conn msg]
  (try
    (handle-op conn msg)
    (catch clojure.lang.ExceptionInfo e
      (fail (or (:datomic-opensource/error (ex-data e)) :transactor/error)
            (.getMessage e)
            (select-keys (ex-data e) [:attribute :e :t :index :op])))
    (catch Exception e
      (fail :transactor/error (.getMessage e) {}))))

(defn- handle-connection [conn lock ^java.net.Socket sock]
  (try
    (with-open [sock sock]
      (let [in (.getInputStream sock)
            out (.getOutputStream sock)]
        (loop []
          (when-some [msg (wire/read-msg in)]
            (let [reply
                  (if (= :transact (:op msg))
                    ;; single writer: serialize transacts into one total order
                    (do (.lock lock)
                        (try (handle conn msg)
                             (finally (.unlock lock))))
                    (handle conn msg))]
              (wire/write-msg out reply)
              (recur))))))
    (catch Exception _)))

(defn start!
  "Start a transactor. Opts: :port (0 picks an ephemeral port), :host,
  :storage (:memory or :file), :path (for file storage). Returns a map with
  :port, :stop!. The transactor owns one connection; concurrent client
  writes serialize through it."
  [{:keys [host port storage path] :or {port 0 storage :memory}}]
  (let [conn (api/connect (if (= :file storage)
                            {:storage :file :path path}
                            {:storage storage}))
        lock (java.util.concurrent.locks.ReentrantLock.)
        server (ServerSocket. port 50 (InetAddress/getByName (or host "127.0.0.1")))
        executor (Executors/newCachedThreadPool)
        acceptor (Thread. (fn []
                            (loop []
                              (when-not (.isClosed ^ServerSocket server)
                                (try
                                  (let [sock (.accept ^ServerSocket server)]
                                    (.execute executor #(handle-connection conn lock sock)))
                                  (catch SocketException _))
                                (recur)))))]
    (.setDaemon acceptor true)
    (.start acceptor)
    {:port (.getLocalPort server)
     :server server
     :conn conn
     :stop! (fn []
              (.close server)
              (.shutdownNow executor))}))

(defn parse-config
  "The transactor config file: EDN map with :host, :port, :storage, :path."
  [s]
  (edn/read-string s))

(defn -main [config-path & _]
  (let [config (parse-config (slurp config-path))
        {:keys [port acceptor] :as transactor} (start! config)]
    (println "Transactor listening on" port "storage" (:storage config))
    (.join acceptor)))
