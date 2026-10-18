(ns kala.compaction-test
  "The compaction seam: snapshot-and-prune preserves the live db, the
  allocation counter, and time travel above the base on both backends;
  history below the base is loudly pruned (:db/pruned-t); and the
  monotonicity guard survives a log that pruning emptied — including
  across a reopen, where no log files remain to derive the floor from."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.client :as client]
            [kala.compaction :as compaction]
            [kala.storage :as storage]
            [kala.storage.file :as file]
            [kala.storage.memory :as memory]
            [kala.transactor :as tx])
  (:import [java.util UUID]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "kala-compact-" (UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private schema
  [[:db/add "s1" :db/ident :x/name]
   [:db/add "s1" :db/valueType :db.type/string]
   [:db/add "s1" :db/cardinality :db.cardinality/one]
   [:db/add "s2" :db/ident :x/age]
   [:db/add "s2" :db/valueType :db.type/long]
   [:db/add "s2" :db/cardinality :db.cardinality/one]
   [:db/add "s3" :db/ident :x/friend]
   [:db/add "s3" :db/valueType :db.type/ref]
   [:db/add "s3" :db/cardinality :db.cardinality/one]])

(def ^:private names-q '[:find ?n :where [_ :x/name ?n]])

(defn- project [db]
  (mapv (fn [^kala.core.Datom d]
          [(.e d) (.a d) (.v d) (.tx d) (.added d)])
        (api/datoms db :eavt [])))

(defn- by-name
  [db n]
  (ffirst (api/q '[:find ?e :in $ ?n :where [?e :x/name ?n]] db n)))

(defn- build-store!
  "Schema, three people, a friend edge, and a correction (retract and
  re-assert alice's age) — a log with history worth pruning."
  [connect-opts]
  (let [conn (api/connect connect-opts)
        _ (api/transact conn schema)
        _ (api/transact conn [[:db/add "a" :x/name "alice"]
                              [:db/add "a" :x/age 30]
                              [:db/add "b" :x/name "bob"]
                              [:db/add "b" :x/age 25]
                              [:db/add "c" :x/name "carol"]])
        db (api/db conn)
        alice (by-name db "alice")
        bob (by-name db "bob")
        _ (api/transact conn [[:db/add alice :x/friend bob]])
        _ (api/transact conn [[:db/retract alice :x/age 30]
                              [:db/add alice :x/age 31]])]
    {:conn conn
     :t (api/db-t (api/db conn))
     :names (fn [] (set (map first (api/q names-q (api/db conn)))))}))

(deftest compaction-preserves-the-live-db
  (doseq [[label opts] [["memory" {:storage :memory}]
                        ["file" {:storage :file :path (tmp-dir)}]]]
    (testing (str label " backend")
      (let [{:keys [conn names] :as built} (build-store! opts)
            before (project (api/db conn))
            storage-h (api/db-storage (api/db conn))
            prunable (count (storage/log-scan storage-h 0 (:t built)))
            report (api/compact conn)]
        (testing "the report names the base and its work"
          (is (= (:t built) (:t report)))
          (is (pos? (:datoms report)))
          (is (= prunable (:pruned report))))
        (testing "the live db is untouched"
          (is (= (:t built) (api/db-t (api/db conn))))
          (is (= before (project (api/db conn))))
          (is (= #{"alice" "bob" "carol"} (names))))
        (testing "the log below the base is gone; the snapshot segment exists"
          (is (empty? (storage/log-scan storage-h 0 (:t report))))
          (is (some? (storage/get-segment storage-h storage/snapshot-segment-id)))
          (is (= (:t report) (compaction/snapshot-base storage-h))))))))

(deftest reopen-after-compaction-replays-snapshot-plus-tail
  (let [dir (tmp-dir)
        {:keys [conn]} (build-store! {:storage :file :path dir})
        before (project (api/db conn))
        report (api/compact conn)
        ;; writes above the base: a new entity and fresh vocabulary
        _ (api/transact conn [[:db/add "s4" :db/ident :x/email]
                              [:db/add "s4" :db/valueType :db.type/string]
                              [:db/add "s4" :db/cardinality :db.cardinality/one]])
        _ (api/transact conn [[:db/add "d" :x/name "dave"]
                              [:db/add "d" :x/email "d@x.io"]])
        tail-t (api/db-t (api/db conn))
        reopened (api/connect {:storage :file :path dir})]
    (testing "the reopened store is the pre-compaction db plus the tail"
      (is (= tail-t (api/db-t (api/db reopened))))
      (is (= (project (api/db conn)) (project (api/db reopened))))
      (is (= before (project (api/as-of (api/db reopened) (:t report))))))
    (testing "vocabulary registered before the base resolves after reopen"
      (is (= "d@x.io" (:x/email (api/pull (api/db reopened) '[*]
                                          (ffirst (api/q '[:find ?e :in $ ?n :where [?e :x/name ?n]]
                                                          (api/db reopened) "dave"))))))
      (is (= #{"alice" "bob" "carol" "dave"}
             (set (map first (api/q names-q (api/db reopened)))))))
    (testing "the monotonicity guard survives the reopen with no log files below the base"
      (let [fresh (file/open-storage dir)]
        (is (thrown-with-msg? Exception #"strictly increasing"
                              (storage/append-log fresh {:t 2 :datoms []}))))
      (testing "and on the running adapter too"
        (let [live (file/open-storage dir)]
          (is (thrown-with-msg? Exception #"strictly increasing"
                                (storage/append-log live {:t (:t report) :datoms []}))))))))

(deftest as-of-below-the-base-raises-and-above-it-is-exact
  (let [dir (tmp-dir)
        {:keys [conn]} (build-store! {:storage :file :path dir})
        base (:t (api/compact conn))
        _ (api/transact conn [[:db/add "e" :x/name "erin"]])
        db (api/db conn)
        reopened (api/connect {:storage :file :path dir})
        reopened-db (api/db reopened)]
    (testing "as-of below the base raises the pruned-history kind, both processes"
      (doseq [d [db reopened-db]]
        (let [e (try (api/as-of d (dec base)) nil
                     (catch Exception e e))]
          (is (some? e))
          (is (= :db/pruned-t (:kala/error (ex-data e))))
          (is (= base (:base (ex-data e)))))))
    (testing "as-of at the base is the snapshot state"
      (is (= #{"alice" "bob" "carol"}
             (set (map first (api/q names-q (api/as-of db base)))))))
    (testing "as-of above the base sees pre-base facts by their original tx"
      (is (= #{"alice" "bob" "carol" "erin"}
             (set (map first (api/q names-q (api/as-of db (api/db-t db)))))))
      (is (= #{"alice" "bob" "carol" "erin"}
             (set (map first (api/q names-q (api/as-of reopened-db (api/db-t reopened-db))))))))))

(deftest allocation-counter-survives-retracted-entities
  ;; the regression the explicit :next-idx exists for: an entity whose
  ;; every datom was retracted contributes nothing to a datom-max over
  ;; live state, so its id could be re-allocated after compaction
  (let [dir (tmp-dir)
        conn (api/connect {:storage :file :path dir})
        _ (api/transact conn schema)
        doomed (get (:tempids (api/transact conn [[:db/add "doomed" :x/name "gone"]])) "doomed")
        _ (api/transact conn [[:db.fn/retractEntity doomed]])
        _ (api/compact conn)
        after (get (:tempids (api/transact conn [[:db/add "fresh" :x/name "here"]])) "fresh")]
    (testing "the retracted entity's id is never reused"
      (is (> after doomed))
      (testing "and the counter survives the reopen too"
        (let [reopened (api/connect {:storage :file :path dir})
              later (get (:tempids (api/transact reopened [[:db/add "fresh2" :x/name "here2"]])) "fresh2")]
          (is (> later after)))))))

(deftest compaction-repeats-and-advances-the-base
  (let [dir (tmp-dir)
        {:keys [conn]} (build-store! {:storage :file :path dir})
        first-base (:t (api/compact conn))
        between (get (:tempids (api/transact conn [[:db/add "z" :x/name "zoe"]])) "z")
        _ (api/transact conn [[:db/retract between :x/name "zoe"]])
        second (:t (api/compact conn))]
    (testing "a second compaction moves the base forward"
      (is (< first-base second))
      (is (= second (compaction/snapshot-base (api/db-storage (api/db conn))))))
    (testing "the window between the bases stays readable as-of"
      (let [db (api/db conn)]
        (is (some? (api/q '[:find ?e :in $ ?n :where [?e :x/name ?n]] (api/as-of db second) "zoe"))))
      (testing "and below the first base still raises"
        (is (= :db/pruned-t (:kala/error (try (api/as-of (api/db conn) (dec first-base)) nil
                                              (catch Exception e (ex-data e))))))))
    (testing "the re-run pruned the tail it covered"
      (is (empty? (storage/log-scan (api/db-storage (api/db conn)) 0 second))))))

(deftest history-after-compaction-shows-survivors-plus-the-tail
  (let [dir (tmp-dir)
        {:keys [conn]} (build-store! {:storage :file :path dir})
        alice (ffirst (api/q '[:find ?e :in $ ?n :where [?e :x/name ?n]] (api/db conn) "alice"))
        base (:t (api/compact conn))
        _ (api/transact conn [[:db/retract alice :x/age 31]])
        h (api/history (api/db conn))
        age-story (set (map (juxt :v :added)
                            (filter #(= :x/age (:a %)) (api/datoms h :eavt [alice]))))]
    (testing "the correction's OUTCOME survives; the trail below the base does not"
      ;; pre-base: 30 asserted then corrected away; the snapshot holds
      ;; only the live 31, so history shows no 30 at all
      (is (contains? age-story [31 true]))
      (is (not (contains? age-story [30 true])))
      (is (not (contains? age-story [30 false])))
      (testing "post-compaction retracts are full history members"
        (is (contains? age-story [31 false]))))
    (testing "the name (never touched) reads as ever-asserted"
      (is (contains? (set (map (juxt :v :added)
                               (filter #(= :x/name (:a %)) (api/datoms h :eavt [alice]))))
                     ["alice" true])))))

(deftest compaction-is-peer-only
  (let [t (tx/start! {:storage :memory :port 0})]
    (try
      (let [conn (client/connect {:host "127.0.0.1" :port (:port t)})]
        (is (= :client/peer-only
               (:kala/error (try (api/compact conn) nil
                                 (catch Exception e (ex-data e))))))
        (client/close conn))
      (finally
        ((:stop! t))))))
