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
  (:require [clojure.string :as str]
            [kala.api :as api]
            [kala.core :as core]
            [kala.eid :as eid]))

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

(def ^:private data-only-op-heads
  "The agent write surface is data-only: adds, retracts, compare-and-swap.
  Everything else a transaction can carry is refused by record itself —
  retractEntity would erase a session entity from current state (the
  provenance join would lose its target), and transaction-function calls
  would hand the stored code a chance to write what the data rules refuse
  (ADR-0004: those are the developer's code, reached through the
  developer's own transacts, never through record)."
  #{:db/add :db/retract :db.fn/cas})

(def ^:private current-tx-tempid "datomic.tx")

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

(declare check-entity-map!)

(defn- check-source-value!
  "The citation coordinate is the transaction entity id (:tx from record),
  or the string \"datomic.tx\" for the current turn — never the turn's t.
  Dangling refs would be accepted silently by the transactor, so the spine
  validates its own namespace here (issue #51)."
  [db v]
  (cond
    (= v current-tx-tempid) nil
    (and (int? v) (eid/tx-eid? v) (seq (api/datoms db :eavt [v]))) nil
    :else
    (core/err :memory/bad-source
              ":memory/source cites a turn's tx entity id (the :tx value from record), or \"datomic.tx\" for the current turn"
              {:value v})))

(defn- check-value!
  "Walk a map form's values: component children are entity maps too."
  [db v]
  (cond
    (map? v) (check-entity-map! db v)
    (sequential? v) (run! #(check-value! db %) v)
    :else nil))

(defn- check-entity-map!
  [db m]
  (doseq [[k v] m]
    (cond
      (reserved-write-attrs k)
      (core/err :memory/reserved-attr
                "Session attribution is written by kala.memory, not by transaction data"
                {:attr k})
      (= k :memory/source) (check-source-value! db v)
      :else (check-value! db v))))

(defn- check-op!
  "One op vector. Data-only is an allowlist, not a case list: an op whose
  head record does not name is refused outright, whatever it is — the
  transactor grows op kinds, and a refusal must not depend on remembering
  to enumerate the dangerous ones. For the allowed heads, the attribute
  slot is the only place an op names an attribute."
  [db op]
  (let [[head & args] op]
    (cond
      (not (keyword? head)) nil                       ; not an op shape — the transactor rejects it
      (not (data-only-op-heads head))
      (core/err :memory/data-only
                "record takes data-only writes: :db/add, :db/retract, :db.fn/cas"
                {:op head})
      :else
      (let [attr (nth args 1 nil)
            val (nth args 2 nil)]
        (cond
          (reserved-write-attrs attr)
          (core/err :memory/reserved-attr
                    "Session attribution is written by kala.memory, not by transaction data"
                    {:attr attr})
          (= attr :memory/source)
          (check-source-value! db val))))))

(defn- check-user-ops!
  "Refuse reserved attributes anywhere in user transaction data — op
  vectors, entity maps, and the nested component maps an entity map
  carries — and validate citation coordinates. The layer's attribution
  must survive the agent's own ops in the same transaction, and same-tx
  cardinality-one writes are last-write-wins, so the check walks the
  data rather than trusting ordering."
  [db tx-data]
  (run! (fn [op]
          (cond
            (map? op) (check-entity-map! db op)
            (vector? op) (check-op! db op)
            :else nil))
        tx-data))

(defn- require-session!
  [db session]
  (or (session-entity-id db session)
      (core/err :memory/unknown-session
                "No session carries this handle"
                {:session session})))

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
    (check-user-ops! db tx-data)
    (let [s-eid (require-session! db session)
          report (api/transact conn (into [{:db/id current-tx-tempid :memory/session s-eid}]
                                          tx-data))]
      {:t (:t report)
       :turn (turn-count (:db-after report) s-eid)
       :tx (get-in report [:tempids :db/current-tx])
       :tempids (dissoc (:tempids report) :db/current-tx current-tx-tempid)
       :datoms (count (:tx-data report))})))

;; ---- read doors ----------------------------------------------------------------

(def ^:private default-limit 100)
(def ^:private max-limit "The hard ceiling a caller's :limit clamps to." 1000)
(def ^:private max-pull-chars "The serialized-size cap of one pulled document." 16000)

(defn- pin-db
  "Doors take :as-of so pinning a belief state is structural — every
  section of the read sees the same t."
  [db {:keys [as-of]}]
  (if (some? as-of) (api/as-of db as-of) db))

(defn- opt-int!
  "A positive-integer option, clamped to its ceiling — every budget knob
  in the layer behaves the same way: absent means the default, above the
  ceiling means the ceiling, garbage means :memory/bad-option."
  [k v default ceiling]
  (when (some? v)
    (when-not (pos-int? v)
      (core/err :memory/bad-option
                (str (subs (str k) 1) " must be a positive integer")
                {k v})))
  (min (or v default) ceiling))

(defn- opt-t!
  [k v]
  (when (some? v)
    (when-not (nat-int? v)
      (core/err :memory/bad-option (str (subs (str k) 1) " must be a t (non-negative integer)") {k v})))
  v)

(defn- truncated
  [shown dropped limit]
  (if (pos? dropped)
    {:capped? true :shown (count shown) :dropped dropped :limit limit}
    {:capped? false}))

(defn- cap-list
  "Cap a list-shaped result. A silently truncated answer would misstate
  the agent's belief, so the cap always rides along visibly."
  [rows limit]
  (let [shown (vec (take limit rows))
        dropped (- (count rows) (count shown))]
    {:result shown
     :truncated (truncated shown dropped limit)}))

(defn query
  "Run a Datalog query over memory — joins included:

     (query db '[:find ?v :in $ ?e :where [?e :proj/deploy-command ?v]]
            {:args [[:proj/kala]] :as-of 43})

  The db binds $; {:args [...]} feeds the rest of :in in order. List
  results are capped at :limit (default 100, hard ceiling 1000) and any
  capped answer carries :truncated. Scalar and collection find shapes
  pass through uncapped. kala's own error kinds surface verbatim."
  [db query-form {:keys [args as-of limit] :or {args []}}]
  (let [db (pin-db db {:as-of as-of})
        limit (opt-int! :limit limit default-limit max-limit)
        result (if (seq args)
                 (apply api/q query-form db args)
                 (api/q query-form db))]
    (if (sequential? result)
      (cap-list result limit)
      {:result result :truncated {:capped? false}})))

(defn pull
  "Shape one entity into a document — the whole entity by default:

     (pull db eid {:as-of 43})
     (pull db eid {:pattern [:proj/name]})

  All or nothing: a document over the serialized-size cap refuses the
  read with :memory/result-too-large and a narrower-pattern hint, so a
  pulled entity is never partially shaped. A nonexistent entity is nil."
  [db eid {:keys [pattern as-of] :or {pattern '[*]}}]
  (let [db (pin-db db {:as-of as-of})
        doc (api/pull db pattern eid)]
    (when doc
      (let [size (count (pr-str doc))]
        (when (> size max-pull-chars)
          (core/err :memory/result-too-large
                    "The pulled entity exceeds the size cap; narrow the pattern"
                    {:size size :cap max-pull-chars
                     :pattern-hint "name attributes instead of *"}))
        doc))))

(defn- norm-datom
  "One shape for trails across both access modes: {:e :a :v :t :added} —
  the datom's own coordinates in the memory vocabulary, :t the counter
  (datoms carry the tx entity id; the two are one mapping apart)."
  [d]
  {:e (:e d) :a (:a d) :v (:v d) :t (eid/eid->t (:tx d)) :added (:added d)})

(defn history
  "The trail of one scope, in t order:

     (history db {:scope :entity :entity eid :attrs #{:proj/name}})
     (history db {:scope :session :session \"run-42\"})

  Entity scope is the correction trail — every assertion and retraction
  of one entity, optionally narrowed to attributes. Session scope is the
  provenance trail — every datom the session's turns wrote, joined
  through the spine. Entries are {:e :a :v :t :added}, optionally bounded
  by the pin and inclusive :from-t/:to-t — the pin bounds the trail
  itself, in both access modes. Capped like query."
  [db {:keys [scope entity session attrs from-t to-t as-of limit]}]
  (let [db (pin-db db {:as-of as-of})
        limit (opt-int! :limit limit default-limit max-limit)
        _ (opt-t! :from-t from-t)
        _ (opt-t! :to-t to-t)
        entries (->> (case scope
                       :entity
                       (do (when-not (int? entity)
                             (core/err :memory/bad-option
                                       ":entity scope needs an entity id" {:entity entity}))
                           (api/datoms (api/history db) :eavt [entity]))

                       :session
                       (let [s-eid (require-session! db session)
                             txs (into #{}
                                       (map first)
                                       (api/q '[:find ?tx :in $ ?s
                                                :where [?tx :memory/session ?s]]
                                              db s-eid))]
                         (filter (fn [d] (contains? txs (:tx d)))
                                 (api/datoms (api/history db) :aevt [])))

                       (core/err :memory/bad-scope
                                 "History scope is :entity or :session"
                                 {:scope scope}))
                     (map norm-datom)
                     (filter (fn [{:keys [t]}]
                               (and (or (nil? as-of) (<= t as-of))
                                    (or (nil? from-t) (>= t from-t))
                                    (or (nil? to-t) (<= t to-t)))))
                     (filter (fn [{:keys [a]}]
                               (or (nil? attrs) (contains? (set attrs) a))))
                     (sort-by :t)
                     vec)]
    (cap-list entries limit)))

(defn turns
  "The step index of a session — the way \"step 12\" becomes a t:

     (turns db \"run-42\" {:as-of 43})

  {:session <id> :turns [[t ordinal txInstant] ...]} — one triple per
  turn in t order, bounded by :as-of, capped like query. The wall clock
  rides here, honestly: the context document keeps it out, this door
  does not."
  [db session {:keys [as-of limit]}]
  (let [db (pin-db db {:as-of as-of})
        limit (opt-int! :limit limit default-limit max-limit)
        s-eid (require-session! db session)
        sid (or (:memory.session/id (api/pull db '[:memory.session/id] s-eid)) session)
        {:keys [result truncated]}
        (cap-list (->> (api/q '[:find ?tx ?inst :in $ ?s
                                :where [?tx :memory/session ?s]
                                       [?tx :db/txInstant ?inst]]
                              db s-eid)
                       (map (fn [[tx inst]] [(eid/eid->t tx) inst]))
                       (sort-by first)
                       (into [])
                       (map-indexed (fn [i [t inst]] [t (inc i) inst]))
                       vec)
                  limit)]
    {:session sid :turns result :truncated truncated}))

;; ---- the context document ------------------------------------------------------

(def ^:private default-cap 50)
(def ^:private max-cap "The hard ceiling a caller's :cap clamps to." 500)

(defn- reserved-attr-ns?
  "The db and memory namespaces belong to the machinery; memory's content
  is the developer's vocabulary — this predicate names the boundary, the
  glossary names the content."
  [a]
  (let [ns (namespace a)]
    (or (= ns "db")
        (= ns "memory")
        (str/starts-with? (or ns "") "memory."))))

(defn- ident-map
  "Every ident-bearing entity — the map that lets a document name entities
  the way a person would."
  [db]
  (into {} (map (fn [[e i]] [e i]))
        (api/q '[:find ?e ?i :where [?e :db/ident ?i]] db)))

(defn- ref-attr-idents
  "The idents whose values are references — the ones a document may name."
  [db]
  (if-some [ref-eid (api/entid db :db.type/ref)]
    (into #{}
          (map (fn [[a]] (get (ident-map db) a a)))
          (api/q '[:find ?a :in $ ?vt :where [?a :db/valueType ?vt] [?a :db/ident _]]
                 db ref-eid))
    #{}))

(defn- card-many-idents
  "The idents whose values accumulate — a set-valued :now stays a set
  even when one value remains."
  [db]
  (if-some [many-eid (api/entid db :db.cardinality/many)]
    (let [im (ident-map db)]
      (into #{}
            (map (fn [[a]] (get im a a)))
            (api/q '[:find ?a :in $ ?c :where [?a :db/cardinality ?c] [?a :db/ident _]]
                   db many-eid)))
    #{}))

(defn- head-t
  "The db's t, read off its datoms so the document can state its own pin
  in both access modes (a client db is a lazy marker; its datoms are not)."
  [datoms]
  (reduce (fn [acc d] (max acc (eid/eid->t (:tx d)))) core/bootstrap-t datoms))

(defn- render-entity [ident-map e] (get ident-map e e))

(defn- render-value
  [ref-attrs ident-map a v]
  (if (and (contains? ref-attrs a) (contains? ident-map v))
    (get ident-map v)
    v))

(defn- doc-turns
  "Every session's [t turn] pairs — the step index without the wall clock."
  [db]
  (->> (api/q '[:find ?sid ?tx :where [?tx :memory/session ?s]
                                       [?s :memory.session/id ?sid]] db)
       (group-by first)
       (map (fn [[sid rows]]
              [sid (->> rows
                        (map second)
                        (map eid/eid->t)
                        (sort)
                        (map-indexed (fn [i t] [t (inc i)]))
                        vec)]))
       (into {})))

(defn- current-values
  [current-datoms e a]
  (->> current-datoms
       (filter (fn [d] (and (= (:e d) e) (= (:a d) a))))
       (map :v)
       distinct
       vec))

(defn- correction-entries
  "The full trail of the shown entities: for every retracted value, where
  it came from and what holds now. :asserted/:until are log coordinates —
  the assertion's t and the retraction's t; :now is the attribute's
  current value (a set for cardinality-many, nil when nothing holds)."
  [db t ident-map ref-attrs many-attrs current-datoms shown-entities]
  (->> shown-entities
       (sort)
       (mapcat (fn [e]
                 (let [hist (->> (api/datoms (api/history db) :eavt [e])
                                 (filter (fn [d] (<= (eid/eid->t (:tx d)) t)))
                                 vec)]
                   (->> (filter (fn [d] (false? (:added d))) hist)
                        (sort-by (comp eid/eid->t :tx))
                        (map (fn [r]
                               (let [a (:a r)
                                     v (:v r)
                                     until (eid/eid->t (:tx r))
                                     asserted (->> (filter (fn [d]
                                                             (and (:added d)
                                                                  (= (:a d) a)
                                                                  (= (:v d) v)
                                                                  (<= (eid/eid->t (:tx d)) until)))
                                                           hist)
                                                   (map (comp eid/eid->t :tx))
                                                   (apply max))
                                   now (let [vs (current-values current-datoms e a)]
                                         (cond
                                           (empty? vs) nil
                                           (contains? many-attrs a) (set vs)
                                           :else (first vs)))]
                                 {:entity (render-entity ident-map e)
                                  :attr a
                                  :was (render-value ref-attrs ident-map a v)
                                  :asserted asserted
                                  :until until
                                  :now (render-value ref-attrs ident-map a now)})))))))
       (sort-by (juxt :entity :attr))
       vec))

(defn context
  "The context document — one readable-EDN belief-state map, the thing a
  harness stuffs into a prompt:

     (context db {:cap 5 :as-of 43})

  :current is memory's content, newest first, capped in facts (:cap,
  default 50, ceiling 500); over cap, :truncated names the dropped
  attributes and never re-lists their values. :corrections is the full
  trail of the entities shown — superseded values and retractions,
  :now nil when retraction resolved them; :corrections? false omits the
  section. :turns is every session's [t turn] pairs, bounded at the pin.
  The document is store-wide (sessions are provenance, not partitions)
  and never carries a wall clock."
  [db {:keys [as-of cap corrections?] :or {corrections? true}}]
  (let [db (pin-db db {:as-of as-of})
        cap (opt-int! :cap cap default-cap max-cap)
        all (api/datoms db :eavt [])
        t (head-t all)
        ident-map (ident-map db)
        ref-attrs (ref-attr-idents db)
        many-attrs (card-many-idents db)
        content (->> all
                     (remove (fn [d] (reserved-attr-ns? (:a d))))
                     (remove (fn [d] (eid/tx-eid? (:e d))))
                     (sort-by (fn [d] (- (eid/eid->t (:tx d)))))
                     vec)
        shown (vec (take cap content))
        dropped (- (count content) (count shown))
        shown-entities (vec (distinct (map :e shown)))
        current-datoms content]
    (cond-> {:as-of t
             :truncated (if (pos? dropped)
                          {:capped? true :shown (count shown) :dropped dropped :cap cap
                           :dropped-attrs (vec (distinct (map :a (drop cap content))))}
                          {:capped? false})
             :current (mapv (fn [d]
                              [(render-entity ident-map (:e d))
                               (:a d)
                               (render-value ref-attrs ident-map (:a d) (:v d))])
                            shown)
             :turns (doc-turns db)}
      corrections? (assoc :corrections
                          (correction-entries db t ident-map ref-attrs many-attrs
                                              current-datoms shown-entities)))))
