(ns kala.pull
  "Pull API: shape documents from entities with attribute specs, wildcards,
  nested and reverse map specs, and the :default / :missing / :limit /
  :as options."
  (:require [kala.core :as core]))


;; ---- pattern parsing -----------------------------------------------------------

(declare parse-pattern)

(defn- parse-attr-key [k]
  (let [reverse? (core/reverse-attr? k)]
    {:attr (core/reverse-attr k)
     :key k
     :reverse? reverse?
     :opts {}}))

(def ^:private pull-options #{:default :missing :limit :as})

;; wildcard marker inside a vector pattern

(defn- parse-attr-expr [x]
  (cond
    (keyword? x) (parse-attr-key x)
    (vector? x)
    (let [ident (first x)]
      (when-not (keyword? ident)
        (core/err :pull/bad-pattern "Attribute expression must start with a keyword" {:expr x}))
      (loop [spec (parse-attr-key ident)
             [o v & more] (rest x)]
        (if-not o
          spec
          (do (when-not (contains? pull-options o)
                (core/err :pull/bad-pattern "Unknown pull option" {:option o}))
              (recur (assoc-in spec [:opts o] v) more)))))
    :else (core/err :pull/bad-pattern "Unsupported pull element" {:element x})))

(defn- parse-map-spec [m]
  (when-not (= 1 (count m))
    (core/err :pull/bad-pattern "Map specs have exactly one entry" {:spec m}))
  (let [[k pattern] (first m)
        spec (parse-attr-expr k)]
    (assoc spec :pattern (parse-pattern pattern))))

(defn- parse-spec [x]
  (cond
    (= x '*) {:wildcard? true}
    (map? x) (parse-map-spec x)
    :else (parse-attr-expr x)))

(defn- parse-pattern [pat]
  (cond
    (= pat '*) {:wildcard? true}
    (keyword? pat) {:specs [(parse-spec pat)]}
    (vector? pat) {:specs (mapv parse-spec pat)}
    (map? pat) {:specs [(parse-map-spec pat)]}
    :else (core/err :pull/bad-pattern "Unsupported pull pattern" {:pattern pat})))

;; ---- evaluation ------------------------------------------------------------------

(declare pull-forward pull-reverse)

;; Shared tail for every attr spec: absent-value options and keying by the
;; original spec form (:ns/_attr for reverse specs, :as override if given).
(defn- finish-attr [spec absent? v]
  (let [{:keys [opts]} spec
        out-key (or (:as opts) (:key spec))]
    (cond
      (and absent? (contains? opts :default)) [out-key (:default opts)]
      (and absent? (contains? opts :missing)) [out-key (:missing opts)]
      absent? nil
      :else [out-key v])))

(defn- pull-entity* [db pattern e]
  (if (:wildcard? pattern)
    (core/materialize db e)
    (reduce (fn [m spec]
              (if (:wildcard? spec)
                (merge m (core/materialize db e))
                (if-some [[k v] (if (:reverse? spec)
                                  (pull-reverse db e spec)
                                  (pull-forward db e spec))]
                  (assoc m k v)
                  m)))
            {:db/id e}
            (:specs pattern))))

(defn- pull-forward [db e spec]
  (let [{:keys [attr opts pattern]} spec
        attr-map (core/require-attr db attr :pull/unknown-attribute)
        vs (map :v (core/entity-datoms db (:id attr-map) (core/resolve-entid db e)))
        vs (if-some [lim (:limit opts)] (take lim vs) vs)
        vals (if pattern
               (mapv (fn [v] (pull-entity* db pattern v)) vs)
               (vec vs))
        absent? (empty? vals)
        v (cond absent? nil
                (core/many-attr? attr-map) vals
                :else (first vals))]
    (finish-attr spec absent? v)))

(defn- pull-reverse [db e spec]
  (let [{:keys [attr opts pattern]} spec
        attr-map (core/require-attr db attr :pull/unknown-attribute)
        referrers (core/referrers db attr-map e)
        referrers (if-some [lim (:limit opts)] (take lim referrers) referrers)
        vals (if pattern
               (mapv (fn [r] (pull-entity* db pattern r)) referrers)
               (vec referrers))]
    (finish-attr spec (empty? vals) vals)))

(defn pull
  "Shape one entity into a document per the pull pattern. Entities without
  datoms pull as nil — including a keyword eid that resolves to nothing."
  [db pattern eid]
  (let [resolved (core/resolve-entid db eid)]
    (when (and resolved (seq (core/index-slice db :eavt [resolved])))
      (pull-entity* db (parse-pattern pattern) eid))))
