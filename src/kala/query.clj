(ns kala.query
  "Datalog query engine: pattern clauses with constants and variables, joins
  through ref attributes (forward and reverse), predicates and function
  expressions in :where, user-defined rules (including recursion) supplied
  through the % input, :in bindings (scalar, tuple, collection, relation,
  database), find specs (relation, collection, scalar, single string), and
  aggregates with :with-aware grouping."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kala.core :as core]
            [kala.pull :as pull]))

(def ^:private unbound ::unbound)


;; ---- built-ins for predicates and function expressions ------------------------

(def ^:private builtins
  {'< < '> > '<= <= '>= >= '= = 'not= not=
   '+ + '- - '* * 'quot quot 'mod mod 'inc inc 'dec dec
   'str str 'count count
   'lower-case str/lower-case 'upper-case str/upper-case})

;; ---- query parsing ----------------------------------------------------------

(defn- parse-vector-query [v]
  (loop [v v, cur nil, acc {}]
    (if-some [x (first v)]
      (if (keyword? x)
        (recur (next v) x (update acc x #(or % [])))
        (recur (next v) cur (update acc cur conj x)))
      acc)))

(defn- normalize-clause [c]
  (if (or (vector? c) (seq? c))
    c
    (core/err :query/bad-clause "Where clauses must be vectors or lists" {:clause c})))

(defn- sections->query [sections]
  {:find (:find sections)
   :in (:in sections)
   :with (:with sections)
   :where (mapv normalize-clause (:where sections))})

(defn- parse-query [q]
  (cond
    (string? q) (recur (edn/read-string q))
    :else (sections->query (if (map? q) q (parse-vector-query q)))))

;; ---- rules -------------------------------------------------------------------

;; rules: [[(name ?a ?b) clause+]] keyed by [name arity]
(defn- parse-rules [rules]
  (when-not (sequential? rules)
    (core/err :query/bad-rules "Rules must be a sequence of rule definitions" {:rules rules}))
  (reduce (fn [acc rule]
            (when-not (and (vector? rule) (seq? (first rule)) (>= (count rule) 2))
              (core/err :query/bad-rules
                   "A rule is [[(name args...) clause+]]" {:rule rule}))
            (let [[rname & args] (first rule)]
              (when-not (symbol? rname)
                (core/err :query/bad-rules "Rule head must start with a name" {:rule rule}))
              ;; store the full rule [(head) clause+] so solve-rule-body can
              ;; destructure head and clauses
              (update acc [rname (count args)] (fnil conj []) rule)))
          {}
          rules))

(defn- unify-args [args tuple bindings]
  (loop [b bindings, args args, tuple tuple]
    (if-not (seq args)
      b
      (let [arg (first args) v (first tuple)]
        (cond
          (= arg '_) (recur b (next args) (next tuple))
          (and (symbol? arg) (contains? b arg))
          (when (core/value-eq (get b arg) v)
            (recur b (next args) (next tuple)))
          (symbol? arg) (recur (assoc b arg v) (next args) (next tuple))
          :else (when (core/value-eq arg v)
                  (recur b (next args) (next tuple))))))))

(defn- match-rule-call [rule-sols call bindings]
  (let [key [(first call) (count (rest call))]
        tuples (get rule-sols key)]
    (when (nil? tuples)
      (core/err :query/unknown-rule "No rule defined" {:rule (first call) :arity (count (rest call))}))
    (keep #(unify-args (vec (rest call)) % bindings) tuples)))

;; ---- find specs ---------------------------------------------------------------

(def ^:private n-aggregates '#{min max rand sample})

(def ^:private aggregate-names
  '#{count count-distinct sum min max avg median variance std-dev rand sample})

(defn- parse-aggregate [expr]
  (let [[f & args] expr]
    (when-not (contains? aggregate-names f)
      (core/err :query/unknown-aggregate "Unknown aggregate" {:aggregate f}))
    (cond
      (and (contains? #{'rand 'sample} f) (= 1 (count args)))
      (core/err :query/bad-aggregate "Aggregate requires a count argument" {:expr expr})
      (and (contains? n-aggregates f) (= 2 (count args)))
      (let [[n v] args]
        (when-not (and (integer? n) (pos? n) (symbol? v))
          (core/err :query/bad-aggregate "Expected (f n ?var)" {:expr expr}))
        {:agg-fn f :n n :var v})
      (and (= 1 (count args)) (symbol? (first args)))
      {:agg-fn f :var (first args)}
      :else (core/err :query/bad-aggregate "Unsupported aggregate expression" {:expr expr}))))

(defn- parse-find [find-elems]
  (let [[x & more] find-elems]
    (cond
      (or (nil? find-elems) (empty? find-elems))
      (core/err :query/bad-find "Query needs :find" {})

      (every? symbol? find-elems)
      {:type :relation :vars find-elems}

      (and (= 1 (count find-elems)) (vector? x))
      (cond
        (= '... (last x))
        (if (symbol? (first x))
          {:type :coll :var (first x)}
          (core/err :query/bad-find "Collection spec requires a variable" {:find find-elems}))

        (and (= 1 (count x)) (symbol? (first x))) {:type :scalar :var (first x)}
        (and (= 2 (count x)) (symbol? (first x)) (= '. (second x)))
        {:type :scalar :var (first x)}

        (and (= 1 (count x)) (seq? (first x)))
        (let [expr (first x)]
          (when-not (and (= 2 (count expr)) (= (first expr) 'str) (symbol? (second expr)))
            (core/err :query/bad-find
                 "Only single-variable (str ?x) expressions are supported" {:find find-elems}))
          {:type :string :expr expr})

        :else (core/err :query/bad-find "Unsupported find specification" {:find find-elems}))

      (and (some list? find-elems)
           (not-any? vector? find-elems)
           (every? #(= 'pull (first %)) (filter list? find-elems)))
      {:type :pull
       :els (mapv (fn [el]
                    (if (list? el)
                      (let [[_ var pattern] el]
                        (when-not (symbol? var)
                          (core/err :query/bad-find "(pull ?var pattern) needs a variable" {:find find-elems}))
                        {:pull var :pattern pattern})
                      el))
                  find-elems)}

      (and (some list? find-elems)
           (not-any? vector? find-elems))
      {:type :aggregate :els (mapv (fn [el] (if (list? el) (parse-aggregate el) el)) find-elems)}

      (some list? find-elems)
      (core/err :query/bad-aggregate
           "Aggregates require the relation find form" {:find find-elems})

      :else (core/err :query/bad-find "Unsupported find specification" {:find find-elems}))))

;; ---- patterns ------------------------------------------------------------------

(defn- resolve-attr [db a]
  (cond
    (= a '_) {:any? true}
    (keyword? a)
    (let [ident (core/reverse-attr a)
          attr (core/require-attr db ident :query/unknown-attribute)]
      {:id (:id attr)
       :reverse (boolean (core/reverse-attr? a))
       :ref? (= :db.type/ref (:value-type attr))})
    :else (core/err :query/bad-attribute
               "Attribute must be a keyword or _" {:attribute a})))

(defn- pad3 [clause]
  (case (count clause)
    3 clause
    2 [(clause 0) (clause 1) '_]
    (core/err :query/bad-clause "Patterns must have 2 or 3 elements" {:clause clause})))

(defn- resolve-constant [db attr v]
  ;; keyword constants in ref position resolve to entity ids (error if unknown)
  (if (and v (keyword? v) (:ref? attr) (not (:any? attr)))
    (or (core/entid db v)
        (core/err :query/unknown-ident "Unknown ident in value position" {:ident v}))
    v))

(defn- prep-pattern [db clause]
  (let [[e a v] (pad3 clause)
        attr (resolve-attr db a)
        v (resolve-constant db attr v)
        ;; a forward pattern's :e constant is the same entity reference form
        ;; the other read doors take: a keyword resolves through entid,
        ;; raising :query/unknown-ident when unresolvable (#34). A reverse
        ;; pattern's entity ref rides the :v slot, already resolved above.
        e (if (and (not (:reverse attr)) (keyword? e))
            (or (core/entid db e)
                (core/err :query/unknown-ident "Unknown ident in entity position" {:ident e}))
            e)]
    (if (:reverse attr)
      [v (:id attr) e]
      [e (:id attr) v])))

(defn- slot-value [slot bindings]
  (cond
    (= slot '_) unbound
    (symbol? slot) (get bindings slot unbound)
    :else slot))

(defn- choose-datoms [db e a v]
  (cond
    (and (not= unbound e) a)
    (if (not= unbound v)
      (filter #(core/value-eq (:v %) v) (core/entity-datoms db a e))
      (core/entity-datoms db a e))
    (and a (not= unbound v)) (core/value-datoms db a v)
    a (core/attr-datoms-vec db a)
    (not= unbound e) (core/index-slice db :eavt [e])
    :else (core/index-slice db :eavt [])))

;; Per-datom slot work is specialized on what choose-datoms already enforced:
;; a bound e slot (variable already bound or a constant) is filtered by the
;; index lookup itself, and a bound v slot is filtered whenever the attribute
;; is concrete — so those checks are skipped per datom. Only wildcard
;; attributes (:a written as _) leave v unfiltered, forcing a live check.
(defn- match-pattern [db [es as vs] bindings]
  (let [e (slot-value es bindings)
        v (slot-value vs bindings)
        datoms (choose-datoms db e as v)
        e-need (if (and (symbol? es) (not= es '_) (not (contains? bindings es)))
                 :bind :skip)
        v-need (cond
                 (= vs '_) :skip
                 (and (symbol? vs) (not (contains? bindings vs))) :bind
                 (nil? as) :check
                 :else :skip)
        ;; [?x :attr ?x]: e and v are one variable — keep the datom only
        ;; when e == v, binding ?x once
        same-e-v (fn [ds]
                   (into [] (keep (fn [d]
                                    (when (core/value-eq (:e d) (:v d))
                                      (assoc bindings es (:e d))))
                                  ds)))]
    (case [e-need v-need]
      [:skip :skip] (into [] (map (constantly bindings)) datoms)
      [:skip :check] (into [] (keep (fn [d] (when (core/value-eq v (:v d)) bindings))) datoms)
      [:skip :bind] (into [] (map (fn [d] (assoc bindings vs (:v d))) datoms))
      [:bind :skip] (into [] (map (fn [d] (assoc bindings es (:e d))) datoms))
      [:bind :check] (if (= es vs)
                       (same-e-v datoms)
                       (into [] (keep (fn [d]
                                        (when (core/value-eq v (:v d))
                                          (assoc bindings es (:e d))))
                                      datoms)))
      [:bind :bind] (if (= es vs)
                      (same-e-v datoms)
                      (into [] (map (fn [d] (assoc bindings es (:e d) vs (:v d))) datoms))))))

;; ---- predicates and function expressions ----------------------------------------

(defn- arg-value [x bindings clause]
  (cond
    (= x '_) (core/err :query/bad-binding
                  "Wildcard not allowed in expression arguments" {:clause clause})
    (symbol? x) (if (contains? bindings x)
                  (get bindings x)
                  (core/err :query/unbound-var
                       "Argument not bound by an earlier clause" {:var x :clause clause}))
    :else x))

;; Returns a COLLECTION of bindings (like match-pattern), so mapcat can
;; concatenate results across clauses.
(defn- apply-fn-expr [f args result-slot clause bindings]
  (let [out (apply f (map #(arg-value % bindings clause) args))]
    (cond
      (or (nil? result-slot) (= result-slot '_)) (if out [bindings] nil)
      (symbol? result-slot)
      (cond
        (contains? bindings result-slot)
        (when (core/value-eq (get bindings result-slot) out) [bindings])
        :else [(assoc bindings result-slot out)])
      :else (core/err :query/bad-binding
                 "Expression result must bind a variable" {:slot result-slot :clause clause}))))

;; ---- evaluation --------------------------------------------------------------------

;; Clauses compile ONCE per query: pattern preparation (attribute and
;; constant resolution), builtin lookup, and rule-call validation happen
;; here, not per solution. A matcher is
;; (fn [rule-sols bindings] -> collection of bindings); pattern and
;; expression matchers ignore rule-sols.

(defn- clause-kind [clause]
  (cond
    (seq? clause) :rule-call
    (and (vector? clause) (seq? (first clause))) :fn-expr
    (vector? clause) :pattern
    :else :bad))

(defn- compile-clause [db clause rule-keys]
  (case (clause-kind clause)
    :rule-call
    (let [key [(first clause) (count (rest clause))]]
      ;; validate eagerly so :query/unknown-rule raises regardless of the
      ;; planned execution order
      (when-not (contains? rule-keys key)
        (core/err :query/unknown-rule "No rule defined"
             {:rule (first clause) :arity (count (rest clause))}))
      (fn [rule-sols bindings] (match-rule-call rule-sols clause bindings)))

    :fn-expr
    (let [[f-expr result-slot] clause
          [f & args] f-expr
          f' (or (get builtins f)
                 (core/err :query/unknown-builtin "Unknown function" {:fn f :clause clause}))]
      (fn [_ bindings] (apply-fn-expr f' args result-slot clause bindings)))

    :pattern
    (let [prepped (prep-pattern db clause)]
      (fn [_ bindings] (match-pattern db prepped bindings)))

    (core/err :query/bad-clause "Unsupported where clause" {:clause clause})))

(defn- run-matches [rule-sols matchers initial-solutions]
  (reduce (fn [solutions match]
            (mapcat (fn [b] (match rule-sols b)) solutions))
          initial-solutions
          matchers))

;; ---- clause ordering (ADR-0001) -------------------------------------------------
;; Clauses execute in an order computed from binding dependencies: predicates
;; and function expressions require their argument variables to be bound first;
;; pattern and rule-call clauses require nothing. Beyond that hard constraint
;; the order is a performance choice: a greedy pass prefers, among eligible
;; clauses, those that bind nothing new (pure filters) and then the most
;; anchored ones (constants, concrete attributes, already-bound variables),
;; with written order breaking ties. Solution multisets are order-invariant —
;; conjunctive evaluation with deterministic expressions — so the result set
;; is unaffected; row order is unspecified by contract.

(def ^:private filter-tier 1000000)

(defn- slot-var [s]
  (when (and (symbol? s) (not= s '_)) s))

(defn- clause-info [clause]
  (case (clause-kind clause)
    :rule-call
    (let [args (rest clause)]
      {:provides (into #{} (keep slot-var) args)
       :requires #{}
       :anchors (count (remove symbol? args))})

    :fn-expr
    (let [[f-expr result] clause
          [_ & args] f-expr]
      {:provides (if-let [v (slot-var result)] #{v} #{})
       :requires (into #{} (keep slot-var) args)
       :anchors 0})

    :pattern
    (let [[e a v] (pad3 clause)
          const-slot? (fn [s] (and (not (symbol? s)) (not= s '_)))]
      {:provides (into #{} (keep slot-var) [e v])
       :requires #{}
       :anchors (count (filter identity
                               [(const-slot? e) (const-slot? v) (not= a '_)]))})

    {:provides #{} :requires #{} :anchors 0}))

(defn- plan-order [matchers infos initial-vars]
  (let [score (fn [bound i]
                (let [{:keys [provides anchors]} (infos i)]
                  ;; filters (bind nothing new) outrank anchored expansions;
                  ;; the tier offset dominates any realistic anchor/var count
                  (+ (if (every? bound provides) filter-tier 0)
                     anchors
                     (count (filter bound provides)))))
        ;; strictly-greater replacement keeps the EARLIEST maximum — ties
        ;; fall back to written order (max-key would keep the last)
        best (fn [bound a b] (if (> (score bound b) (score bound a)) b a))]
    (loop [remaining (vec (range (count matchers)))
           bound initial-vars
           order []]
      (if-some [head (first remaining)]
        (let [eligible (filter (fn [idx] (every? bound (:requires (infos idx)))) remaining)
              pick (if (empty? eligible)
                     ;; nothing eligible means a predicate's arguments are
                     ;; bound by no clause: keep written order and let
                     ;; :query/unbound-var raise
                     head
                     (reduce (fn [a b] (best bound a b))
                             (first eligible)
                             (rest eligible)))]
          (recur (filterv #(not= % pick) remaining)
                 (into bound (:provides (infos pick)))
                 (conj order pick)))
        (mapv matchers order)))))

(defn- compile-and-plan [db clauses initial-vars rule-keys]
  (let [matchers (mapv #(compile-clause db % rule-keys) clauses)
        infos (mapv clause-info clauses)]
    (plan-order matchers infos initial-vars)))

(defn- run-where* [db rule-sols clauses initial-solutions]
  (run-matches rule-sols
               (compile-and-plan db clauses
                                 (into #{} (mapcat keys) initial-solutions)
                                 (into #{} (keys rule-sols)))
               initial-solutions))

(defn- compile-rule-body [db body rule-keys]
  (let [[head & clauses] body]
    {:rule-name (first head)
     :args (vec (rest head))
     :matchers (compile-and-plan db clauses #{} rule-keys)}))

(defn- solve-rule-body [db rule-sols compiled-body]
  (let [{:keys [rule-name args matchers]} compiled-body
        solutions (run-matches rule-sols matchers [{}])]
    (map (fn [b]
           (mapv (fn [a]
                   (if (contains? b a)
                     (get b a)
                     (core/err :query/bad-rules
                          "Rule head var not bound by the body" {:rule rule-name :var a})))
                 args))
         solutions)))

;; Naive fixed point: rule bodies see every tuple derived so far; tuples are
;; accumulated in sets, so each iteration either adds distinct tuples or the
;; derivation is stable. This terminates while bodies only derive values that
;; appear in the database; bodies that synthesize ever-fresh values (e.g. via
;; function expressions) exhaust the iteration fuel and raise instead.
(def ^:private rule-fuel 1000)

(defn- solve-rules [db rules]
  (let [rule-keys (into #{} (keys rules))
        compiled (into {}
                       (map (fn [[key bodies]]
                              [key (mapv #(compile-rule-body db % rule-keys) bodies)]))
                       rules)]
    (loop [sols (into {} (map (fn [k] [k #{}]) (keys rules)))
           fuel rule-fuel]
      (when (zero? fuel)
        (core/err :query/rule-overflow
             "Rule evaluation did not converge" {:iterations rule-fuel :rules (mapv first (keys rules))}))
      (let [sols' (reduce-kv (fn [acc key bodies]
                               (update acc key
                                       into (mapcat (fn [body] (solve-rule-body db acc body)) bodies)))
                             sols
                             compiled)]
        (if (= sols' sols) sols (recur sols' (dec fuel)))))))

;; ---- :in bindings ----------------------------------------------------------------

(defn- bind-input [binding-form value]
  (cond
    (symbol? binding-form) [{binding-form value}]
    (and (vector? binding-form) (= '... (last binding-form)))
    (let [vars (vec (butlast binding-form))]
      (when-not (= 1 (count vars))
        (core/err :query/bad-binding "Only single-var collection bindings are supported" {:binding binding-form}))
      (map (fn [x] {(first vars) x}) value))
    (and (= 1 (count binding-form)) (vector? (first binding-form)))
    (let [vars (first binding-form)]
      (map (fn [row] (apply hash-map (interleave vars row))) value))
    (and (vector? binding-form) (every? symbol? binding-form))
    [(zipmap binding-form value)]
    :else (core/err :query/bad-binding "Unsupported binding form" {:binding binding-form})))

;; ---- projection -------------------------------------------------------------------

(defn- dedupe-by-keys [key-vars solutions]
  (loop [solutions solutions, seen #{}, out []]
    (if-some [s (first solutions)]
      (let [k (mapv #(get s %) key-vars)]
        (if (contains? seen k)
          (recur (next solutions) seen out)
          (recur (next solutions) (conj seen k) (conj out s))))
      out)))

(defn- mean-of [values]
  (/ (double (reduce + 0 values)) (count values)))

(defn- variance-of [values]
  (let [mean (mean-of values)]
    (/ (double (reduce + (map (fn [v] (let [d (- v mean)] (* d d))) values)))
       (count values))))

(defn- extreme [cmp values]
  (reduce (fn [a b] (if (cmp a b) a b)) values))

(defn- agg-apply [{:keys [agg-fn n var]} values]
  (case agg-fn
    count (count values)
    count-distinct (count (distinct values))
    sum (if (every? integer? values)
          (reduce + 0 values)
          (double (reduce + 0 values)))
    min (if n (vec (take n (sort values))) (extreme #(neg? (compare %1 %2)) values))
    max (if n (vec (take n (reverse (sort values)))) (extreme #(pos? (compare %1 %2)) values))
    avg (mean-of values)
    median (let [vs (sort values) c (count vs)]
             (if (odd? c)
               (nth vs (quot c 2))
               (/ (double (+ (nth vs (dec (quot c 2))) (nth vs (quot c 2)))) 2)))
    variance (variance-of values)
    std-dev (Math/sqrt (variance-of values))
    rand (vec (take n (shuffle (distinct values))))
    sample (vec (take n (shuffle values)))))

(defn- dedupe-on [var-seqs solutions]
  (dedupe-by-keys (vec (distinct (apply concat var-seqs))) solutions))

(defn- project-aggregate [els solutions with-vars]
  (let [group-vars (vec (filter symbol? els))
        agg-els (vec (filter map? els))
        agg-vars (map :var agg-els)
        deduped (dedupe-on [group-vars agg-vars (vec with-vars)] solutions)
        groups (group-by (fn [s] (mapv #(get s %) group-vars)) deduped)]
    ;; group values are already the plain find els; append aggregate results
    (map (fn [[group-values group]]
           (into group-values
                 (map (fn [el]
                        (agg-apply el (keep #(get % (:var el)) group)))
                      agg-els)))
         groups)))

(defn- project-pull [db els solutions with-vars]
  (let [plain-vars (vec (filter symbol? els))
        pull-vars (mapv :pull (filter map? els))
        deduped (dedupe-on [plain-vars pull-vars (vec with-vars)] solutions)]
    (map (fn [s]
           (mapv (fn [el]
                   (cond
                     (symbol? el) (get s el)
                     (contains? el :pull) (pull/pull db (:pattern el) (get s (:pull el)))
                     :else nil))
                 els))
         deduped)))

(defn- project [db spec solutions with-vars]
  (cond
    (= :pull (:type spec))
    (project-pull db (:els spec) solutions with-vars)

    (= :aggregate (:type spec))
    (project-aggregate (:els spec) solutions with-vars)

    :else
    (let [find-vars (case (:type spec)
                      :relation (vec (:vars spec))
                      [(:var spec)])
          solutions (if (seq with-vars)
                      (dedupe-by-keys (into find-vars (vec with-vars)) solutions)
                      solutions)]
      (case (:type spec)
        :relation (let [tuple (fn [s] (mapv #(get s %) find-vars))]
                    (if (seq with-vars)
                      (map tuple solutions)
                      (vec (distinct (map tuple solutions)))))
        :coll (map #(get % (:var spec)) solutions)
        :scalar (first (map #(get % (:var spec)) solutions))
        :string (let [[_ var] (:expr spec)]
                  (str/join " " (map #(get % var) solutions)))))))

;; ---- entry point --------------------------------------------------------------------

(defn q
  "Run a Datalog query. `query` is a data-form vector/map or an EDN string;
  `inputs` supply the :in bindings in order — the database bound to $ and,
  when the :in clause names %, a sequence of rules."
  [query inputs]
  (let [{:keys [find in with where]} (parse-query query)
        _ (when (empty? find) (core/err :query/bad-query "Query needs :find" {}))
        _ (when (empty? where) (core/err :query/bad-query "Query needs :where" {}))
        spec (parse-find find)
        in (vec (or (not-empty in) ['$]))
        _ (when-not (= (count in) (count inputs))
            (core/err :query/arity
                 "Number of inputs does not match :in" {:expected (count in) :given (count inputs)}))
        pairs (map vector in inputs)
        db (or (some (fn [[f v]] (when (= f '$) v)) pairs)
               (core/err :query/no-db "Query requires a database bound to $" {}))
        _ (when-not (map? db)
            (core/err :query/no-db "$ must be a db value" {}))
        rules (when-let [rs (some (fn [[f v]] (when (= f '%) v)) pairs)]
                (parse-rules rs))
        rule-sols (if (seq rules) (solve-rules db rules) {})
        initial-solutions
        (reduce (fn [sols [form val]]
                  (if (contains? #{'$ '%} form)
                    sols
                    (for [s sols
                          b (bind-input form val)]
                      (merge s b))))
                [{}]
                pairs)
        solutions (run-where* db rule-sols where initial-solutions)]
    (project db spec solutions (vec with))))
