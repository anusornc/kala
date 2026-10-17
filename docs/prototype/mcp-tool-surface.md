# PROTOTYPE — MCP tool surface for `kala.memory` (wayfinder ticket #43)

> Throwaway artifact to react to. **Not the server** — nothing here runs; these
> are hand-rendered sketches of whole tool surfaces over the same kala.memory API.
> The decision this settles: what the MCP server exposes, what its edges look
> like, where it lives. Implementation follows `/to-spec`, not this ticket.

## The precedents, side by side

| surface | tools | posture | what it lacks (the kala gaps) |
|---|---|---|---|
| official reference server | 9 (CRUD + `read_graph` + `search_nodes` + `open_nodes`) | maximally thin: LLM curates a JSONL knowledge graph | no time fields, no provenance, no vocabulary, no joins |
| Zep Cloud context MCP | 7 (`search_graph`, `get_user_summary`, `get_subgraph`, ... + optional `add_memory`) | read/write split: one curated context read, narrow writes | no whole-graph as-of, no tx-level provenance, no joins |
| mem0 hosted MCP | 11 (`add_memory`, `search_memories`, `update_memory`, ...) | hosted-platform CRUD over free-text memories | audit-log history only, no rollback, no structure |
| Graphiti experimental MCP | 13 (`add_memory`, `search_memory_facts`, `get_episodes`, ...) | exposure of a temporal-graph engine's every operation | per-fact validity windows, not store-wide as-of |

Reading: thin surfaces win on wiring cost and lose on everything kala exists to
expose — structure, time, provenance. The tool surface must make those
differentiators *callable*, not just stored.

## Three whole-surface variants

Same dataset as the #42 prototype (sessions `kala-main-1016`/`1017`, head t=59).

### Variant A — thin CRUD mirror (the official-server reaction)

Expose kala's primitives verbatim; the LLM composes. ~8 tools: `add-datoms`,
`retract-datoms`, `query`, `pull`, `history`, `read-graph`, `schema`, `stats`.
Session handling is a tool argument: every write carries `"session-id"` or
provenance is silently lost.

- **Wins:** maximal coverage, no opinions to maintain, zero gap between tool and
  `kala.api` — the thinnest possible wrapper, closest to what harnesses wire today.
- **Costs:** session discipline lives in the LLM's head (one forgotten
  `session-id` = an unattributed turn, undetectable); no curated default read —
  the #42 context doc has no tool to live in; 8 shapes for an LLM to pick among
  where 6 would do.

### Variant B — small orthogonal `kala.memory` set (**recommended**)

Six tools, one per memory op, mirroring the `kala.memory` API 1:1. The server
owns the session; writes are one obvious path; reads are the #41 doors plus the
#42 doc. Bare tool names (`record`, not `kala_memory_record`) — the server *is*
the memory surface, per the reference-server convention.

| tool | wraps | input (sketch) | returns |
|---|---|---|---|
| `context` | `kala.memory/context` | `{:as-of t? :cap n? :corrections? bool?}` | the #42 belief-state map, verbatim |
| `record` | `kala.memory/record` | `{:tx-data "EDN op seq" :session-id? str}` | `{:t 60 :turn 4}` — the new turn's coordinates |
| `recall` | query door | `{:query "EDN" :args? "EDN" :as-of? t :limit? n}` | relation rows, readable EDN |
| `read` | pull door | `{:entity eid :pattern? "EDN" :as-of? t}` | entity map, readable EDN |
| `history` | history door | `{:scope entity\|session :entity? eid :session-id? str :from-t? :to-t? :attrs? "EDN"}` | assertion/retraction trail |
| `turns` | turn listing | `{:session-id? str :as-of? t}` | `{:session "…" :turns [[t n] …]}` |

Edge conventions: structure-bearing args (`:query`, `:tx-data`, `:pattern`,
`:attrs`) arrive as **EDN strings** parsed server-side — MCP transport stays
JSON, the memory surface speaks EDN at both edges; scalars (`t`, `cap`,
`session-id`, entity ids) are JSON natives. Results are readable-EDN strings.
Errors surface `:kala/error` maps verbatim with MCP `isError: true` (#41
decision 4, unchanged). No `install`/`session` tools: setup is the developer's
(`kala.memory/install conn` at server boot), not the agent's.

- **Wins:** provenance by construction (the server stamps its session; the agent
  cannot forget); the #42 doc is the first-class default read; every tool result
  is a door output verbatim — zero new serialization; `recall` keeps joins, `as-of`
  and `history` keep time and provenance *callable*, which is the thesis.
- **Costs:** the server owns one policy (session ownership — below); six names to
  lock instead of none.

### Variant C — read/write split (the Zep reaction)

One read — `context`, aggressively defaulted — plus one write — `record`.
`recall`/`read`/`history`/`turns` are not exposed in v0.

- **Wins:** smallest wire surface that can exist; fastest harness integration;
  matches how the managed vendors position context MCP.
- **Costs:** hides exactly the differentiators this effort exists to expose. The
  canonical question ("what did I believe at step 12?") has **no door** —
  `context`'s `:as-of` only re-reads the capped doc, and joins are unreachable.
  The gaps thesis inverts into the gaps. Dead on the scenario.

## Transcript sketch (Variant B, the canonical scenario)

Server booted with `install` done and session id `kala-main-1017` (same-id
upsert resumes it — #40). Head is t=59.

**1. Default read — the harness's first call:**

```json
{"method": "tools/call", "params": {"name": "context", "arguments": {"cap": 5}}}
```

```
{:as-of 59
 :truncated {:capped? true :shown 5 :dropped 1 :cap 5
             :dropped-attrs [:proj/test-command]}
 :current   [[:proj/kala :proj/docs-host "kala.dev"] …]
 :corrections [{:entity :proj/kala :attr :proj/deploy-command
                :was "./deploy.sh staging" :asserted 43 :until 56 :now "make deploy"} …]
 :turns {"kala-main-1016" [[41 1] [42 2] [43 3] [44 4] [45 5] [46 6]]
         "kala-main-1017" [[57 1] [58 2] [59 3]]}}   ;; the #42 locked map, verbatim
```

**2. Write one turn — the deploy pipeline changed again:**

```json
{"name": "record", "arguments":
  {"tx-data": "[[:db/add :proj/kala :proj/deploy-command \"make deploy prod\"]]"}}
```

```
{:t 60 :turn 4}      ;; attributed to kala-main-1017 by construction
```

**3. Canonical question — "what did I believe about the deploy command at turn 3
of kala-main-1016?":**

```json
{"name": "turns", "arguments": {"session-id": "kala-main-1016"}}
```
```
{:session "kala-main-1016" :turns [[41 1] [42 2] [43 3] [44 4] [45 5] [46 6]]}
```

```json
{"name": "recall", "arguments":
  {"query": "[[:find ?v :in $ ?e :where [?e :proj/deploy-command ?v]]]",
   "args": "[[:proj/kala]]", "as-of": 43}}
```
```
([["./deploy.sh staging"]])
```

**4. Error behavior — the agent invents an attribute:**

```json
{"name": "record", "arguments":
  {"tx-data": "[[:db/add :proj/kala :agent/editor :vscode]]"}}
```
```
isError: true
{:kala/error {:kind :transact/unregistered-ident   ;; exact kind per the existing whitelist
              :message "…"}}
```

## Decision axes

| axis | A thin mirror | B small orthogonal set | C read/write split |
|---|---|---|---|
| tool count | ~8 | 6 | 2 |
| write path | datom-level, session id per call | one `record`, session by construction | one `record`, session by construction |
| default read | none (raw relations) | `context` (the #42 doc) | `context` (only read) |
| joins / as-of callable? | yes (raw) | yes (`recall`/`read`/`history`) | **no** |
| provenance guarantee | LLM discipline | by construction | by construction |
| precedent affinity | official server | — | Zep context MCP |

## Open questions (grilling order)

1. **Surface shape** — A, B, C, or a merge?
2. **Exact cut + session ownership** — all six of B, or trim (`turns` inside
   `history`?); who owns the session id — the server (one per process, resumed
   by configured id) vs. a required `record` argument? Bare names?
3. **Transport + ADR-0006 positioning** — stdio local (harness spawns the
   server; harness→server hop is same-user local, token auth on the
   server→transactor `:connect` per ADR-0006) vs. loopback HTTP (remote-capable,
   TLS by termination) vs. both from day one?
4. **Packaging** — `kala.mcp` as an extra-path module beside `web/` (house
   pattern) but as a **separate artifact** (MCP SDK dependency stays out of the
   core jar) vs. same jar vs. separate repo?

---

## LOCKED — wayfinder #43 resolution (2026-10-17)

All four questions answered; every pick took the recommended variant:

- **Surface B** — six bare-named tools: `context`, `record`, `recall`, `read`,
  `history`, `turns`. The thin mirror (A) puts session discipline in the LLM's
  head; the read/write split (C) leaves the canonical question with no door.
- **Server owns the session** — at boot the server runs `install` and opens the
  configured session id (same-id upsert resumes it, #40); `record` takes only
  tx-data and is attributed by construction. `install`/`session` are not tools.
- **EDN at both edges** — `:tx-data`/`:query`/`:pattern`/`:attrs` arrive as EDN
  strings parsed server-side; scalars (`t`, `cap`, entity ids, session-id
  override) are JSON natives; results are readable EDN; `:kala/error` surfaces
  verbatim with MCP `isError: true` (#41 decision 4 unchanged).
- **stdio local in v0** — the harness spawns the server as a child process; the
  harness→server hop is same-user local (no auth surface); the server→transactor
  connection carries the shared token at `:connect` (ADR-0006 unchanged).
  Loopback HTTP / remote positioning is a deliberate deferral — v0 answers the
  canonical single-user scenario — recorded here, not reopened as fog.
- **Packaging** — extra-path `mcp/` module, namespace `kala.mcp` (house pattern
  beside `web/`), built as a **separate artifact**: the MCP SDK dependency stays
  out of the core jar; one repo, one release train.

Variants A and C are dead. Spec inputs carried to `/to-spec` (not v0
decisions): the error kinds for EDN-parse failures, the exact `record` result
shape beyond `{:t :turn}`, and the Clojure MCP SDK choice.
