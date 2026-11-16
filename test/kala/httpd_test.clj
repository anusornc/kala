(ns kala.httpd-test
  "The shared HTTP plumbing's contract, tested at its interface: the
   EDN→JSON mapping, the query-param decoding, the endpoint wrapper's
   envelopes (success :ok injection, ex-info error kinds, the Throwable
   fallback), and one composition slice — the monitor's real endpoint
   table driven over a real loopback HTTP server on a plain in-memory
   conn, no booted store. Full-app journeys stay with the bash e2e
   scripts."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kala.httpd :as httpd]
            [kala.monitor :as monitor]
            [kala.test-util :refer [fresh-conn]])
  (:import [com.sun.net.httpserver HttpServer]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(defn- with-server
  "Boots a real JDK HttpServer on an ephemeral loopback port around
   handler-map (path → HttpHandler), runs f with the port, stops."
  [handlers f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (doseq [[path h] handlers]
      (.createContext server path h))
    (.start server)
    (try (f (.getPort (.getAddress server)))
         (finally (.stop server 0)))))

(defn- get!
  "GET http://127.0.0.1:<port><path>?<query> -> {:status s :body str}."
  [port path query]
  (let [client (HttpClient/newHttpClient)
        req (.. HttpRequest
                newBuilder
                (uri (URI. (str "http://127.0.0.1:" port path
                                (when (seq query) (str "?" query)))))
                GET
                build)
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (.body resp)}))

(deftest the-json-mapping-is-edn-on-the-wire
  (testing "keywords keep their colon; booleans are lowercase; nil is null;
            leaf values fall through to pr-str"
    (is (= (str "{\"ok\":true,\"kw\":\":assert\",\"s\":\"hi\",\"n\":3,\"none\":null,"
                "\"vec\":[1,2],\"map\":{\"inner\":true},\"ms\":1.5}")
           (httpd/jobj {:ok true :kw :assert :s "hi" :n 3 :none nil
                        :vec [1 2] :map {:inner true} :ms 1.5}))))
  (testing "a set serializes as an array in pr-str order; a non-keyword
            map key renders as its pr-str instead of throwing"
    (is (= "{\"tags\":[\":alpha\",\":beta\"],\"42\":1}"
           (httpd/jobj {:tags #{:beta :alpha} 42 1})))))

(deftest params-decode-the-query-string
  (is (= {:a "1" :b "run&x" :c "two words" :d ""}
         (httpd/parse-params "a=1&b=run%26x&c=two+words&d")))
  (is (= {} (httpd/parse-params nil)))
  (is (= {} (httpd/parse-params "")))
  (is (= {} (httpd/parse-params "=x")) "a pair with an empty key drops"))

(deftest the-success-envelope-gets-ok-injected
  (with-server {"/x" (httpd/handler (fn [_deps _ex _params] {:m {:t 7}}) ::deps)}
    (fn [port]
      (testing "the success envelope gets {:ok true} injected ahead of the
                endpoint's own keys — endpoints never write it"
        (is (= {:status 200 :body "{\"ok\":true,\"t\":7}"}
               (get! port "/x" "")))))))

(deftest an-ex-info-becomes-the-error-envelope
  (with-server {"/x" (httpd/handler
                      (fn [_deps _ex _params]
                        (throw (ex-info "t must be an integer"
                                        {:kala/error :monitor/bad-param
                                         :t "abc"})))
                      ::deps)}
    (fn [port]
      (testing "the project's error envelope, kind and all"
        (is (= {:status 400
                :body (str "{\"ok\":false,\"kind\":\":monitor/bad-param\","
                           "\"message\":\"t must be an integer\",\"data\":{\"t\":\"abc\"}}")}
               (get! port "/x" "")))))))

(deftest a-plain-throwable-falls-back-to-the-fallback-kind
  (with-server {"/x" (httpd/handler
                      (fn [_deps _ex _params]
                        (throw (RuntimeException. "boom")))
                      ::deps
                      {:fallback-kind :web/bad-input})}
    (fn [port]
      (testing "the message only — stack-trace noise stays off the wire"
        (is (str/starts-with? (:body (get! port "/x" ""))
                              "{\"ok\":false,\"kind\":\":web/bad-input\",\"message\":\"boom\"}"))))))

(deftest the-monitor-endpoint-table-runs-on-a-plain-conn
  ;; the composition slice: the real endpoint table over a fresh
  ;; in-memory conn — coercion, deps wiring, drilldowns — no booted
  ;; store. Live statics and full journeys stay in monitor-e2e.sh.
  (let [state (atom {:conn (fresh-conn) :head (atom 5)})
        handlers (apply hash-map (monitor/endpoint-table state))
        get (fn [port path query] (get! port path query))]
    (with-server handlers
      (fn [port]
        (testing "head reads the injected state, not a global"
          (is (= {:status 200 :body "{\"ok\":true,\"t\":5}"}
                 (get port "/api/head" ""))))
        (testing "the drilldowns answer on the same conn"
          (is (= {:status 200 :body "{\"ok\":true,\"t\":999,\"datoms\":[]}"}
                 (get port "/api/turn" "t=999")))
          (is (= {:status 200 :body "{\"ok\":true,\"id\":999,\"trail\":[]}"}
                 (get port "/api/entity" "id=999"))))
        (testing "bad params raise the app's kind over the wire"
          (let [{:keys [status body]} (get port "/api/turn" "t=abc")]
            (is (= 400 status))
            (is (str/starts-with? body "{\"ok\":false,\"kind\":\":monitor/bad-param\"")))
          (is (= (str "{\"ok\":false,\"kind\":\":monitor/bad-param\","
                      "\"message\":\"id must be an entity id\",\"data\":{\"id\":\"zzz\"}}")
                 (:body (get port "/api/entity" "id=zzz")))))))))
