# Kala · กาล

Kala (Pali–Sanskrit–Thai: "time, era") — a time-traveling Datalog database.
An independent, clean-room, open-source (EPL-1.0) reimplementation of
[Datomic](https://docs.datomic.com)'s architecture in Clojure on the JVM: a
transactor that serializes writes into an append-only log, an embedded peer
library with in-memory indexes, a remote client library, Datalog queries, the
pull API, and time travel — every db value is the database as of one t.

**Status: v1 complete, published to [Clojars](https://clojars.org/io.github.anusornc/kala)** — behavioral suite green across {peer, client} ×
{memory, file}; the query-engine optimization wave (#15), the
original-Datomic bench adapter (#19), and the Datahike cross-check have
landed; the roadmap lives in the issue tracker.

## Build and test

```bash
clojure -M:test          # full matrix: {peer, client} x {memory, file} + backend suites
e2e.sh                   # project E2E: transactor SIGKILL crash-recovery over real
                         #   processes, release jar, demo, web demo (all|transactor|release|demo|web)
clojure -T:build jar     # build the release jar (EPL-1.0 notices included)
```

The behavioral suite runs twice per cycle — peer (embedded) and client (remote, over the wire against a live transactor) — on both storage backends, which is what enforces access-mode and storage-backend parity. Entity navigation and seek scans are peer-only; clients use pull. Tests run at two seams: the behavioral API suite and the storage-protocol contract suite per adapter.

## Demo

```bash
clojure -M:demo
```

A single-file walkthrough ([demo/kala/demo.clj](demo/kala/demo.clj)) proving
the public surface end to end: schema, list- and map-form transactions,
Datalog (predicates, joins, recursive rules, aggregates), pull, entity
navigation (forward and reverse refs), compare-and-swap including its
structured failure, stored transaction functions, time travel (as-of,
history, with), durable file storage across a reopen, and a remote client
over the transactor wire. Every claim is asserted — a mismatch aborts with a
nonzero exit, so a run that prints `ALL PROOFS PASSED` means the behavior is
real.

## Web demo

```bash
clojure -M:web              # http://127.0.0.1:8090  (or: clojure -M:web 9000)
```

The same demo as an interactive app: a JVM server ([web/kala/web.clj](web/kala/web.clj))
embedding Kala as a peer — JDK's built-in HttpServer, zero dependencies —
seeding the people fixture into file storage and serving a small no-build
browser UI. Write your own Datalog queries, transact EDN ops (failures come
back as the project's error kinds), slide reads across t with as-of, browse
the raw datoms and the full history, then hit **Reopen from disk** and watch
every acked transaction survive. EDN in, EDN out — responses carry pr-str'd
values. The server binds 127.0.0.1; tx-data is read with clojure.edn (same
documented no-sandboxing posture as ADR-0004).

## Benchmark

```bash
clojure -M:bench                          # core suite: kala, DataScript, Datahike
clojure -M:bench-datomic --suite=datomic  # Datomic suite: kala, DataScript, original Datomic
clojure -M:bench 5000 20000               # custom entity counts
```

Two suites, never one classpath: Datahike's and com.datomic/peer's
data_readers.clj both claim the `db/id` and `db/fn` tags, so the JVM
refuses to boot with both jars present. Both suites cross-check that
kala, DataScript, and the third engine return identical query results,
then print latency tables. Dependencies (datascript, datahike,
com.datomic/peer) are bench-only.

Latency rows report the mean plus p50/p95/p99 of **per-operation** times:
after a warmup, every call is timed with `System/nanoTime` — GC pauses land
in p95/p99, which is honest for a database. Workloads: bulk ingest and 100
single-entity updates as wall clock, plus three read shapes (simple
pattern, filtered + input, join) and a point read. The final section times
the same peer engine on durable **file storage** against memory — the
durability cost: reads are comparable (indexes are rebuilt in memory),
writes pay the fsync-before-ack.

Methodology: engine setup (connection, schema transact — noticeable for
original Datomic's peer startup) happens before any timing and is excluded
from every number. Ingest is cold single-pass wall clock over batched
transactions. The datoms-in-store totals include engine-internal
bookkeeping (schema and transaction datoms), so they differ by engine; the
cross-check compares query results, not totals. The Datomic engine is
peer-only; its API deviations from the other engines are recorded in
[bench/kala/bench/datomic_peer.clj](bench/kala/bench/datomic_peer.clj), the
Datahike engine's in
[bench/kala/bench/datahike_engine.clj](bench/kala/bench/datahike_engine.clj).

## Launch a transactor

```bash
echo '{:port 4334 :storage :file :path "/var/data/mydb"}' > transactor.properties.edn
clojure -M:transactor transactor.properties.edn
```

Then connect remotely with `kala.client/connect` — see [docs/wire-protocol.md](docs/wire-protocol.md) for the protocol.

## Release

Kala is published to [Clojars](https://clojars.org/io.github.anusornc/kala):

```clojure
io.github.anusornc/kala {:mvn/version "0.1.0"}
```

Releases are tag-triggered: pushing a `v*` tag runs the behavioral matrix
as a final gate, builds the jar from the tag version, deploys to Clojars
(deps-deploy with the `CLOJARS_USERNAME` / `CLOJARS_PASSWORD` repo
secrets), and attaches the jar to a GitHub release. CI
(`.github/workflows/ci.yml`) additionally runs the bench suites and the
project/web E2E journeys on every push and PR.

## Architecture (v1 target)

| Module | Role |
| --- | --- |
| core | Datoms, schema, EAVT/AEVT/AVET/VAET indexes, transaction pipeline, Datalog, pull, time travel |
| storage-api | Documented storage protocol: segment put/get/list + ordered log append/scan |
| storage-memory / storage-file | v1 backends: volatile in-memory, durable local directory |
| transactor | Standalone single-writer process; serializes and logs transactions |
| peer | Embedded library: local indexes and queries, writes via the transactor |
| client | Remote library: same operation surface, served through the transactor |
| wire-protocol | Open, versioned, EDN-over-TCP protocol between peers/clients and the transactor |

## License

[EPL-1.0](LICENSE) — the same license Nubank chose for Datomic itself and the Clojure ecosystem standard.
