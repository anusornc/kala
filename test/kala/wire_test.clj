(ns kala.wire-test
  "Codec contract: framing round-trips, and the codec is silent — frame
  echo on every read/write was debug residue in the highest-traffic seam
  (#20)."
  (:require [clojure.test :refer [deftest is testing]]
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
