(ns kala.backup
  "Backup and migration over any Storage adapter, built on the protocol's
  log-scan half: export the log as EDN (one entry per line), import it into
  fresh or continuing storage, copy between backends, and verify that two
  storages hold the same log.

  Log entries are the raw storage shape {:t t :datoms [[e a v tx added] ...]}
  — entity ids and attr ids as plain longs, no ident resolution — so a
  restored store replays exactly like the original and entity ids stay
  stable. Value classes without a readable EDN literal ride tagged
  literals: #kala/float, #kala/bytes (base64), #kala/uri. Everything else
  (instants, uuids, bigints, keywords, ...) round-trips through native EDN;
  an unknown tag fails the import rather than silently corrupting a value.

  The offline story stays true too: copying a quiesced file store's
  directory is a valid backup — see docs/backup-and-migration.md for both
  procedures."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kala.core :as core]
            [kala.storage :as storage])
  (:import [java.io File Reader Writer]
           [java.util Arrays Base64]))

;; ---- value encoding ----------------------------------------------------------

(def ^:private read-bytes
  #(.decode (Base64/getDecoder) ^String %))

(def ^:private readers
  "The EDN data-readers an exported file may carry: the value classes whose
  stored shape has no readable EDN literal. Unknown tags are refused — a
  silently wrong value is worse than a failed import."
  {'kala/float #(Float/parseFloat ^String %)
   'kala/bytes read-bytes
   'kala/uri   #(java.net.URI. ^String %)})

(defn- encode-v [v]
  (cond
    (instance? Float v) (tagged-literal 'kala/float (Float/toString v))
    ;; float renders 1.5 readably but reads back as a Double — the stored
    ;; class is part of the value contract (:db.type/float validates Float)
    (bytes? v)          (tagged-literal 'kala/bytes (.encodeToString (Base64/getEncoder) v))
    (instance? java.net.URI v) (tagged-literal 'kala/uri (str v))
    :else v))

(defn- decode-v [v]
  (if (instance? clojure.lang.TaggedLiteral v)
    (let [{:keys [tag form]} v]
      (if-some [f (get readers tag)]
        (f form)
        (core/err :backup/unknown-tag
                  "Export carries a tag no reader knows"
                  {:tag tag})))
    v))

(defn- encode-entry [{:keys [t datoms]}]
  {:t t
   :datoms (mapv (fn [[e a v tx added]] [e a (encode-v v) tx added]) datoms)})

(defn- decode-entry [entry]
  (when-not (and (map? entry) (int? (:t entry)) (vector? (:datoms entry)))
    (core/err :backup/bad-entry
              "Backup line is not a log entry"
              {:entry (pr-str entry)}))
  {:t (:t entry)
   :datoms (mapv (fn [[e a v tx added]] [e a (decode-v v) tx added]) (:datoms entry))})

;; ---- IO plumbing ---------------------------------------------------------------

(defn- with-writer
  "A path, file, or stream we open and close; a Writer we borrow."
  [x f]
  (if (instance? Writer x)
    (f x)
    (with-open [w (io/writer x)] (f w))))

(defn- with-reader
  [x f]
  (if (instance? Reader x)
    (f x)
    (with-open [r (io/reader x)] (f r))))

;; ---- the four operations -------------------------------------------------------

(defn export-log
  "Write the storage log as EDN, one entry per line, to out (a path, file,
  stream, or Writer). :from-t/:to-t bound the range; the default is the
  whole log. Returns {:entries n :from-t :to-t} (nil bounds for an empty
  log). The output is the migration format: import-log consumes it, and a
  re-export of an imported store is byte-identical."
  ([storage out] (export-log storage out {}))
  ([storage out {:keys [from-t to-t] :or {from-t 0 to-t Long/MAX_VALUE}}]
   (with-writer out
     (fn [^Writer w]
       (let [entries (storage/log-scan storage from-t to-t)]
         (doseq [entry entries]
           (.write w (str (pr-str (encode-entry entry)) "\n")))
         {:entries (count entries)
          :from-t (:t (first entries))
          :to-t (:t (last entries))})))))

(defn import-log
  "Append log entries read from in (a path, file, stream, or Reader) into
  storage. Entries append in file order and the backend's monotonicity
  guard holds the line: importing over a store whose last t reaches an
  entry's t raises :storage/non-monotonic-t, so an overlap can never
  silently overwrite. Returns {:entries n :from-t :to-t}."
  [storage in]
  (with-reader in
    (fn [^Reader r]
      ;; line-seq wants a BufferedReader; the borrowed-reader path may hand
      ;; us any Reader
      (reduce (fn [acc line]
                (if (re-matches #"\s*" line)
                  acc
                  ;; :default keeps unknown tags as literals so decode-entry
                  ;; raises the store's own :backup/unknown-tag kind instead
                  ;; of the EDN reader's generic refusal
                  (let [entry (decode-entry (edn/read-string {:readers readers
                                                              :default tagged-literal}
                                                             line))]
                    (storage/append-log storage entry)
                    (-> acc
                        (update :entries inc)
                        (assoc :from-t (or (:from-t acc) (:t entry))
                               :to-t (:t entry))))))
              {:entries 0 :from-t nil :to-t nil}
              (line-seq (java.io.BufferedReader. r))))))

(defn copy-storage
  "Copy the complete log of one storage into another through a temporary
  export file — migration between backends, restore into fresh storage.
  The target continues its own t sequence: copying over a non-empty store
  whose last t reaches an entry's t raises :storage/non-monotonic-t.
  Returns the import report."
  [from to]
  (let [tmp (File/createTempFile "kala-backup" ".edn")]
    (try
      (export-log from tmp)
      (import-log to tmp)
      (finally
        (.delete tmp)))))

(defn- v=
  "Value equality along the backup seam: byte arrays compare by content —
  two independently loaded logs hold distinct array instances."
  [x y]
  (if (and (bytes? x) (bytes? y))
    (Arrays/equals ^bytes x ^bytes y)
    (= x y)))

(defn- datoms= [[e1 a1 v1 tx1 ad1] [e2 a2 v2 tx2 ad2]]
  (and (= e1 e2) (= a1 a2) (= tx1 tx2) (= ad1 ad2) (v= v1 v2)))

(defn- entries= [ea eb]
  (and (= (:t ea) (:t eb))
       (= (count (:datoms ea)) (count (:datoms eb)))
       (every? (fn [[x y]] (datoms= x y)) (map vector (:datoms ea) (:datoms eb)))))

(defn verify-storage
  "Do two storages hold the same log (within :from-t/:to-t, default the
  whole log)? Returns {:match? true :entries n :from-t :to-t}, or
  {:match? false :reason :entry-count | :t-sequence | :entry-content} with
  the first divergence named — :at-t where the content or sequence splits,
  :expected/:actual counts for the count case. Verification reads both
  logs in full; it never mutates either side."
  ([a b] (verify-storage a b {}))
  ([a b {:keys [from-t to-t] :or {from-t 0 to-t Long/MAX_VALUE}}]
   (let [ea (storage/log-scan a from-t to-t)
         eb (storage/log-scan b from-t to-t)]
     (cond
       (not= (count ea) (count eb))
       {:match? false :reason :entry-count
        :expected (count ea) :actual (count eb)}

       (not= (mapv :t ea) (mapv :t eb))
       {:match? false :reason :t-sequence
        :at-t (first (for [[x y] (map vector (map :t ea) (map :t eb))
                           :when (not= x y)] x))}

       :else
       (if-some [at-t (first (for [[x y] (map vector ea eb)
                                   :when (not (entries= x y))]
                               (:t x)))]
         {:match? false :reason :entry-content :at-t at-t}
         {:match? true
          :entries (count ea)
          :from-t (:t (first ea))
          :to-t (:t (last ea))})))))
