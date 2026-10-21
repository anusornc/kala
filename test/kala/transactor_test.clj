(ns kala.transactor-test
  "Behavioral seam for the service mode: tests speak the raw wire protocol
  against a running transactor, exactly as a remote client would."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.test-util :refer [define!]]
            [kala.transactor :as tx]
            [kala.wire :as wire])
  (:import [java.net Socket]))

(defn- tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
        (str "kala-tx-" (java.util.UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- with-socket [{:keys [port]} f]
  (with-open [sock (Socket. "127.0.0.1" port)]
    (f (.getInputStream sock) (.getOutputStream sock))))

(defn- request [sock-refs msg]
  (let [[in out] sock-refs]
    (wire/write-msg out msg)
    (wire/read-msg in)))

(defn- connect! [refs]
  (request refs {:op :connect :protocol-version wire/protocol-version}))

(defn- start-file-transactor! []
  (tx/start! {:storage :file :path (tmp-dir) :port 0}))

(deftest wire-frame-round-trips-rich-values
  (let [msg {:op :x
             :vals ["s" :kw 'sym 42 (java.util.Date. 0) (java.util.UUID/randomUUID)
                    (byte-array (map byte [1 2 3])) [1 [2 [3]]] {:m {:nested :v}} true nil]}]
    ;; clean EOF reads as nil, not an exception
    (is (nil? (wire/read-msg (java.io.ByteArrayInputStream. (byte-array 0)))))
    ;; round-trip through the codec via pipes
    (let [pip-in (java.io.PipedInputStream.)
          pip-out (java.io.PipedOutputStream. pip-in)]
      (future (wire/write-msg pip-out msg))
      (let [back (wire/read-msg pip-in)]
        (is (= (dissoc msg :vals) (dissoc back :vals)))
        (is (= "s" (get-in back [:vals 0])))
        (is (= 42 (get-in back [:vals 3])))
        (is (= (java.util.Date. 0) (get-in back [:vals 4])))
        (is (instance? java.util.UUID (get-in back [:vals 5])))
        (is (= (seq (map byte [1 2 3])) (seq (get-in back [:vals 6]))))))))

(deftest connect-negotiates-protocol-version
  (let [t (start-file-transactor!)]
    (try
      (testing "matching version connects"
        (with-socket t
          (fn [in out]
            (let [reply (request [in out] {:op :connect :protocol-version wire/protocol-version})]
              (is (= :ok (:status reply)))
              (is (pos? (:t reply)))))))
      (testing "a fresh socket rejects a bad version"
        (with-open [s2 (Socket. "127.0.0.1" (:port t))]
          (let [reply (request [(.getInputStream s2) (.getOutputStream s2)]
                               {:op :connect :protocol-version 999})]
            (is (= :error (:status reply)))
            (is (= :protocol/version-mismatch (:error reply))))))
      (finally ((:stop! t))))))

(deftest transact-and-read-over-the-wire
  (let [t (start-file-transactor!)]
    (try
      (with-socket t
        (fn [in out]
          (connect! [in out])
          (testing "schema + data transact with reports"
            (let [r1 (request [in out]
                              {:op :transact
                               :tx-data [[:db/add "n" :db/ident :person/name]
                                         [:db/add "n" :db/valueType :db.type/string]]})]
              (is (= :ok (:status r1)))
              (let [r2 (request [in out] {:op :transact :tx-data [[:db/add "a" :person/name "Alice"]]})
                    name-datom (some #(when (= :person/name (:a %)) %) (:tx-data r2))]
                (is (= :ok (:status r2)))
                (is (= "Alice" (:v name-datom)))
                (is (pos? (get-in r2 [:tempids "a"])))))
            (testing "q runs server-side"
              (let [r (request [in out] {:op :q :query '[:find ?n :where [?p :person/name ?n]]})]
                (is (= ["Alice"] (mapv first (:rows r))))))
            (testing "q pins a t via :t"
              (let [db-r (request [in out] {:op :db})
                    t (:t db-r)
                    r (request [in out] {:op :transact :tx-data [[:db/add "b" :person/name "Bob"]]})
                    _ (is (= :ok (:status r)))
                    stale (request [in out] {:op :q :t t
                                             :query '[:find ?n :where [?p :person/name ?n]]})]
                (is (= ["Alice"] (mapv first (:rows stale))))
                (is (= 1 (count (:rows stale)))))))
          (testing "pull and datoms run server-side"
            (let [d (request [in out] {:op :datoms :index :aevt
                                       :components [(api/entid (api/db (:conn t)) :person/name)]})]
              (is (seq (:datoms d))))
            (let [eid (ffirst (:rows (request [in out] {:op :q :query '[:find ?p :where [?p :person/name "Alice"]]})))
                  doc (:doc (request [in out] {:op :pull :pattern [:person/name] :eid eid}))]
              (is (= "Alice" (:person/name doc)))))))
      (finally ((:stop! t))))))

(deftest concurrent-writers-serialize-into-one-total-order
  (let [t (start-file-transactor!)]
    (try
      (with-socket t
        (fn [in out]
          (connect! [in out])
          (request [in out] {:op :transact
                             :tx-data [[:db/add "c" :db/ident :counter/n]
                                       [:db/add "c" :db/valueType :db.type/long]]})
          (testing "20 concurrent increments all land, in one total order"
            (let [replies (atom [])
                  threads
                  (mapv (fn [i]
                          (Thread. (fn []
                                     (with-open [s (Socket. "127.0.0.1" (:port t))]
                                       (let [refs [(.getInputStream s) (.getOutputStream s)]]
                                         (connect! refs)
                                         (let [r (request refs
                                                          {:op :transact
                                                           :tx-data [[:db/add (str "i" i) :counter/n i]]})]
                                           (swap! replies conj r)))))))
                        (range 20))]
              (doseq [th threads] (.start th))
              (doseq [th threads] (.join th))
              (let [ts (mapv :t @replies)]
                (testing "every writer got a distinct t from one contiguous range"
                  (is (= 20 (count (distinct ts))))
                  (is (= 20 (- (apply max ts) (apply min ts) -1))))
                (let [rows (request [in out] {:op :q :query '[:find ?n :where [?p :counter/n ?n]]})]
                  (is (= 20 (count (:rows rows))))
                  (is (= (set (range 20)) (set (map first (:rows rows)))))))))))
      (finally ((:stop! t))))))

(deftest unique-conflicts-return-structured-error-replies
  (let [t (start-file-transactor!)]
    (try
      (with-socket t
        (fn [in out]
          (connect! [in out])
          (request [in out] {:op :transact
                             :tx-data [[:db/add "em" :db/ident :person/email]
                                       [:db/add "em" :db/valueType :db.type/string]
                                       [:db/add "em" :db/unique :db.unique/value]]})
          (let [r1 (request [in out] {:op :transact :tx-data [[:db/add "e1" :person/email "x@y.io"]]})
                _ (is (= :ok (:status r1)))
                r2 (request [in out] {:op :transact :tx-data [[:db/add "e2" :person/email "x@y.io"]]})]
            (is (= :error (:status r2)))
            (is (= :transact/unique-conflict (:error r2)))
            (is (string? (:message r2))))
          (testing "the db is untouched after the failed write; a retry with a new value succeeds"
            (let [r3 (request [in out] {:op :transact :tx-data [[:db/add "e2" :person/email "y@z.io"]]})]
              (is (= :ok (:status r3)))))))
      (finally ((:stop! t))))))

(deftest transactor-restart-resumes-from-existing-storage
  (let [dir (tmp-dir)
        t1 (tx/start! {:storage :file :path dir :port 0})
        t1-last (try
                  (with-socket t1
                    (fn [in out]
                      (connect! [in out])
                      (request [in out] {:op :transact
                                         :tx-data [[:db/add "n" :db/ident :person/name]
                                                   [:db/add "n" :db/valueType :db.type/string]]})
                      (:t (request [in out] {:op :transact :tx-data [[:db/add "a" :person/name "Alice"]]}))))
                  (finally
                    ((:stop! t1))))]
    ;; the old transactor "dies"; a new one opens the same storage
    (let [t2 (tx/start! {:storage :file :path dir :port 0})]
      (try
        (with-socket t2
          (fn [in out]
            (let [hello (connect! [in out])]
              (testing "the restarted transactor resumes at the old t"
                (is (= :ok (:status hello)))
                (is (= t1-last (:t hello))))
              (let [r (request [in out] {:op :transact :tx-data [[:db/add "b" :person/name "Bob"]]})]
                (testing "t continues monotonically after restart"
                  (is (= :ok (:status r)))
                  (is (= (inc t1-last) (:t r))))
                (let [rows (:rows (request [in out] {:op :q :query '[:find ?n :where [?p :person/name ?n]]}))]
                  (is (= #{"Alice" "Bob"} (set (map first rows)))))))))
        (finally
          ((:stop! t2)))))))

(deftest parse-config-reads-edn
  (is (= {:port 4334 :storage :file :path "/var/db"}
         (tx/parse-config "{:port 4334 :storage :file :path \"/var/db\"}")))
  ;; the config leg of the :compact opt-in (ADR-0010): the key survives
  ;; parsing into the map start! consumes
  (is (= true (:compact (tx/parse-config "{:port 4334 :storage :file :compact true}")))))

(deftest op-handlers-cover-the-wire-table
  ;; every op the protocol table declares has a transactor-side handler, and
  ;; no handler exists for an op the table doesn't know (#26)
  (is (= (set (keys wire/ops))
         (set (keys kala.transactor/handlers)))))
