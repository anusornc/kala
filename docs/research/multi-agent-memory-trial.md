# Four agents, one memory: the concurrent trial

**TL;DR:** Claude Code, Grok, AGY, and Qwen Code ran the same ops task against
one kala memory store at the same time, each under its own session, with
memory as their only channel. The store's own record (not the agents'
self-reports) shows a provenance chain — claude (t 11) corrected the stale
deploy target; grok (t 12), agy (t 13), and qwen (t 14) each read the
correction, verified it independently, and cited the earlier turns via
`:memory/source`. Nothing was lost, nothing collided, every acked turn
survived. Two findings came out of it: a design question about identity on
schema registration (#53) and a modelling observation about single-valued
"note" attributes as a coordination channel.

*Run 2026-10-20. Round 1 (2026-10-17) had run the four agents one at a time
in peer mode on the same directory — four independent trials, and Qwen's MCP
server never started. This round is the first with all four live at once.*

## What made concurrency possible

Peer mode (`{:conn {:storage :file :path ...}}`) embeds kala in the MCP server
process, so four servers on one directory would be four writers on a
single-writer store. Round 2 instead ran **one transactor** over the store
(`transactor.edn`, port 4334) and pointed each agent's MCP server at it in
**client mode**:

```edn
{:conn {:host "127.0.0.1" :port 4334} :session "grok"}
```

The transactor serializes the writes; `kala.memory` behaves identically over
a client connection (spec story 8), so provenance-by-construction holds: each
server owns its session, every `record` is one transaction attributed to it.

Harness wiring: Grok and Qwen already had `kala` registered pointing at
`/tmp/kala-agents2/<agent>.edn` (so only the edn contents changed); Claude
took `--mcp-config mcp-configs/claude-mcp.json`; AGY's registration pointed
at the repo's pre-rename path and had to be re-added. `probe.sh` asks each
harness to call `context` once before the real run — all four answered
`:as-of 10` — which is the check round 1 was missing.

## The task

`task.txt` is round 1's task verbatim (load memory, deploy to staging for
real, fix what fails, record the corrected belief citing the turn that
produced the evidence). `task-multi.txt` appends one paragraph: you are one
of four sessions working this at the same time; memory is the only channel;
call `context` before every write; verify another session's correction
rather than re-deriving it; cite the turn you relied on; never overwrite a
fresher correction you did not verify. `run-round2.sh` starts the four 45 s
apart so all are alive together but later ones are likely to find earlier
turns.

## What the store recorded

From `analyze.clj` (client mode, read-only) — full output in `analysis.txt`:

| session | turn | cites (`:memory/source`) |
| --- | --- | --- |
| runbook-keeper | t 6 | — (seeded the stale `staging-old`) |
| claude | t 11 | — (first to arrive; evidence was `DEPLOY.md` + its own deploy) |
| grok | t 12 | claude t 11 |
| agy | t 13 | claude t 11, grok t 12 |
| qwen | t 14 | claude t 11, agy t 13 |

- The deploy target and command changed **once**, at t 11. grok, agy, and
  qwen re-asserted the identical values; cardinality-one re-assertion of an
  equal value produced no datoms — the idempotence the spec promised.
- Every agent ran `./deploy.sh staging-eu` for real; `deployments.log` holds
  four lines, one per agent, in start order.
- Every later agent re-read `context` immediately before writing (grok and
  agy say so explicitly; agy's pre-write read saw grok's turn land at t 12
  and cited it). Qwen explained why it cited agy but not grok: grok's note
  had already been superseded when it read.
- Qwen's first `record` call failed with `:transact/bad-op` (it passed
  several top-level op vectors instead of one vector of ops); the server
  refused cleanly and wrote nothing.

## Findings

**1. Identity on schema registration is ambiguous (#53).** The seed
registered `:proj/runbook` as a vocabulary attribute and then transacted
`[:db/add "rb" :db/ident :proj/runbook]` for the runbook entity. kala's
registration path deliberately mints a fresh entity rather than upserting
(`resolve-e`, with ADR-0007's "retract here, define elsewhere" rationale),
so two entities held one ident — and `api/entid` answered `...111` over the
wire while the seed-time peer had answered `...112`. The agents all wrote to
`...112` because the `read` tool resolved consistently for them; that was
luck, not a guarantee. The open question is what registration should do
when the ident already exists — upsert if compatible, refuse loudly, or
mint-fresh with duplicate detection — and it needs a domain decision before
a fix.

**2. A single-valued note is a bad coordination channel.** `:proj/note` is
cardinality-one, so each turn replaced the previous agent's account; all
four agents noticed and flagged it in their own notes. The history keeps
every version (the corrections trail shows all five), so nothing is lost —
but `context`'s `:current` shows only the last voice. Multi-agent accounts
want either a cardinality-many attribute or a per-session attribute, which
is a vocabulary decision for the developer owning the schema.

**3. Round 1's Qwen failure was wiring, not kala.** With the probe in place
and the edn rewritten, Qwen connected first time.

## Re-running it

From the kala repo root, with the four CLIs installed and their `kala` MCP
registrations pointing at `/tmp/kala-agents2/<agent>.edn`:

1. `mkdir -p /tmp/kala-agents2 && cp -r ops-repo seed.clj task*.txt *.sh *.edn mcp-configs /tmp/kala-agents2/`, then `clojure -M /tmp/kala-agents2/seed.clj` (fresh store, stale belief at t 6).
2. `clojure -M:transactor /tmp/kala-agents2/transactor.edn &`
3. Overwrite the four edn files with the client-mode configs in `mcp-configs/`.
4. `bash probe.sh` — expect four `10`s.
5. `bash run-round2.sh`; then `clojure -M analyze.clj` while the transactor is up.

`store.edn` is `kala.backup/export-log` of the final store (13 entries,
t 2–14): `import-log` it into fresh storage to inspect the exact datoms,
including the duplicate ident, without re-running anything.

## Limitations

One run, one seed, one task. The 45 s stagger made a relay likely but did
not force it — a fully simultaneous start would test the CAS story (spec
story 7) rather than the citation story, and has not been run. Agents were
told the coordination rules; a run with `task.txt` alone would show how
much of the relay is kala's affordance versus the instruction.
