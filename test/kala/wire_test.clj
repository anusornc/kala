(ns kala.wire-test
  "Codec contract: framing round-trips, and the codec is silent — frame
  echo on every read/write was debug residue in the highest-traffic seam
  (#20). Also the protocol's op table: the single source the connection
  loop, handle-op, the client, and docs/wire-protocol.md all agree on
  (#26)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kala.wire :as wire])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream DataOutputStream]))

(defn- round-trip [msg]
  (let [baos (ByteArrayOutputStream.)]
    (wire/write-msg (DataOutputStream. baos) msg)
    (wire/read-msg (DataInputStream. (ByteArrayInputStream. (.toByteArray baos))))))

(defn- captured-stdout [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*out* sw]
      (f))
    (str sw)))

(defn- write-raw-frame! [^java.io.OutputStream out ^String s]
  (let [bytes (.getBytes s "UTF-8")]
    (doto (DataOutputStream. out)
      (.writeInt (alength bytes))
      (.write bytes))
    (.flush out)))

(defn- read-raw [^String s]
  (let [baos (ByteArrayOutputStream.)]
    (write-raw-frame! baos s)
    (wire/read-msg (DataInputStream. (ByteArrayInputStream. (.toByteArray baos))))))

(deftest messages-round-trip
  (testing "plain EDN message"
    (is (= {:op :transact :tx-data [[:db/add 1 :a 2]]}
           (round-trip {:op :transact :tx-data [[:db/add 1 :a 2]]}))))
  (testing "bytes travel the dos/b64 tag"
    (is (= [1 2 3] (map int (:b (round-trip {:b (byte-array [1 2 3])})))))))

(deftest codec-is-silent
  (testing "read-msg/write-msg never print — every frame, both ends"
    (is (zero? (count (captured-stdout #(round-trip {:op :echo :x 1})))))
    (is (zero? (count (captured-stdout #(round-trip {:big (vec (repeat 300 "x"))})))))))

;; ---- unparseable frames (#32) ---------------------------------------------------

(deftest unparseable-frames-raise-carrying-the-frame
  (testing "an unknown reader tag raises ex-info carrying the raw frame"
    (let [e (try (read-raw "{:op :q :args [#kala.no/Such {}]}") nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (.contains (ex-message e) "Unparseable"))
      (is (= "{:op :q :args [#kala.no/Such {}]}" (:frame (ex-data e))))
      (is (.contains (ex-message (ex-cause e)) "kala.no/Such"))))
  (testing "invalid EDN syntax raises the same way"
    (is (thrown? clojure.lang.ExceptionInfo (read-raw "{:op :q"))))
  (testing "clean EOF and truncated frames still read as nil"
    (is (nil? (wire/read-msg (DataInputStream. (ByteArrayInputStream. (byte-array 0))))))
    (let [baos (ByteArrayOutputStream.)
          dos (DataOutputStream. baos)]
      (.writeInt dos 10)
      (.write dos (.getBytes "abcd" "UTF-8"))
      (is (nil? (wire/read-msg (DataInputStream. (ByteArrayInputStream. (.toByteArray baos)))))))))

;; ---- op table -----------------------------------------------------------------

(deftest op-table-declares-every-op
  (testing "every op declares its request fields, reply keys, and write-ness"
    (doseq [[op spec] wire/ops]
      (is (vector? (:request spec)) (str op " request"))
      (is (every? keyword? (:request spec)) (str op " request fields"))
      (is (vector? (:reply spec)) (str op " reply"))
      (is (every? keyword? (:reply spec)) (str op " reply keys"))
      ;; the loop locks what the table flags — kala.wire/ops owns the
      ;; single-writer invariant
      (is (boolean? (:is-write? spec)) (str op " is-write?"))))
  (testing ":transact is the one write op"
    (is (= [:transact]
           (vec (for [[op spec] wire/ops
                      :when (:is-write? spec)]
                  op)))))
  (testing "db-reference ops are exactly the reads that address a named db value"
    (is (= #{:with :q :pull :datoms}
           (into #{} (keep (fn [[op spec]] (when (:db-reference? spec) op)))
                 wire/ops)))))

(deftest write-op-answers-from-the-table
  (is (true? (wire/write-op? :transact)))
  (is (false? (wire/write-op? :q)))
  (is (false? (wire/write-op? :nonexistent))))

(deftest error-data-kinds-are-enumerated
  (testing "every kind is namespaced and carries a vector of keyword keys"
    (doseq [[kind keys] wire/error-data-keys]
      (is (namespace kind) (str kind))
      (is (vector? keys) (str kind))
      (is (every? keyword? keys) (str kind))))
  (testing "representative engine kinds are all enumerated"
    (is (every? #(contains? wire/error-data-keys %)
                [:transact/unique-conflict
                 :transact/unknown-ident
                 :transact/bad-value-type
                 :pull/unknown-attribute
                 :query/unknown-attribute
                 :datoms/unknown-index
                 :db/view-not-writable
                 :client/peer-only])))
  (testing "unknown kinds fall back to the historical whitelist"
    (is (= [:attribute :e :t :index :op]
           (wire/error-data-keys-for :something/else)))))

(defn- md-code-spans [cell]
  (map #(subs % 1 (dec (count %))) (re-seq #"`[^`]+`" cell)))

;; keyword-shaped spans inside prose that name query-language forms rather
;; than wire fields — e.g. ":q :args (further `:in` inputs, in order)"
(def ^:private doc-prose-spans #{":in"})

(deftest wire-protocol-doc-matches-the-op-table
  (let [doc (slurp "docs/wire-protocol.md")
        rows (into {}
                   (keep (fn [line]
                           (when-let [[_ op fields reply]
                                      (re-matches #"\| `([^`]+)` \| (.+?) \| (.+?) \| *" line)]
                             [(keyword (subs op 1))
                              [(md-code-spans fields) (md-code-spans reply)]])))
                   (str/split-lines doc))]
    (is (= (set (keys wire/ops)) (set (keys rows)))
        "the doc's Requests table lists exactly the ops the spec declares")
    (doseq [[op spec] wire/ops]
      (let [[doc-fields doc-reply] (get rows op)
            ;; a db-reference op's "optional" clause is db-reference-fields
            expected-fields (set (map str (:request spec)))
            expected-fields (cond-> expected-fields
                              (:db-reference? spec)
                              (into (map str wire/db-reference-fields)))]
        (testing (str op)
          ;; fields cells are pinned both ways: the doc documents every
          ;; spec'd field, and documents no field the spec doesn't declare
          (doseq [f expected-fields]
            (is (some #{f} doc-fields) (str "request field " f " documented")))
          (doseq [f doc-fields]
            (is (or (contains? expected-fields f) (contains? doc-prose-spans f))
                (str "documented field " f " declared in the spec")))
          ;; reply cells carry prose references (e.g. :db/current-tx), so
          ;; only the forward direction is pinned there
          (doseq [r (:reply spec)]
            (is (some #{(str r)} doc-reply) (str "reply key " r " documented"))))))))
