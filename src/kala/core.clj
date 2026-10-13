(ns kala.core
  "Datom representation, the bootstrap schema, index construction, and the
  read interfaces over immutable db values. Entity-id packing lives in
  kala.eid — a pure-arithmetic leaf this namespace places bootstrap schema
  entities through; the allocation counter is db-value state kala.tx
  advances. The transaction pipeline lives in kala.tx; time travel and log
  replay in kala.time-travel. No I/O here: storage sits behind the Storage
  protocol (seam 2). Reads go through immutable db values — plain maps
  keyed :kala.core/*, which every kala module shares."
  (:require [clojure.string :as str]
            [kala.eid :as eid]
            [kala.storage :as storage]
            [kala.weak-cache :as weak-cache]))

;; ---- errors -----------------------------------------------------------------

(defn err
  "Raise the error shape every kala module shares: an ex-info whose data
  carries :kala/error. The error contract is part of the public interface."
  [kind msg data]
  (throw (ex-info msg (merge {:kala/error kind} data))))

;; ---- datoms -----------------------------------------------------------------

(defrecord Datom [e a v tx added])

;; A unique minimal sentinel so index slices can start below any real value,
;; whatever the value type of the attribute being scanned.
(def ^:private MIN-V (Object.))

(defn- cmp-sentinel [x y]
  (cond
    (identical? x MIN-V) (if (identical? y MIN-V) 0 -1)
    (identical? y MIN-V) 1
    :else nil))

(defn- cmp-val [x y]
  (or (cmp-sentinel x y)
      (if (= (class x) (class y))
        ;; Values of one attribute share a type; across types the string form
        ;; gives a stable, arbitrary order so index scans never throw.
        (try (compare x y) (catch Exception _ (compare (str x) (str y))))
        (compare (str x) (str y)))))

(defn- cmp-field [x y]
  (or (cmp-sentinel x y)
      (Long/compare (long x) (long y))))

(defn- cmp-by [ks]
  ;; Indexed loop over the field vector: this comparator runs O(log n) times
  ;; per index seek and once per insert, so it must not allocate per call.
  (let [n (count ks)
        v-pos (loop [i 0]
                (cond
                  (= i n) -1
                  (= :v (nth ks i)) i
                  :else (recur (inc i))))]
    (fn [x y]
      (loop [i 0]
        (if (= i n)
          0
          (let [k (nth ks i)
                cx (k x)
                cy (k y)
                c (if (= i v-pos)
                    (cmp-val cx cy)
                    (cmp-field cx cy))]
            (if (zero? c) (recur (inc i)) c)))))))

;; One spec per index drives everything index-shaped below: the comparator
;; field order, the db key of its sorted set, and — derived from :fields
;; (everything before :tx, which every index sorts last) — prefix bounds,
;; prefix matching, and component resolution. Adding an index is one entry
;; here, not a case arm in five places. Public: kala.tx maintains the indexes
;; and kala.time-travel filters them for since, both through this table.
(def index-specs
  {:eavt {:fields [:e :a :v :tx] :set ::eavt :cmp (cmp-by [:e :a :v :tx])}
   :aevt {:fields [:a :e :v :tx] :set ::aevt :cmp (cmp-by [:a :e :v :tx])}
   :avet {:fields [:a :v :e :tx] :set ::avet :cmp (cmp-by [:a :v :e :tx])}
   ;; Datomic's VAET holds only reference datoms (reverse-ref lookups)
   :vaet {:fields [:v :a :e :tx] :set ::vaet :cmp (cmp-by [:v :a :e :tx]) :ref-only true}})

(defn ref-datom?
  "Is this datom's attribute a :db.type/ref? Drives VAET membership.
  Public for kala.tx's index maintenance."
  [db ^Datom d]
  (= :db.type/ref (:value-type (get (::schema-by-id db) (.a d)))))

;; ---- value types ------------------------------------------------------------

;; Byte arrays need content equality; everything else is Clojure =.
(defn- val= [x y]
  (if (and (bytes? x) (bytes? y))
    (java.util.Arrays/equals ^bytes x ^bytes y)
    (= x y)))

;; ---- bootstrap schema -------------------------------------------------------

(def bootstrap-t
  "The transaction counter of the genesis transaction."
  1)

(def ^:private meta-attrs
  [{:db/ident :db/ident :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :db/cardinality :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :db/valueType :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :db/doc :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :db/unique :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :db/isComponent :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :db/txInstant :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}])

(def ^:private enum-idents
  [:db.cardinality/one :db.cardinality/many
   :db.type/string :db.type/keyword :db.type/symbol :db.type/long :db.type/bigint
   :db.type/float :db.type/double :db.type/boolean :db.type/instant :db.type/uuid
   :db.type/uri :db.type/bytes :db.type/ref
   :db.unique/value :db.unique/identity])

(def ^:private attr-pos
  {:db/ident 1 :db/cardinality 2 :db/valueType 3 :db/doc 4
   :db/unique 5 :db/isComponent 6 :db/txInstant 7})

(defn- enum-eids []
  (into {} (map-indexed (fn [j k] [k (eid/bootstrap-eid (+ 100 j))]) enum-idents)))

(defn attr-eid
  "The deterministic entity id of a bootstrap schema attribute. Public for
  kala.tx, which stamps :db/txInstant on every transaction entity."
  [ident]
  (eid/bootstrap-eid (attr-pos ident)))

(defn- bootstrap-tuples []
  (let [enums (enum-eids)
        attr-tuples
        (mapcat (fn [spec]
                  (let [e (attr-eid (:db/ident spec))]
                    (cond-> [[:db/add e :db/ident (:db/ident spec)]
                             [:db/add e :db/valueType (get enums (:db/valueType spec))]
                             [:db/add e :db/cardinality (get enums (:db/cardinality spec) :db.cardinality/one)]]
                      (:db/unique spec) (conj [:db/add e :db/unique (get enums (:db/unique spec))])
                      (:db/isComponent spec) (conj [:db/add e :db/isComponent true])
                      (:db/doc spec) (conj [:db/add e :db/doc (:db/doc spec)]))))
                meta-attrs)
        enum-tuples (map (fn [k] [:db/add (get enums k) :db/ident k]) enum-idents)
        ;; the genesis transaction is itself a transaction entity, stamped
        ;; deterministically so a rebuilt db (TT8 recovery) matches
        genesis-meta [[:db/add (eid/tx-eid bootstrap-t) :db/txInstant (java.util.Date. 0)]]]
    (into attr-tuples (into enum-tuples genesis-meta))))

(defn attr-map
  "The registry attribute-map for a schema spec — the one constructor of the
  ::schema-by-ident shape, cardinality default included. Public for kala.tx,
  which re-derives the same spec from an entity's datoms at registration;
  the bootstrap schema builds it from its literal specs."
  [{:db/keys [ident valueType cardinality unique isComponent]} id]
  {:id id
   :ident ident
   :value-type valueType
   :cardinality (or cardinality :db.cardinality/one)
   :unique unique
   :component? (boolean isComponent)})

;; ---- db values ---------------------------------------------------------------

(defn empty-db [storage]
  (let [datoms (into []
                     (map (fn [[_ e a v]]
                            (->Datom e (attr-eid a) v bootstrap-t true)))
                     (bootstrap-tuples))
        schema-by-ident (into {}
                              (map (fn [spec]
                                     (let [id (attr-eid (:db/ident spec))]
                                       [(:db/ident spec) (attr-map spec id)]))
                                   meta-attrs))
        schema-by-id (into {} (map (fn [[ident m]] [(:id m) m]) schema-by-ident))
        ref-datom?* (fn [^Datom d]
                      (= :db.type/ref (:value-type (get schema-by-id (.a d)))))
        index-sets (into {}
                         (map (fn [[index {:keys [set cmp ref-only]}]]
                                [set (into (sorted-set-by cmp)
                                           (if ref-only (filter ref-datom?* datoms) datoms))]))
                         index-specs)]
    (merge index-sets
           {::storage storage
            ::t bootstrap-t
            ::next-idx {1 0}
            ::idents (merge (into {} (map (fn [spec] [(:db/ident spec) (attr-eid (:db/ident spec))]) meta-attrs))
                            (enum-eids))
            ::schema-by-ident schema-by-ident
            ::schema-by-id schema-by-id})))

(defn db-t
  "The db value's transaction counter t. The plain-map read accessor for
  the ::t key — read db-value state through this, not the raw key."
  [db]
  (::t db))

(defn db-storage
  "The storage handle backing a db value. The plain-map read accessor for
  the ::storage key — read db-value state through this, not the raw key."
  [db]
  (::storage db))

(defn entid [db ident-or-eid]
  (if (int? ident-or-eid)
    ident-or-eid
    (get (::idents db) ident-or-eid)))

(defn lookup-attr
  "An attribute's schema map by ident or by attr id, or nil. Public for
  kala.tx's op resolution and map-form expansion."
  [db ident-or-id]
  (or (get (::schema-by-ident db) ident-or-id)
      (get (::schema-by-id db) ident-or-id)))

;; ---- index reads --------------------------------------------------------------

(defn- low-bound [index comps]
  (let [{:keys [fields]} (index-specs index)
        n (count comps)
        field-pos (zipmap fields (range))
        slot (fn [field fill]
               (let [i (field-pos field)]
                 (if (< i n) (comps i) fill)))]
    (->Datom (slot :e MIN-V) (slot :a MIN-V) (slot :v MIN-V) (slot :tx Long/MIN_VALUE) true)))

(defn- prefix-pred [index comps]
  ;; Runs once per datom on every slice; the indexed loop keeps it
  ;; allocation-free (a lazy map here costs ~150ns per element).
  (let [fields (:fields (index-specs index))
        n (count comps)]
    (fn [d]
      (loop [i 0]
        (or (= i n)
            (let [k (nth fields i)
                  c (nth comps i)]
              (and (if (= k :v)
                     (val= (k d) c)
                     (= (k d) c))
                   (recur (inc i)))))))))

(defn slice
  "The contiguous run of one index matching a prefix of components, eager.
  Public for kala.tx (current-datom lookups, entity retraction) and the
  entity/pull readers — the same primitive as every other slice."
  [index-set index comps]
  ;; Eager vector: every caller reduces the slice immediately; laziness only
  ;; added a LazySeq allocation per element.
  (into [] (take-while (prefix-pred index comps))
        (subseq index-set >= (low-bound index comps))))

;; ---- per-attribute read views (query engine) ----------------------------------
;; Sorted sets stay the source of truth (transactions, public datoms, time
;; travel). The query engine's per-solution index reads go through views that
;; are pure functions of one attribute's slice of an index: a flat vector for
;; scans, and hash maps for [a v] / [e a] point lookups.
;;
;; The views cache through kala.weak-cache, keyed by the db value itself —
;; db values must stay plain maps (structural equality is part of their
;; observable surface), and caching views is sound because a view is a pure
;; function of the index sets. Views reference only datoms, so no
;; strip-before-store is needed. Clojure maps cache their hashCode, so the
;; equality-keyed lookup is O(1) after the first touch of a db value.

(def ^:private view-caches (weak-cache/make))

(defn- attr-vec
  "The attribute's datoms as a flat vector in index order."
  [db index a-id]
  (weak-cache/fetch view-caches db [index a-id]
                    #(vec (slice (get db (:set (index-specs index))) index [a-id]))))

(defn- attr-vmap [db a-id]
  (weak-cache/fetch view-caches db [:vmap a-id]
                    #(group-by :v (attr-vec db :aevt a-id))))

(defn- attr-emap [db a-id]
  (weak-cache/fetch view-caches db [:emap a-id]
                    #(group-by :e (attr-vec db :aevt a-id))))

;; Bridges for the query engine (kala.query) and the entity/pull readers,
;; which work on raw datoms with attribute ids. Ident translation lives at
;; each consumer's boundary: idents resolve to schema maps once per spec
;; (:id carries the attr id), and attr-ident maps back for output shaping.
(defn attribute [db ident]
  (get (::schema-by-ident db) ident))

(defn attr-datoms-vec [db a-id]
  (attr-vec db :aevt a-id))

(defn value-datoms
  "All datoms of attribute a-id whose value is v. Hash lookup; byte-array
  values fall back to a content-equality scan because arrays cannot be
  hash keys (their equality is content equality, their hash is identity)."
  [db a-id v]
  (if (bytes? v)
    (filter #(val= (:v ^Datom %) v) (attr-vec db :aevt a-id))
    (get (attr-vmap db a-id) v [])))

(defn entity-datoms
  "All datoms of attribute a-id for entity e."
  [db a-id e]
  (get (attr-emap db a-id) e []))

(defn index-slice [db index comps]
  (slice (get db (:set (index-specs index))) index comps))

(defn value-eq [x y]
  (val= x y))

;; Reverse reference syntax: :ns/_attr names the inverse of :ns/attr. Shared
;; by the query engine and the pull engine.
(defn reverse-attr? [a]
  (and (keyword? a) (str/starts-with? (name a) "_")))

(defn reverse-attr [a]
  (if (reverse-attr? a)
    (keyword (namespace a) (subs (name a) 1))
    a))

(defn many-attr? [attr]
  (= :db.cardinality/many (:cardinality attr)))

(defn resolve-entid
  "The :e-slot rule resolve-components applies: a keyword resolves through
  entid (nil when unresolvable), everything else is already an entity id.
  Public for kala.entity and kala.pull, whose entry points accept the same
  reference forms."
  [db x]
  (if (keyword? x) (entid db x) x))

(defn- attr-ident
  "An attr id's ident, or the raw id when no schema entry exists — the same
  fallback public-datom applies."
  [db a-id]
  (or (:ident (get (::schema-by-id db) a-id)) a-id))

(defn- ref-entid
  "Resolution for a ref slot's value: keywords must resolve to an entity id
  (:transact/unknown-ident otherwise); everything else passes through. On
  VAET the sought entity rides the ref-typed :v slot, so referrers keys
  reverse reads through this rule — the same one resolve-components
  applies."
  [db a-id v]
  (let [a (get (::schema-by-id db) a-id)]
    (if (and (keyword? v) a (= :db.type/ref (:value-type a)))
      (or (entid db v)
          (err :transact/unknown-ident "No entity with ident" {:ident v}))
      v)))

(defn referrers
  "Entity ids that reference e through attr — the VAET read keyed by
  ref-entid. Public for kala.entity and kala.pull, whose reverse reads are
  this one shape."
  [db attr e]
  (mapv :e (index-slice db :vaet [(ref-entid db (:id attr) e) (:id attr)])))

(defn materialize
  "An entity's datoms as a plain map keyed by attribute ident (the raw attr
  id when no schema entry exists), :db/id kept as the argument's original
  reference. Cardinality-many attributes collect into vectors. The optional
  value-fn transforms each (attr value) before it lands — kala.entity's
  touch expands components through it. Public for kala.entity and
  kala.pull, whose touch and wildcard reads are this one shape."
  ([db e]
   (materialize db e (fn [_attr v] v)))
  ([db e value-fn]
   (reduce (fn [m ^Datom d]
             (let [attr (lookup-attr db (.a d))
                   a (or (:ident attr) (.a d))
                   v (value-fn attr (.v d))]
               (if (many-attr? attr)
                 (update m a (fnil conj []) v)
                 (assoc m a v))))
           {:db/id e}
           (index-slice db :eavt [(resolve-entid db e)]))))

(defn public-datom
  "The public shape of a datom: attribute resolved to its ident. Public for
  kala.tx, which reports :tx-data in the same shape readers see."
  [db ^Datom d]
  (->Datom (.e d) (attr-ident db (.a d)) (.v d) (.tx d) (.added d)))

(defn- resolve-components [db index components]
  (let [comps (->> (vec components)
                   reverse
                   (drop-while nil?)
                   reverse
                   vec)
        n (count comps)
        _ (when (> n 3)
            (err :datoms/bad-components
                 "Index prefixes have at most e/a/v components" {:index index :components comps}))
        fields (vec (butlast (:fields (index-specs index))))
        a-res (fn [x]
                (:id (or (lookup-attr db x)
                         (err :transact/unknown-attribute "Unknown attribute" {:attribute x}))))
        e-res (fn [x] (resolve-entid db x))
        v-res (partial ref-entid db)
        ;; resolve :a and :e slots first (:v needs the :a slot's attr id)
        pass1 (reduce (fn [acc i]
                        (assoc acc i
                               (case (fields i)
                                 :a (a-res (comps i))
                                 :e (e-res (comps i))
                                 :v ::v-pending)))
                      (vec (repeat n nil))
                      (range n))
        a-id (first (keep-indexed (fn [i r] (when (= :a (fields i)) r)) pass1))]
    (reduce (fn [acc i]
              (if (= ::v-pending (acc i))
                (assoc acc i (v-res a-id (comps i)))
                acc))
            pass1
            (range n))))

;; raw datoms from the requested position to the end of the index
(defn- index-from [db index comps]
  (subseq (get db (:set (index-specs index))) >= (low-bound index comps)))

(defn datoms [db index components]
  (when-not (contains? index-specs index)
    (err :datoms/unknown-index "Unknown index" {:index index}))
  (let [comps (resolve-components db index components)]
    (->> (index-from db index comps)
         (filter (prefix-pred index comps))
         (map (partial public-datom db)))))

;; seek-datoms: like datoms but WITHOUT the prefix filter — everything from
;; the requested position to the end of the index, for paging scans.
(defn seek-datoms [db index components]
  (when-not (contains? index-specs index)
    (err :datoms/unknown-index "Unknown index" {:index index}))
  (map (partial public-datom db)
       (index-from db index (resolve-components db index components))))
