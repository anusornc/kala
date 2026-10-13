(ns kala.tx
  "The transaction pipeline: op validation and resolution, tempid/upsert
  machinery, entity-map expansion, transaction-function invocation, schema
  re-derivation (schema is data), and the transact/with entry points. No
  I/O beyond the Storage protocol's append-log at the very end of
  `transact`.

  Db values keep their shape from kala.core — plain maps keyed :kala.core/*,
  referenced here through the ::core/ alias. Index maintenance goes through
  core's index-specs table and slice primitive; nothing index-shaped is
  re-implemented here."
  (:require [kala.core :as core]
            [kala.eid :as eid]
            [kala.storage :as storage]
            [kala.weak-cache :as weak-cache])
  (:import [kala.core Datom]))

(def ^:private retract-entity-ops #{:db.fn/retractEntity :db/retractEntity})

;; The op heads that name pipeline features rather than stored transaction
;; functions: a keyword op head outside this set resolves as a fn call (ADR-0004).
(def ^:private reserved-op-heads
  #{:db/add :db/retract :db/retractEntity :db.fn/retractEntity :db.fn/cas :db.fn/call})

(defn- tx-fn-call?
  "A keyword op head that names no pipeline feature resolves as a stored
  transaction-function call (ADR-0004): the bare ident, or :db.fn/call with
  the ident as its target."
  [op]
  (let [head (first op)]
    (and (keyword? head)
         (or (= :db.fn/call head)
             (not (contains? reserved-op-heads head))))))

;; The reserved tempid that names the transaction entity itself, so tx
;; metadata is ordinary map-form data: {:db/id "datomic.tx" :db/txInstant ...}
(def ^:private tx-tempid "datomic.tx")

(defn- current-datom [db e a-id v]
  (first (filter #(core/value-eq v (.v ^Datom %))
                 (core/slice (::core/eavt db) :eavt [e a-id]))))

(defn- current-datom-any-v [db e a-id]
  (first (core/slice (::core/eavt db) :eavt [e a-id])))

(defn- avet-holder [db a-id v]
  (when-some [^Datom d (first (core/slice (::core/avet db) :avet [a-id v]))]
    (.e d)))

;; Map form -> list-form ops. Returns [eid ops]. Nested maps are allowed only
;; under component attributes (Datomic semantics); cardinality-many values
;; given as collections expand to one op per element. Entities without :db/id
;; get an opaque internal tempid that can never collide with user tempids.
(defn- expand-adds [eid k v]
  (mapv (fn [x] [eid k x]) v))

(defn- parse-entity-map [db m]
  (let [eid (or (:db/id m) (Object.))]
    (loop [[[k v] & more] (seq (dissoc m :db/id))
           adds []
           child-ops []]
      (if-not k
        [eid (into (mapv (fn [[e a v]] [:db/add e a v]) adds) child-ops)]
        (let [attr (or (core/lookup-attr db k)
                       (core/err :transact/unknown-attribute "Unknown attribute" {:attribute k}))
              many? (= :db.cardinality/many (:cardinality attr))
              ref? (= :db.type/ref (:value-type attr))
              nest! (fn [cm]
                      (when-not (:component? attr)
                        (core/err :transact/nested-map-requires-component
                                  "Nested maps require a component attribute" {:attribute k}))
                      (parse-entity-map db cm))]
          (cond
            (and ref? (map? v))
            (let [[child cops] (nest! v)]
              (recur more (conj adds [eid k child]) (into child-ops cops)))

            (and ref? (coll? v))
            (if (every? map? v)
              (let [parsed (mapv nest! v)]
                (recur more
                       (into adds (map (fn [[child _]] [eid k child]) parsed))
                       (into child-ops (mapcat second parsed))))
              (if many?
                (recur more (into adds (expand-adds eid k v)) child-ops)
                (recur more (conj adds [eid k v]) child-ops)))

            (and (coll? v) many?)
            (recur more (into adds (expand-adds eid k v)) child-ops)

            :else
            (recur more (conj adds [eid k v]) child-ops)))))))

(defn- validate-op [op]
  (when-not (vector? op)
    (core/err :transact/bad-op "Operations must be vectors" {:op op}))
  (let [head (first op)]
    (cond
      (and (contains? #{:db/add :db/retract} head) (= 4 (count op))) op
      (and (contains? retract-entity-ops head) (= 2 (count op))) op
      (and (= :db.fn/cas head) (= 5 (count op))) op
      ;; the reserved call head with no target is a malformed op, not a fn
      ;; call — everything else keyword-headed resolves as a stored
      ;; transaction-function call; whether the ident names a fn is checked
      ;; against the db in invoke-tx-fn, so validation stays structural
      (and (= :db.fn/call head) (< (count op) 2))
      (core/err :transact/bad-op
                "Operations must be [:db/add e a v], [:db/retract e a v], [:db.fn/retractEntity e], [:db.fn/cas e a old new], or a transaction-function call"
                {:op op})
      (tx-fn-call? op) op
      :else (core/err :transact/bad-op
                      "Operations must be [:db/add e a v], [:db/retract e a v], [:db.fn/retractEntity e], [:db.fn/cas e a old new], or a transaction-function call"
                      {:op op}))))

(defn- resolve-static-e [db x]
  (cond
    (int? x) (if (pos? x)
               x
               (core/err :transact/bad-entity "Entity id must be positive" {:e x}))
    (keyword? x) (or (core/entid db x)
                     (core/err :transact/unknown-ident "No entity with ident" {:ident x}))
    :else (core/err :transact/bad-entity
                    "Entity must be a positive id, tempid string, or ident" {:e x})))

(defn- resolve-v [db kind attr x ensure-temp]
  (if (not= :db.type/ref (:value-type attr))
    [db x]
    (cond
      (string? x) (if (= :db/add kind)
                    (let [[db' e] (ensure-temp db x)]
                      [db' e])
                    (core/err :transact/bad-entity "Cannot retract using a tempid" {:v x}))
      (keyword? x) [db (or (core/entid db x)
                           (core/err :transact/unknown-ident "No entity with ident" {:ident x}))]
      (int? x) (if (pos? x)
                 [db x]
                 (core/err :transact/bad-entity "Entity id must be positive" {:v x}))
      :else (if (= :db/add kind)
              (ensure-temp db x)
              (core/err :transact/bad-entity "Cannot retract using a tempid" {:v x})))))

;; Public for kala.time-travel: replay maintains the indexes through this
;; one primitive so a rebuilt db is exactly the transacted db.
(defn update-indexes [db f ^Datom d]
  (reduce (fn [db {:keys [set ref-only]}]
            (if (and ref-only (= conj f) (not (core/ref-datom? db d)))
              db
              (update db set f d)))
          db
          (vals core/index-specs)))

;; Public for kala.time-travel: replay applies a logged retraction through
;; this primitive, not raw disj. A raw retraction datom carries the RETRACTING
;; tx, which no indexed datom shares (indexes hold asserting txs), and the
;; index comparator orders by (e a v tx) — disj by the retraction itself would
;; never match. The datom it retracts is the unique live datom with its
;; (e a v) — the same lookup the live path performs in apply-retract — and an
;; absent datom means already retracted: a no-op, as on the live path.
(defn retract-from-indexes [db ^Datom d]
  (if-some [current (current-datom db (.e d) (.a d) (.v d))]
    (update-indexes db disj current)
    db))

(defn- add-datom [db ^Datom d]
  [(update-indexes db conj d)
   [d]])

(defn- drop-datom [db ^Datom current retract-d]
  [(update-indexes db disj current)
   [retract-d]])

(defn- apply-add [db tx attr e v]
  (let [a-id (:id attr)]
    (if (= :db.cardinality/one (:cardinality attr))
      (if-let [current (current-datom-any-v db e a-id)]
        (if (core/value-eq (.v ^Datom current) v)
          [db []]
          (let [[db' rd] (drop-datom db current (core/->Datom e a-id (.v ^Datom current) tx false))
                [db'' ad] (add-datom db' (core/->Datom e a-id v tx true))]
            [db'' (into rd ad)]))
        (add-datom db (core/->Datom e a-id v tx true)))
      (if (current-datom db e a-id v)
        [db []]
        (add-datom db (core/->Datom e a-id v tx true))))))

(defn- apply-retract [db tx attr e v]
  (let [a-id (:id attr)]
    (if-let [current (current-datom db e a-id v)]
      (drop-datom db current (core/->Datom e a-id v tx false))
      [db []])))

(defn- schema-attr-ids [db pred]
  (into #{}
        (comp (filter (fn [[_ a]] (pred a)))
              (map key))
        (::core/schema-by-id db)))

;; Datomic semantics: retracts every datom of the entity, every incoming
;; reference to it, and recurses through component children.
(defn- retract-entity [db tx e]
  (let [ref-ids (schema-attr-ids db (fn [a] (= :db.type/ref (:value-type a))))
        component-ids (schema-attr-ids db :component?)]
    (loop [db db
           stack [e]
           processed #{}
           acc []]
      (if-some [e' (first stack)]
        (if (contains? processed e')
          (recur db (rest stack) processed acc)
          (let [subject (vec (core/slice (::core/eavt db) :eavt [e']))
                incoming (vec (for [a ref-ids
                                    d (core/slice (::core/avet db) :avet [a e'])]
                                d))
                retracted (distinct (concat subject incoming))
                db' (reduce (fn [db ^Datom d]
                              (update-indexes db disj d))
                            db retracted)
                kids (keep (fn [^Datom d]
                             (when (contains? component-ids (.a d)) (.v d)))
                           subject)
                retract-datoms (mapv (fn [^Datom d]
                                       (core/->Datom (.e d) (.a d) (.v d) tx false))
                                     retracted)]
            (recur db'
                   (into (rest stack) kids)
                   (conj processed e')
                   (into acc retract-datoms))))
        [db acc]))))

(def ^:private value-preds
  {:db.type/string  string?
   :db.type/keyword keyword?
   :db.type/symbol  symbol?
   :db.type/long    (fn [x] (instance? Long x))
   :db.type/bigint  (fn [x] (or (instance? clojure.lang.BigInt x)
                                (instance? java.math.BigInteger x)))
   :db.type/float   (fn [x] (instance? Float x))
   :db.type/double  (fn [x] (instance? Double x))
   :db.type/boolean boolean?
   :db.type/instant (fn [x] (instance? java.util.Date x))
   :db.type/uuid    (fn [x] (instance? java.util.UUID x))
   :db.type/uri     (fn [x] (instance? java.net.URI x))
   :db.type/bytes   bytes?
   :db.type/ref     (fn [x] (and (int? x) (pos? x)))})

(defn- value-type-pred [attr]
  (or (get value-preds (:value-type attr))
      (core/err :transact/unknown-value-type
                "Unknown value type"
                {:attribute (:ident attr) :value-type (:value-type attr)})))

(defn- validate-value-type [attr v op]
  (when-not ((value-type-pred attr) v)
    (core/err :transact/bad-value-type
              "Value does not match the attribute's value type"
              {:attribute (:ident attr)
               :value-type (:value-type attr)
               :op op})))

(defn- resolve-e [db kind e unique-holder unique {:keys [temps ensure-temp upsert-claimants]} op]
  (if (or (string? e) (and (not (int? e)) (not (keyword? e))))
    ;; user tempid (string) or internal opaque tempid from map form
    (if (= :db/retract kind)
      (core/err :transact/bad-entity "Cannot retract using a tempid" {:op op})
      (let [allocated (get @temps e)]
        (cond
          (and unique-holder allocated (not= allocated unique-holder))
          (if (= :db.unique/identity unique)
            (core/err :transact/conflicting-upserts
                      "Tempid already resolves to a different entity" {:op op})
            (core/err :transact/unique-conflict
                      "Tempid's entity already holds a different value for a unique attribute" {:op op}))
          allocated [db allocated]
          (and unique-holder (= :db.unique/value unique))
          (core/err :transact/unique-conflict
                    "Unique value already held by another entity" {:op op})
          unique-holder (do (when-let [claimant (get @upsert-claimants unique-holder)]
                              (when (not= claimant e)
                                (core/err :transact/conflicting-upserts
                                          "Two tempids upsert the same unique value" {:op op})))
                            (vswap! upsert-claimants assoc unique-holder e)
                            (vswap! temps assoc e unique-holder)
                            [db unique-holder])
          :else (ensure-temp db e))))
    (let [e' (resolve-static-e db e)]
      (when (and (= :db/add kind) unique-holder (not= e' unique-holder))
        (core/err :transact/unique-conflict
                  "Unique value already held by another entity" {:op op}))
      [db e'])))

;; Compare-and-swap on a cardinality-one attribute: the write lands only if
;; the current value matches :old (nil :old expects the attribute to be
;; absent). Everything else about the write is an ordinary add — value-type
;; validation and uniqueness included — so a failed CAS leaves the db
;; untouched and a successful one flows through the same primitives.
(defn- apply-cas [db {:keys [tx ensure-temp]} [_ e a old new :as op]]
  (let [attr (or (core/lookup-attr db a)
                 (core/err :transact/unknown-attribute "Unknown attribute" {:attribute a}))]
    (when (= :db.cardinality/many (:cardinality attr))
      (core/err :transact/bad-op "CAS requires a cardinality-one attribute" {:op op}))
    (let [e (resolve-static-e db e)
          current (some-> (current-datom-any-v db e (:id attr)) :v)
          matches? (if (nil? old)
                     (nil? current)
                     (and (some? current) (core/value-eq current old)))]
      (if (not matches?)
        (core/err :transact/cas-failed "CAS failed — the current value does not match :old"
                  {:op op :old old :new new :actual current})
        (let [[db v] (resolve-v db :db/add attr new ensure-temp)
              _ (validate-value-type attr v op)
              holder (when (:unique attr) (avet-holder db (:id attr) v))]
          (when (and holder (not= holder e))
            (core/err :transact/unique-conflict
                      "Unique value already held by another entity" {:op op}))
          (apply-add db tx attr e v))))))

;; ---- transaction functions (ADR-0004) -----------------------------------------
;;
;; A transaction function is stored as data: an entity whose ident names it
;; and whose :db/fn attribute holds its source — an EDN-transportable string,
;; read with the full Clojure reader (the trust posture admits full eval, so
;; the reader is not restricted either). Invocation is by that ident, bare as
;; the op head or via [:db.fn/call ident args...]. Source compiles once per
;; string in the fn-env namespace — which requires kala.api and nothing of
;; kala.tx — memoized through kala.weak-cache keyed by the source itself (the
;; compiled fn never references its source string, so the no-self-reference
;; invariant holds). The function receives the accumulated db (prior ops of
;; the same tx applied) and its arguments unresolved, and returns nil,
;; sequential tx-data, or an entity map, spliced at the call position. Trust
;; is documented, not engineered (ADR-0004): transact access is code
;; execution on the transactor.

(def ^:private tx-fn-fuel
  "Transaction-function invocations one transaction may make — aligned with
  kala.query's rule fuel; both guard the same runaway-recursion shape."
  1000)

(def ^:private fn-env-ns
  ;; created lazily, not at kala.tx load time: requiring kala.api mid-load
  ;; would find kala.api itself mid-load and leave its vars unbound here.
  ;; create-ns installs no mappings, so core is referred explicitly — stored
  ;; fn bodies use bare core names — and the setup itself runs qualified.
  (delay (let [ns (create-ns 'kala.tx-fn-env)]
           (binding [*ns* ns]
             (eval '(clojure.core/refer-clojure))
             (eval '(clojure.core/require 'kala.api)))
           ns)))

(def ^:private fn-compiles (weak-cache/make))

(defn- compile-tx-fn
  "The fn object for stored source — read with the full Clojure reader and
  compiled in fn-env, once per source string. Store-time callers run this as
  the structural validation: the source must read and eval to an ifn before
  the :db/fn datom is accepted. Clojure compiles fn bodies eagerly, so
  unresolved symbols fail here too — at store time, never silently stored."
  [source op]
  (weak-cache/fetch fn-compiles source ::fn
    (fn []
      (let [form (try (read-string source)
                      (catch Exception e
                        (core/err :transact/bad-tx-fn
                                  (str "Transaction function source does not read: " (.getMessage e))
                                  {:op op :source source})))
            f (try (binding [*ns* @fn-env-ns] (eval form))
                   (catch Exception e
                     (core/err :transact/bad-tx-fn
                               (str "Transaction function source does not compile: "
                                    (.getMessage (or (ex-cause e) e)))
                               {:op op :source source})))]
        (when-not (ifn? f)
          (core/err :transact/bad-tx-fn
                    "Transaction function source must evaluate to a function"
                    {:op op :source source}))
        f))))

(defn- fn-output->ops
  "A transaction function's return value as list-form ops spliced at the
  call position: nil is a no-op, sequential values are the ops themselves,
  a map is one entity-map expansion against the db as of the call position.
  Anything else is a structural fn failure (:transact/bad-tx-fn)."
  [db out op source]
  (cond
    (nil? out) []
    (sequential? out) (vec out)
    (map? out) (second (parse-entity-map db out))
    :else (core/err :transact/bad-tx-fn
                    "Transaction function must return nil, sequential tx-data, or an entity map"
                    {:op op :source source})))

(defn- invoke-tx-fn
  "Resolve the op head to a stored fn, invoke it against the accumulated db,
  and return [db expansion-ops]. Every failure mode has a structured kind:
  the head names no stored fn (:transact/unknown-tx-fn), or the fn threw
  (:transact/tx-fn-failed, wrapping the throw so peer and client see the
  same shape)."
  [db op]
  (let [[head & more] op
        call? (= :db.fn/call head)
        ident (if call? (second op) head)
        args (if call? (nnext op) more)]
    (when-not (keyword? ident)
      (core/err :transact/unknown-tx-fn
                "Transaction functions are invoked by ident keyword"
                {:op op :ident ident}))
    (let [fn-e (or (core/entid db ident)
                   (core/err :transact/unknown-tx-fn "No entity carries this ident" {:op op :ident ident}))
          source (some-> (current-datom-any-v db fn-e (core/attr-eid :db/fn)) :v)]
      (when-not (string? source)
        (core/err :transact/unknown-tx-fn
                  "The ident's entity carries no :db/fn source" {:op op :ident ident}))
      (let [f (compile-tx-fn source op)
            out (try (apply f db args)
                     (catch Exception ex
                       (core/err :transact/tx-fn-failed
                                 "Transaction function threw"
                                 {:fn ident :message (.getMessage ex)})))]
        [db (fn-output->ops db out op source)]))))

(defn- apply-op [db {:keys [tx temps ensure-temp upsert-claimants] :as txn} [kind e a v :as op]]
  (if (contains? retract-entity-ops kind)
    (let [[db' ds] (retract-entity db tx (resolve-static-e db e))]
      [db' ds])
    (if (= :db.fn/cas kind)
      (apply-cas db txn op)
      (let [attr (or (core/lookup-attr db a)
                     (core/err :transact/unknown-attribute "Unknown attribute" {:attribute a}))
            [db v] (resolve-v db kind attr v ensure-temp)
            _ (validate-value-type attr v op)
            ;; eager validation: a :db/fn source must read and eval to an
            ;; ifn before the datom is accepted — a broken fn is never stored
            _ (when (and (= :db/add kind) (= :db/fn (:ident attr)))
                (compile-tx-fn v op))
            unique (:unique attr)
            unique-holder (when (and unique (= :db/add kind))
                            (avet-holder db (:id attr) v))
            [db e] (resolve-e db kind e unique-holder unique txn op)
            [db' datoms] (if (= :db/add kind)
                           (apply-add db tx attr e v)
                           (apply-retract db tx attr e v))]
        [db' datoms]))))

;; Schema is data: entities that gain a :db/ident become resolvable, and those
;; that also carry :db/valueType become attributes, re-derived from their datoms
;; through core/attr-map — the registry's one constructor.
(defn- register-new-entities [db datoms]
  (if-some [ident-aid (get (::core/idents db) :db/ident)]
    (let [touched (into (sorted-set)
                        (comp (filter (fn [^Datom d] (= ident-aid (.a d))))
                              (map (fn [^Datom d] (.e d))))
                        datoms)]
      (reduce (fn [db e]
                (let [by-a (into {} (map (fn [^Datom d] [(.a d) (.v d)]))
                                 (core/slice (::core/eavt db) :eavt [e]))
                      ident (get by-a ident-aid)]
                  (if ident
                    (if-some [vt (get by-a (get (::core/idents db) :db/valueType))]
                      (let [idents-by-eid (into {} (map (fn [[k v]] [v k])) (::core/idents db))
                            attr (core/attr-map {:db/ident ident
                                                 :db/valueType (get idents-by-eid vt)
                                                 :db/cardinality (get idents-by-eid (get by-a (get (::core/idents db) :db/cardinality)))
                                                 :db/unique (get idents-by-eid (get by-a (get (::core/idents db) :db/unique)))
                                                 :db/isComponent (get by-a (get (::core/idents db) :db/isComponent))}
                                                e)
                            _ (value-type-pred attr)
                            _ (when-not (contains? #{:db.cardinality/one :db.cardinality/many}
                                                   (:cardinality attr))
                                (core/err :transact/unknown-cardinality
                                          "Unknown cardinality"
                                          {:attribute ident :cardinality (:cardinality attr)}))
                            _ (when (and (:unique attr) (= :db.type/bytes (:value-type attr)))
                                (core/err :transact/unique-not-supported
                                          "Byte values cannot be unique" {:attribute ident}))
                            _ (when (and (:unique attr) (= :db.cardinality/many (:cardinality attr)))
                                (core/err :transact/unique-not-supported
                                          "Unique attributes must have cardinality one" {:attribute ident}))]
                        (-> db
                            (update ::core/idents assoc ident e)
                            (assoc-in [::core/schema-by-ident ident] attr)
                            (assoc-in [::core/schema-by-id e] attr)))
                      (update db ::core/idents assoc ident e))
                    db)))
              db
              touched))
    db))

;; Schema is data on the way out too: a tx that retracts an entity's :db/ident
;; datom kills the registry entries the entity's net datoms no longer justify —
;; an attribute's entries, or an enum's ::idents entry (ADR-0003). Keying on
;; the ident datom (rather than "entity gone") keeps a rebuilt db consistent:
;; re-derivation on replay needs the ident datom to register, so the live db
;; must drop the entry the moment its ident datom is retracted. Orphaned data
;; datoms of the dead attribute stay in the indexes; only the registry is
;; pruned. The register-then-prune ordering lives on tx/derive-schema.
(defn- prune-retracted-schema [db datoms]
  (if-some [ident-aid (get (::core/idents db) :db/ident)]
    (let [retracted (into #{}
                     (comp (filter (fn [^Datom d]
                                     (and (not (.added d)) (= ident-aid (.a d)))))
                           (map (fn [^Datom d] (.e d))))
                     datoms)]
      (reduce (fn [db e]
                ;; ADR-0003: net state wins. Register-new-entities has already
                ;; re-derived whatever the surviving ident datom justifies, so
                ;; the prune drops only entries the entity's slice no longer
                ;; carries: an entity keeps exactly its current ident's
                ;; entries — a same-tx re-assertion survives and a rename
                ;; leaves no stale key.
                (let [current-ident (when-some [^Datom d (current-datom-any-v db e ident-aid)]
                                      (.v d))]
                  (as-> db db
                    (update db ::core/idents
                            (fn [m]
                              (into {} (remove (fn [[ident holder]]
                                                 (and (= holder e) (not= ident current-ident))))
                                     m)))
                    (update db ::core/schema-by-ident
                            (fn [m]
                              (into {} (remove (fn [[ident attr]]
                                                 (and (= (:id attr) e) (not= ident current-ident))))
                                     m)))
                    (if (nil? current-ident)
                      (update db ::core/schema-by-id dissoc e)
                      db))))
              db
              retracted))
    db))

(defn derive-schema
  "Re-derive the schema registry from a tx's net datoms: register what they
  justify, then prune what they no longer carry (ADR-0003). Public for
  kala.time-travel: the live pipeline and replay call this alike, so the
  register-then-prune ordering is the registry's contract, stated here once.
  Its caller must have finished the datom pass: retract-entity resolves
  ref/component attributes through the registry mid-pass, so the prune must
  not run until every op has applied."
  [db datoms]
  (-> db
      (register-new-entities datoms)
      (prune-retracted-schema datoms)))

(defn transact-report
  "Pure transaction: applies tx-data against a db value and returns the
  report {:db-before :db-after :t :tx-data :tempids :raw-datoms} WITHOUT
  touching storage. This is the speculative path (with) and the core of
  the write path; the transactor (TT9) owns serialization across processes.
  :raw-datoms carries internal (attr-id) datoms for the storage log."
  [db-before tx-data]
  (when (::core/view db-before)
    (core/err :db/view-not-writable "Filtered db views (since) cannot be transacted against" {:view (::core/view db-before)}))
  (when-not (and (vector? tx-data) (every? #(or (vector? %) (map? %)) tx-data))
    (core/err :transact/bad-op "Transaction data must be a vector of operations or entity maps" {:tx-data tx-data}))
  (let [t (inc (::core/t db-before))
        tx (eid/tx-eid t)
        temps (volatile! {tx-tempid tx})
        upsert-claimants (volatile! {})
        ensure-temp (fn [db tempid]
                      (if-some [e (get @temps tempid)]
                        [db e]
                        (let [n (get (::core/next-idx db) 1 0)
                              e (eid/alloc-user-eid n)]
                          (vswap! temps assoc tempid e)
                          [(assoc-in db [::core/next-idx 1] (inc n)) e])))
        txn {:tx tx
             :temps temps
             :ensure-temp ensure-temp
             :upsert-claimants upsert-claimants}
        ops (mapcat (fn [x]
                      (if (map? x)
                        (second (parse-entity-map db-before x))
                        [x]))
                    tx-data)
        ;; The op pass. A transaction-function call splices its returned
        ;; tx-data at the call position (DataScript sequential semantics):
        ;; the fn sees every earlier op of the tx applied, and its effects
        ;; are in the db for everything after it. Fuel bounds runaway
        ;; recursion across the whole call tree; the log records only the
        ;; expansion, so replay never re-runs a fn.
        [db-after datoms]
        (loop [db db-before
               acc []
               pending ops
               fuel tx-fn-fuel]
          (if-some [op (first pending)]
            (let [op (validate-op op)
                  pending (rest pending)]
              (if (tx-fn-call? op)
                (do
                  (when (zero? fuel)
                    (core/err :transact/tx-fn-overflow
                              "Transaction functions exceeded the invocation fuel"
                              {:iterations tx-fn-fuel}))
                  (let [[db' more] (invoke-tx-fn db op)]
                    (recur db' acc (into (vec more) pending) (dec fuel))))
                (let [[db' ds] (apply-op db txn op)]
                  (recur db' (into acc ds) pending fuel))))
            [db acc]))
        ;; Every transaction entity carries :db/txInstant unless the tx itself
        ;; supplied one via metadata. The stamp is part of the transaction: it
        ;; goes to the log, the indexes, and the report's :tx-data.
        [db-stamped tx-stamp]
        (if (current-datom-any-v db-after tx (core/attr-eid :db/txInstant))
          [db-after []]
          (let [attr (get (::core/schema-by-id db-after) (core/attr-eid :db/txInstant))
                [db' ds] (apply-add db-after tx attr tx (java.util.Date.))]
            [db' ds]))
        db-after (derive-schema db-stamped datoms)
        final-db (assoc db-after ::core/t t)]
    {:db-before db-before
     :db-after final-db
     :t t
     :tx-data (mapv (partial core/public-datom final-db) (into datoms tx-stamp))
     :raw-datoms (into datoms tx-stamp)
     :tempids (-> (into {} (filter (fn [[k _]] (string? k)) @temps))
                  (assoc :db/current-tx tx))}))

(defn transact
  "Apply transaction data against the connection (list-form ops, map-form
  entity maps, any mix) and return the tx report with :db-before,
  :db-after, :tx-data, and :tempids. Appends to the storage log and swaps
  the connection atom."
  [conn tx-data]
  (let [{:keys [db-after t raw-datoms] :as report} (transact-report @conn tx-data)]
    (storage/append-log (::core/storage db-after)
                        {:t t
                         :datoms (mapv (fn [^Datom d] [(.e d) (.a d) (.v d) (.tx d) (.added d)])
                                       raw-datoms)})
    (reset! conn db-after)
    (dissoc report :raw-datoms)))
