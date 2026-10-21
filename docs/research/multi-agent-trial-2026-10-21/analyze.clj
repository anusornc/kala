;; Round-2 analysis: read the shared store through the transactor (client
;; mode — never a second peer on a single-writer file store) and lay out
;; the communication that memory recorded: who wrote what, who cited whom.
(require '[kala.api :as api]
         '[kala.client :as client]
         '[kala.eid :as eid]
         '[kala.memory :as memory])

(def conn (client/connect {:host "127.0.0.1" :port 4334}))
(def db (api/db conn))
(def sessions ["runbook-keeper" "claude" "grok" "agy" "qwen"])

(println "\n=== turns per session: [t ordinal] ===")
(doseq [s sessions]
  (println (format "%-16s %s" s
                   (pr-str (mapv (fn [[t o _]] [t o]) (:turns (memory/turns db s {})))))))

;; tx entity -> session id, through the spine
(def tx->session
  (into {} (api/q '[:find ?tx ?id
                    :where [?tx :memory/session ?s]
                           [?s :memory.session/id ?id]]
                  db)))

(println "\n=== :proj/runbook correction trail (deploy-target, deploy-command, note) ===")
(def rb (api/entid db :proj/runbook))
(doseq [{:keys [a v t added]}
        (:result (memory/history db {:scope :entity :entity rb
                                     :attrs #{:proj/deploy-target :proj/deploy-command :proj/note}}))]
  (println (format "t=%-3d %-7s %-22s %s  <- %s"
                   t (if added "assert" "RETRACT") a (pr-str v)
                   (get tx->session (eid/tx-eid t) "?"))))

(println "\n=== citations: who cited whose turn (:memory/source) ===")
(def cites (api/datoms db :aevt [:memory/source]))
(if (empty? cites)
  (println "(none)")
  (doseq [d cites]
    (let [citing-t (eid/eid->t (:tx d))
          cited (:v d)
          cited-t (when (int? cited) (eid/eid->t cited))]
      (println (format "turn t=%-3d by %-7s cites t=%-3s by %s   (on entity %s)"
                       citing-t (get tx->session (:tx d) "?")
                       (str cited-t) (get tx->session cited "?") (:e d))))))

(println "\n=== final belief state (context, cap 12) ===")
(prn (dissoc (memory/context db {:cap 12}) :turns))
(println "\n=== turns in context ===")
(prn (:turns (memory/context db {:cap 12})))
(client/close conn)
(System/exit 0)
