(ns kala.bench.datomic-peer
  "Original-Datomic engine adapter for the bench harness, satisfying the
  engine map contract ({:name :setup :transact-batch :q :datoms-count}).

  Peer-only: uses the embedded datomic:mem protocol through datomic.api,
  no transactor process. The schema transaction is supplied by the caller
  so this namespace shares the bench's exact schema and stays free of
  harness details.

  Deviations from our clone's and DataScript's conventions (:peer-only
  notes, #19):
  - datomic.api requires :db/cardinality on every schema attribute; the
    shared bench schema supplies it.
  - transact is asynchronous and returns a future — deref to the report.
  - datoms is variadic and returns an Iterable: all datoms is
    (datoms db :eavt), not (datoms db :eavt [])."
  (:require [datomic.api :as d]))

(defn engine
  "Datomic peer engine on a fresh datomic:mem database transacted with
  schema-tx before any bench workload runs."
  [schema-tx]
  (let [uri "datomic:mem://kala-bench"]
    {:name "original Datomic (datomic:mem)"
     :setup (fn []
              ;; datomic:mem databases live for the JVM's lifetime, so a db
              ;; left over from a previous size run would silently
              ;; accumulate — delete-if-present (false when absent), create,
              ;; connect, then apply the shared schema.
              (d/delete-database uri)
              (d/create-database uri)
              (let [conn (d/connect uri)]
                (deref (d/transact conn schema-tx))
                conn))
     :transact-batch (fn [conn batch] (deref (d/transact conn batch)))
     :q (fn [conn query & args] (apply d/q query (d/db conn) args))
     :datoms-count (fn [conn] (count (seq (d/datoms (d/db conn) :eavt))))}))
