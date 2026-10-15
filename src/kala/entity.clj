(ns kala.entity
  "Lazy entity navigation: keyword access through the cached bridge with
  entities returned for ref attributes, reverse navigation via :ns/_attr
  over VAET, and touch for expanding an entity (including its component
  tree) into a plain map. Idents resolve to schema maps at each read; the
  bridge returns raw attr-id datoms."
  (:require [kala.core :as core]))

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
    (let [ident (core/reverse-attr k)
          attr (core/require-attr db ident :transact/unknown-attribute)]
      (map (fn [e] (->Entity db e))
           (core/referrers db attr eid)))
    :else
    (when-some [attr (core/attribute db k)]
      (let [many? (core/many-attr? attr)
            vs (map :v (core/entity-datoms db (:id attr) (core/resolve-entid db eid)))]
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

(defn touch
  "Expand an entity into a plain map, loading its component tree."
  [^Entity e]
  (let [db (.db e)]
    (core/materialize db (.eid e)
                      (fn [attr v]
                        (if (and (= :db.type/ref (:value-type attr)) (:component? attr))
                          (touch (->Entity db v))
                          v)))))
