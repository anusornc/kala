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
                         #   processes, release jar, demo, web demo, MCP server
                         #   (all|transactor|release|demo|web|mcp)
clojure -T:build jar     # build the release jar (EPL-1.0 notices included)
clojure -T:build mcp-jar # build the kala-mcp server artifact (separate jar)
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

## Memory for agents

Kala ships a memory layer (`kala.memory`, inside the core jar) and an MCP
server (`kala.mcp`, its own artifact) that exposes it to any agent harness.

The vocabulary is the domain's: **memory** is agent-run knowledge held as
datoms under a three-ident provenance **spine**; a **session** is one run
recording memory; a **turn** is exactly one transaction; and the **context
document** is the belief-state map an LLM reads. One turn = one transaction,
attributed to its session by tx-meta — provenance holds by construction,
because the layer writes the attribution, never the caller.

```clojure
(require '[kala.api :as api] '[kala.memory :as memory])
(def conn (api/connect {:storage :file :path "/var/data/agent-memory"}))
(memory/install conn)                          ; idempotent: the spine
(def s (memory/session conn {:id "run-42"}))   ; resume by id, upsert
(memory/record conn s [[:db/add "p" :proj/deploy-command "make deploy"]])
; => {:t 60, :turn 1, :tx 9700001, :tempids {"p" 12}, :datoms 3}

(memory/context (api/db conn) {:cap 5})        ; the belief-state document
(memory/turns (api/db conn) "run-42" {})       ; step N -> t, then read as-of t
```

Reads are the doors — `query` (Datalog, joins included), `pull`, `history`
(entity and session scopes), `turns` — every one addressable `:as-of t`,
every list capped with the cap visible (`:truncated`), because a silently
shortened answer would misstate what the agent believed. Corrections are
data: a cardinality-one re-assertion supersedes the old belief into history,
and `context`'s `:corrections` section shows the full trail of the entities
it shows you.

### Backup and migration

The storage log exports as EDN and restores into any backend — entity
ids and t replay identically, values ride tagged literals, and the
append-only guard means an import can extend a store, never overwrite
one. See [docs/backup-and-migration.md](docs/backup-and-migration.md).

### MCP server

```bash
echo '{:conn {:storage :file :path "/var/data/agent-memory"}
       :session "my-agent"}' > mcp.edn
clojure -M:mcp mcp.edn
```

Six tools over stdio, JSON-RPC: `context` (call this first — the capped,
correction-annotated belief state; `with-t` stamps every `:current` tuple
with its asserting t, `min-t` bounds it to recent facts), `record` (one
turn; the server owns the session, so tx-data carries no attribution),
`recall` (Datalog with joins; the data-only predicate allowlist includes
the lexical matchers `includes?`/`starts-with?`/`ends-with?` — regex stays
out: agent-written patterns would be a ReDoS surface on the transactor),
`read` (one entity, current or as-of), `history` (correction/provenance
trails), `turns` ("step 12" becomes a t). EDN in, EDN out — arguments that
carry structure are EDN strings, results are readable EDN, and errors are
the store's own error maps under the MCP `isError` flag. The harness→server
hop is same-user local; when the server reaches a remote transactor it
authenticates with the shared token like any client (see
[ADR-0006](docs/adr/0006-shared-token-auth-tls-by-termination.md)).

No Clojure CLI at hand? One executable jar runs the server:

```bash
clojure -T:build mcp-uberjar          # once, from a checkout
java -jar target/kala-mcp-*-standalone.jar mcp.edn
```

`server.json` (MCP registry metadata) sits at the repo root, ready for a
future listing — the official registry's package types do not include
Maven/Clojars yet, so the listing itself waits on an OCI shim or upstream
support.

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
writes pay the fsync-before-ack. A kala-only last section prices
**contention**: 1–8 concurrent client connections transacting through one
transactor (memory storage, per-op percentiles per concurrency level),
which is what serialization through the single writer costs.

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

Then connect remotely with `kala.client/connect` — see [docs/wire-protocol.md](docs/wire-protocol.md) for the protocol. Adding `:compact true` to the config lets clients trigger compaction over the wire; the default refuses (`:compact/disabled`, [ADR-0010](docs/adr/0010-client-mode-compaction-rides-the-wire-behind-a-transactor-opt-in.md)).

## Release

Kala is published to [Clojars](https://clojars.org/io.github.anusornc/kala):

```clojure
io.github.anusornc/kala     {:mvn/version "0.4.0"}   ; the database + memory layer
io.github.anusornc/kala-mcp {:mvn/version "0.4.0"}   ; the MCP server (separate artifact)
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
| storage-api | Documented storage protocol: segment put/get/list + ordered log append/scan/prune |
| storage-memory / storage-file | v1 backends: volatile in-memory, durable local directory |
| transactor | Standalone single-writer process; serializes and logs transactions |
| peer | Embedded library: local indexes and queries, writes in-process through the storage log |
| client | Remote library: same operation surface, served through the transactor |
| wire-protocol | Open, versioned, EDN-over-TCP protocol between peers/clients and the transactor |
| memory | Agent memory: the provenance spine, one-turn-one-transaction ingestion, capped read doors, the context document |
| mcp | The memory layer as an MCP server: six tools over stdio, separate artifact |

## Constraints (v1, by design)

Kala's production bar is the embedded library — an application opens a peer
in its own process ([ADR-0005](docs/adr/0005-production-bar-is-the-embedded-library.md)).
What that deliberately does not include:

- **RAM-bound stores** — a file store replays its whole log into in-memory
  indexes at boot, so a store's size is the machine's memory. Explicit
  checkpoint-and-prune compaction (`api/compact`) bounds boot replay to the
  live state plus the tail, at the cost of history below the compaction
  base ([ADR-0008](docs/adr/0008-compaction-is-explicit-checkpoint-and-prune.md)).
  In client mode the transactor serves the same call over the wire — but
  only when started with `:compact true`, the operator's opt-in, since
  pruning history is the one client-reachable effect that cannot be undone
  ([ADR-0010](docs/adr/0010-client-mode-compaction-rides-the-wire-behind-a-transactor-opt-in.md)).
- **Single writer, single node** — one transactor serializes all writes;
  no replication or HA.
- **Transport security is opt-in and minimal** — starting the transactor
  with a `:token` requires every client connection (subscriptions
  included) to authenticate; comparison is constant time. TLS is by
  termination in front of the transactor (stunnel/nginx/wireguard) —
  there are no native TLS sockets
  ([#33](https://github.com/anusornc/kala/issues/33)).
- **No query sandboxing** — queries and transaction functions run with the
  embedding process's full privileges (the documented trust posture of
  [ADR-0004](docs/adr/0004-transaction-functions-are-stored-edn-source.md)).

## License

[EPL-1.0](LICENSE) — the same license Nubank chose for Datomic itself and the Clojure ecosystem standard.
