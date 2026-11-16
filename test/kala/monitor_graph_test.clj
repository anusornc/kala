(ns kala.monitor-graph-test
  "Behavioral seam: the monitor's graph derivation driven through its
   public fn — exactly as the monitor's HTTP layer would call it. Runs in
   the full {peer, client} x {memory, file} matrix, which enforces
   access-mode parity: production always reads through a client marker,
   so the derivation must be identical over both."
  (:require [clojure.test :refer [deftest is testing]]
            [kala.api :as api]
            [kala.eid :as eid]
            [kala.memory :as memory]
            [kala.monitor.graph :as graph]
            [kala.test-util :refer [fresh-conn]]))

(defn- ready-conn
  "A conn with the monitor fixture vocabulary (:dev/title card-one string,
   :dev/note card-many string, :dev/ref card-one ref) and the memory spine."
  []
  (let [conn (fresh-conn)]
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
    conn))

(defn- seeded-store!
  "A deterministic memory store: run-a records a person, corrects the
   person's card-one title, and adds a note; run-b records a second person
   with a ref to the first, then a derivation citing run-a's third turn.
   Corrections address the person by the eid the first record resolved —
   string tempids are per-transaction and would mint a fresh entity.
   Returns the turns' coordinates and resolved entity ids."
  [conn]
  (let [s-a (memory/session conn {:id "run-a"})
        s-b (memory/session conn {:id "run-b"})
        t1 (memory/record conn "run-a" [{:db/id "p1" :dev/title "Ada"}])
        p1 (get-in t1 [:tempids "p1"])
        t2 (memory/record conn "run-a" [{:db/id p1 :dev/title "Ada Lovelace"}])
        t3 (memory/record conn "run-a" [{:db/id p1 :dev/note "analytical engine"}])
        t4 (memory/record conn "run-b" [{:db/id "p2" :dev/title "Bob"}
                                        {:db/id "p2" :dev/ref p1}])
        p2 (get-in t4 [:tempids "p2"])
        t5 (memory/record conn "run-b" [{:db/id "n1" :dev/note "derived"
                                         :memory/source (:tx t3)}])
        n1 (get-in t5 [:tempids "n1"])]
    {:sessions {"run-a" s-a "run-b" s-b}
     :turns [t1 t2 t3 t4 t5]
     :eids {"p1" p1 "p2" p2 "n1" n1}}))

(defn- turn-t-by-label
  "The ts of a seeded store's turns indexed by their tempid names."
  [seeded]
  (into {} (map (fn [[i t]] [(str "t" (inc i)) (:t t)])
                (map-indexed vector (:turns seeded)))))

(deftest recorded-store-derives-the-graph-shape
  (let [conn (ready-conn)
        seeded (seeded-store! conn)
        {:keys [sessions eids]} seeded
        ts (turn-t-by-label seeded)
        g (graph/graph (api/db conn) {})]
    (testing "the payload carries the viewed t — head, when no as-of"
      (is (= (:t (last (:turns seeded))) (:t g))))
    (testing "sessions list every session with its id label and turn count"
      (is (= [{"id" "run-a" "turns" 3} {"id" "run-b" "turns" 2}]
             (->> (:sessions g)
                  (map (fn [s] {"id" (:label s) "turns" (:turns s)}))
                  (sort-by #(get % "id"))))))
    (testing "every transaction is a turn node in the total order —
              the fixture made 10: the db's bootstrap, vocab, spine, two
              session creations, five records"
      (is (= 10 (count (:turns g))))
      (doseq [t (vals ts)]
        (is (some #(= t (:t %)) (:turns g))
            (str "recorded turn t" t " is drawn"))))
    (testing "recorded turns carry their session; setup turns are unattributed"
      (is (= 3 (count (filter #(= (get sessions "run-a") (:session %)) (:turns g)))))
      (is (= 2 (count (filter #(= (get sessions "run-b") (:session %)) (:turns g)))))
      (is (= 5 (count (filter nil? (map :session (:turns g)))))))
    (testing "entities are the memory's user entities — session entities
              are scope, not content"
      (is (= #{(str "e" (get eids "p1"))
               (str "e" (get eids "p2"))
               (str "e" (get eids "n1"))}
             (set (map :id (:entities g))))))
    (testing "entity labels read the current string value; kind is the
              attribute namespace"
      (let [by-id (into {} (map (juxt :id identity)) (:entities g))]
        (is (= "Ada Lovelace" (:label (get by-id (str "e" (get eids "p1"))))))
        (is (= "Bob" (:label (get by-id (str "e" (get eids "p2"))))))
        (is (= "derived" (:label (get by-id (str "e" (get eids "n1"))))))
        (is (= "dev" (:kind (get by-id (str "e" (get eids "p1"))))))))))

(deftest edges-carry-the-four-kinds
  (let [conn (ready-conn)
        {:keys [turns eids]} (seeded-store! conn)
        [t1 t2 t3 t4 t5] (map :t turns)
        e-p1 (str "e" (get eids "p1"))
        e-p2 (str "e" (get eids "p2"))
        e-n1 (str "e" (get eids "n1"))
        g (graph/graph (api/db conn) {})
        edges (set (:edges g))]
    (testing "assert edges: what each drawn turn asserted on a drawn entity"
      (is (= #{(str "t" t1) (str "t" t2) (str "t" t3) (str "t" t4) (str "t" t5)}
             (into #{} (map :from) (filter #(= :assert (:kind %)) edges))))
      (is (contains? edges {:kind :assert :from (str "t" t1) :to e-p1 :attr :dev/title}))
      (is (contains? edges {:kind :assert :from (str "t" t2) :to e-p1 :attr :dev/title}))
      (is (contains? edges {:kind :assert :from (str "t" t3) :to e-p1 :attr :dev/note}))
      (is (contains? edges {:kind :assert :from (str "t" t4) :to e-p2 :attr :dev/title}))
      (is (contains? edges {:kind :assert :from (str "t" t5) :to e-n1 :attr :dev/note})))
    (testing "the card-one correction leaves a retract edge on the trail"
      (is (contains? edges {:kind :retract :from (str "t" t2) :to e-p1 :attr :dev/title})))
    (testing "a derivation's citation is a turn-to-turn edge"
      (is (contains? edges {:kind :cite :from (str "t" t5) :to (str "t" t3)
                            :attr :memory/source})))
    (testing "ref attributes are entity-to-entity edges, not asserts"
      (is (contains? edges {:kind :ref :from e-p2 :to e-p1 :attr :dev/ref})))
    (testing "the spine's attribution never masquerades as an edge"
      (is (not-any? #(contains? #{:memory/session :memory.session/id} (:attr %)) edges))
      (is (= 8 (count edges)) "five asserts, one retract, one cite, one ref"))))

(deftest a-fresh-store-draws-its-bootstrap-turn-only
  (let [conn (fresh-conn)
        g (graph/graph (api/db conn) {})]
    (is (= 1 (:t g)) "the db's own bootstrap transaction is t=1")
    (is (= 1 (count (:turns g))) "one turn: the bootstrap, unattributed")
    (is (nil? (:session (first (:turns g)))))
    (is (= [] (:entities g) (:edges g) (:sessions g)))))

(deftest scope-narrows-the-drawing
  (let [conn (ready-conn)
        {:keys [turns eids sessions]} (seeded-store! conn)
        [t1 t2 t3 t4 t5] (map :t turns)]
    (testing "as-of pins the whole derivation to a belief state"
      (let [g (graph/graph (api/db conn) {:as-of t1})]
        (is (= t1 (:t g)))
        (is (= #{(str "t" t1)} (set (map :id (filter #(= (get sessions "run-a") (:session %)) (:turns g))))))
        (is (= #{(str "e" (get eids "p1"))} (set (map :id (:entities g)))))
        (is (= "Ada" (:label (first (:entities g)))) "the belief state before the correction")
        (is (not-any? #(contains? #{:retract :cite :ref} (:kind %)) (:edges g)))))
    (testing "session filter: only that session's turns and what they touch —
              the session menu still lists every session"
      (let [g (graph/graph (api/db conn) {:session "run-a"})]
        (is (= 3 (count (:turns g))))
        (is (every? #(= (get sessions "run-a") (:session %)) (:turns g)))
        (is (= #{(str "e" (get eids "p1"))} (set (map :id (:entities g)))))
        (is (= #{"run-a" "run-b"} (into #{} (map :label) (:sessions g))))))
    (testing "window: the recent-t tail"
      (let [g (graph/graph (api/db conn) {:window 2})]
        (is (= #{t4 t5} (into #{} (map :t) (:turns g))))
        (is (= #{(str "e" (get eids "p2")) (str "e" (get eids "n1"))}
               (set (map :id (:entities g)))))))
    (testing "cap: the newest turns keep their entities until the budget is spent"
      (let [conn2 (ready-conn)
            s (memory/session conn2 {:id "run-many"})
            ts (doall (for [i (range 10)]
                        (memory/record conn2 "run-many"
                                       [{:db/id (str "x" i) :dev/title (str "entity " i)}])))
            g (graph/graph (api/db conn2) {:cap 8})]
        (is (= 8 (+ (count (:turns g)) (count (:entities g)))))
        (is (= 4 (count (:turns g))) "newest four turns fit with their entities")
        (is (= (set (map :t (take-last 4 ts))) (into #{} (map :t) (:turns g))))
        (is (= #{"entity 6" "entity 7" "entity 8" "entity 9"}
               (into #{} (map :label) (:entities g))))))))

(deftest turn-datoms-list-one-turns-transacted-datoms
  (let [conn (ready-conn)
        {:keys [turns eids sessions]} (seeded-store! conn)
        [t1 t2] (map :t turns)
        db (api/db conn)]
    (testing "a record turn's rows are [entity attr value added] over the
              whole transaction — its spine attribution and txInstant included"
      (let [rows (set (graph/turn-datoms db t1))]
        (is (= 3 (count rows)))
        (is (contains? rows [(get eids "p1") ":dev/title" "\"Ada\"" true]))
        (is (contains? rows [(eid/tx-eid t1) ":memory/session"
                             (pr-str (get sessions "run-a")) true]))
        (is (some #(and (= (eid/tx-eid t1) (nth % 0))
                        (= ":db/txInstant" (nth % 1))
                        (true? (nth % 3)))
                  rows))))
    (testing "the correction turn carries its assert and the retraction
              the card-one replacement wrote"
      (let [rows (set (graph/turn-datoms db t2))]
        (is (contains? rows [(get eids "p1") ":dev/title" "\"Ada Lovelace\"" true]))
        (is (contains? rows [(get eids "p1") ":dev/title" "\"Ada\"" false]))))
    (testing "a t no turn owns lists nothing"
      (is (= [] (graph/turn-datoms db 999))))))

(deftest entity-trail-is-the-correction-trail-with-sessions
  (let [conn (ready-conn)
        {:keys [turns eids sessions]} (seeded-store! conn)
        [t1 t2 t3 t4] (map :t turns)
        db (api/db conn)
        trail (graph/entity-trail db (get eids "p1"))]
    (testing "rows are [t attr value added session], sorted by t —
              the card-one correction's retract on the trail with its session"
      (is (= 4 (count trail)))
      (is (apply <= (map first trail)) "in t order")
      (is (= #{[t1 ":dev/title" "\"Ada\"" true (get sessions "run-a")]
               [t2 ":dev/title" "\"Ada Lovelace\"" true (get sessions "run-a")]
               [t2 ":dev/title" "\"Ada\"" false (get sessions "run-a")]
               [t3 ":dev/note" "\"analytical engine\"" true (get sessions "run-a")]}
             (set trail))))
    (testing "a ref's target does not inherit the ref row — p2's trail is
              its own two rows, run-b's"
      (is (= #{[t4 ":dev/title" "\"Bob\"" true (get sessions "run-b")]
               [t4 ":dev/ref" (pr-str (get eids "p1")) true (get sessions "run-b")]}
             (set (graph/entity-trail db (get eids "p2"))))))
    (testing "schema entities have a trail too — their turns carry no session"
      (let [vtrail (graph/entity-trail db (api/entid db :dev/title))]
        (is (= 3 (count vtrail)) "ident, valueType, cardinality at the vocab turn")
        (is (every? #(nil? (nth % 4)) vtrail))
        (is (= 1 (count (distinct (map first vtrail)))) "one vocab turn wrote it all")))
    (testing "as-of bounds the trail to the belief state"
      (is (= #{[t1 ":dev/title" "\"Ada\"" true (get sessions "run-a")]
               [t2 ":dev/title" "\"Ada Lovelace\"" true (get sessions "run-a")]
               [t2 ":dev/title" "\"Ada\"" false (get sessions "run-a")]}
             (set (graph/entity-trail (api/as-of db t2) (get eids "p1"))))))
    (testing "an entity that never was has no trail"
      (is (= [] (graph/entity-trail db 12345))))))

(deftest the-trail-and-the-graph-tell-one-story
  (let [conn (ready-conn)
        {:keys [eids]} (seeded-store! conn)
        db (api/db conn)
        g (graph/graph db {})
        e-p1 (str "e" (get eids "p1"))
        trail (graph/entity-trail db (get eids "p1"))
        session-of-turn (into {} (map (juxt :t :session)) (:turns g))]
    (testing "every trail row is a drawn edge on the entity, same t, same attr
              — assert/retract edges only; the ref INTO p1 draws as a ref edge,
              not a trail step"
      (is (= (count trail)
             (count (filter #(and (#{:assert :retract} (:kind %)) (= e-p1 (:to %)))
                            (:edges g)))))
      (doseq [[t a _v added _s] trail]
        (is (some #(and (= (str "t" t) (:from %))
                        (= e-p1 (:to %))
                        (= a (pr-str (:attr %)))
                        (= added (= :assert (:kind %))))
                  (:edges g)))))
    (testing "the trail's session column is the turn node's session"
      (doseq [[t _a _v _added s] trail]
        (is (= s (session-of-turn t)))))))
