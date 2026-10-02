(ns datomic-opensource.wire
  "Wire framing and codec for the transactor protocol: each message is a
  4-byte big-endian length followed by UTF-8 EDN. Byte arrays (the one EDN
  gap) travel as #dos/b64 base64 tags; #inst and #uuid use the default EDN
  readers."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:import [java.io DataInputStream DataOutputStream]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def protocol-version 1)

;; incoming frames larger than this are rejected before allocation
(def ^:private max-frame-bytes (* 64 1024 1024))

(defn- b64-encode [^bytes bs]
  (String. (.encode (Base64/getEncoder) bs) StandardCharsets/UTF_8))

(defn- b64-decode [s]
  (.decode (Base64/getDecoder) (str s)))

(defn- to-uri [s]
  (java.net.URI. (str s)))

(defn- to-edn [x]
  (walk/postwalk (fn [v]
                   (cond
                     (bytes? v) (tagged-literal 'dos/b64 (b64-encode v))
                     (instance? Float v) (tagged-literal 'dos/float (float v))
                     (instance? java.net.URI v) (tagged-literal 'dos/uri (str v))
                     :else v))
                 x))

(defn read-msg
  "One message from the stream, or nil at EOF — clean or mid-frame
  (a truncated frame never decodes)."
  [in]
  (let [dis (DataInputStream. in)]
    (try
      (let [len (.readInt dis)]
        (when (and (pos? len) (<= len max-frame-bytes))
          (let [buf (byte-array len)
                read (loop [off 0]
                       (if (< off len)
                         (let [n (.read dis buf off (- len off))]
                           (if (pos? n)
                             (recur (+ off n))
                             off))                       ; EOF mid-frame
                         len))]
            (when (= read len)
              (let [frame (String. buf StandardCharsets/UTF_8)]
                (println :WIRE-READ (subs frame 0 (min 200 (count frame))))
                (edn/read-string {:readers {'dos/b64 b64-decode
                                            'dos/float float
                                            'dos/uri to-uri}}
                                 frame))))))
      (catch java.io.EOFException _ nil)
      (catch java.net.SocketException _ nil))))

(defn write-msg
  "Write one message and flush."
  [out msg]
  (let [dos (DataOutputStream. out)
        payload (.getBytes (pr-str (to-edn msg)) StandardCharsets/UTF_8)]
    (println :WIRE-WRITE (subs (String. payload StandardCharsets/UTF_8) 0 (min 200 (alength payload))))
    (.writeInt dos (alength payload))
    (.write dos payload)
    (.flush dos)))

(defn write-msg-str
  "Debug helper: the exact wire payload for a message."
  [msg]
  (pr-str (to-edn msg)))
