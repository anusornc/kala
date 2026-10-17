(ns kala.memory
  "The memory layer: an agent's runs held as datoms under the memory spine.

  One turn is exactly one transaction, attributed to its session by tx-meta
  (:memory/session on the transaction entity); a session is an ordinary
  entity resumed by its :memory.session/id; derivations cite their turn via
  :memory/source. The spine is the only schema this namespace owns — all
  content attributes are the developer's registered vocabulary, rejected by
  the transactor when unregistered.

  Every function here is uniform over both access modes: pass a peer
  connection or a client connection from kala.client. Errors ride the
  library-wide :kala/error contract with :memory/* kinds; they are raised
  in the caller's process and never cross the wire."
  (:require [kala.api :as api]
            [kala.core :as core]))

;; ---- the spine ---------------------------------------------------------------

(def spine
  "The provenance spine: the only idents kala.memory registers. Anything
  else in a memory transaction is the developer's vocabulary."
  [{:db/id "memory/session"
    :db/ident :memory/session
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/id "memory.session/id"
    :db/ident :memory.session/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/id "memory/source"
    :db/ident :memory/source
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}])

(def ^:private spine-idents (mapv :db/ident spine))

(def ^:private reserved-write-attrs
  "Session attribution is the layer's to write. A user op that named these
  could re-attribute or forge turns within its own transaction (same-tx
  cardinality-one writes are last-write-wins), so record refuses them —
  including their reverse-ref spellings. :memory/source stays open: citing
  a turn is the whole point of the citation path."
  #{:memory/session :memory/_session :memory.session/id :memory.session/_id})

(def ^:private tx-meta-id "datomic.tx")

(def ^:private session-tempid "kala.memory/session")

(defn install
  "Register the memory spine on conn. Idempotent: returns the transaction
  report when the spine was installed, nil when it was already present.
  Call it explicitly at setup — the schema stays visible where the
  developer's own vocabulary is registered."
  [conn]
  (let [db (api/db conn)
        missing (remove #(some? (api/entid db %)) spine-idents)]
    (when (seq missing)
      (let [by-ident (into {} (map (juxt :db/ident identity)) spine)]
        (api/transact conn (mapv #(get by-ident %) missing))))))

(defn- ensure-spine!
  [db]
  (when-not (every? #(some? (api/entid db %)) spine-idents)
    (core/err :memory/not-installed
              "The memory spine is not installed; call kala.memory/install first"
              {:spine spine-idents})))

;; ---- sessions ----------------------------------------------------------------

(defn- session-entity-id
  "Resolve a session handle — a session entity id or a :memory.session/id
  string — to its entity id; nil when no session carries it."
  [db session]
  (cond
    (int? session)
    (when (seq (api/q '[:find ?id :in $ ?e :where [?e :memory.session/id ?id]]
                      db session))
      session)

    (string? session)
    (ffirst (api/q '[:find ?e :in $ ?id :where [?e :memory.session/id ?id]]
                   db session))

    :else
    (core/err :memory/unknown-session
              "A session is a session entity id or a :memory.session/id string"
              {:session session})))

(defn session
  "Open a memory session, returning its entity id:

     (session conn)                    ; a fresh generated id
     (session conn {:id \"run-42\"})   ; your own id

  Re-opening an id returns the SAME session entity — the id is a
  unique-identity attribute, so resume is an upsert and provenance never
  forks across processes."
  ([conn]
   (session conn {:id (str (java.util.UUID/randomUUID))}))
  ([conn {:keys [id] :or {id (str (java.util.UUID/randomUUID))}}]
   (when-not (and (string? id) (pos? (.length ^String id)))
     (core/err :memory/unknown-session
               "A session id must be a non-empty string"
               {:id id}))
   (let [db (api/db conn)]
     (ensure-spine! db)
     (or (session-entity-id db id)
         (get (:tempids (api/transact conn [{:db/id session-tempid
                                             :memory.session/id id}]))
              session-tempid)))))

;; ---- recording ---------------------------------------------------------------

(def ^:private guarded-op-heads #{:db/add :db/retract :db.fn/cas})

(declare check-entity-map!)

(defn- check-value!
  "Walk a map form's values: component children are entity maps too."
  [v]
  (cond
    (map? v) (check-entity-map! v)
    (sequential? v) (run! check-value! v)
    :else nil))

(defn- check-entity-map!
  [m]
  (doseq [[k v] m]
    (when (reserved-write-attrs k)
      (core/err :memory/reserved-attr
                "Session attribution is written by kala.memory, not by transaction data"
                {:attr k}))
    (check-value! v)))

(defn- check-op!
  "One op vector: the attribute slot of add/retract/cas is the only place
  an op names an attribute. Transaction-function calls pass data to
  developer-authored stored code (ADR-0004) — the caller's own trust
  domain, outside this guard."
  [op]
  (let [[head & args] op]
    (when (and (keyword? head) (guarded-op-heads head))
      (let [attr (nth args 1 nil)]
        (when (reserved-write-attrs attr)
          (core/err :memory/reserved-attr
                    "Session attribution is written by kala.memory, not by transaction data"
                    {:attr attr}))))))

(defn- check-user-ops!
  "Refuse reserved attributes anywhere in user transaction data — op
  vectors, entity maps, and the nested component maps an entity map
  carries. The layer's attribution must survive the agent's own ops in
  the same transaction, and same-tx cardinality-one writes are
  last-write-wins, so the check walks the data rather than trusting
  ordering."
  [tx-data]
  (run! (fn [op]
          (cond
            (map? op) (check-entity-map! op)
            (vector? op) (check-op! op)
            :else nil))
        tx-data))

(defn- turn-count
  "How many turns the session has — its transactions in the log. The newest
  of them is the turn just recorded, so the count is its 1-based ordinal."
  [db s-eid]
  (or (ffirst (api/q '[:find (count ?tx) :in $ ?s :where [?tx :memory/session ?s]]
                     db s-eid))
      0))

(defn record
  "Record one turn of memory: tx-data becomes exactly one transaction,
  attributed to session (an entity id or a :memory.session/id string) by
  tx-meta. Returns the turn's coordinates:

     {:t 60        ; the turn's t — address every read with it
      :turn 4      ; its 1-based ordinal within the session
      :tx 9700001  ; the transaction entity id — citable as :memory/source
      :tempids {\"p\" 12}  ; your tempids, resolved
      :datoms 3}   ; datoms the turn wrote, attribution and stamp included

  Inside tx-data, the string \"datomic.tx\" in a ref position cites the
  current turn; earlier turns are cited by the :tx returned then, and a
  map form keyed \"datomic.tx\" carries your own tx-meta. A
  cardinality-one add over a different current value is a correction —
  the old belief stays in history; [:db.fn/cas e a old new] is the
  guarded variant for concurrent sessions."
  [conn session tx-data]
  (let [db (api/db conn)]
    (ensure-spine! db)
    (check-user-ops! tx-data)
    (let [s-eid (or (session-entity-id db session)
                    (core/err :memory/unknown-session
                              "No session carries this handle"
                              {:session session}))
          report (api/transact conn (into [{:db/id tx-meta-id :memory/session s-eid}]
                                          tx-data))]
      {:t (:t report)
       :turn (turn-count (:db-after report) s-eid)
       :tx (get-in report [:tempids :db/current-tx])
       :tempids (dissoc (:tempids report) :db/current-tx tx-meta-id)
       :datoms (count (:tx-data report))})))
