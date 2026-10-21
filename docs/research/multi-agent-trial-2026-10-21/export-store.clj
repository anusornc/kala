;; Export the final trial store as a kala.backup log. Run from the kala
;; repo root AFTER the transactor is stopped (single writer — never a peer
;; on a file store the transactor holds):
;;   clojure -M /tmp/kala-agents2/export-store.clj /tmp/kala-agents2/store-3b.edn
(require '[kala.api :as api]
         '[kala.backup :as backup])

(def ag "/tmp/kala-agents2")
(def out (or (first *command-line-args*)
             (do (println "usage: clojure -M export-store.clj <out.edn>") (System/exit 1))))
(def conn (api/connect {:storage :file :path (str ag "/store")}))
(def report (backup/export-log (api/db-storage (api/db conn)) out))
(println "exported" (:entries report) "entries, t" (:from-t report) "-" (:to-t report) "->" out)
(System/exit 0)
