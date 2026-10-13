(ns kala.file-recovery-test
  "Behavioral seam on file storage: core flows run against a durable
  directory, and kill-and-restart against the same directory neither loses
  nor double-applies transactions."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.eid :as eid]
            [kala.storage :as storage]
            [kala.test-util :refer [define! install-tx-fn!]]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "kala-recovery-" (java.util.UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- log-entries [db-dir]
  (-> (api/connect {:storage :file :path db-dir})
      api/db
      api/db-storage
      (storage/log-scan 0 Long/MAX_VALUE)))

(defn- open-db [db-dir]
  (api/db (api/connect {:storage :file :path db-dir})))

(deftest file-storage-end-to-end-and-recovery
  ;; first "process": schema + data, then the process "dies" (conn dropped)
  (let [db-dir (tmp-dir)
        conn1 (api/connect {:storage :file :path db-dir})]
    (define! conn1 "n" :person/name :db.type/string)
    (define! conn1 "a" :person/age :db.type/long)
    (let [{:keys [tempids]} (api/transact conn1 [[:db/add "alice" :person/name "Alice"]
                                                 [:db/add "alice" :person/age 30]])
          alice (get tempids "alice")
          name-aid (api/entid (api/db conn1) :person/name)
          age-aid (api/entid (api/db conn1) :person/age)
          entries-before (log-entries db-dir)
          t-before (api/db-t (open-db db-dir))]
      (testing "data readable in the writing process"
        (is (= ["Alice"] (mapv :v (api/datoms (api/db conn1) :eavt [alice name-aid])))))

      ;; second "process": reconnect over the same directory
      (let [db-reopened (open-db db-dir)]
        (testing "nothing lost, nothing double-applied"
          (is (= t-before (api/db-t db-reopened)))
          (is (= (count entries-before) (count (log-entries db-dir))))
          (is (= [["Alice"] [30]]
                 (mapv (fn [aid]
                         (mapv :v (api/datoms db-reopened :eavt [alice aid])))
                       [name-aid age-aid]))))

          ;; the restored database continues writing where the log left off
          (let [conn2 (api/connect {:storage :file :path db-dir})
                {:keys [tempids tx-data]} (api/transact conn2 [[:db/add "bob" :person/name "Bob"]])
                bob (get tempids "bob")]
            (testing "t continues monotonically after restart"
              (is (pos? (count tx-data)))
              (is (> (api/db-t (api/db conn2)) t-before)))
            (testing "the restored allocation counter continues without collision"
              (is (eid/user-eid? bob))
              (is (not= bob alice))
              (is (= ["Bob"]
                     (mapv :v (api/datoms (api/db conn2) :eavt [bob :person/name])))
                  "the fresh eid resolves to Bob's facts only, nothing stale"))
            (testing "both processes' data coexist"
              (is (= 2 (count (api/q '[:find ?p :where [?p :person/name _]]
                                      (api/db conn2))))))
            (testing "time travel works against restored storage"
              (let [db-now (api/db conn2)
                    first-t (eid/eid->t (:tx (first (api/datoms db-now :eavt [alice name-aid]))))]
                (is (= ["Alice"]
                       (mapv :v (api/datoms (api/as-of db-now first-t)
                                            :eavt [alice name-aid])))))))))))

(deftest transaction-function-effects-are-log-expansions
  ;; A stored fn runs once at transact time; the log records what it DID (its
  ;; expansion datoms), so rebuilding applies datoms and never re-runs source.
  (let [db-dir (tmp-dir)
        conn (api/connect {:storage :file :path db-dir})]
    (define! conn "n" :t/n :db.type/long)
    (install-tx-fn! conn :t/claim
                    "(fn [db e] (if (empty? (kala.api/q '[:find ?c :in $ ?e :where [?e :t/n ?c]] db (kala.api/entid db e))) [[:db/add e :t/n 99]] nil))")
    (let [{:keys [tempids t]} (api/transact conn [[:t/claim "e"]])
          e (get tempids "e")
          n-aid (api/entid (api/db conn) :t/n)]
      (testing "the fn's effect landed through the call"
        (is (= [99] (mapv :v (api/datoms (api/db conn) :eavt [e n-aid])))))
      (testing "the call's log entry carries the expansion, not an invocation"
        (let [entry (first (filter #(= t (:t %)) (log-entries db-dir)))]
          (is (some (fn [[_ _ v _ _]] (= 99 v)) (:datoms entry)))))
      (testing "a rebuilt db applies the expansion exactly once"
        (is (= [99] (mapv :v (api/datoms (open-db db-dir) :eavt [e n-aid]))))))))
