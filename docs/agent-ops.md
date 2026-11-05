# Agent ops: shared transactor + fast memory calls

Real multi-agent deployments (one transactor, many sessions over MCP or
`kala.memory`) hit three recurring frictions: restarting the transactor
after a reboot, paying a fresh JVM+MCP boot for every one-shot tool call,
and opaque `:transact/unknown-attribute` errors when an agent invents
vocabulary. This page ships the upstream helpers.

## Start the transactor (idempotent)

```bash
bin/start-transactor /path/to/transactor.edn
```

Reads `:host` (default `127.0.0.1`) and `:port` from the same EDN the
transactor itself parses. If something already accepts TCP there, the
script prints that and exits 0. Otherwise it starts
`clojure -M:transactor <config>` under `nohup`, writes
`transactor.pid` next to the config (override the directory with
`KALA_TRANSACTOR_LOG_DIR`), and waits up to 60s for the port.

```bash
bin/start-transactor transactor.edn --foreground   # no nohup; exec's the JVM
bin/start-transactor --foreground transactor.edn   # flag may appear anywhere
```

Never run a second writer against the same store directory while one is
live — the transactor is the single writer (see the README constraints).

## Call memory without booting MCP each time

MCP remains the universal SDK ([ADR-0011](adr/0011-memory-layer-four-boundaries.md)).
`bin/kala-mem` is the ops shortcut: a client of an already-running
transactor that talks `kala.memory` directly — no MCP server process, no
JSON-RPC handshake, no per-call spine install beyond the first.

Config shape matches the MCP server (client-mode `:conn`):

```edn
{:conn {:host "127.0.0.1" :port 4334 :token "..."}
 :session "my-agent"}
```

```bash
bin/kala-mem mcp.edn context '{:with-t true}'
bin/kala-mem mcp.edn record '{:tx-data [{:db/ident :note/text
                                         :db/valueType :db.type/string
                                         :db/cardinality :db.cardinality/one}
                                        {:note/text "hello"}]}'
bin/kala-mem mcp.edn recall '{:query [:find ?t :where [?e :note/text ?t]]}'
```

Tools: the six names in `kala.mem/tool-names` (same surface as MCP).
Args are an **EDN map** (not JSON); structure rides as data. Exit status
1 means the tool raised a `:kala/error` map (printed as EDN on stdout);
2 is usage.

### Persistent mode (one JVM, many calls)

Cold start still pays one JVM boot. For a tight agent loop keep a single
process open:

```bash
bin/kala-mem mcp.edn --serve
# then send newline-delimited EDN, one call per line:
context {:with-t true}
{:op "record" :args {:tx-data [{:note/text "hi"}]}}
# empty line quits
```

A long-lived MCP harness pointed at the same transactor is the other
warm path — prefer that when the agent runtime already speaks MCP.

## Unknown attributes

`:transact/unknown-attribute` now carries a `:hint` and a message that
points at declaration. Attributes must be registered before use; the
registration may share the same transaction as the first write
([ADR-0007](adr/0007-schema-registrations-apply-first-in-their-transaction.md)):

```edn
[{:db/ident :note/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
 {:note/text "hello"}]
```
