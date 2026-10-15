(ns kala.bench.datahike-engine
  "Datahike engine adapter for the bench harness, satisfying the engine map
  contract ({:name :setup :transact-batch :q :datoms-count}).

  In-memory store through datahike.api, schema transacted at setup. The
  schema is the bench's exact three attributes in map form — Datahike's
  natural schema path (its :initial-tx / schemaful create shape) — so this
  namespace carries the mapping instead of assuming the harness's list-form
  with string tempids.

  Deviations from our clone's and DataScript's conventions (observed, pinned
  by the harness cross-check):
  - create/delete take a config map ({:store {:backend :memory :id ...}});
    datomic:mem-style URIs do not exist here.
  - transact is synchronous like DataScript's, and the report carries
    :tempids for string-tempid resolution.
  - datoms is variadic over a db value: (datoms db :eavt ...)."
  (:require [datahike.api :as d]
            [taoensso.timbre :as timbre]))

(def ^:private schema
  [{:db/ident :person/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/age
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :person/friend
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}])

(defn engine
  "Datahike engine on a fresh in-memory database transacted with the bench's
  schema (map form) before any bench workload runs."
  []
  (let [cfg {:store {:backend :memory
                     ;; konserve 0.7 validates :id as a UUID, not a string
                     :id (java.util.UUID/randomUUID)}}]
    ;; datahike logs every transact at DEBUG via timbre — silence it so the
    ;; bench table stays the only output
    (timbre/set-level! :warn)
    {:name "Datahike (memory)"
     :setup (fn []
              (d/delete-database cfg)
              (d/create-database cfg)
              (let [conn (d/connect cfg)]
                (d/transact conn schema)
                conn))
     :transact-batch (fn [conn batch] (d/transact conn batch))
     :q (fn [conn query & args] (apply d/q query @conn args))
     :datoms-count (fn [conn] (count (d/datoms @conn :eavt)))}))
