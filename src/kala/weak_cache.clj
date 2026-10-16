(ns kala.weak-cache
  "The value-keyed weak cache: ADR-0002's rule that caches live outside
  immutable db values, written once. A cache is an opaque synchronized weak
  table keyed by equality — equal db values legitimately share entries, and
  an entry's whole set dies with the last strong reference to its key.

  The invariant callers must keep: a stored value must not strongly refer to
  its own weak key, or the key is never reclaimable. A db value carries its
  storage handle, so a replay cache strips the handle before storing and
  re-assocs the live one on the way out; read views over a db value reference
  only datoms, so they need no stripping."
  (:import [java.util Collections WeakHashMap]))

(defn make
  "A fresh cache table."
  []
  (Collections/synchronizedMap (WeakHashMap.)))

(defn per-key
  "The per-key entry atom of key k — one atom holds all of the key's entries
  so they are replaced together and die together. Public for kala.weak-cache-test,
  which pins the dying-together property."
  [^java.util.Map cache k]
  (locking cache
    (or (.get cache k)
        (let [a (atom {})]
          (.put cache k a)
          a))))

(defn fetch
  "The cached value for [key k, entry-key ek], building it through build
  (a thunk) on first use. Normally built once per [k ek]: concurrent first
  touches may each build, last write wins. A falsy build result raises —
  a stored nil or false would rebuild on every fetch, silently."
  [cache k ek build]
  (let [a (per-key cache k)]
    (or (get @a ek)
        (let [v (build)]
          (when-not v
            ;; an internal invariant of kala's own caches, not a user-facing
            ;; error kind — a falsy entry would rebuild on every fetch
            (throw (ex-info "Weak-cache build returned a falsy value"
                            {:entry ek})))
          (swap! a assoc ek v)
          v))))
