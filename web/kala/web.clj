(ns kala.web
  "Interactive web demo: Kala embedded as a peer behind a tiny HTTP API
  served by the JDK's built-in HttpServer — zero dependencies beyond
  org.clojure/clojure. The browser UI (web/public) talks EDN-over-text:
  request bodies are EDN, responses are small hand-written JSON envelopes
  whose values are pr-str'd, so EDN stays the presentation language end to
  end.

  Run:   clojure -M:web [port]        (default port 8090, binds 127.0.0.1)
  Opens on the demo people fixture, in file storage under a fresh temp
  directory so the /api/reopen endpoint can prove durability live: write,
  reopen from the same directory, and the data is still there.

  Trust note: tx-data arrives as EDN and is read with clojure.edn — the
  same documented no-sandboxing posture as transaction functions (ADR-0004):
  a web demo runs where you would run the database itself. Binds localhost
  only."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.string :as str]
            [kala.api :as api])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent Executors]
           [java.util UUID]))

;; ---- state -----------------------------------------------------------------

(defonce state (atom {:conn nil :dir nil}))

(defn- tmp-dir! []
  (str (Files/createTempDirectory
        (str "kala-web-" (UUID/randomUUID))
        (make-array FileAttribute 0))))

;; ---- seed (the demo people fixture) ------------------------------------------

(defn- define! [conn ident vt card]
  (api/transact conn [[:db/add (name ident) :db/ident ident]
                      [:db/add (name ident) :db/valueType vt]
                      [:db/add (name ident) :db/cardinality card]]))

(defn- seed! [conn]
  (define! conn :person/name :db.type/string :db.cardinality/one)
  (define! conn :person/age :db.type/long :db.cardinality/one)
  (define! conn :person/friend :db.type/ref :db.cardinality/one)
  (define! conn :person/tag :db.type/string :db.cardinality/many)
  (define! conn :person/parent :db.type/ref :db.cardinality/many)
  (let [{:keys [tempids]} (api/transact conn
                            [[:db/add "alice" :person/name "Alice"]
                             [:db/add "alice" :person/age 30]
                             [:db/add "bob" :person/name "Bob"]
                             [:db/add "bob" :person/age 25]
                             [:db/add "carol" :person/name "Carol"]
                             [:db/add "carol" :person/age 35]
                             [:db/add "dora" :person/name "Dora"]
                             [:db/add "dora" :person/age 60]])
        alice (get tempids "alice")
        bob (get tempids "bob")
        carol (get tempids "carol")
        dora (get tempids "dora")]
    (api/transact conn
      [[:db/add alice :person/friend bob]
       [:db/add carol :person/parent alice]
       [:db/add carol :person/parent bob]
       [:db/add dora :person/parent carol]])
    (api/transact conn
      [{:db/id "dave" :person/name "Dave" :person/age 28
        :person/tag ["clojure" "datalog"]}])))

;; ---- tiny JSON writer (values render as EDN strings on purpose) --------------

(defn- jlit ^String [^String s]
  (str "\"" (-> s
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\"")
                (str/replace "\n" "\\n")
                (str/replace "\r" "\\r")
                (str/replace "\t" "\\t"))
       "\""))

(declare jval)

(defn- ^:private jval [v]
  (cond
    (instance? Boolean v) (str v)
    (number? v) (str v)
    (string? v) (jlit v)
    (sequential? v) (str "[" (str/join "," (map jval v)) "]")
    :else (jlit (pr-str v))))

(defn- jobj ^String [m]
  (str "{" (str/join "," (map (fn [[k v]] (str (jlit (name k)) ":" (jval v))) m)) "}"))

;; ---- http plumbing -------------------------------------------------------------

(defn- body-text ^String [^HttpExchange ex]
  (slurp (.getRequestBody ex) :encoding "UTF-8"))

(defn- respond! [^HttpExchange ex status ^String ctype ^String body]
  (let [bytes (.getBytes body StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders ex) "Content-Type" ctype)
    (.sendResponseHeaders ex status (alength bytes))
    (doto (.getResponseBody ex)
      (.write bytes)
      (.close))))

(defn- respond-json! [^HttpExchange ex status m]
  (respond! ex status "application/json; charset=utf-8" (jobj m)))

(defn- params [^HttpExchange ex]
  (into {}
        (for [pair (str/split (or (.. ex getRequestURI getQuery) "") #"&")
              :when (pos? (.length pair))
              :let [[k v] (str/split pair #"=" 2)]]
          [(keyword k) (or v "")])))

(defn- statics
  "Whitelisted static resources off the classpath (web/public is a classpath
  root, so resource names are bare)."
  [^HttpExchange ex]
  (let [path (.. ex getRequestURI getPath)
        served {"/" ["index.html" "text/html; charset=utf-8"]
                "/index.html" ["index.html" "text/html; charset=utf-8"]
                "/app.js" ["app.js" "application/javascript; charset=utf-8"]
                "/styles.css" ["styles.css" "text/css; charset=utf-8"]}]
    (if-let [[_ [resource ctype]] (find served path)]
      (let [src (io/resource resource)]
        (if src
          (respond! ex 200 ctype (slurp src :encoding "UTF-8"))
          (respond! ex 404 "text/plain; charset=utf-8" "not found")))
      (respond! ex 404 "text/plain; charset=utf-8" "not found"))))

(defn- handler
  "Wrap an api fn: it receives the exchange and parsed query params and
  returns a response map; any ex-info becomes the project's error envelope
  over the wire — kinds and all."
  [api-fn]
  (reify HttpHandler
    ;; no arg hints here: inside reify the compiler treats hinted args'
    ;; interop as Object and rejects it for this void method — the try/
    ;; finally shape is the one that compiles clean
    (handle [_ ex]
      (try
        (let [{:keys [status m]} (api-fn ex (params ex))]
          (respond-json! ex (or status 200) m))
        (catch clojure.lang.ExceptionInfo e
          (respond-json! ex 400 {:ok false
                                 :kind (:kala/error (ex-data e))
                                 :message (.getMessage e)
                                 :data (dissoc (ex-data e) :kala/error)}))
        (catch Throwable e
          (respond-json! ex 400 {:ok false
                                 :kind :web/bad-input
                                 :message (.getMessage e)}))
        (finally (.close ex))))))

;; ---- api endpoints ---------------------------------------------------------------

(defn- db-with-as-of [as-of]
  (let [db (api/db (:conn @state))]
    (if as-of (api/as-of db (parse-long as-of)) db)))

(defn- find-columns [q]
  (->> (drop-while #(not= :find %) q)
       rest
       (take-while #(not (keyword? %)))
       (mapv pr-str)))

(defn- api-query [^HttpExchange ex {:keys [as-of]}]
  (let [q (edn/read-string (body-text ex))
        db (db-with-as-of as-of)
        t0 (System/nanoTime)
        rel (api/q q db)
        rows (->> rel (mapv vec) (sort-by pr-str) (take 500) (mapv #(mapv pr-str %)))
        ms (/ (- (System/nanoTime) t0) 1e6)]
    {:m {:ok true
         :columns (find-columns q)
         :rows rows
         :t (api/db-t db)
         :as-of (or as-of "")
         :ms ms}}))

(defn- api-transact [^HttpExchange ex _]
  (let [tx-data (edn/read-string (body-text ex))
        report (api/transact (:conn @state) tx-data)
        tx-datoms (mapv (fn [d]
                          (str (:e d) " " (:a d) " " (pr-str (:v d))
                               (when (false? (:added d)) " retract")))
                        (:tx-data report))
        tempids (mapv (fn [[k v]] (str (pr-str k) " → " v))
                      (sort-by (comp str key) (:tempids report)))]
    {:m {:ok true
         :t (api/db-t (:db-after report))
         :tx-data tx-datoms
         :tempids tempids}}))

(defn- api-datoms [_ {:keys [as-of]}]
  (let [db (db-with-as-of as-of)
        rows (->> (api/datoms db :eavt [])
                  (take 500)
                  (mapv (fn [d] [(str (:e d)) (pr-str (:a d)) (pr-str (:v d)) (str (:tx d))])))]
    {:m {:ok true :rows rows :t (api/db-t db)}}))

(defn- api-history [_ _]
  (let [db (api/db (:conn @state))
        rows (->> (api/datoms (api/history db) :eavt [])
                  (take 500)
                  (mapv (fn [d] [(str (:e d)) (pr-str (:a d)) (pr-str (:v d))
                                 (str (:tx d)) (if (:added d) "+" "-")])))]
    {:m {:ok true :rows rows}}))

(defn- api-meta [_ _]
  (let [db (api/db (:conn @state))
        attrs (->> (api/q '[:find ?i :where [?e :db/ident ?i]] db)
                   (mapv #(pr-str (first %)))
                   sort)
        ;; per-t labels for the as-of dropdown: what each step of the log
        ;; added, so time travel reads like the story it is
        idents-at (fn [t]
                    (set (map first (api/q '[:find ?i :where [?e :db/ident ?i]]
                                           (api/as-of db t)))))
        top-t (api/db-t db)
        prev (volatile! #{})
        labels (mapv (fn [t]
                       (let [ids (idents-at t)
                             added (sort (cset/difference ids @prev))
                             datoms (count (api/datoms (api/as-of db t) :eavt []))]
                         (vreset! prev ids)
                         (str "t = " t " · "
                              (cond
                                (empty? added) (str "no new idents")
                                (<= (count added) 3) (str "+ " (str/join " " (map pr-str added)))
                                :else (str "+ " (count added) " idents"))
                              " · " datoms " datoms")))
                     (range 1 (inc top-t)))]
    {:m {:ok true
         :t top-t
         :datoms (count (api/datoms db :eavt []))
         :dir (:dir @state)
         :attributes attrs
         :timeline-t (vec (range 1 (inc top-t)))
         :timeline-labels labels}}))

(defn- api-reopen [_ _]
  (let [{:keys [dir] :as s} @state
        fresh (api/connect {:storage :file :path dir})]
    (reset! state (assoc s :conn fresh))
    {:m {:ok true
         :t (api/db-t (api/db fresh))
         :message (str "reopened from " dir)}}))

;; ---- main ------------------------------------------------------------------------

(defn -main [& args]
  (let [port (or (some-> args first parse-long) 8090)
        dir (tmp-dir!)
        conn (api/connect {:storage :file :path dir})]
    (reset! state {:conn conn :dir dir})
    (seed! conn)
    (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
      (.createContext server "/" (reify HttpHandler
                                   (handle [_ ex]
                                     (try (statics ex)
                                          (catch Throwable t
                                            (println :STATIC-ERR (.getMessage t))
                                            (respond! ex 500 "text/plain; charset=utf-8"
                                                      (str "static error: " (.getMessage t))))
                                          (finally (.close ex))))))
      (doseq [[path f] (partition 2
                                  ["/api/query" api-query
                                   "/api/transact" api-transact
                                   "/api/datoms" api-datoms
                                   "/api/history" api-history
                                   "/api/meta" api-meta
                                   "/api/reopen" api-reopen])]
        (.createContext server path (handler f)))
      (.setExecutor server (Executors/newCachedThreadPool))
      (.start server)
      (println "Kala web demo → http://127.0.0.1:" port)
      (println "storage dir:" dir))))
