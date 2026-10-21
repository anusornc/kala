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

## Round 3 design (2026-10-21; run the same day — results below)

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

## Round 3 results (2026-10-21)

All three variants ran the same afternoon, in the order 3b → 3c → 3a, each
against a fresh store with the full gate (seed, transactor on 4334, four
client-mode configs, `probe.sh` answering four `10`s before any agent
launched — all four passed first time). Harness, task texts, transcripts,
analyses, and store exports live in `multi-agent-trial-2026-10-21/`.
Everything below is the store's account (`analyze.clj` per variant) with
transcripts as commentary.

**3b — `task.txt` verbatim (affordance versus instruction).** The
correction still converged: one target/command change, at t 11 (claude),
everyone else adopted it, four real deploys (a fifth log line is grok
running `bash -x ./deploy.sh` as a trace). Two measured deviations from
the pre-registered prediction:

- *The citation graph was not sparse.* Six `:memory/source` edges from
  three of four agents (claude t11 → t6; agy t12 → t11; qwen t14/t15 →
  t11, t13). The reason sits in the task's own text: `task.txt` step 4
  already instructs citing "the turn that produced your evidence" — the
  coordination paragraph only ever added the who-else-is-running rule.
  "Affordance versus instruction" splits finer than the design assumed:
  the cite instruction rode along in the base task.
- *The clobber happened anyway, and the instruction-less agents handled
  it well.* `:proj/note` was replaced at t 12, 13, 14, 15 exactly as
  finding 2 predicts — but qwen caught the clobber via `history`, and
  wrote a second, consolidating turn (t 15) that discloses it and cites
  the superseded turns. Unprompted. Round 2 sharpens into: neither
  affordance nor instruction prevented the clobber (round 2's
  coordination rule didn't either); the history is what preserves every
  voice, and a diligent agent can self-repair from it. The schema fix
  (3c) is what removes it.

One caveat on interpreting 3b as "pure affordance": qwen's transcript
shows it editing its own project memory mid-run and quoting the round-2
"say so" rule that `task.txt` does not contain — Qwen Code carries
per-project memory across sessions, so its round-2 learning leaked in.
The store-level results (one correction, clobber chain, citations) stand
regardless; qwen's polished coordination behavior should not be read as
emergent from kala alone.

**3c — `:proj/note` cardinality-many (finding 2 fixed at the schema).**
Success signal met exactly. After all four record, `context`'s `:current`
shows five notes at once — runbook-keeper's seeded note plus one per
session (claude t 11, grok t 12, agy t 13, qwen t 14), each self-labelled
"session <name>" — with no history excavation. The corrections trail
holds only the two real t 11 corrections; no agent turn retracts
anything, and each session wrote exactly one turn. The relay is the
cleanest of the three rounds: grok cites claude, agy cites claude, qwen
cites claude + grok + agy (grok's citation lands in the store this time).
Comic counterpoint: qwen's note carries a "WARNING for readers:
:proj/note is single-valued" — its stale round-2 belief, contradicted by
the very document it wrote into, and harmless because a cardinality-many
add never retracts. Cardinality-many was sufficient; no residual
`kala.memory` design question.

**3a — simultaneous starts, `:db.fn/cas` (spec story 7, first live
exercise).** Success signal met: exactly one successful target change in
the store (claude t 11, via two successful `:db.fn/cas` ops — target and
command together), and three `:transact/cas-failed` refusals in the
transcripts that wrote nothing:

- agy read `context` at `:as-of 11` — claude's change already landed —
  and *still* attempted the cas against the `staging-old` it had read
  first. Refused, `:actual "staging-eu"`. The guard catching a stale
  read is precisely the race this variant was built to expose.
- qwen and grok, who read before claude wrote, hit the same refusal and
  each took the "record only the note" branch; nobody retried the cas.
- The refusal's shape — `{:kala/error :transact/cas-failed, :op …,
  :old …, :new …, :actual "staging-eu", :message …}` — carried
  everything the agents needed; all three recovered on the first
  attempt. The store enforced "never overwrite a fresher correction you
  did not verify" that round 2 achieved by instruction alone.

Four real deploys, one per session. Citations: agy → claude (both
op-level and turn-level), qwen → claude; near-empty as predicted, since
nobody could go second on purpose. One repeat of the 3b anomaly: grok's
note says it cited the turn that set the target, and the store holds no
such datom.

**Cross-round findings.**

1. CAS works end to end through MCP with LLM agents, including the
   interesting failure direction — a refusal against a value the agent
   had already seen change. Spec story 7 is exercised, not just claimed.
2. The self-report gap is now measured, not hypothetical: grok twice
   reported a `:memory/source` citation the store does not contain (3b
   t 13, 3a t 14). The round-2 discipline — the store record is the
   evidence, transcripts are commentary — has a concrete counterexample
   behind it now.
3. Agent-side memory contamination is a harness variable from here on.
   Qwen Code's per-project memory carried round-2 learnings into both
   later rounds (quoted rules that weren't in the task; a false
   single-valued warning in 3c). Any future round that wants naive
   agents needs fresh profiles or an explicit instruction to ignore
   prior-session memory — otherwise the same leakage compounds.

Store exports: `store-3b.edn` (14 entries, t 2–15), `store-3c.edn` and
`store-3a.edn` (13 entries each, t 2–14) — `kala.backup/import-log` them
into fresh storage to inspect the exact datoms without re-running
anything.

