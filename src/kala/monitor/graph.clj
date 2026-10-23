(ns kala.monitor.graph
  "The monitor's knowledge-graph derivation — the one pure seam between
   the store's reads and the drawing. Given a db value or a client db
   marker (the api surface is uniform over access modes) plus a scope, it
   returns the whole graph payload the monitor renders:

     (graph db {:as-of 42 :session \"run-a\" :window 100 :cap 200})

     {:t 42                                ; the viewed belief state
      :sessions [{:id 3 :label \"run-a\" :turns 17}]
      :turns    [{:id \"t7\" :t 7 :session 3}]     ; every transaction, in order
      :entities [{:id \"e12\" :label \"Ada\" :kind \"person\"}]
      :edges    [{:kind :assert  :from \"t7\" :to \"e12\" :attr :person/role}
                 {:kind :retract ...}
                 {:kind :cite :from \"t9\" :to \"t7\" :attr :memory/source}
                 {:kind :ref :from \"e12\" :to \"e4\" :attr :person/friend}]}

   Two node kinds: turn nodes (the transaction entity at its t — every
   transaction is in the total order, attributed or not) and entity nodes
   (user-partition entities as of the viewed t; session entities are the
   filter's vocabulary, not content, so they are not drawn). Four edge
   kinds: assert, retract, citation (:memory/source — the writing turn
   cites the turn it derived from), and ref (entity-to-entity through
   :db.type/ref attributes). Attributes are edge labels, never nodes.

   The scope caps the drawing, filter-first per the monitor's design:
   :session narrows to one memory session, :window keeps the recent-t
   tail, :cap (default 200) keeps the newest turns with the entities they
   touch — once a turn no longer fits, older ones stop being considered.

   v1 posture: each derivation reads the full :eavt of the viewed db and
   of its history (history supplies the retracts and the per-turn trail
   the current view cannot show). The cap bounds the drawing, not the
   read."
  (:require [kala.api :as api]
            [kala.core :as core]
            [kala.eid :as eid])
  (:import (kala.client RemoteDb)))

(def default-cap 200)

(defn- viewed-t
  "The t the db value or marker is pinned to."
  [db]
  (if (instance? RemoteDb db)
    (:t db)
    (api/db-t db)))

(defn- resolve-session
  "A scope :session is a :memory.session/id string or a session entity id."
  [db handle]
  (cond
    (nil? handle) nil
    (int? handle) handle
    (string? handle)
    (or (ffirst (api/q '[:find ?e :in $ ?id :where [?e :memory.session/id ?id]]
                       db handle))
        (core/err :memory/unknown-session
                  "No session carries this handle"
                  {:session handle}))
    :else
    (core/err :memory/unknown-session
              "A session is a session entity id or a :memory.session/id string"
              {:session handle})))

(defn- ident-map
  "Attribute-entity-id → ident keyword, from the current view."
  [current]
  (into {} (for [d current :when (= :db/ident (:a d))] [(:e d) (:v d)])))

(defn- schema-attrs
  "The entity ids of schema attributes whose schema-attr datom resolves to
   vt — the value is an enum entity id in storage, so idents resolves it
   into the keyword being asked for."
  [current idents schema-attr vt]
  (into #{}
        (comp (filter #(= schema-attr (:a %)))
              (keep #(when (= vt (get idents (:v %))) (:e %))))
        current))

(defn- select-turns
  "The drawn turns, newest-first accumulation under the node cap: keep a
   turn while its own entity load fits, stop at the first that does not."
  [turns touched-by cap]
  (loop [kept []
         entities #{}
         [turn & more] (reverse turns)]
    (if-not turn
      (reverse kept)
      (let [fresh (remove entities (get touched-by (:t turn)))]
        ;; the budget is nodes: turns kept so far, this turn, and every
        ;; entity the whole drawing now carries
        (if (<= (+ (count kept) 1 (count entities) (count fresh)) cap)
          (recur (conj kept turn) (into entities fresh) more)
          (reverse kept))))))

(defn graph
  "Derive the monitor's graph payload — the shape documented at the
   namespace — from db as of the scope."
  [db {:keys [as-of session window cap]}]
  (let [;; the HTTP layer passes absent options as explicit nils, so the
        ;; cap's default cannot live in the destructuring :or
        cap (or cap default-cap)
        db (cond-> db as-of (api/as-of as-of))
        t (viewed-t db)
        head (eid/tx-eid t)
        current (api/datoms db :eavt [])
        ;; history supplies what the current view cannot: retracts and,
        ;; bounded to the viewed t, every assertion ever made
        history (->> (api/datoms (api/history db) :eavt [])
                     (filter #(<= (:tx %) head)))
        idents (ident-map current)
        ;; the current view names attributes by ident keyword — resolve
        ;; the schema's entity ids into that same vocabulary
        string-attrs (into #{} (map idents)
                           (schema-attrs current idents :db/valueType :db.type/string))
        ref-attrs (into #{} (map idents)
                        (schema-attrs current idents :db/valueType :db.type/ref))
        ;; cardinality-one attributes read like names — they label before
        ;; the card-many ones do
        one-attrs (into #{} (map idents)
                        (schema-attrs current idents :db/cardinality :db.cardinality/one))
        session-entities (into #{} (map :e)
                               (filter #(= :memory.session/id (:a %)) current))
        ;; the schema registry — user-transacted attributes and enums —
        ;; is vocabulary, not memory content: attributes label edges,
        ;; they are never nodes
        schema-entities (into #{} (map :e)
                              (filter #(= :db/ident (:a %)) current))
        meta-entities (into session-entities schema-entities)
        ;; turns: every transaction entity, its session attribution, and
        ;; the entities each turn touches
        turn-eids (into #{} (map :e)
                        (filter #(= :db/txInstant (:a %)) current))
        turn-session (into {} (for [d current
                                    :when (= :memory/session (:a d))]
                                [(:e d) (:v d)]))
        touched-by (reduce (fn [acc d]
                             (if (and (eid/user-eid? (:e d))
                                      (not (contains? meta-entities (:e d))))
                               (update acc (eid/eid->t (:tx d)) conj (:e d))
                               acc))
                           {}
                           history)
        turns (->> turn-eids
                   (map (fn [e] {:t (eid/eid->t e)
                                 :session (get turn-session e)}))
                   (sort-by :t))
        session-handle (resolve-session db session)
        scoped (cond->> turns
                 session-handle (filter #(= session-handle (:session %)))
                 window (filter #(> (:t %) (- t window))))
        ;; entities: what the drawn turns touched, with labels read from
        ;; the viewed state — the cardinality-one string attribute reads
        ;; like a name and labels the entity, its attribute namespace
        ;; kinds it
        current-by-e (group-by :e current)
        selected (select-turns scoped touched-by cap)
        turn-set (into #{} (map :t) selected)
        entity-set (into #{} (mapcat #(touched-by (:t %))) selected)
        entities (->> entity-set
                      (map (fn [e]
                             (let [datoms (get current-by-e e)
                                   attrs (->> datoms (map :a) sort)
                                   label (->> (filter string-attrs attrs)
                                              (sort-by (juxt (complement one-attrs) identity))
                                              (map (fn [a]
                                                     (some #(when (and (= a (:a %))
                                                                       (string? (:v %)))
                                                             (:v %))
                                                           datoms)))
                                              (some identity))
                                   kind (namespace (first attrs))]
                               {:id (str "e" e)
                                :label (or label (str "e" e))
                                :kind kind})))
                      (sort-by :id))
        edge-of (fn [kind d]
                  {:kind kind
                   :from (str "t" (eid/eid->t (:tx d)))
                   :to (str "e" (:e d))
                   :attr (:a d)})
        edges (concat
               ;; assert + retract: the correction trail every drawn turn
               ;; wrote on a drawn entity, straight from history — ref
               ;; attributes are excluded here, they draw as ref edges
               (for [d history
                     :when (and (contains? turn-set (eid/eid->t (:tx d)))
                                (contains? entity-set (:e d))
                                (not= :memory/source (:a d))
                                (not (contains? ref-attrs (:a d))))]
                 (edge-of (if (:added d) :assert :retract) d))
               ;; citations: the writing turn derives from the turn it names
               (for [d current
                     :when (and (= :memory/source (:a d))
                                (contains? turn-set (eid/eid->t (:tx d)))
                                (eid/tx-eid? (:v d))
                                (contains? turn-set (eid/eid->t (:v d))))]
                 {:kind :cite
                  :from (str "t" (eid/eid->t (:tx d)))
                  :to (str "t" (eid/eid->t (:v d)))
                  :attr :memory/source})
               ;; refs: entity-to-entity through ref attributes
               (for [d current
                     :when (and (contains? ref-attrs (:a d))
                                (contains? entity-set (:e d))
                                (contains? entity-set (:v d)))]
                 {:kind :ref
                  :from (str "e" (:e d))
                  :to (str "e" (:v d))
                  :attr (:a d)}))]
    {:t t
     ;; every session of the viewed store, not only the filtered one —
     ;; the filter is a control, the list is the menu
     :sessions (->> (for [d current
                          :when (= :memory/session (:a d))]
                      (:v d))
                    frequencies
                    (map (fn [[s n]]
                           {:id s
                            :label (some #(when (and (= s (:e %)) (string? (:v %)))
                                           (:v %))
                                         (filter #(= :memory.session/id (:a %)) current))
                            :turns n}))
                    (sort-by :label))
     :turns (map (fn [turn]
                   {:id (str "t" (:t turn))
                    :t (:t turn)
                    :session (:session turn)})
                 selected)
     :entities entities
     :edges (sort-by (juxt :from :to :kind :attr) edges)}))
