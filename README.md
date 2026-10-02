# DatomicOpenSource

An independent, clean-room, open-source (EPL-1.0) reimplementation of [Datomic](https://docs.datomic.com)'s architecture in Clojure on the JVM: a transactor that serializes writes into an append-only log, an embedded peer library with in-memory indexes, a remote client library, Datalog queries, the pull API, and time travel.

**Status: implementation underway** — TT1 (in-memory end-to-end datom store) is in; the v1 specification and ticket backlog are tracked in the issue tracker.

## Build and test

```bash
clojure -M:test          # full matrix: {peer, client} x {memory, file} + backend suites
clojure -T:build jar     # build the release jar (EPL-1.0 notices included)
```

The behavioral suite runs twice per cycle — peer (embedded) and client (remote, over the wire against a live transactor) — on both storage backends, which is what enforces access-mode and storage-backend parity. Entity navigation and seek scans are peer-only; clients use pull. Tests run at two seams: the behavioral API suite and the storage-protocol contract suite per adapter.

## Launch a transactor

```bash
echo '{:port 4334 :storage :file :path "/var/data/mydb"}' > transactor.properties.edn
clojure -M:transactor transactor.properties.edn
```

Then connect remotely with `datomic-opensource.client/connect` — see [docs/wire-protocol.md](docs/wire-protocol.md) for the protocol.

## Release (local)

Everything runs locally — there is no CI. Test before releasing:

```bash
clojure -M:test
```

Then build the jar (EPL-1.0 notices included) and optionally publish it to
Clojars as `io.github.anusornc/datomic-opensource`:

```bash
RELEASE_VERSION=0.1.0 clojure -T:build jar
CLOJARS_USERNAME=... CLOJARS_PASSWORD=... clojure -M:deploy -m deploy
```

The deploy step reads your Clojars credentials from the environment; skip
it entirely if you only want the jar.

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
