(ns kala.httpd
  "The web apps' shared HTTP plumbing: a tiny JSON writer whose values
   render as pr-str'd EDN — the presentation language end to end — the
   response and query-param helpers, and the endpoint wrapper that turns
   any ex-info into the project's error envelope, kinds and all. JDK's
   built-in HttpServer only; zero dependencies beyond org.clojure/clojure."
  (:require [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler]
           [java.nio.charset StandardCharsets]))

(defn jlit ^String [^String s]
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
    (map? v) (str "{" (str/join "," (map (fn [[k x]]
                                           (str (jlit (name k)) ":" (jval x)))
                                         v)) "}")
    (nil? v) "null"
    :else (jlit (pr-str v))))

(defn jobj ^String [m]
  (str "{" (str/join "," (map (fn [[k v]] (str (jlit (name k)) ":" (jval v))) m)) "}"))

(defn body-text ^String [^HttpExchange ex]
  (slurp (.getRequestBody ex) :encoding "UTF-8"))

(defn respond! [^HttpExchange ex status ^String ctype ^String body]
  (let [bytes (.getBytes body StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders ex) "Content-Type" ctype)
    (.sendResponseHeaders ex status (alength bytes))
    (doto (.getResponseBody ex)
      (.write bytes)
      (.close))))

(defn respond-json! [^HttpExchange ex status m]
  (respond! ex status "application/json; charset=utf-8" (jobj m)))

(defn params [^HttpExchange ex]
  (into {}
        (for [pair (str/split (or (.. ex getRequestURI getQuery) "") #"&")
              :when (pos? (.length pair))
              :let [[k v] (str/split pair #"=" 2)]]
          [(keyword k) (or v "")])))

(defn handler
  "Wrap an endpoint fn: it receives the exchange and parsed query params
   and returns a response map whose :m is the envelope and :status the
   optional HTTP status; any ex-info becomes the project's error envelope
   over the wire — kinds and all. An app may name its own :fallback-kind
   for the non-ex-info Throwable branch."
  ([endpoint-fn] (handler endpoint-fn nil))
  ([endpoint-fn {:keys [fallback-kind]}]
   (reify HttpHandler
    ;; no arg hints here: inside reify the compiler treats hinted args'
    ;; interop as Object and rejects it for this void method — the try/
    ;; finally shape is the one that compiles clean
    (handle [_ ex]
      (try
        (let [{:keys [status m]} (endpoint-fn ex (params ex))]
          (respond-json! ex (or status 200) m))
        (catch clojure.lang.ExceptionInfo e
          (respond-json! ex 400 {:ok false
                                 :kind (:kala/error (ex-data e))
                                 :message (.getMessage e)
                                 :data (dissoc (ex-data e) :kala/error)}))
        (catch Throwable e
          (println :ENDPOINT-ERR (.getMessage e))
          (.printStackTrace e)
          (respond-json! ex 400 {:ok false
                                 :kind (or fallback-kind :httpd/bad-request)
                                 :message (.getMessage e)}))
        (finally (.close ex)))))))
