(ns kala.storage.file
  "Durable directory-backed Storage adapter. Layout:

     <root>/segments/<url-encoded-id>   content files
     <root>/log/<zero-padded-t>.dat     one file per log entry

  Appends write to a temp file, fsync, atomically rename, then fsync the
  parent directory — durability before ack, surviving both process kill and
  machine crash. A fresh adapter over the same directory sees every acked
  entry, so crash recovery is: open, rebuild, continue.

  Segment ids round-trip through URL-encoding of (str id); note that
  filesystems with case-insensitive names (e.g. macOS APFS defaults) will
  conflate ids differing only by case."
  (:require [clojure.java.io :as io]
            [kala.storage :as storage])
  (:import [java.io File FileInputStream FileOutputStream ObjectInputStream ObjectOutputStream]
           [java.nio.channels FileChannel]
           [java.nio.file StandardOpenOption]))

(defn- encode-id [id]
  (java.net.URLEncoder/encode (str id) "UTF-8"))

(defn- segment-file [^File root id]
  (io/file root "segments" (encode-id id)))

(def ^:private log-entry-re #"\A(\d{19})\.dat\Z")

(defn- entry-t [^File f]
  (when-some [[_ digits] (re-matches log-entry-re (.getName f))]
    (Long/parseLong digits)))

(defn- log-file [^File root t]
  ;; Locale/ROOT: the default locale may render digits non-ASCII, which the
  ;; entry-name regex would then never match at recovery time
  (io/file root "log" (String/format java.util.Locale/ROOT "%019d.dat" (to-array [t]))))

(defn- read-obj [^File f]
  (with-open [in (ObjectInputStream. (FileInputStream. f))]
    (.readObject in)))

(defn- fsync-dir [^File dir]
  ;; POSIX directory fsync so the renamed entry survives machine crash;
  ;; unsupported platforms (e.g. Windows) are best-effort no-ops
  (try
    (let [ch (FileChannel/open (.toPath dir) (make-array StandardOpenOption 0))]
      (try (.force ch true) (finally (.close ch))))
    (catch Exception _)))

;; write temp -> fsync -> atomic rename -> fsync parent dir: callers observe
;; a complete file or none at all, never a partial write
(defn- write-obj [^File f obj]
  (let [tmp (File/createTempFile "seg" ".tmp" (.getParentFile f))]
    (try
      (let [fos (FileOutputStream. tmp)]
        (try
          (let [out (ObjectOutputStream. fos)]
            (.writeObject out obj)
            (.flush out)
            (.sync (.getFD fos))
            (.close out))
          (finally
            (.close fos))))
      (when-not (.renameTo tmp f)
        (throw (ex-info "Atomic rename failed"
                        {:kala/error :storage/rename-failed
                         :target (str f)})))
      (fsync-dir (.getParentFile f))
      (catch Exception e
        (io/delete-file tmp true)
        (throw e)))))

(defn- log-ts [^File root]
  (->> (.listFiles (io/file root "log"))
       (keep entry-t)
       sort
       vec))

(defn- checkpoint-base-t
  "The t a stored checkpoint claims as its base, or 0 — the append floor a
  fully pruned log loses. A payload that is not a kala checkpoint map
  (anything else under the reserved id) contributes nothing."
  [^File root]
  (let [f (segment-file root storage/checkpoint-segment-id)]
    (if-not (.exists f)
      0
      (let [v (read-obj f)]
        (or (and (map? v) (int? (:t v)) (:t v)) 0)))))

(defn open-storage
  "Open (or create) the storage rooted at path. Over an existing directory
  every acked segment and log entry is preserved. The append-time
  monotonicity check compares against the last t held from open — under the
  single-writer topology that is always the freshest acked entry; the adapter
  does not re-derive it from the directory per commit."
  [path]
  (let [root (io/file path)
        _ (doseq [d [root (io/file root "segments") (io/file root "log")]]
            (.mkdirs d))
        ;; cold start: one directory scan derives last-t (raised to the
        ;; checkpoint's base when a compaction has pruned the log — a fully
        ;; pruned log's files hold no floor of their own); appends compare
        ;; against the held value — the monotonicity check is O(1) per
        ;; commit, matching the memory adapter's sorted-map cost model
        last-t (atom (max (or (last (log-ts root)) 0)
                          (checkpoint-base-t root)))]
    (reify storage/Storage
      (put-segment [_ id data]
        (write-obj (segment-file root id) data)
        :ok)
      (get-segment [_ id]
        (let [f (segment-file root id)]
          (when (.exists f) (read-obj f))))
      (list-segments [_]
        (->> (.listFiles (io/file root "segments"))
             (filter #(.isFile ^File %))
             (filter #(not (re-matches #".*\.tmp\z" (.getName ^File %))))
             (map (fn [^File f]
                    (java.net.URLDecoder/decode (.getName f) "UTF-8")))
             vec))
      (append-log [_ entry]
        (let [t (:t entry)]
          (storage/check-monotonic-t t @last-t)
          ;; last-t advances only when the entry is acked — a failed write
          ;; leaves the held value untouched, so the retry contract holds
          (write-obj (log-file root t) entry)
          (reset! last-t t)
          :ok))
      (log-scan [_ from-t to-t]
        (->> (log-ts root)
             (filter #(and (>= % from-t) (<= % to-t)))
             (mapv #(read-obj (log-file root %)))))
      (prune-log! [_ up-to-t]
        (doseq [t (filter #(<= % up-to-t) (log-ts root))]
          ;; a failed delete leaves a replay-ignored file, not a corrupt
          ;; store — replay starts after the checkpoint's base — but the
          ;; ceiling this prunes toward would silently stop shrinking, so
          ;; surface it
          (let [f (log-file root t)]
            (when (and (.exists f) (not (.delete f)))
              (throw (ex-info "Log prune failed to delete an entry file"
                              {:kala/error :storage/prune-failed
                               :t t :file (str f)})))))
        ;; the held last-t must not regress below the pruned-up-to t even
        ;; when the pruned range held every entry in the log
        (swap! last-t max up-to-t)
        :ok))))
