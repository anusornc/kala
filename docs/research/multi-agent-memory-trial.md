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

## Round 3 design (2026-10-21, not yet run)

Three variants, each isolating one variable round 2 left confounded. Every
variant reuses the round-2 recipe unchanged apart from its delta — fresh
store + `seed.clj`, one transactor on 4334, four client-mode MCP configs,
`probe.sh` expecting four `10`s before any agent launches. Each is
independently runnable; the run order is the operator's call. Task texts
and run scripts are authored into a dated `multi-agent-trial-<date>/`
folder when the round is greenlit — quoted here so the design is
reviewable first.

**3a — simultaneous starts: the CAS story (spec story 7).** `run-round3a.sh`
drops the 45 s sleeps; the four agents launch back to back, so none can
rely on finding an earlier turn. `task-cas.txt` keeps the round-2 job but
rewrites the coordination paragraph: the corrected belief is written as
`[:db.fn/cas <runbook> :proj/deploy-target <what-you-read> staging-eu]` —
the guarded variant `record`'s docstring names for exactly this case — and
a `:transact/cas-failed` refusal means re-read, then either record only the
note citing the refuser's turn (when the store now holds the value you
verified) or retry the cas once with the store's current old value. Success
signal: at most one successful target change in the store, and the
cas-failed refusals in the transcripts are the finding, not noise — they
are the store enforcing "never overwrite a fresher correction" that round 2
achieved by instruction alone. Expect a near-empty citation graph (nobody
could go second on purpose); `deployments.log` should still carry four real
deploys. Analysis note: CAS refusals never write, so they appear only in
`transcripts/`, never in the store — grep there.

**3b — `task.txt` verbatim: affordance versus instruction.** The staggered
runner unchanged; the task is round 1's `task.txt` with no coordination
paragraph, no mention of the other sessions, no cite instruction. The
measure is what emerges unprompted: does anyone re-read `context` before
writing, does any `:memory/source` edge appear, do later agents adopt the
earlier correction or re-derive it (four deploys), does anyone clobber
`:proj/note`? Round 2 is the baseline row. Pre-registered prediction, so
the analysis cannot drift: corrections should still converge — the trail is
visible whether or not anyone is told to look — but the citation graph
should be sparse or empty, and if the clobbering happens anyway, finding 2
sharpens into "the affordance shows the trail; only the instruction
prevented the clobber."

**3c — per-session account vocabulary: finding 2 fixed at the schema.**
The seed registers `:proj/note` as cardinality-many (the seed's vocabulary
loop splits it out of the `/one` list), and the task keeps the round-2
coordination paragraph plus one sentence: your account goes in `:proj/note`
and every session's note is kept. Success signal: after all four record,
`context`'s `:current` shows four notes — one voice per session — instead
of only the last one, with no history excavation. This is the
developer-owned vocabulary decision finding 2 pointed at, exercised for
real; if cardinality-many is still not enough for multi-agent accounts,
the residual is a `kala.memory` design question (per-session shaping of the
context document), not a task-authoring one.

