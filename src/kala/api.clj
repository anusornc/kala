(ns kala.api
  "Public API, uniform over both access modes:

  - peer (embedded): (connect {:storage :memory}) / {:storage :file :path}
  - client (remote): (client/connect {:host ... :port ...}) from
    kala.client — pass that connection to these same
    functions and every operation travels through the transactor.

  Entity navigation (entity/touch) is a peer-only feature; client-mode
  calls raise :client/peer-only."
  (:require [kala.client :as client]
            [kala.compaction :as compaction]
            [kala.core :as core]
            [kala.entity :as entity]
            [kala.pull :as pull]
            [kala.query :as query]
            [kala.storage :as storage]
            [kala.storage.file :as file]
            [kala.storage.memory :as memory]
            [kala.time-travel :as time-travel]
            [kala.tx :as tx])
  (:import (kala.client ClientConn RemoteDb)))

(defn- client-conn? [c]
  (instance? ClientConn c))

(defn- remote-db? [d]
  (instance? RemoteDb d))

(defn- peer-only!
  "The one client-mode rejection for peer-only features."
  [db op message]
  (when (remote-db? db)
    (core/err :client/peer-only message {:op op})))

(defn connect
  "Open a peer connection to a database:

     (connect {:storage :memory})
     (connect {:storage :file :path \"/var/data/mydb\"})
     (connect {:storage my-adapter})   ; any Storage adapter

  The keyword forms name the built-in storage backends; a value satisfying
  the Storage protocol is wired in directly, making the storage seam
  exercisable through the front door. Opening existing storage rebuilds the
  db from the log — crash recovery is part of connect. For remote access,
  connect through kala.client/connect and use these same operations."
  [{:keys [storage path] :or {storage :memory}}]
  (let [s (cond
            (satisfies? storage/Storage storage) storage
            :else (case storage
                    :memory (memory/empty-storage)
                    :file (do (when-not path
                                (core/err :connect/missing-path "File storage requires :path" {}))
                              (file/open-storage path))
                    (core/err :connect/unknown-storage "Unknown storage backend" {:storage storage})))]
    (atom (time-travel/restore-db s))))

(defn db
  "The current immutable db value (peer), or a marker for the transactor's
  current db (client)."
  [conn]
  (if (client-conn? conn)
    (client/db conn)
    @conn))

(defn transact
  "Apply transaction data (list-form ops and/or map-form entity maps, any
  mix) and return the tx report {:db-before :db-after :tx-data :tempids}."
  [conn tx-data]
  (if (client-conn? conn)
    (client/transact conn tx-data)
    (tx/transact conn tx-data)))

(defn q
  "Run a Datalog query against the db (and any further :in inputs):

     (q '[:find ?n :in $ ?name :where [?p :person/name ?name]] (db conn) \"Alice\")

  In peer mode query/q binds :in by symbol, so % may precede $. Client
  mode keeps the wire's shape: $ is implicit-first there (the wire
  carries the db out-of-band), so the inputs after the db marker follow
  the :in symbols in order. The query is a data form or an EDN string."
  [query & inputs]
  (if-let [dbm (first (filter remote-db? inputs))]
    ;; only the first marker becomes the client's db; any later markers are
    ;; ordinary inputs (a RemoteDb riding :args is how a bad frame gets made)
    (let [[before [_ & after]] (split-with #(not (remote-db? %)) inputs)]
      (apply client/q query dbm (concat before after)))
    (query/q query inputs)))

(defn pull
  "Shape one entity into a document per the pull pattern, e.g.
  (pull db '[*] eid) or (pull db '[{:person/friend [:person/name]}] eid)."
  [db pattern eid]
  (if (remote-db? db)
    (client/pull db pattern eid)
    (pull/pull db pattern eid)))

(defn entity
  "A lazy navigable entity (peer mode only): (e :attr), (:attr e), :db/id,
  reverse refs via :ns/_attr. Ref attributes return entities."
  [db eid]
  (peer-only! db :entity "Entity navigation is peer-only; use pull in client mode")
  (entity/entity db eid))

(defn touch
  "Expand an entity into a plain map, loading its component tree (peer
  mode only)."
  [e]
  (entity/touch e))

(defn datoms
  "Enumerate datoms from an index (:eavt :aevt :avet :vaet), narrowed by
  prefix components. Returned datoms carry the attribute ident as :a."
  [db index components]
  (if (remote-db? db)
    (client/datoms db index components)
    (core/datoms db index components)))

(defn seek-datoms
  "All datoms from the requested index position onward (no prefix filter),
  for paging scans (peer mode only)."
  [db index components]
  (peer-only! db :seek-datoms "seek-datoms is peer-only in v1")
  (core/seek-datoms db index components))

(defn entid
  "Resolve an ident keyword to its entity id; entity ids pass through."
  [db ident]
  (if (remote-db? db)
    (client/entid db ident)
    (core/entid db ident)))

(defn as-of
  "The db value as of transaction t (a t counter or a tx entity id).
  All readers — q, pull, entity, datoms — work on the returned value."
  [db t]
  (if (remote-db? db)
    (client/as-of db t)
    (time-travel/as-of db t)))

(defn since
  "A view of the db exposing only datoms asserted after t."
  [db t]
  (if (remote-db? db)
    (client/since db t)
    (time-travel/since db t)))

(defn history
  "Every assertion and retraction ever transacted, each with its added flag."
  [db]
  (if (remote-db? db)
    (client/history db)
    (time-travel/history db)))

(defn with
  "Speculative transaction against a db value: returns the report
  {:db-before :db-after :t :tx-data :tempids} without any storage effect."
  [db tx-data]
  (if (remote-db? db)
    (client/with db tx-data)
    (time-travel/db-with db tx-data)))

(defn db-t
  "The db value's transaction counter t — the plain-map read accessor."
  [db]
  (core/db-t db))

(defn db-storage
  "The storage handle backing a db value — the plain-map read accessor."
  [db]
  (core/db-storage db))

(defn compact
  "Checkpoint-and-prune compaction (peer mode only): write a checkpoint of
  the live state into a storage segment, then prune the log entries it
  covers, so boot replays live state + tail instead of the whole log.
  Returns {:t the-base :datoms checkpointted :pruned pruned}.

  The cost is history below the base: as-of reads there raise
  :db/pruned-t and history shows only surviving datoms (ADR-0008).
  Explicit and opt-in by design — never a background process. Safe to
  run while transacts land (they go above the base) and idempotent;
  re-running after an interruption finishes the job."
  [conn]
  (when (client-conn? conn)
    (core/err :client/peer-only
              "Compaction is peer-only; in client mode the transactor owns the storage"
              {:op :compact}))
  (compaction/compact! conn))
