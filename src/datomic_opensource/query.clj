(ns datomic-opensource.query
  "Datalog query engine: pattern clauses with constants and variables, joins
  through ref attributes (forward and reverse), :in bindings (scalar, tuple,
  collection, relation, database), and find specs (relation, collection,
  scalar, single string). Predicates, function expressions, rules, and
  aggregates arrive in TT5."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic-opensource.core :as core]))

(def ^:private unbound ::unbound)

(defn- err [kind msg data]
  (throw (ex-info msg (merge {:datomic-opensource/error kind} data))))

;; ---- query parsing ----------------------------------------------------------

(defn- parse-vector-query [v]
  (loop [v v, cur nil, acc {}]
    (if-some [x (first v)]
      (if (keyword? x)
        (recur (next v) x (update acc x #(or % [])))
        (recur (next v) cur (update acc cur conj x)))
      acc)))

(defn- normalize-clause [c]
  (cond
    (vector? c) c
    (seq? c) (vec c)
    :else (err :query/bad-clause "Where clauses must be vectors" {:clause c})))

(defn- sections->query [sections]
  {:find (:find sections)
   :in (:in sections)
   :with (:with sections)
   :where (mapv normalize-clause (:where sections))})

(defn- parse-query [q]
  (cond
    (string? q) (recur (edn/read-string q))
    :else (sections->query (if (map? q) q (parse-vector-query q)))))

;; ---- find specs ---------------------------------------------------------------

(defn- parse-find [find-elems]
  (let [[x & more] find-elems]
    (cond
      (or (nil? find-elems) (empty? find-elems))
      (err :query/bad-find "Query needs :find" {})

      (every? symbol? find-elems)
      {:type :relation :vars find-elems}

      (and (= 1 (count find-elems)) (vector? x))
      (cond
        (= '... (last x))
        (if (symbol? (first x))
          {:type :coll :var (first x)}
          (err :query/bad-find "Collection spec requires a variable" {:find find-elems}))

        (and (= 1 (count x)) (symbol? (first x))) {:type :scalar :var (first x)}
        (and (= 2 (count x)) (= '. (second x))) {:type :scalar :var (first x)}

        (and (= 1 (count x)) (seq? (first x)))
        (let [expr (first x)]
          (when-not (and (= 2 (count expr)) (= (first expr) 'str) (symbol? (second expr)))
            (err :query/bad-find
                 "Only single-variable (str ?x) expressions are supported" {:find find-elems}))
          {:type :string :expr expr})

        :else (err :query/bad-find "Unsupported find specification" {:find find-elems}))

      :else (err :query/bad-find "Unsupported find specification" {:find find-elems}))))

;; ---- patterns ------------------------------------------------------------------

(defn- reverse-attr? [a]
  (and (keyword? a) (str/starts-with? (name a) "_")))

(defn- resolve-attr [db a]
  (cond
    (= a '_) {:any? true}
    (keyword? a)
    (let [ident (if (reverse-attr? a)
                  (keyword (namespace a) (subs (name a) 1))
                  a)
          attr (or (core/attribute db ident)
                   (err :query/unknown-attribute "Unknown attribute" {:attribute a}))]
      {:id (:id attr)
       :reverse (boolean (reverse-attr? a))
       :ref? (= :db.type/ref (:value-type attr))})
    :else (err :query/bad-attribute
               "Attribute must be a keyword or _" {:attribute a})))

(defn- pad3 [clause]
  (case (count clause)
    3 clause
    2 [(clause 0) (clause 1) '_]
    (err :query/bad-clause "Patterns must have 2 or 3 elements" {:clause clause})))

(defn- resolve-constant [db attr v]
  ;; keyword constants in ref position resolve to entity ids (error if unknown)
  (if (and v (keyword? v) (:ref? attr) (not (:any? attr)))
    (or (core/entid db v)
        (err :query/unknown-ident "Unknown ident in value position" {:ident v}))
    v))

(defn- prep-pattern [db clause]
  (let [[e a v] (pad3 clause)
        attr (resolve-attr db a)
        v (resolve-constant db attr v)]
    (if (:reverse attr)
      [v (:id attr) e]
      [e (:id attr) v])))

(defn- slot-value [slot bindings]
  (cond
    (= slot '_) unbound
    (symbol? slot) (get bindings slot unbound)
    :else slot))

(defn- bind-slot [slot val bindings]
  (cond
    (= slot '_) bindings
    (symbol? slot) (if (contains? bindings slot)
                     (when (core/value-eq (get bindings slot) val) bindings)
                     (assoc bindings slot val))
    :else (when (core/value-eq slot val) bindings)))

(defn- choose-datoms [db e a v]
  (cond
    (and (not= unbound e) a)
    (if (not= unbound v)
      (core/index-slice db :eavt [e a v])
      (core/index-slice db :eavt [e a]))
    (and a (not= unbound v)) (core/index-slice db :avet [a v])
    a (core/index-slice db :aevt [a])
    (not= unbound e) (core/index-slice db :eavt [e])
    :else (core/index-slice db :eavt [])))

(defn- match-pattern [db [es as vs] bindings]
  (let [e (slot-value es bindings)
        v (slot-value vs bindings)
        datoms (choose-datoms db e as v)]
    (keep (fn [d]
            (when-some [b1 (bind-slot es (:e d) bindings)]
              (bind-slot vs (:v d) b1)))
          datoms)))

;; ---- :in bindings ----------------------------------------------------------------

(defn- bind-input [binding-form value]
  (cond
    (= binding-form '$) [{'$ value}]
    (symbol? binding-form) [{binding-form value}]
    (and (vector? binding-form) (= '... (last binding-form)))
    (let [vars (vec (butlast binding-form))]
      (when-not (= 1 (count vars))
        (err :query/bad-binding "Only single-var collection bindings are supported" {:binding binding-form}))
      (map (fn [x] {(first vars) x}) value))
    (and (= 1 (count binding-form)) (vector? (first binding-form)))
    (let [vars (first binding-form)]
      (map (fn [row] (apply hash-map (interleave vars row))) value))
    (and (vector? binding-form) (every? symbol? binding-form))
    [(zipmap binding-form value)]
    :else (err :query/bad-binding "Unsupported binding form" {:binding binding-form})))

;; ---- evaluation --------------------------------------------------------------------

(defn- run-where [db clauses initial-solutions]
  (reduce (fn [solutions clause]
            (let [pattern (prep-pattern db clause)]
              (mapcat (fn [b] (match-pattern db pattern b)) solutions)))
          initial-solutions
          clauses))

(defn- dedupe-by-keys [key-vars solutions]
  (loop [solutions solutions, seen #{}, out []]
    (if-some [s (first solutions)]
      (let [k (mapv #(get s %) key-vars)]
        (if (contains? seen k)
          (recur (next solutions) seen out)
          (recur (next solutions) (conj seen k) (conj out s))))
      out)))

(defn- project [spec solutions with-vars]
  (let [find-vars (case (:type spec)
                    :relation (vec (:vars spec))
                    [(:var spec)])
        solutions (if (seq with-vars)
                    (dedupe-by-keys (into find-vars (vec with-vars)) solutions)
                    solutions)]
    (case (:type spec)
      :relation (let [tuples (map (fn [s] (mapv #(get s %) find-vars)) solutions)]
                  (if (seq with-vars) tuples (distinct tuples)))
      :coll (map #(get % (:var spec)) solutions)
      :scalar (first (map #(get % (:var spec)) solutions))
      :string (let [[_ var] (:expr spec)]
                (str/join " " (map #(get % var) solutions))))))

(defn q
  "Run a Datalog query. `query` is a data-form vector/map or an EDN string;
  `inputs` supply the :in bindings in order, the first usually being the db
  bound to $."
  [query inputs]
  (let [{:keys [find in with where]} (parse-query query)
        _ (when (empty? find) (err :query/bad-query "Query needs :find" {}))
        _ (when (empty? where) (err :query/bad-query "Query needs :where" {}))
        in (vec (or (not-empty in) ['$]))
        _ (when-not (= (count in) (count inputs))
            (err :query/arity
                 "Number of inputs does not match :in" {:expected (count in) :given (count inputs)}))
        pairs (map vector in inputs)
        db (or (some (fn [[f v]] (when (= f '$) v)) pairs)
               (err :query/no-db "Query requires a database bound to $" {}))
        _ (when-not (map? db)
            (err :query/no-db "$ must be a db value" {}))
        initial-solutions
        (reduce (fn [sols [form val]]
                  (if (= form '$)
                    sols
                    (for [s sols
                          b (bind-input form val)]
                      (merge s b))))
                [{}]
                pairs)
        solutions (run-where db where initial-solutions)]
    (project (parse-find find) solutions (vec with))))
