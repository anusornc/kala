(ns datomic-opensource.time-travel-test
  "Behavioral seam: time travel — as-of snapshots, since views, history with
  retraction status, and speculative with()."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic-opensource.api :as api]
            [datomic-opensource.core :as core]
            [datomic-opensource.storage :as storage]
            [datomic-opensource.test-util :refer [define! fresh-conn]]))

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
      (let [t-now (::core/t db-now)]
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
        age-datoms (filter #(= :person/age (:a %))
                           (api/datoms db-hist :eavt [alice]))]
    (testing "both the assertion and the retraction are present"
      (is (= [true false] (mapv :added age-datoms))))
    (testing "retraction carries the retracting tx"
      (is (= tx4 (:tx (some #(when (false? (:added %)) %) age-datoms)))))))

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
        storage (::core/storage db)
        log-count (fn [] (count (storage/log-scan storage 0 Long/MAX_VALUE)))
        before (log-count)]
    (api/with db [[:db/add "ghost" :person/name "Ghost"]])
    (is (= before (log-count)))))

(deftest since-accepts-raw-t-counters
  (let [{:keys [conn tx2 tx3]} (load-fixture!)
        t2 (core/eid->t tx2)
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
