(ns kala.test-util
  "Shared helpers for the behavioral-seam test suites. The suite runs twice
  per CI cycle — once per storage backend — via *storage-opts*; fresh-conn
  isolates each connection in its own memory db or temp directory."
  (:require [clojure.test :refer [is]]
            [kala.api :as api]
            [kala.client :as client]
            [kala.transactor :as tx]))

(def ^:dynamic *storage-opts*
  "Storage backend selector for fresh-conn. The test runner binds this to
  {:storage :memory} and {:storage :file :path <tmp-root>} in turn."
  {:storage :memory})

(def ^:dynamic *access-mode* :peer)
(def ^:dynamic *name* "test")

(defn- unique-path []
  (str (java.nio.file.Files/createTempDirectory
        (str "dos-suite-" (java.util.UUID/randomUUID))
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn fresh-conn []
  (if (= :client *access-mode*)
    (let [storage-opts (if (= :file (:storage *storage-opts*))
                         (assoc *storage-opts* :path (unique-path))
                         *storage-opts*)
          t (tx/start! (assoc storage-opts :port 0))]
      (client/connect {:host "127.0.0.1" :port (:port t) :name *name*}))
    (api/connect
     (if (= :file (:storage *storage-opts*))
       (assoc *storage-opts* :path (unique-path))
       *storage-opts*))))

(defn define!
  ([conn tempid ident value-type]
   (define! conn tempid ident value-type nil))
  ([conn tempid ident value-type opts]
   (api/transact conn
                 (cond-> [[:db/add tempid :db/ident ident]
                          [:db/add tempid :db/valueType value-type]
                          [:db/add tempid :db/cardinality (or (:cardinality opts) :db.cardinality/one)]]
                   (:unique opts)    (conj [:db/add tempid :db/unique (:unique opts)])
                   (:component opts) (conj [:db/add tempid :db/isComponent true])))))

(defmacro assert-error [kind & body]
  `(try ~@body
        (is false ~(str "expected ex-info " kind))
        (catch clojure.lang.ExceptionInfo e#
          (is (= ~kind (:kala/error (ex-data e#)))))))
