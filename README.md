# DatomicOpenSource

An independent, clean-room, open-source (EPL-1.0) reimplementation of [Datomic](https://docs.datomic.com)'s architecture in Clojure on the JVM: a transactor that serializes writes into an append-only log, an embedded peer library with in-memory indexes, a remote client library, Datalog queries, the pull API, and time travel.

**Status: implementation underway** — TT1 (in-memory end-to-end datom store) is in; the v1 specification and ticket backlog are tracked in the issue tracker.

## Build and test

```bash
clojure -M:test
```

Tests run at two seams: the behavioral API suite (connect → transact → datoms reads) and the storage-protocol contract suite per adapter.

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
