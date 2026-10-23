(ns kala.monitor
  "The monitor: kala's second web app — a client-mode UI drawing a store's
   transactions as a knowledge graph of entity and turn nodes (ADR-0012).

   The monitor speaks client mode and nothing else: every read travels the
   wire to a live transactor, and the peer path does not exist in this
   namespace. Its main either starts an owned transactor on a store
   directory (connecting to it over loopback like any other client) or
   attaches to a running transactor. A monitor without a transactor shows
   nothing — starting one on the target store is the documented
   prerequisite.

   Run:
     clojure -M:monitor --store PATH [--seed] [--port N]
     clojure -M:monitor --attach HOST:TXPORT[:TOKEN] [--port N]

   A kala.backup log export is viewed by restoring it into a fresh store
   directory first (docs/backup-and-migration.md) and pointing --store at
   that; --seed records a small deterministic memory store through the
   client connection. The serving surface is read-only: the
   HTTP endpoints are graph reads, a head ping, and the per-turn and
   per-entity drilldowns — no transacts.

   Same posture as the web demo: EDN stays the presentation language
   inside JSON envelopes, the server binds 127.0.0.1 only, and tx-data is
   not accepted here (ADR-0004's trust note a fortiori — this app reads)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kala.api :as api]
            [kala.client :as client]
            [kala.core :as core]
            [kala.eid :as eid]
            [kala.httpd :as httpd]
            [kala.memory :as memory]
            [kala.monitor.graph :as graph]
            [kala.transactor :as transactor])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

;; ---- state -----------------------------------------------------------------

(defonce state (atom {:conn nil :head (atom 0)}))

;; ---- param readers ---------------------------------------------------------------

(defn- opt-int
  "A numeric query parameter: absent means nil, otherwise it must satisfy
   pred — anything else raises the monitor's bad-param kind."
  [k v pred what]
  (when (some? v)
    (or (let [n (parse-long v)] (when (and n (pred n)) n))
        (core/err :monitor/bad-param
                  (str (name k) " must be " what)
                  {k v}))))

(defn- statics
  "Whitelisted static resources off the classpath (web/monitor is a
   classpath root, so resource names are bare)."
  [^HttpExchange ex]
  (let [path (.. ex getRequestURI getPath)
        served {"/" ["index.html" "text/html; charset=utf-8"]
                "/index.html" ["index.html" "text/html; charset=utf-8"]
                "/monitor.js" ["monitor.js" "application/javascript; charset=utf-8"]
                "/monitor.css" ["monitor.css" "text/css; charset=utf-8"]}]
    (if-let [[_ [resource ctype]] (find served path)]
      (let [src (io/resource resource)]
        (if src
          (httpd/respond! ex 200 ctype (slurp src :encoding "UTF-8"))
          (httpd/respond! ex 404 "text/plain; charset=utf-8" "not found")))
      (httpd/respond! ex 404 "text/plain; charset=utf-8" "not found"))))

;; ---- endpoints --------------------------------------------------------------------

(defn- endpoint-graph
  "GET /api/graph?as-of=&session=&window=&cap= — the whole graph payload,
   derived through kala.monitor.graph. :session is a :memory.session/id
   string; an as-of below the compaction base surfaces the store's own
   :db/pruned-t kind."
  [_ {:keys [as-of session window cap]}]
  (let [scope {:as-of (opt-int :as-of as-of nat-int? "a t (non-negative integer)")
               :session (when (seq session) session)
               :window (opt-int :window window pos-int? "a positive integer")
               :cap (opt-int :cap cap pos-int? "a positive integer")}
        db (api/db (:conn @state))]
    {:m (assoc (graph/graph db scope) :ok true)}))

(defn- endpoint-head
  "GET /api/head — the t the listener last saw committed. Follow polls
   this; a change means a new turn to fetch."
  [_ _]
  {:m {:ok true :t @(:head @state)}})

(defn- endpoint-turn
  "GET /api/turn?t= — the datoms one turn transacted, from history: the
   click-through for a turn node."
  [_ {:keys [t]}]
  (let [t (opt-int :t t nat-int? "a t (non-negative integer)")
        db (api/db (:conn @state))
        datoms (->> (api/datoms (api/history db) :eavt [])
                    (filter #(= (eid/tx-eid t) (:tx %)))
                    (mapv (fn [d] [(:e d) (pr-str (:a d)) (pr-str (:v d))
                                   (:added d)])))]
    {:m {:ok true :t t :datoms datoms}}))

(defn- endpoint-entity
  "GET /api/entity?id= — the correction trail of one entity: every assert
   and retract it ever received, each row [t attr value added session]
   attributed to the turn that wrote it and that turn's session. The id is
   a raw entity id or the graph's node-id form (e<eid>)."
  [_ {:keys [id]}]
  (let [eid (or (parse-long id)
                (when (str/starts-with? id "e")
                  (parse-long (subs id 1)))
                (core/err :monitor/bad-param
                          "id must be an entity id" {:id id}))
        db (api/db (:conn @state))
        history (api/datoms (api/history db) :eavt [])
        turn-session (into {}
                           (for [d history :when (= :memory/session (:a d))]
                             [(eid/eid->t (:tx d)) (:v d)]))
        trail (->> (filter #(= eid (:e %)) history)
                   (mapv (fn [d]
                           (let [t (eid/eid->t (:tx d))]
                             [t (pr-str (:a d)) (pr-str (:v d)) (:added d)
                              (get turn-session t)])))
                   (sort-by first))]
    {:m {:ok true :id eid :trail trail}}))

(defn- endpoint-sessions
  "GET /api/sessions — the session menu: every memory session of the
   store with its turn count."
  [_ _]
  (let [db (api/db (:conn @state))
        sessions (->> (api/q '[:find ?s ?id (count ?tx)
                               :where
                               [?tx :memory/session ?s]
                               [?s :memory.session/id ?id]]
                             db)
                      (mapv (fn [[s id n]] {:id s :label id :turns n}))
                      (sort-by :label))]
    {:m {:ok true :sessions sessions}}))

;; ---- main ---------------------------------------------------------------------------

(defn- seed!
  "A deterministic memory store recorded THROUGH THE CLIENT CONNECTION —
   the monitor's own posture (client mode, one writer) even while seeding:
   a vocabulary, two sessions, five turns with a correction, a ref, and a
   citation. Lands at t=10 on a fresh store."
  [conn]
  (api/transact conn
                [[:db/add "t" :db/ident :dev/title]
                 [:db/add "t" :db/valueType :db.type/string]
                 [:db/add "t" :db/cardinality :db.cardinality/one]
                 [:db/add "n" :db/ident :dev/note]
                 [:db/add "n" :db/valueType :db.type/string]
                 [:db/add "n" :db/cardinality :db.cardinality/many]
                 [:db/add "r" :db/ident :dev/ref]
                 [:db/add "r" :db/valueType :db.type/ref]
                 [:db/add "r" :db/cardinality :db.cardinality/one]])
  (memory/install conn)
  (memory/session conn {:id "run-a"})
  (memory/session conn {:id "run-b"})
  (let [t1 (memory/record conn "run-a" [{:db/id "p1" :dev/title "Ada"
                                         :dev/note "analytical engine"}])
        p1 (get-in t1 [:tempids "p1"])
        _ (memory/record conn "run-a" [{:db/id p1 :dev/title "Ada Lovelace"}])
        t3 (memory/record conn "run-a" [{:db/id p1 :dev/note "difference engine"}])
        _ (memory/record conn "run-b" [{:db/id "p2" :dev/title "Bob"}
                                       {:db/id "p2" :dev/ref p1}])
        _ (memory/record conn "run-b" [{:db/id "n1" :dev/note "derived"
                                        :memory/source (:tx t3)}])])
  :seeded)

(defn- parse-args
  "The two connection postures plus the knobs:
     --store PATH    start an owned transactor on this store directory
     --attach H:P:T  attach to a running transactor (token optional)
     --seed          record the deterministic fixture (with --store only)
     --port N        the monitor's UI port (default 8091)"
  [args]
  (loop [args args
         {:keys [store attach seed port] :as m} {:port 8091}]
    (if-let [a (first args)]
      (cond
        (= a "--store") (recur (nnext args) (assoc m :store (second args)))
        (= a "--attach") (recur (nnext args) (assoc m :attach (second args)))
        (= a "--seed") (recur (next args) (assoc m :seed true))
        (= a "--port") (recur (nnext args) (assoc m :port (parse-long (second args))))
        :else (throw (ex-info (str "unknown argument " a)
                              {:kala/error :monitor/bad-arg :arg a})))
      m)))

(defn -main [& args]
  (let [{:keys [store attach seed port]} (parse-args args)
        ;; connect: own a transactor over the store, or ride a running one
        [conn tx-port stop-transactor]
        (cond
          store
          (let [t (transactor/start! {:storage :file :path store :port 0})]
            [(client/connect {:host "127.0.0.1" :port (:port t)})
             (:port t)
             (:stop! t)])
          attach
          (let [[host p token] (str/split attach #":")]
            [(client/connect (cond-> {:host host :port (parse-long p)}
                               token (assoc :token token)))
             (parse-long p)
             nil])
          :else
          (throw (ex-info "give the monitor a store (--store PATH) or a transactor (--attach HOST:PORT[:TOKEN])"
                          {:kala/error :monitor/bad-arg})))
        head (atom (:t (client/db conn)))
        ;; the listener: the wire's push channel, bumped on every commit —
        ;; follow polls head, freeze ignores it (the subscription itself
        ;; needs no handle; closing the process tears it down)
        _ (client/listen conn
                         (fn [{:keys [notification t]}]
                           (when (= :tx-completed notification)
                             (reset! head t))))]
    (when seed (seed! conn) (reset! head (:t (client/db conn))))
    (reset! state {:conn conn :head head})
    (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
      (.createContext server "/" (reify HttpHandler
                                   (handle [_ ex]
                                     (try (statics ex)
                                          (catch Throwable t
                                            (println :STATIC-ERR (.getMessage t))
                                            (httpd/respond! ex 500 "text/plain; charset=utf-8"
                                                            (str "static error: " (.getMessage t))))
                                          (finally (.close ex))))))
      (doseq [[path f] (partition 2
                                  ["/api/graph" endpoint-graph
                                   "/api/head" endpoint-head
                                   "/api/turn" endpoint-turn
                                   "/api/entity" endpoint-entity
                                   "/api/sessions" endpoint-sessions])]
        (.createContext server path (httpd/handler f)))
      (.setExecutor server (Executors/newCachedThreadPool))
      (.start server)
      (println "Kala monitor → http://127.0.0.1:" port)
      (println "transactor → 127.0.0.1:" tx-port (when stop-transactor "(owned)"))
      ;; park forever: Ctrl+C (or the process manager) ends everything —
      ;; the daemon listener dies with the JVM and the store stays on disk
      @(promise))))
