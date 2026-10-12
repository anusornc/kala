(ns ^:peer kala.time-travel-test
  "Behavioral seam: time travel — as-of snapshots, since views, history with
  retraction status, and speculative with()."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.eid :as eid]
            [kala.storage :as storage]
            [kala.storage.memory :as memory]
            [kala.test-util :refer [assert-error define! fresh-conn]]
            [kala.time-travel :as time-travel]))

(defn load-fixture! []
  (let [conn (fresh-conn)]
    (define! conn "n" :person/name :db.type/string)
    (define! conn "a" :person/age :db.type/long)
    ;; tx2: Alice=30; tx3: Bob=25; tx4: Alice age retracted
    (let [r1 (api/transact conn [[:db/add "alice" :person/name "Alice"]
                                 [:db/add "alice" :person/age 30]])
          r2 (api/transact conn [[:db/add "bob" :person/name "Bob"]
                                 [:db/add "bob" :person/age 25]])
          {:keys [tempids]} r1
          alice (get tempids "alice")
          r3 (api/transact conn [[:db/retract alice :person/age 30]])]
      {:conn conn
       :alice alice
       :tx2 (get (:tempids r1) :db/current-tx)
       :tx3 (get (:tempids r2) :db/current-tx)
       :tx4 (get (:tempids r3) :db/current-tx)})))

(defn qv [db q & inputs]
  (set (map first (apply api/q q db inputs))))

;; ---- as-of -------------------------------------------------------------------

(deftest as-of-reads-consistent-past-snapshots
  (let [{:keys [conn alice tx2 tx3]} (load-fixture!)
        db-now (api/db conn)]
    (testing "as-of an earlier tx hides later facts"
      (let [db2 (api/as-of db-now tx2)]
        (is (= #{30} (qv db2 '[:find ?a :in $ ?p :where [?p :person/age ?a]] alice)))
        (is (= #{} (qv db2 '[:find ?n :where [?p :person/name "Bob"]
                                           [?p :person/name ?n]])))))
    (testing "as-of a later tx includes them"
      (let [db3 (api/as-of db-now tx3)]
        (is (= #{30} (qv db3 '[:find ?a :in $ ?p :where [?p :person/age ?a]] alice)))
        (is (= 1 (count (qv db3 '[:find ?p :where [?p :person/name "Bob"]]))))))
    (testing "as-of the current t equals the current db"
      (let [t-now (api/db-t db-now)]
        (is (= (set (mapv #(select-keys % [:e :a :v]) (api/datoms db-now :eavt [alice])))
               (set (mapv #(select-keys % [:e :a :v])
                          (api/datoms (api/as-of db-now t-now) :eavt [alice])))))))
    (testing "as-of works through pull and entity"
      (let [db2 (api/as-of db-now tx2)]
        (is (= {:person/name "Alice" :person/age 30}
               (dissoc (api/pull db2 [:person/name :person/age] alice) :db/id)))
        (is (= 30 ((api/entity db2 alice) :person/age)))))))

(deftest as-of-sees-facts-retracted-later
  (let [{:keys [conn alice tx2]} (load-fixture!)
        db-now (api/db conn)]
    (testing "current db: age retracted, gone"
      (is (= #{} (qv db-now '[:find ?a :in $ ?p :where [?p :person/age ?a]] alice))))
    (testing "as-of before the retraction: age still there"
      (let [db2 (api/as-of db-now tx2)]
        (is (= #{30} (qv db2 '[:find ?a :in $ ?p :where [?p :person/age ?a]] alice)))))))

;; ---- since -------------------------------------------------------------------

(deftest since-exposes-only-newer-facts
  (let [{:keys [conn alice tx2 tx3]} (load-fixture!)
        db-now (api/db conn)
        db-since (api/since db-now tx2)]
    (testing "facts from tx3 are visible"
      (is (= 1 (count (qv db-since '[:find ?p :where [?p :person/name "Bob"]])))))
    (testing "facts from tx2 and earlier are hidden (even though still current)"
      (is (= #{} (qv db-since '[:find ?n :in $ ?p :where [?p :person/name ?n]] alice))))
    (testing "retracted facts never appear even if retracted after t"
      (is (= #{} (qv db-since '[:find ?a :in $ ?p :where [?p :person/age ?a]] alice))))))

;; ---- history -----------------------------------------------------------------

(deftest history-includes-retractions-with-status
  (let [{:keys [conn alice tx4]} (load-fixture!)
        db-hist (api/history (api/db conn))
        age-datoms (vec (filter #(= :person/age (:a %))
                                (api/datoms db-hist :eavt [alice])))]
    (testing "both the assertion and the retraction are present"
      (is (= [true false] (mapv :added age-datoms))))
    (testing "retraction carries the retracting tx"
      (let [retraction (some #(when (false? (:added %)) %) age-datoms)]
        (is (some? retraction) (pr-str age-datoms))
        (is (= tx4 (:tx retraction))
            (pr-str {:tx4 tx4 :retraction retraction}))))))

;; ---- with --------------------------------------------------------------------

(deftest with-on-as-of-db-allocates-fresh-entities
  (let [{:keys [conn tx2]} (load-fixture!)
        db2 (api/as-of (api/db conn) tx2)
        report (api/with db2 [[:db/add "extra" :person/name "Extra"]])
        new-eid (get (:tempids report) "extra")]
    (testing "the speculative entity gets a fresh eid, no collision"
      (is (pos? new-eid))
      (is (nil? (api/entid (:db-before report) :extra/never)))
      (is (= 0 (count (qv (:db-before report) '[:find ?p :where [?p :person/name "Extra"]]))))
      ;; the eid must not collide with any pre-existing entity
      (is (empty? (filter #(and (= (:e %) new-eid) (not= (:v %) "Extra"))
                          (api/datoms (:db-after report) :eavt [new-eid])))))))

(deftest with-leaves-storage-untouched
  (let [{:keys [conn]} (load-fixture!)
        db (api/db conn)
        storage (api/db-storage db)
        log-count (fn [] (count (storage/log-scan storage 0 Long/MAX_VALUE)))
        before (log-count)]
    (api/with db [[:db/add "ghost" :person/name "Ghost"]])
    (is (= before (log-count)))))

(deftest since-accepts-raw-t-counters
  (let [{:keys [conn tx2 tx3]} (load-fixture!)
        t2 (eid/eid->t tx2)
        db-since (api/since (api/db conn) t2)]
    (testing "raw t counter normalizes to the same view as the tx eid"
      (is (= (api/since (api/db conn) tx2) db-since)))
    (testing "facts from tx3 visible under raw-t since"
      (is (= 1 (count (qv db-since '[:find ?p :where [?p :person/name "Bob"]])))))))

(deftest with-is-fully-speculative
  (let [{:keys [conn]} (load-fixture!)
        db-before (api/db conn)
        report (api/with db-before [[:db/add "ghost" :person/name "Ghost"]
                                    [:db/add "ghost" :person/age 99]])]
    (testing "the speculative db shows the facts"
      (is (= 1 (count (qv (:db-after report) '[:find ?p :where [?p :person/name "Ghost"]])))))
    (testing "the real db and storage are untouched"
      (is (identical? db-before (api/db conn)))
      (is (= 0 (count (qv (api/db conn) '[:find ?p :where [?p :person/name "Ghost"]])))))
    (testing "the report carries tx-data and tempids"
      (is (pos? (count (:tx-data report))))
      (is (pos? (get (:tempids report) "ghost"))))
    (testing "retractions can be speculated too"
      (let [{:keys [alice]} (load-fixture!)
            report (api/with db-before [[:db.fn/retractEntity alice]])]
        (is (seq (:tx-data report)))))))

;; ---- replay cache --------------------------------------------------------------
;; The cache must be unobservable: same results, same db-value shape. The one
;; thing it changes is that a repeat as-of/history at the same t stops
;; re-scanning storage. The Storage protocol is the seam — a counting wrapper
;; observes the scans without touching any public interface.

(defn- counting-storage
  "A Storage handle delegating every call to s; returns [scans spy] where
  scans counts log-scan invocations."
  [s]
  (let [scans (atom 0)
        spy (reify storage/Storage
              (put-segment [_ id data] (storage/put-segment s id data))
              (get-segment [_ id] (storage/get-segment s id))
              (list-segments [_] (storage/list-segments s))
              (append-log [_ entry] (storage/append-log s entry))
              (log-scan [_ from-t to-t]
                (swap! scans inc)
                (storage/log-scan s from-t to-t)))]
    [scans spy]))

(deftest as-of-and-history-do-not-rescan-storage-at-a-repeated-t
  ;; the counting adapter rides connect's front door — the storage seam the
  ;; replay cache sits behind
  (let [[scans spy] (counting-storage (memory/empty-storage))
        conn (api/connect {:storage spy})]
    (define! conn "n" :person/name :db.type/string)
    (define! conn "a" :person/age :db.type/long)
    (let [r1 (api/transact conn [[:db/add "alice" :person/name "Alice"]
                                 [:db/add "alice" :person/age 30]])
          r2 (api/transact conn [[:db/add "bob" :person/name "Bob"]
                                 [:db/add "bob" :person/age 25]])
          alice (get (:tempids r1) "alice")
          tx2 (get (:tempids r1) :db/current-tx)
          tx3 (get (:tempids r2) :db/current-tx)
          ;; the current t must move past tx3, or as-of tx3 is the identity
          ;; fast path and never touches the log
          _ (api/transact conn [[:db/retract alice :person/age 30]])
          _ (reset! scans 0)]
      (testing "repeat as-of at the same t replays once"
        (let [db-at-t2 (api/as-of (api/db conn) tx2)]
          (is (= 1 @scans) "the first as-of scans the log once")
          (is (= db-at-t2 (api/as-of (api/db conn) tx2)) "the second as-of is equal")
          (is (= 1 @scans) "the second as-of does not re-scan")
          (testing "a different t scans again"
            (is (some? (api/as-of (api/db conn) tx3)))
            (is (= 2 @scans)))))
      (testing "repeat history at the same t replays once"
        (let [hist (api/history (api/db conn))]
          (is (= 3 @scans) "one scan for history after the two as-ofs")
          (is (= hist (api/history (api/db conn))))
          (is (= 3 @scans) "the second history does not re-scan"))))))

(deftest rebuild-applies-retractions
  ;; the fixture's tx6 retracts Alice's age — a rebuilt db must apply that
  ;; retraction, not resurrect the retracted datom (a raw retraction datom
  ;; carries the retracting tx; the indexed datom holds its asserting tx)
  (let [{:keys [conn alice]} (load-fixture!)
        db (api/db conn)
        age-aid (api/entid db :person/age)
        rebuilt (time-travel/restore-db (api/db-storage db))]
    (is (= db rebuilt))
    (is (empty? (api/datoms rebuilt :eavt [alice age-aid])))
    (testing "as-of at the retracting tx replays the retraction too"
      (api/transact conn [[:db/add "x" :person/name "X"]])
      (is (= db (api/as-of (api/db conn) (api/db-t db)))))))

(deftest rebuild-after-a-schema-retract-equals-the-live-db
  ;; the prune lives in the shared tx-apply path, so replay prunes exactly
  ;; what the transactor pruned — a rebuilt db is the transacted one
  (let [{:keys [conn]} (load-fixture!)
        age-eid (api/entid (api/db conn) :person/age)]
    (api/transact conn [[:db.fn/retractEntity age-eid]])
    (let [db (api/db conn)
          rebuilt (time-travel/restore-db (api/db-storage db))]
      (is (= db rebuilt))
      (is (nil? (api/entid db :person/age)))
      (assert-error :transact/unknown-attribute
                    (api/transact conn [[:db/add "x" :person/age 1]])))))

(deftest rebuild-after-an-enum-retract-equals-the-live-db
  ;; ident-only entities prune through the same shared path, so a rebuilt db
  ;; drops the ::idents entry the live one dropped — both retraction forms
  (let [conn (fresh-conn)]
    (api/transact conn [[:db/add "x" :db/ident :t/red]
                        [:db/add "y" :db/ident :t/blue]])
    (let [red (api/entid (api/db conn) :t/red)
          blue (api/entid (api/db conn) :t/blue)]
      (api/transact conn [[:db.fn/retractEntity red]
                          [:db/retract blue :db/ident :t/blue]])
      (let [db (api/db conn)
            rebuilt (time-travel/restore-db (api/db-storage db))]
        (is (= db rebuilt))
        (is (nil? (api/entid db :t/red)))
        (is (nil? (api/entid rebuilt :t/red)))
        (is (nil? (api/entid db :t/blue)))
        (is (nil? (api/entid rebuilt :t/blue)))))))

(deftest rebuild-after-a-same-tx-attribute-redefinition-equals-the-live-db
  ;; ADR-0003: register re-derives the net state before the prune drops the
  ;; entries it no longer justifies — replay runs the same shared path, so a
  ;; rebuilt db is the transacted one
  (let [conn (fresh-conn)]
    (define! conn "n" :person/name :db.type/string)
    (define! conn "a" :person/age :db.type/long)
    (let [age-eid (api/entid (api/db conn) :person/age)]
      (api/transact conn [[:db/retract age-eid :db/ident :person/age]
                          [:db/add age-eid :db/ident :person/age]])
      (let [db (api/db conn)
            rebuilt (time-travel/restore-db (api/db-storage db))]
        (is (= db rebuilt))
        (is (= age-eid (api/entid db :person/age)))
        (is (= age-eid (api/entid rebuilt :person/age)))))))
