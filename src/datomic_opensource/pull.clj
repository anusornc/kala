(ns datomic-opensource.pull
  "Pull API: shape documents from entities with attribute specs, wildcards,
  nested and reverse map specs, and the :default / :missing / :limit /
  :as options."
  (:require [datomic-opensource.core :as core]))

(defn- err [kind msg data]
  (throw (ex-info msg (merge {:datomic-opensource/error kind} data))))

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
        (err :pull/bad-pattern "Attribute expression must start with a keyword" {:expr x}))
      (loop [spec (parse-attr-key ident)
             [o v & more] (rest x)]
        (if-not o
          spec
          (do (when-not (contains? pull-options o)
                (err :pull/bad-pattern "Unknown pull option" {:option o}))
              (recur (assoc-in spec [:opts o] v) more)))))
    :else (err :pull/bad-pattern "Unsupported pull element" {:element x})))

(defn- parse-map-spec [m]
  (when-not (= 1 (count m))
    (err :pull/bad-pattern "Map specs have exactly one entry" {:spec m}))
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
    :else (err :pull/bad-pattern "Unsupported pull pattern" {:pattern pat})))

;; ---- evaluation ------------------------------------------------------------------

(declare pull-forward pull-reverse wildcard-attr)

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
    (wildcard-attr db e)
    (reduce (fn [m spec]
              (if (:wildcard? spec)
                (merge m (wildcard-attr db e))
                (if-some [[k v] (if (:reverse? spec)
                                  (pull-reverse db e spec)
                                  (pull-forward db e spec))]
                  (assoc m k v)
                  m)))
            {:db/id e}
            (:specs pattern))))

(defn- pull-forward [db e spec]
  (let [{:keys [attr opts pattern]} spec
        _ (when-not (core/attribute db attr)
            (err :pull/unknown-attribute "Unknown attribute" {:attribute attr}))
        vs (map :v (core/datoms db :eavt [e attr]))
        vs (if-some [lim (:limit opts)] (take lim vs) vs)
        vals (if pattern
               (mapv (fn [v] (pull-entity* db pattern v)) vs)
               (vec vs))
        absent? (empty? vals)
        v (cond absent? nil
                (core/many-attr? (core/attribute db attr)) vals
                :else (first vals))]
    (finish-attr spec absent? v)))

(defn- pull-reverse [db e spec]
  (let [{:keys [attr opts pattern]} spec
        _ (when-not (core/attribute db attr)
            (err :pull/unknown-attribute "Unknown attribute" {:attribute attr}))
        referrers (map :e (core/datoms db :vaet [e attr]))
        referrers (if-some [lim (:limit opts)] (take lim referrers) referrers)
        vals (if pattern
               (mapv (fn [r] (pull-entity* db pattern r)) referrers)
               (vec referrers))]
    (finish-attr spec (empty? vals) vals)))

(defn- wildcard-attr [db e]
  (reduce (fn [m d]
            (let [a (:a d)]
              (cond
                (core/many-attr? (core/attribute db a))
                (update m a (fnil conj []) (:v d))
                :else (assoc m a (:v d)))))
          {:db/id e}
          (core/datoms db :eavt [e])))

(defn pull
  "Shape one entity into a document per the pull pattern. Entities without
  datoms pull as nil."
  [db pattern eid]
  (when (seq (core/datoms db :eavt [eid]))
    (pull-entity* db (parse-pattern pattern) eid)))
