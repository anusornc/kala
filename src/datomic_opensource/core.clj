(ns datomic-opensource.core
  "Datom representation, entity-id allocation, the bootstrap schema, index
  construction, and the transaction pipeline. No I/O here: storage sits behind
  the Storage protocol (seam 2). Reads go through immutable db values."
  (:require [clojure.string :as str]
            [datomic-opensource.storage :as storage]))

;; ---- errors -----------------------------------------------------------------

(defn- err [kind msg data]
  (throw (ex-info msg (merge {:datomic-opensource/error kind} data))))

;; ---- entity ids: partition / block / index packed into a positive long ------

(def ^:const ^:private partition-bits 42)

(def partitions
  {:db.part/db 0
   :db.part/user 1
   :db.part/tx 2})

(defn eid [partition block idx]
  (bit-or (bit-shift-left partition partition-bits)
          (bit-shift-left block 10)
          idx))

(defn eid->partition [e]
  (bit-shift-right e partition-bits))

(defn tx-eid [t]
  (eid (:db.part/tx partitions) 0 t))

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
  (fn [x y]
    (loop [ks ks]
      (if-not (seq ks)
        0
        (let [k (first ks)
              cx (k x)
              cy (k y)
              c (if (= k :v)
                  (cmp-val cx cy)
                  (cmp-field cx cy))]
          (if (zero? c) (recur (rest ks)) c))))))

;; One spec per index drives everything index-shaped below: the comparator
;; field order, the db key of its sorted set, and — derived from :fields
;; (everything before :tx, which every index sorts last) — prefix bounds,
;; prefix matching, and component resolution. Adding an index is one entry
;; here, not a case arm in five places.
(def ^:private index-specs
  {:eavt {:fields [:e :a :v :tx] :set ::eavt :cmp (cmp-by [:e :a :v :tx])}
   :aevt {:fields [:a :e :v :tx] :set ::aevt :cmp (cmp-by [:a :e :v :tx])}
   :avet {:fields [:a :v :e :tx] :set ::avet :cmp (cmp-by [:a :v :e :tx])}
   ;; Datomic's VAET holds only reference datoms (reverse-ref lookups)
   :vaet {:fields [:v :a :e :tx] :set ::vaet :cmp (cmp-by [:v :a :e :tx]) :ref-only true}})

(defn- ref-datom? [db ^Datom d]
  (= :db.type/ref (:value-type (get (::schema-by-id db) (.a d)))))


;; ---- value types ------------------------------------------------------------

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

;; Byte arrays need content equality; everything else is Clojure =.
(defn- val= [x y]
  (if (and (bytes? x) (bytes? y))
    (java.util.Arrays/equals ^bytes x ^bytes y)
    (= x y)))

;; ---- bootstrap schema -------------------------------------------------------

(def ^:private bootstrap-t 1)

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
  (into {} (map-indexed (fn [j k] [k (eid 0 0 (+ 100 j))]) enum-idents)))

(defn- attr-eid [ident]
  (eid 0 0 (attr-pos ident)))

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
        genesis-meta [[:db/add (tx-eid bootstrap-t) :db/txInstant (java.util.Date. 0)]]]
    (into attr-tuples (into enum-tuples genesis-meta))))

(defn- attr-map [{:db/keys [ident valueType cardinality unique isComponent]} id]
  {:id id
   :ident ident
   :value-type valueType
   :cardinality (or cardinality :db.cardinality/one)
   :unique unique
   :component? (boolean isComponent)})

(def ^:private next-part-0-idx (+ 100 (count enum-idents)))

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
            ::next-idx {0 next-part-0-idx
                        1 0}
            ::idents (merge (into {} (map (fn [spec] [(:db/ident spec) (attr-eid (:db/ident spec))]) meta-attrs))
                            (enum-eids))
            ::schema-by-ident schema-by-ident
            ::schema-by-id schema-by-id})))

(defn entid [db ident-or-eid]
  (if (int? ident-or-eid)
    ident-or-eid
    (get (::idents db) ident-or-eid)))

(defn- lookup-attr [db ident-or-id]
  (or (get (::schema-by-ident db) ident-or-id)
      (get (::schema-by-id db) ident-or-id)))

;; ---- index reads --------------------------------------------------------------

(defn- low-bound [index comps]
  (let [{:keys [fields]} (index-specs index)
        n (count comps)
        _ (when (> n 3)
            (err :datoms/bad-components
                 "Index prefixes have at most e/a/v components" {:index index :components comps}))
        field-pos (zipmap fields (range))
        slot (fn [field fill]
               (let [i (field-pos field)]
                 (if (< i n) (comps i) fill)))]
    (->Datom (slot :e MIN-V) (slot :a MIN-V) (slot :v MIN-V) (slot :tx Long/MIN_VALUE) true)))

(defn- prefix-pred [index comps]
  (let [ks (take (count comps) (:fields (index-specs index)))]
    (fn [d]
      (every? identity
              (map (fn [k c]
                     (if (= k :v)
                       (val= (k d) c)
                       (= (k d) c)))
                   ks comps)))))

(defn- slice [index-set index comps]
  (->> (subseq index-set >= (low-bound index comps))
       (take-while (prefix-pred index comps))))

;; Bridges for the query engine (datomic-opensource.query), which works on
;; raw datoms with attribute ids.
(defn attribute [db ident]
  (get (::schema-by-ident db) ident))

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

(defn- public-datom [db ^Datom d]
  (let [ident (or (:ident (get (::schema-by-id db) (.a d))) (.a d))]
    (->Datom (.e d) ident (.v d) (.tx d) (.added d))))

(defn- resolve-components [db index components]
  (let [comps (->> (vec components)
                   reverse
                   (drop-while nil?)
                   reverse
                   vec)
        n (count comps)
        fields (vec (butlast (:fields (index-specs index))))
        a-res (fn [x]
                (:id (or (lookup-attr db x)
                         (err :transact/unknown-attribute "Unknown attribute" {:attribute x}))))
        e-res (fn [x] (if (keyword? x) (entid db x) x))
        v-res (fn [a-id v]
                (let [a (get (::schema-by-id db) a-id)]
                  (if (and (keyword? v) a (= :db.type/ref (:value-type a)))
                    (or (entid db v)
                        (err :transact/unknown-ident "No entity with ident" {:ident v}))
                    v)))
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

(defn- current-datom [db e a-id v]
  (first (filter #(val= v (.v ^Datom %))
                 (slice (::eavt db) :eavt [e a-id]))))

(defn- current-datom-any-v [db e a-id]
  (first (slice (::eavt db) :eavt [e a-id])))

(defn- avet-holder [db a-id v]
  (when-some [^Datom d (first (slice (::avet db) :avet [a-id v]))]
    (.e d)))

;; ---- transaction pipeline ------------------------------------------------------

(def ^:private retract-entity-ops #{:db.fn/retractEntity :db/retractEntity})

;; The reserved tempid that names the transaction entity itself, so tx
;; metadata is ordinary map-form data: {:db/id "datomic.tx" :db/txInstant ...}
(def ^:private tx-tempid "datomic.tx")

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
        (let [attr (or (lookup-attr db k)
                       (err :transact/unknown-attribute "Unknown attribute" {:attribute k}))
              many? (= :db.cardinality/many (:cardinality attr))
              ref? (= :db.type/ref (:value-type attr))
              nest! (fn [cm]
                      (when-not (:component? attr)
                        (err :transact/nested-map-requires-component
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
    (err :transact/bad-op "Operations must be vectors" {:op op}))
  (cond
    (and (contains? #{:db/add :db/retract} (first op)) (= 4 (count op))) op
    (and (contains? retract-entity-ops (first op)) (= 2 (count op))) op
    :else (err :transact/bad-op
               "Operations must be [:db/add e a v], [:db/retract e a v], or [:db.fn/retractEntity e]"
               {:op op})))

(defn- resolve-static-e [db x]
  (cond
    (int? x) (if (pos? x)
               x
               (err :transact/bad-entity "Entity id must be positive" {:e x}))
    (keyword? x) (or (entid db x)
                     (err :transact/unknown-ident "No entity with ident" {:ident x}))
    :else (err :transact/bad-entity
               "Entity must be a positive id, tempid string, or ident" {:e x})))

(defn- resolve-v [db kind attr x ensure-temp]
  (if (not= :db.type/ref (:value-type attr))
    [db x]
    (cond
      (string? x) (if (= :db/add kind)
                    (let [[db' e] (ensure-temp db x)]
                      [db' e])
                    (err :transact/bad-entity "Cannot retract using a tempid" {:v x}))
      (keyword? x) [db (or (entid db x)
                           (err :transact/unknown-ident "No entity with ident" {:ident x}))]
      (int? x) (if (pos? x)
                 [db x]
                 (err :transact/bad-entity "Entity id must be positive" {:v x}))
      :else (if (= :db/add kind)
              (ensure-temp db x)
              (err :transact/bad-entity "Cannot retract using a tempid" {:v x})))))

(defn- update-indexes [db f ^Datom d]
  (reduce (fn [db {:keys [set ref-only]}]
            (if (and ref-only (= conj f) (not (ref-datom? db d)))
              db
              (update db set f d)))
          db
          (vals index-specs)))

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
        (if (val= (.v ^Datom current) v)
          [db []]
          (let [[db' rd] (drop-datom db current (->Datom e a-id (.v ^Datom current) tx false))
                [db'' ad] (add-datom db' (->Datom e a-id v tx true))]
            [db'' (into rd ad)]))
        (add-datom db (->Datom e a-id v tx true)))
      (if (current-datom db e a-id v)
        [db []]
        (add-datom db (->Datom e a-id v tx true))))))

(defn- apply-retract [db tx attr e v]
  (let [a-id (:id attr)]
    (if-let [current (current-datom db e a-id v)]
      (drop-datom db current (->Datom e a-id v tx false))
      [db []])))

(defn- schema-attr-ids [db pred]
  (into #{}
        (comp (filter (fn [[_ a]] (pred a)))
              (map key))
        (::schema-by-id db)))

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
          (let [subject (vec (slice (::eavt db) :eavt [e']))
                incoming (vec (for [a ref-ids
                                    d (slice (::avet db) :avet [a e'])]
                                d))
                retracted (distinct (concat subject incoming))
                db' (reduce (fn [db ^Datom d]
                              (update-indexes db disj d))
                            db retracted)
                kids (keep (fn [^Datom d]
                             (when (contains? component-ids (.a d)) (.v d)))
                           subject)
                retract-datoms (mapv (fn [^Datom d]
                                       (->Datom (.e d) (.a d) (.v d) tx false))
                                     retracted)]
            (recur db'
                   (into (rest stack) kids)
                   (conj processed e')
                   (into acc retract-datoms))))
        [db acc]))))

(defn- value-type-pred [attr]
  (or (get value-preds (:value-type attr))
      (err :transact/unknown-value-type
           "Unknown value type"
           {:attribute (:ident attr) :value-type (:value-type attr)})))

(defn- validate-value-type [attr v op]
  (when-not ((value-type-pred attr) v)
    (err :transact/bad-value-type
         "Value does not match the attribute's value type"
         {:attribute (:ident attr)
          :value-type (:value-type attr)
          :op op})))

(defn- resolve-e [db kind e unique-holder unique {:keys [temps ensure-temp upsert-claimants]} op]
  (if (or (string? e) (and (not (int? e)) (not (keyword? e))))
    ;; user tempid (string) or internal opaque tempid from map form
    (if (= :db/retract kind)
      (err :transact/bad-entity "Cannot retract using a tempid" {:op op})
      (let [allocated (get @temps e)]
        (cond
          (and unique-holder allocated (not= allocated unique-holder))
          (if (= :db.unique/identity unique)
            (err :transact/conflicting-upserts
                 "Tempid already resolves to a different entity" {:op op})
            (err :transact/unique-conflict
                 "Tempid's entity already holds a different value for a unique attribute" {:op op}))
          allocated [db allocated]
          (and unique-holder (= :db.unique/value unique))
          (err :transact/unique-conflict
               "Unique value already held by another entity" {:op op})
          unique-holder (do (when-let [claimant (get @upsert-claimants unique-holder)]
                              (when (not= claimant e)
                                (err :transact/conflicting-upserts
                                     "Two tempids upsert the same unique value" {:op op})))
                            (vswap! upsert-claimants assoc unique-holder e)
                            (vswap! temps assoc e unique-holder)
                            [db unique-holder])
          :else (ensure-temp db e))))
    (let [e' (resolve-static-e db e)]
      (when (and (= :db/add kind) unique-holder (not= e' unique-holder))
        (err :transact/unique-conflict
             "Unique value already held by another entity" {:op op}))
      [db e'])))

(defn- apply-op [db {:keys [tx temps ensure-temp upsert-claimants] :as txn} [kind e a v :as op]]
  (if (contains? retract-entity-ops kind)
    (let [[db' ds] (retract-entity db tx (resolve-static-e db e))]
      [db' ds])
    (let [attr (or (lookup-attr db a)
                   (err :transact/unknown-attribute "Unknown attribute" {:attribute a}))
          [db v] (resolve-v db kind attr v ensure-temp)
          _ (validate-value-type attr v op)
          unique (:unique attr)
          unique-holder (when (and unique (= :db/add kind))
                          (avet-holder db (:id attr) v))
          [db e] (resolve-e db kind e unique-holder unique txn op)
          [db' datoms] (if (= :db/add kind)
                         (apply-add db tx attr e v)
                         (apply-retract db tx attr e v))]
      [db' datoms])))

;; Schema is data: entities that gain a :db/ident become resolvable, and those
;; that also carry :db/valueType become attributes, re-derived from their datoms.
(defn- register-new-entities [db datoms]
  (if-some [ident-aid (get (::idents db) :db/ident)]
    (let [touched (into (sorted-set)
                        (comp (filter (fn [^Datom d] (= ident-aid (.a d))))
                              (map (fn [^Datom d] (.e d))))
                        datoms)]
      (reduce (fn [db e]
                (let [by-a (into {} (map (fn [^Datom d] [(.a d) (.v d)]))
                                 (slice (::eavt db) :eavt [e]))
                      ident (get by-a ident-aid)]
                  (if ident
                    (if-some [vt (get by-a (get (::idents db) :db/valueType))]
                      (let [idents-by-eid (into {} (map (fn [[k v]] [v k])) (::idents db))
                            attr {:id e
                                  :ident ident
                                  :value-type (get idents-by-eid vt)
                                  :cardinality (or (get idents-by-eid (get by-a (get (::idents db) :db/cardinality)))
                                                   :db.cardinality/one)
                                  :unique (get idents-by-eid (get by-a (get (::idents db) :db/unique)))
                                  :component? (true? (get by-a (get (::idents db) :db/isComponent)))}
                            _ (value-type-pred attr)
                            _ (when-not (contains? #{:db.cardinality/one :db.cardinality/many}
                                                   (:cardinality attr))
                                (err :transact/unknown-cardinality
                                     "Unknown cardinality"
                                     {:attribute ident :cardinality (:cardinality attr)}))
                            _ (when (and (:unique attr) (= :db.type/bytes (:value-type attr)))
                                (err :transact/unique-not-supported
                                     "Byte values cannot be unique" {:attribute ident}))
                            _ (when (and (:unique attr) (= :db.cardinality/many (:cardinality attr)))
                                (err :transact/unique-not-supported
                                     "Unique attributes must have cardinality one" {:attribute ident}))]
                        (-> db
                            (update ::idents assoc ident e)
                            (assoc-in [::schema-by-ident ident] attr)
                            (assoc-in [::schema-by-id e] attr)))
                      (update db ::idents assoc ident e))
                    db)))
              db
              touched))
    db))

(defn transact
  "Apply transaction data against the connection. Accepts list-form ops
  ([:db/add e a v] / [:db/retract e a v] / [:db.fn/retractEntity e]) and
  map-form entity maps, in any mix. Returns a tx report with :db-before,
  :db-after, :tx-data, and :tempids (including :db/current-tx and any
  \"datomic.tx\" metadata tempid). Single writer by construction here; the
  transactor (TT9) owns serialization across processes."
  [conn tx-data]
  (when-not (and (vector? tx-data) (every? #(or (vector? %) (map? %)) tx-data))
    (err :transact/bad-op "Transaction data must be a vector of operations or entity maps" {:tx-data tx-data}))
  (let [db-before @conn
        t (inc (::t db-before))
        tx (tx-eid t)
        temps (volatile! {tx-tempid tx})
        upsert-claimants (volatile! {})
        ensure-temp (fn [db tempid]
                      (if-some [e (get @temps tempid)]
                        [db e]
                        (let [n (get (::next-idx db) 1 0)
                              e (eid 1 (quot n 1024) (rem n 1024))]
                          (vswap! temps assoc tempid e)
                          [(assoc-in db [::next-idx 1] (inc n)) e])))
        txn {:tx tx
             :temps temps
             :ensure-temp ensure-temp
             :upsert-claimants upsert-claimants}
        ops (mapcat (fn [x]
                      (if (map? x)
                        (second (parse-entity-map db-before x))
                        [x]))
                    tx-data)
        [db-after datoms]
        (reduce (fn [[db acc] op]
                  (let [[db' ds] (apply-op db txn (validate-op op))]
                    [db' (into acc ds)]))
                [db-before []]
                ops)
        ;; Every transaction entity carries :db/txInstant unless the tx itself
        ;; supplied one via metadata. The stamp is part of the transaction: it
        ;; goes to the log, the indexes, and the report's :tx-data.
        [db-stamped tx-stamp]
        (if (current-datom-any-v db-after tx (attr-eid :db/txInstant))
          [db-after []]
          (let [attr (get (::schema-by-id db-after) (attr-eid :db/txInstant))
                [db' ds] (apply-add db-after tx attr tx (java.util.Date.))]
            [db' ds]))
        db-after (register-new-entities db-stamped datoms)
        final-db (assoc db-after ::t t)]
    (storage/append-log (::storage final-db)
                        {:t t
                         :datoms (mapv (fn [^Datom d] [(.e d) (.a d) (.v d) (.tx d) (.added d)])
                                       (into datoms tx-stamp))})
    (reset! conn final-db)
    {:db-before db-before
     :db-after final-db
     :tx-data (mapv (partial public-datom final-db) (into datoms tx-stamp))
     :tempids (-> (into {} (filter (fn [[k _]] (string? k)) @temps))
                  (assoc :db/current-tx tx))}))
