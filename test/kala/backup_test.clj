(ns kala.backup-test
  "The backup seam: export/import round-trips every value type the store
  can hold (including the three that ride tagged literals), copy migrates
  between backends, and verify compares two logs byte-honestly — byte
  arrays by content, never by instance."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.backup :as backup]
            [kala.storage :as storage]
            [kala.storage.file :as file]
            [kala.storage.memory :as memory])
  (:import [java.io File]
           [java.util UUID]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "kala-backup-" (UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- tmp-file []
  (doto (File/createTempFile "kala-backup-test" ".edn")
    (.deleteOnExit)))

(def ^:private rich-schema
  [[:db/add "s1" :db/ident :thing/s]
   [:db/add "s1" :db/valueType :db.type/string]
   [:db/add "s1" :db/cardinality :db.cardinality/one]
   [:db/add "s2" :db/ident :thing/kw]
   [:db/add "s2" :db/valueType :db.type/keyword]
   [:db/add "s2" :db/cardinality :db.cardinality/one]
   [:db/add "s3" :db/ident :thing/sym]
   [:db/add "s3" :db/valueType :db.type/symbol]
   [:db/add "s3" :db/cardinality :db.cardinality/one]
   [:db/add "s4" :db/ident :thing/long]
   [:db/add "s4" :db/valueType :db.type/long]
   [:db/add "s4" :db/cardinality :db.cardinality/one]
   [:db/add "s5" :db/ident :thing/bigint]
   [:db/add "s5" :db/valueType :db.type/bigint]
   [:db/add "s5" :db/cardinality :db.cardinality/one]
   [:db/add "s6" :db/ident :thing/float]
   [:db/add "s6" :db/valueType :db.type/float]
   [:db/add "s6" :db/cardinality :db.cardinality/one]
   [:db/add "s7" :db/ident :thing/double]
   [:db/add "s7" :db/valueType :db.type/double]
   [:db/add "s7" :db/cardinality :db.cardinality/one]
   [:db/add "s8" :db/ident :thing/bool]
   [:db/add "s8" :db/valueType :db.type/boolean]
   [:db/add "s8" :db/cardinality :db.cardinality/one]
   [:db/add "s9" :db/ident :thing/instant]
   [:db/add "s9" :db/valueType :db.type/instant]
   [:db/add "s9" :db/cardinality :db.cardinality/one]
   [:db/add "s10" :db/ident :thing/uuid]
   [:db/add "s10" :db/valueType :db.type/uuid]
   [:db/add "s10" :db/cardinality :db.cardinality/one]
   [:db/add "s11" :db/ident :thing/uri]
   [:db/add "s11" :db/valueType :db.type/uri]
   [:db/add "s11" :db/cardinality :db.cardinality/one]
   [:db/add "s12" :db/ident :thing/bytes]
   [:db/add "s12" :db/valueType :db.type/bytes]
   [:db/add "s12" :db/cardinality :db.cardinality/one]
   [:db/add "s13" :db/ident :thing/other]
   [:db/add "s13" :db/valueType :db.type/ref]
   [:db/add "s13" :db/cardinality :db.cardinality/one]
   [:db/add "s14" :db/ident :thing/counter]
   [:db/add "s14" :db/valueType :db.type/long]
   [:db/add "s14" :db/cardinality :db.cardinality/one]])

(def ^:private the-instant (java.util.Date. 1700000000000))
(def ^:private the-uuid (UUID/fromString "0f4d2a51-9b6e-4f0a-8c1f-2f7d3e5b6a90"))
(def ^:private the-bytes (byte-array (map byte [0 -1 42 7 127])))
(def ^:private the-uri (java.net.URI. "https://example.com/pa%20th?q=1"))

(defn- rich-tx [other-eid]
  [[:db/add "r" :thing/s "hello\nworld"]
   [:db/add "r" :thing/kw :thing/kw-value]
   [:db/add "r" :thing/sym 'sym/bol]
   [:db/add "r" :thing/long -9223372036854775808]
   [:db/add "r" :thing/bigint (bigint "123456789012345678901234567890")]
   [:db/add "r" :thing/float (float 1.5)]
   [:db/add "r" :thing/double 2.25]
   [:db/add "r" :thing/bool false]
   [:db/add "r" :thing/instant the-instant]
   [:db/add "r" :thing/uuid the-uuid]
   [:db/add "r" :thing/uri the-uri]
   [:db/add "r" :thing/bytes the-bytes]
   [:db/add "r" :thing/other other-eid]])

(defn- build-nothing [path]
  (api/connect {:storage :file :path path}))

(defn- build-store!
  "A store whose log carries the full value-type surface: schema, one rich
  entity, a retraction-and-reassert on :thing/counter, and a second tx so
  the log spans several t."
  [connect-opts]
  (let [conn (api/connect connect-opts)
        _ (api/transact conn rich-schema)
        anchor-report (api/transact conn [[:db/add "a" :thing/s "anchor"]])
        eid (get (:tempids anchor-report) "a")
        report (api/transact conn (rich-tx eid))
        rich-eid (get (:tempids report) "r")
        _ (api/transact conn [[:db/add rich-eid :thing/counter 1]])
        _ (api/transact conn [[:db/retract rich-eid :thing/counter 1]
                              [:db/add rich-eid :thing/counter 2]])
        t (api/db-t (api/db conn))]
    {:conn conn :eid eid :t t :rich-eid rich-eid}))

(defn- norm-v [v]
  (if (bytes? v) (vec v) v))

(defn- project
  "Datoms as comparable data, byte values content-shaped. Plain = on two
  independently loaded stores would compare array instances."
  [db]
  (mapv (fn [^kala.core.Datom d]
          [(.e d) (.a d) (norm-v (.v d)) (.tx d) (.added d)])
        (api/datoms db :eavt [])))

(defn- project-as-of [db t]
  (project (api/as-of db t)))

(deftest export-import-round-trips-every-value-type
  (let [a (build-store! {:storage :memory})
        out (tmp-file)
        export-report (backup/export-log (api/db-storage (api/db (:conn a))) out)
        b-path (tmp-dir)
        b-storage (file/open-storage b-path)
        import-report (backup/import-log b-storage out)
        b (build-nothing b-path)]
    (testing "the reports describe the same span"
      (is (= (:from-t export-report) (:from-t import-report)))
      (is (= (:to-t export-report) (:to-t import-report)))
      (is (pos? (:entries export-report)))
      (is (= (:entries export-report) (:entries import-report))))
    (testing "the imported store replays to the identical current state"
      (is (= (:t a) (api/db-t (api/db b))))
      (is (= (project (api/db (:conn a))) (project (api/db b)))))
    (testing "every value class survives, not just prints"
      (let [rich (api/pull (api/db b) '[*] (:rich-eid a))]
        (is (= "hello\nworld" (:thing/s rich)))
        (is (= :thing/kw-value (:thing/kw rich)))
        (is (= 'sym/bol (:thing/sym rich)))
        (is (= -9223372036854775808 (:thing/long rich)))
        (is (= (bigint "123456789012345678901234567890") (:thing/bigint rich)))
        (is (instance? Float (:thing/float rich)))
        (is (= (float 1.5) (:thing/float rich)))
        (is (= 2.25 (:thing/double rich)))
        ;; = false, not (false? ...) — file-storage values ride Java
        ;; serialization, whose Boolean instances are not the compiled
        ;; false literal (the identity check time-travel.clj documents)
        (is (= false (:thing/bool rich)))
        (is (= the-instant (:thing/instant rich)))
        (is (= the-uuid (:thing/uuid rich)))
        (is (= the-uri (:thing/uri rich)))
        (is (= (seq the-bytes) (seq (:thing/bytes rich))))
        (is (= (:eid a) (:thing/other rich)))))
    (testing "as-of reads across the boundary match at every t"
      (doseq [t (range 2 (inc (:t a)))]
        (is (= (project-as-of (api/db (:conn a)) t)
               (project-as-of (api/db b) t))
            (str "as-of " t))))
    (testing "history matches too"
      (is (= (project (api/history (api/db (:conn a))))
             (project (api/history (api/db b))))))
    (testing "a re-export of the import is byte-identical"
      (let [out2 (tmp-file)]
        (backup/export-log b-storage out2)
        (is (= (slurp out) (slurp out2)))))))

(deftest range-export-exports-only-the-window
  (let [a (build-store! {:storage :memory})
        s (api/db-storage (api/db (:conn a)))
        out (tmp-file)
        report (backup/export-log s out {:from-t 3 :to-t 4})]
    (is (= 2 (:entries report)))
    (is (= 3 (:from-t report)))
    (is (= 4 (:to-t report)))
    (testing "importing a range that overlaps a store's last t raises"
      (let [b (memory/empty-storage)]
        (backup/import-log b out)
        (is (thrown-with-msg? Exception #"strictly increasing"
                              (backup/import-log b out)))))))

(deftest copy-storage-migrates-between-backends
  (let [a (build-store! {:storage :memory})
        b-storage (memory/empty-storage)]
    (backup/copy-storage (api/db-storage (api/db (:conn a))) b-storage)
    (let [c-path (tmp-dir)
          c-storage (file/open-storage c-path)]
      (backup/copy-storage b-storage c-storage)
      (testing "memory -> file copy verifies clean"
        (is (:match? (backup/verify-storage b-storage c-storage))))
      (testing "the copy is the original"
        (let [c (api/connect {:storage :file :path c-path})]
          (is (= (project (api/db (:conn a))) (project (api/db c)))))))))

(deftest verify-names-the-first-divergence
  (let [a (build-store! {:storage :memory})
        s (api/db-storage (api/db (:conn a)))
        b (memory/empty-storage)]
    (testing "an identical copy matches"
      (backup/copy-storage s b)
      (let [report (backup/verify-storage s b)]
        (is (:match? report))
        (is (= (:entries report) (count (storage/log-scan s 0 Long/MAX_VALUE))))))
    (testing "a dropped entry is an entry-count divergence"
      (let [d (memory/empty-storage)
            out (tmp-file)
            last-t (:t (last (storage/log-scan s 0 Long/MAX_VALUE)))]
        (backup/export-log s out {:from-t 0 :to-t (dec last-t)})
        (backup/import-log d out)
        (let [report (backup/verify-storage s d)]
          (is (not (:match? report)))
          (is (= :entry-count (:reason report))))))
    (testing "a differing value at the same t is a content divergence"
      (let [e (memory/empty-storage)
            out (tmp-file)
            _ (backup/export-log s out)
            lines (with-open [r (io/reader out)] (vec (line-seq r)))
            tampered (map (fn [line]
                            (if (re-find #"anchor" line)
                              (clojure.string/replace line "anchor" "tampered")
                              line))
                          lines)
            out2 (tmp-file)]
        (spit out2 (clojure.string/join "\n" tampered))
        (backup/import-log e out2)
        (let [report (backup/verify-storage s e)]
          (is (not (:match? report)))
          (is (= :entry-content (:reason report)))
          (is (int? (:at-t report))))))))

(deftest import-refuses-what-it-cannot-trust
  (testing "an unknown tag fails the import"
    (let [b (memory/empty-storage)
          out (tmp-file)
          ;; an unknown tag riding a value slot inside an otherwise
          ;; well-formed entry
          _ (spit out "{:t 2, :datoms [[1 2 #kala/nope \"x\" 3 true]]}")]
      (is (thrown-with-msg? Exception #"no reader knows"
                            (backup/import-log b out)))))
  (testing "a non-entry line fails the import"
    (let [b (memory/empty-storage)
          out (tmp-file)
          _ (spit out "{:foo 1}")]
      (is (thrown-with-msg? Exception #"not a log entry"
                            (backup/import-log b out)))))
  (testing "blank lines are skipped"
    (let [a (build-store! {:storage :memory})
          out (tmp-file)
          _ (backup/export-log (api/db-storage (api/db (:conn a))) out)
          b (memory/empty-storage)
          with-blanks (str "\n\n" (slurp out) "\n\n")]
      (is (pos? (:entries (backup/import-log b (java.io.StringReader. with-blanks))))))))
