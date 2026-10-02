(ns datomic-opensource.file-recovery-test
  "Behavioral seam on file storage: core flows run against a durable
  directory, and kill-and-restart against the same directory neither loses
  nor double-applies transactions."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic-opensource.api :as api]
            [datomic-opensource.storage :as storage]
            [datomic-opensource.core :as core]
            [datomic-opensource.test-util :refer [define!]]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "dos-recovery-" (java.util.UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- log-entries [db-dir]
  (-> (api/connect {:storage :file :path db-dir})
      api/db
      ::core/storage
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
          t-before (::core/t (open-db db-dir))]
      (testing "data readable in the writing process"
        (is (= ["Alice"] (mapv :v (api/datoms (api/db conn1) :eavt [alice name-aid])))))

      ;; second "process": reconnect over the same directory
      (let [db-reopened (open-db db-dir)]
        (testing "nothing lost, nothing double-applied"
          (is (= t-before (::core/t db-reopened)))
          (is (= (count entries-before) (count (log-entries db-dir))))
          (is (= [["Alice"] [30]]
                 (mapv (fn [aid]
                         (mapv :v (api/datoms db-reopened :eavt [alice aid])))
                       [name-aid age-aid]))))

        ;; the restored database continues writing where the log left off
        (let [conn2 (api/connect {:storage :file :path db-dir})
              {:keys [tx-data]} (api/transact conn2 [[:db/add "bob" :person/name "Bob"]])]
          (testing "t continues monotonically after restart"
            (is (pos? (count tx-data)))
            (is (> (::core/t (api/db conn2)) t-before)))
          (testing "both processes' data coexist"
            (is (= 2 (count (api/q '[:find ?p :where [?p :person/name _]]
                                    (api/db conn2))))))
          (testing "time travel works against restored storage"
            (let [db-now (api/db conn2)
                  first-t (core/eid->t (:tx (first (api/datoms db-now :eavt [alice name-aid]))))]
              (is (= ["Alice"]
                     (mapv :v (api/datoms (api/as-of db-now first-t)
                                          :eavt [alice name-aid])))))))))))
