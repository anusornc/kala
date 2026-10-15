(ns kala.wire
  "Wire framing and codec for the transactor protocol: each message is a
  4-byte big-endian length followed by UTF-8 EDN. Byte arrays (the one EDN
  gap) travel as #dos/b64 base64 tags; #inst and #uuid use the default EDN
  readers. The protocol's op table lives here too — the single source the
  connection loop, handle-op, the client, and docs/wire-protocol.md all
  read (#26)."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:import [java.io DataInputStream DataOutputStream]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def protocol-version 1)

;; ---- op table ------------------------------------------------------------------
;;
;; One spec per op, declared once: the connection loop locks whatever the
;; table flags :is-write? (so a new write op cannot skip serialization —
;; the flag must be declared, never inferred), the client merges db-marker
;; fields only for :db-reference? ops, and the doc-honesty test pins
;; docs/wire-protocol.md to this table. Plain data, not a handler registry:
;; handlers close over kala.api and would drag client→wire→api→client.

(def ops
  {:connect {:request [:protocol-version]
             :reply [:protocol-version :t]
             :is-write? false}
   :db {:request []
        :reply [:t]
        :is-write? false}
   :transact {:request [:tx-data]
              :reply [:t :tx-data :tempids]
              :is-write? true}
   :with {:request [:tx-data]
          :reply [:t :db-ref :tx-data :tempids]
          :is-write? false
          :db-reference? true}
   :q {:request [:query :args]
       :reply [:rows]
       :is-write? false
       :db-reference? true}
   :pull {:request [:pattern :eid]
          :reply [:doc]
          :is-write? false
          :db-reference? true}
   :datoms {:request [:index :components]
            :reply [:datoms]
            :is-write? false
            :db-reference? true}
   :listen {:request []
            :reply [:listening]
            :is-write? false}})

(defn op-spec
  "One op's spec from the table, or nil."
  [op]
  (get ops op))

(defn write-op?
  "Does this op serialize through the single-writer lock? Keyed off the
  op table — the connection loop's only write-ordering knowledge."
  [op]
  (boolean (:is-write? (op-spec op))))

;; ---- error data ----------------------------------------------------------------
;;
;; Which ex-data keys an error kind ships over the wire, so client-mode
;; error data is not silently thinner than peer mode. Keys must be
;; EDN-safe (they ride the reply frame). Every kind the transactor's
;; handlers can see is enumerated — including storage failures raised
;; mid-transact; kinds that can only surface outside a reply (peer-side
;; connect, the client's own boundary) are enumerated too. A kind's keys
;; are the union its raise sites carry; a site may ship a subset. Unknown
;; kinds keep the historical whitelist.

(def error-data-keys
  {:protocol/version-mismatch [:server-version :client-version]
   :protocol/connect-required [:op]
   :protocol/bad-frame []
   :transactor/unknown-op [:op]
   :connect/missing-path []
   :connect/unknown-storage [:storage]
   :client/peer-only [:op]
   :client/error []
   :db/bad-t [:t :e]
   :db/view-not-writable [:view]
   :storage/non-monotonic-t [:t :last-t]
   :storage/rename-failed [:target]
   :transact/bad-op [:op :tx-data]
   :transact/bad-entity [:e :v :op]
   :transact/unknown-attribute [:attribute]
   :transact/unknown-ident [:ident]
   :transact/unknown-value-type [:attribute :value-type]
   :transact/bad-value-type [:attribute :value-type :op]
   :transact/unknown-cardinality [:attribute :cardinality]
   :transact/unique-not-supported [:attribute]
   :transact/unique-conflict [:op]
   :transact/conflicting-upserts [:op]
   :transact/cas-failed [:op :old :new :actual]
   :transact/nested-map-requires-component [:attribute]
   :transact/unknown-tx-fn [:op :ident]
   :transact/bad-tx-fn [:op :source]
   :transact/tx-fn-failed [:fn :message]
   :transact/tx-fn-overflow [:iterations]
   :datoms/unknown-index [:index]
   :datoms/bad-components [:index :components]
   :pull/bad-pattern [:expr :option :element :pattern :spec]
   :pull/unknown-attribute [:attribute]
   :query/bad-query []
   :query/no-db []
   :query/arity [:expected :given]
   :query/bad-clause [:clause]
   :query/bad-find [:find]
   :query/bad-aggregate [:expr :find]
   :query/bad-attribute [:attribute]
   :query/unknown-attribute [:attribute]
   :query/unknown-ident [:ident]
   :query/unknown-rule [:rule :arity]
   :query/bad-rules [:rules :rule]
   :query/unknown-aggregate [:aggregate]
   :query/unknown-builtin [:fn :clause]
   :query/unbound-var [:var :clause]
   :query/bad-binding [:clause :slot :binding]
   :query/rule-overflow [:iterations :rules]})

(def ^:private default-error-data-keys [:attribute :e :t :index :op])

(defn error-data-keys-for
  "The ex-data keys an error kind ships over the wire."
  [kind]
  (get error-data-keys kind default-error-data-keys))

;; The db-marker fields a :db-reference? op accepts: as-of :t, a :since-t
;; view, :history, or a :db-ref speculative value. docs/wire-protocol.md's
;; "optional" clause per read op is these, and the doc-honesty test pins
;; the union per op.
(def db-reference-fields [:t :since-t :history :db-ref])

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
  (a truncated frame never decodes). A frame that arrived whole but
  cannot be read as EDN throws ex-info carrying {:frame <raw text>}
  with the reader error as cause (#32) — the transactor turns that
  into a :protocol/bad-frame reply and closes the connection."
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
                (try
                  (edn/read-string {:readers {'dos/b64 b64-decode
                                              'dos/float float
                                              'dos/uri to-uri}}
                                   frame)
                  (catch Exception e
                    (throw (ex-info "Unparseable frame" {:frame frame} e)))))))))
      (catch java.io.EOFException _ nil)
      (catch java.net.SocketException _ nil))))

(defn write-msg
  "Write one message and flush."
  [out msg]
  (let [dos (DataOutputStream. out)
        payload (.getBytes (pr-str (to-edn msg)) StandardCharsets/UTF_8)]
    (.writeInt dos (alength payload))
    (.write dos payload)
    (.flush dos)))

(defn write-msg-str
  "Debug helper: the exact wire payload for a message."
  [msg]
  (pr-str (to-edn msg)))
