(ns kala.httpd
  "The web apps' shared HTTP plumbing: a tiny JSON writer whose values
   render as pr-str'd EDN — the presentation language end to end — the
   response and query-param helpers, the static-file handler, and the
   endpoint wrapper that turns any ex-info into the project's error
   envelope, kinds and all. JDK's built-in HttpServer only; zero
   dependencies beyond org.clojure/clojure.

   The EDN→JSON mapping, the contract both web apps build on:

   - an endpoint returns {:m {...} :status ...}; the handler unwraps it,
     so the body IS the :m map (never a {\"m\": ...} wrapper),
   - the handler owns the success envelope's :ok flag — it injects
     {:ok true} ahead of the endpoint's own keys; endpoints never
     write it,
   - keywords serialize as strings that keep the leading colon
     (\":assert\", \":dev/title\") — EDN on the wire; booleans are JSON
     lowercase, nil is null, maps serialize as nested JSON objects, and
     every other value (keywords among them) falls through to pr-str,
   - a set serializes as a JSON array in pr-str order, and a map key
     that is neither keyword nor string renders as its pr-str — neither
     occurs in the apps' envelopes today; both render instead of
     throwing mid-response,
   - so error-envelope :data is a nested JSON object whose LEAF values
     are still pr-str'd EDN strings."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler]
           [java.net URLDecoder]
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

(defn- ^:private key-str ^String [k]
  (cond
    (keyword? k) (name k)
    (string? k) k
    :else (pr-str k)))

(defn- ^:private jval [v]
  (cond
    (instance? Boolean v) (str v)
    (number? v) (str v)
    (string? v) (jlit v)
    (sequential? v) (str "[" (str/join "," (map jval v)) "]")
    (set? v) (str "[" (str/join "," (map jval (sort-by pr-str v))) "]")
    (map? v) (str "{" (str/join "," (map (fn [[k x]]
                                           (str (jlit (key-str k)) ":" (jval x)))
                                         v)) "}")
    (nil? v) "null"
    :else (jlit (pr-str v))))

(defn jobj ^String [m]
  (str "{" (str/join "," (map (fn [[k v]] (str (jlit (key-str k)) ":" (jval v))) m)) "}"))

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

(defn parse-params
  "A raw query string decoded: keyword keys, URL-decoded values (+ reads
   as a space, per the query-string convention). A pair without '=' has
   an empty value; a malformed escape raises, and the endpoint wrapper
   turns that into the 400 error envelope."
  [^String query]
  (into {}
        (for [pair (str/split (or query "") #"&")
              :when (pos? (.length pair))
              :let [[k v] (str/split pair #"=" 2)]
              :when (seq k)]
          [(keyword (URLDecoder/decode k "UTF-8"))
           (URLDecoder/decode (or v "") "UTF-8")])))

(defn params [^HttpExchange ex]
  (parse-params (.. ex getRequestURI getQuery)))

(defn static-handler
  "The root HttpHandler of a web app: serves the whitelist map
   {path [resource content-type]} off the classpath (resource names are
   bare — the app's static dir is a classpath root), 404s misses and
   absent resources, wraps read failures in the 500 plain-text
   response, and always closes the exchange."
  [served]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (if-let [[_ [resource ctype]] (find served (.. ex getRequestURI getPath))]
          (let [src (io/resource resource)]
            (if src
              (respond! ex 200 ctype (slurp src :encoding "UTF-8"))
              (respond! ex 404 "text/plain; charset=utf-8" "not found")))
          (respond! ex 404 "text/plain; charset=utf-8" "not found"))
        (catch Throwable t
          (println :STATIC-ERR (.getMessage t))
          (respond! ex 500 "text/plain; charset=utf-8"
                    (str "static error: " (.getMessage t))))
        (finally (.close ex))))))

(defn handler
  "Wrap an endpoint fn: it receives [deps exchange parsed-params] — deps
   is whatever the app passes (typically its state atom), so the
   endpoint table is constructible — and testable — without a booted
   server; it returns a response map whose :m is the envelope and
   :status the optional HTTP status. The wrapper injects the success
   envelope's {:ok true} ahead of the endpoint's own keys, and turns any
   ex-info into the project's error envelope over the wire — kinds and
   all. An app may name its own :fallback-kind for the non-ex-info
   Throwable branch."
  ([endpoint-fn deps] (handler endpoint-fn deps nil))
  ([endpoint-fn deps {:keys [fallback-kind]}]
   (reify HttpHandler
    ;; no arg hints here: inside reify the compiler treats hinted args'
    ;; interop as Object and rejects it for this void method — the try/
    ;; finally shape is the one that compiles clean
    (handle [_ ex]
      (try
        (let [{:keys [status m]} (endpoint-fn deps ex (params ex))]
          (respond-json! ex (or status 200) (into {:ok true} m)))
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
