# Kala · กาล

Kala (Pali–Sanskrit–Thai: "time, era") — a time-traveling Datalog database.
An independent, clean-room, open-source (EPL-1.0) reimplementation of
[Datomic](https://docs.datomic.com)'s architecture in Clojure on the JVM: a
transactor that serializes writes into an append-only log, an embedded peer
library with in-memory indexes, a remote client library, Datalog queries, the
pull API, and time travel — every db value is the database as of one t.

**Status: v1 complete** — behavioral suite green across {peer, client} ×
{memory, file}; the query-engine optimization wave (#15) and the
original-Datomic bench adapter (#19) have landed; the roadmap lives in the
issue tracker.

## Build and test

```bash
clojure -M:test          # full matrix: {peer, client} x {memory, file} + backend suites
clojure -T:build jar     # build the release jar (EPL-1.0 notices included)
```

The behavioral suite runs twice per cycle — peer (embedded) and client (remote, over the wire against a live transactor) — on both storage backends, which is what enforces access-mode and storage-backend parity. Entity navigation and seek scans are peer-only; clients use pull. Tests run at two seams: the behavioral API suite and the storage-protocol contract suite per adapter.

## Benchmark

```bash
clojure -M:bench                # 200 and 2000 entities
clojure -M:bench 5000 20000     # custom entity counts
```

Runs our peer engine, DataScript, and original Datomic (com.datomic/peer,
embedded via datomic:mem) on identical seed data, cross-checks that all
engines return the same query results, then prints ingest and query
latencies. Dependencies (criterium, datascript, com.datomic/peer) are
bench-only.

Methodology: query latencies are steady-state — criterium warms up, and the
pre-timing cross-check warms each engine's first-touch code paths. Engine
setup (connection, schema transact — noticeable for original Datomic's peer
startup) happens before any timing and is excluded from every number. Ingest
is cold single-pass wall clock over batched transactions. The datoms-in-store
totals include engine-internal bookkeeping (schema and transaction datoms),
so they differ by engine; the cross-check compares query results, not totals.
The Datomic engine is peer-only; its API deviations from the other engines
are recorded in
[bench/kala/bench/datomic_peer.clj](bench/kala/bench/datomic_peer.clj).

## Launch a transactor

```bash
echo '{:port 4334 :storage :file :path "/var/data/mydb"}' > transactor.properties.edn
clojure -M:transactor transactor.properties.edn
```

Then connect remotely with `kala.client/connect` — see [docs/wire-protocol.md](docs/wire-protocol.md) for the protocol.

## Release

The project lives only on this GitHub repository — no external package
registry. Everything runs locally; there is no CI.

```bash
clojure -M:test          # run before releasing
RELEASE_VERSION=0.1.0 clojure -T:build jar   # builds target/*.jar
```

To attach a jar to a tagged release on GitHub (optional):

```bash
git tag v0.1.0 && git push origin v0.1.0
gh release create v0.1.0 target/kala-0.1.0.jar
```

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
