(ns datomic-opensource.client
  "Remote client library: the same core operation surface as the embedded
  peer — db, transact, q, pull, datoms, and the time-travel reads — served
  through a running transactor over the wire protocol (docs/wire-protocol.md).

  A client connection holds one socket; operations are serialized on it.
  (api/db conn) returns a RemoteDb marker pinned to the transactor's current
  t; as-of/since/history derive further markers; transact reports carry
  :db-before/:db-after markers at the surrounding ts. Entity navigation is
  a peer-only feature and is rejected explicitly in client mode."
  (:require [datomic-opensource.wire :as wire])
  (:import [java.io BufferedInputStream BufferedOutputStream]
           [java.net Socket]))

(defrecord ClientConn [socket in out lock])

;; A reference to a server-side db value: :current, :as-of, :since, or
;; :history. t pins the as-of point (t counter or tx entity id).
(defrecord RemoteDb [conn mode t spec])

(defn- db-extras [{:keys [mode t spec]}]
  (cond
    spec {:db-ref spec}
    (= mode :as-of) {:t t}
    (= mode :since) {:since-t t}
    (= mode :history) {:history true}
    ;; :current markers are snapshots: pin the t they were taken at
    t {:t t}
    :else {}))

(defn- request [^ClientConn c msg]
  (locking (:lock c)
    (wire/write-msg (:out c) msg)
    (wire/read-msg (:in c))))

(defn- check [reply]
  (if (= :ok (:status reply))
    reply
    (throw (ex-info (or (:message reply) "Client request failed")
                    (merge {:datomic-opensource/error (or (:error reply) :client/error)}
                           (dissoc reply :status :error :message))))))

(defn connect
  "Connect to a running transactor: {:host \"127.0.0.1\" :port 4334 :name \"db\"}.
  Performs the protocol handshake; a version mismatch throws."
  [{:keys [host port name] :or {host "127.0.0.1" port 4334 name "db"}}]
  (let [sock (Socket. ^String host (int ^long port))
        c (->ClientConn sock
                        (BufferedInputStream. (.getInputStream sock))
                        (BufferedOutputStream. (.getOutputStream sock))
                        (Object.))]
    (try
      (locking (:lock c)
        (wire/write-msg (:out c) {:op :connect :protocol-version wire/protocol-version})
        (let [reply (wire/read-msg (:in c))]
          (when (not= :ok (:status reply))
            (.close sock)
            (throw (ex-info (or (:message reply) "Protocol handshake failed")
                            {:datomic-opensource/error (or (:error reply) :client/error)
                             :reply reply})))))
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

(defn q
  "Run a Datalog query server-side against the remote db; extra :in inputs
  (beyond $) travel in :args."
  [query ^RemoteDb db & inputs]
  (let [reply (check (request (:conn db)
                              (merge {:op :q
                                      :query query
                                      :args (vec inputs)}
                                     (db-extras db))))]
    (:rows reply)))

(defn pull
  "Pull a document server-side."
  [^RemoteDb db pattern eid]
  (let [reply (check (request (:conn db)
                              (merge {:op :pull :pattern pattern :eid eid}
                                     (db-extras db))))]
    (:doc reply)))

(defn datoms
  "Enumerate datoms server-side. Datoms arrive as plain maps with
  :e :a :v :tx :added (attribute idents as :a)."
  [^RemoteDb db index components]
  (let [reply (check (request (:conn db)
                              (merge {:op :datoms :index index :components components}
                                     (db-extras db))))]
    (:datoms reply)))

(defn entid
  "Resolve an ident to its entity id via a server-side lookup; entity ids
  pass through."
  [^RemoteDb db ident]
  (if (int? ident)
    ident
    (let [rows (:rows (check (request (:conn db)
                                      (merge {:op :q
                                              :query '[:find ?e :in $ ?i :where [?e :db/ident ?i]]
                                              :args [ident]}
                                             (db-extras db)))))]
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
        (check (request (:conn db)
                        (merge {:op :with :tx-data tx-data} (db-extras db))))]
    {:db-before (->RemoteDb (:conn db) :current (dec t) nil)
     :db-after (->RemoteDb (:conn db) :spec nil db-ref)
     :t t
     :tx-data tx-data
     :tempids tempids}))

(defn listen
  "Open a notification socket to the same transactor and invoke (callback
  msg) for every pushed transaction completion ({:notification
  :tx-completed :t t}). Returns {:stop! f}. Callbacks run on the reader
  thread — return fast."
  [^ClientConn c callback]
  (let [host (.. c socket getInetAddress getHostAddress)
        port (.. c socket getPort)
        sock (Socket. ^String host (int ^long port))
        in (BufferedInputStream. (.getInputStream sock))
        out (BufferedOutputStream. (.getOutputStream sock))
        stop-flag (atom false)
        thread (Thread. (fn []
                          (try
                            (wire/write-msg out {:op :connect :protocol-version wire/protocol-version})
                            (when-some [hello (wire/read-msg in)]
                              (when (= :ok (:status hello))
                                (wire/write-msg out {:op :listen})
                                (wire/read-msg in)         ; the :listen ack
                                (loop []
                                  (when-not @stop-flag
                                    (when-some [msg (wire/read-msg in)]
                                      (callback msg)
                                      (recur))))))
                            (catch Exception _))))]
    (.setDaemon thread true)
    (.start thread)
    {:stop! (fn []
              (reset! stop-flag true)
              (.close sock))}))

(defn close
  "Close the client's socket."
  [^ClientConn c]
  (.close (:socket c)))
