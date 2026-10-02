(ns datomic-opensource.api
  "Public embedded (peer-style) API. Tests and downstream programs drive
  everything through this namespace (behavioral seam)."
  (:require [datomic-opensource.core :as core]
            [datomic-opensource.query :as query]
            [datomic-opensource.storage.memory :as memory]))

(defn connect
  "Open a connection to a database. v1 supports in-memory storage:

     (connect {:storage :memory :name \"dev\"})

  Returns a connection holding the current immutable db value."
  [{:keys [storage name] :or {storage :memory name "db"}}]
  (let [s (case storage
            :memory (memory/empty-storage)
            (throw (ex-info "Unknown storage backend"
                            {:datomic-opensource/error :connect/unknown-storage
                             :storage storage})))]
    (atom (core/empty-db s))))

(defn db
  "The current immutable db value."
  [conn]
  @conn)

(defn transact
  "Apply transaction data against the connection and return the tx report
  ({:db-before, :db-after, :tx-data, :tempids})."
  [conn tx-data]
  (core/transact conn tx-data))

(defn datoms
  "Enumerate datoms from an index (:eavt or :aevt), narrowed by prefix
  components. Returned datoms carry the attribute ident as :a."
  [db index components]
  (core/datoms db index components))

(defn entid
  "Resolve an ident keyword to its entity id; entity ids pass through."
  [db ident]
  (core/entid db ident))

(defn q
  "Run a Datalog query against the db (and any further :in inputs):

     (q '[:find ?n :in $ ?name :where [?p :person/name ?name]] (db conn) \"Alice\")

  The query is a data form or an EDN string."
  [query & inputs]
  (query/q query inputs))
