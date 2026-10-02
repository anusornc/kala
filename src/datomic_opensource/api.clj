(ns datomic-opensource.api
  "Public embedded (peer-style) API. Tests and downstream programs drive
  everything through this namespace (behavioral seam)."
  (:require [datomic-opensource.core :as core]
            [datomic-opensource.entity :as entity]
            [datomic-opensource.pull :as pull]
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

(defn pull
  "Shape one entity into a document per the pull pattern, e.g.
  (pull db '[*] eid) or (pull db '[{:person/friend [:person/name]}] eid)."
  [db pattern eid]
  (pull/pull db pattern eid))

(defn entity
  "A lazy navigable entity: (e :attr), (:attr e), :db/id, reverse refs via
  :ns/_attr. Ref attributes return entities."
  [db eid]
  (entity/entity db eid))

(defn touch
  "Expand an entity into a plain map, loading its component tree."
  [e]
  (entity/touch e))

(defn seek-datoms
  "All datoms from the requested index position onward (no prefix filter),
  for paging scans."
  [db index components]
  (core/seek-datoms db index components))

(defn as-of
  "The db value as of transaction t (a t counter or a tx entity id).
  All readers — q, pull, entity, datoms — work on the returned value."
  [db t]
  (core/as-of db t))

(defn since
  "A view of the db exposing only datoms asserted after t."
  [db t]
  (core/since db t))

(defn history
  "Every assertion and retraction ever transacted, each with its added flag."
  [db]
  (core/history db))

(defn with
  "Speculative transaction against a db value: returns the report
  {:db-before :db-after :t :tx-data :tempids} without any storage effect."
  [db tx-data]
  (core/db-with db tx-data))
