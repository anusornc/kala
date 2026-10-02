(ns datomic-opensource.api
  "Public API, uniform over both access modes:

  - peer (embedded): (connect {:storage :memory}) / {:storage :file :path}
  - client (remote): (client/connect {:host ... :port ...}) from
    datomic-opensource.client — pass that connection to these same
    functions and every operation travels through the transactor.

  Entity navigation (entity/touch) is a peer-only feature; client-mode
  calls raise :client/peer-only."
  (:require [datomic-opensource.client :as client]
            [datomic-opensource.core :as core]
            [datomic-opensource.entity :as entity]
            [datomic-opensource.pull :as pull]
            [datomic-opensource.query :as query]
            [datomic-opensource.storage.file :as file]
            [datomic-opensource.storage.memory :as memory])
  (:import (datomic_opensource.client ClientConn RemoteDb)))

(defn- client-conn? [c]
  (instance? ClientConn c))

(defn- remote-db? [d]
  (instance? RemoteDb d))

(defn connect
  "Open a peer connection to a database:

     (connect {:storage :memory :name \"dev\"})
     (connect {:storage :file :path \"/var/data/mydb\"})

  Opening existing storage rebuilds the db from the log — crash recovery is
  part of connect. For remote access, connect through
  datomic-opensource.client/connect and use these same operations."
  [{:keys [storage path name] :or {storage :memory name "db"}}]
  (let [s (case storage
            :memory (memory/empty-storage)
            :file (do (when-not path
                        (throw (ex-info "File storage requires :path"
                                        {:datomic-opensource/error :connect/missing-path})))
                      (file/open-storage path))
            (throw (ex-info "Unknown storage backend"
                            {:datomic-opensource/error :connect/unknown-storage
                             :storage storage})))]
    (atom (core/restore-db s))))

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
    (core/transact conn tx-data)))

(defn q
  "Run a Datalog query against the db (and any further :in inputs):

     (q '[:find ?n :in $ ?name :where [?p :person/name ?name]] (db conn) \"Alice\")

  The query is a data form or an EDN string."
  [query & inputs]
  (if (remote-db? (first inputs))
    (apply client/q query (first inputs) (rest inputs))
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
  (when (remote-db? db)
    (throw (ex-info "Entity navigation is peer-only; use pull in client mode"
                    {:datomic-opensource/error :client/peer-only :op :entity})))
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
  (when (remote-db? db)
    (throw (ex-info "seek-datoms is peer-only in v1"
                    {:datomic-opensource/error :client/peer-only :op :seek-datoms})))
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
    (core/as-of db t)))

(defn since
  "A view of the db exposing only datoms asserted after t."
  [db t]
  (if (remote-db? db)
    (client/since db t)
    (core/since db t)))

(defn history
  "Every assertion and retraction ever transacted, each with its added flag."
  [db]
  (if (remote-db? db)
    (client/history db)
    (core/history db)))

(defn with
  "Speculative transaction against a db value: returns the report
  {:db-before :db-after :t :tx-data :tempids} without any storage effect."
  [db tx-data]
  (if (remote-db? db)
    (client/with db tx-data)
    (core/db-with db tx-data)))
