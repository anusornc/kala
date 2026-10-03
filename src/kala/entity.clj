(ns kala.entity
  "Lazy entity navigation: keyword access over EAVT with entities returned
  for ref attributes, reverse navigation via :ns/_attr over VAET, and touch
  for expanding an entity (including its component tree) into a plain map."
  (:require [kala.core :as core]))

(defn- attr-values [db eid ident]
  (map :v (core/datoms db :eavt [eid ident])))

(declare lookup)

(deftype Entity [db eid]
  clojure.lang.ILookup
  (valAt [_ k] (lookup db eid k))
  (valAt [_ k default]
    (let [v (lookup db eid k)]
      (if (nil? v) default v)))
  clojure.lang.IFn
  (invoke [_ k] (lookup db eid k)))

(defn- lookup [db eid k]
  (cond
    (= k :db/id) eid
    (and (keyword? k) (core/reverse-attr? k))
    (let [ident (core/reverse-attr k)]
      (map (fn [d] (->Entity db (:e d)))
           (core/datoms db :vaet [eid ident])))
    :else
    (when-some [attr (core/attribute db k)]
      (let [many? (core/many-attr? attr)
            vs (attr-values db eid (:ident attr))]
        (if (= :db.type/ref (:value-type attr))
          (let [es (map (fn [v] (->Entity db v)) vs)]
            (if many? es (first es)))
          (if many? (vec vs) (first vs)))))))

(defn entity
  "A lazy navigable entity value."
  [db eid]
  (->Entity db eid))

;; Components expand to nested plain maps; other references stay entity ids,
;; keeping touch results serializable.
(declare touch)

(defn- touch-value [db ident v]
  (let [attr (core/attribute db ident)]
    (if (and (= :db.type/ref (:value-type attr)) (:component? attr))
      (touch (->Entity db v))
      v)))

(defn touch
  "Expand an entity into a plain map, loading its component tree."
  [^Entity e]
  (let [db (.db e)
        eid (.eid e)]
    (reduce (fn [m d]
              (let [a (:a d)
                    v (touch-value db a (:v d))]
                (if (core/many-attr? (core/attribute db a))
                  (update m a (fnil conj []) v)
                  (assoc m a v))))
            {:db/id eid}
            (core/datoms db :eavt [eid]))))
