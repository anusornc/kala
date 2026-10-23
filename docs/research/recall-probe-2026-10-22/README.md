# Recall probe: lexical builtins through MCP, live agents

**TL;DR:** After adding `includes?`/`starts-with?`/`ends-with?` to the query
allowlist (kala.query/builtins), all four harnesses — Claude Code, Grok, AGY,
Qwen Code — used `recall` with an `includes?` clause to find a fact they
could not have known verbatim, through MCP, first try (4/4, both rounds).
The store-side lexical filter is now real for the niche it was built for:
an agent that half-remembers a fact can make the store filter instead of
dumping `context` and reading it all.

*Run 2026-10-22. One seeded store (three services, three notes, one red
herring each), one transactor on 4334, four client-mode MCP sessions.*

## Method

The store (seed.clj) holds three `:service/*` entities whose notes share no
answer-bearing fragment with the task:

- billing: "invoices regenerate nightly at 02:10 UTC; retries every 15 min"
- ingest: "**kafka consumer lag pages the on-call at 3 min backlog**"
- auth: "JWT rotation runs hourly; old tokens stay valid 5 min"

Task (task.txt): using ONLY `recall`, find the service whose note mentions a
paging alert threshold; the query must filter with `includes?` in `:where`.
Round 1 asked for the answer; round 2 (task-quoted.txt) additionally
required quoting the verbatim query, because headless text output does not
include tool calls — round 1 verified answers, round 2 verified queries.

Gate before launch: all four harnesses answered the same `:as-of` through
`context` (transcripts/gate2-*). The first gate failed: macOS had cleaned
`/tmp/kala-agents2` overnight, so grok's and qwen's registered kala servers
could not start and agy refused loudly (transcripts/gate-*). The
registrations were repointed at `/tmp/kala-probe/<agent>.edn` and the gate
went green — the round-1 lesson (probe before launch) catching a real
failure again.

## Results

| harness | round 1 (answer) | round 2 (query quoted) | query |
|---|---|---|---|
| claude | correct | correct | `[(includes? ?v "page")]` |
| grok | correct | correct | `[(includes? ?v "pages")]` + `:db/ident` join |
| agy | correct | correct | `[(includes? ?v "pag")]` + `:db/ident` join |
| qwen | correct | correct | `[(includes? ?v "pag")]` |

grok's transcript shows the iteration the feature exists for: its first
fragment `"paging"` matched nothing — a miss the store reported cleanly —
and its second query with `"pages"` hit. No agent fell back to `context`.

Caveat: quoted queries are self-reports; a read-only probe leaves no store
trace. They are corroborated by the round-1 answers being exact and by the
miss-then-hit narration in grok's transcript.

## Found along the way

The `record` tool's input-schema example showed `[:db/add [:proj/kala]
:proj/note "hello"]` — the one-element `[:ident]` vector is not a valid
entity reference (`:transact/bad-lookup-ref`: "A lookup ref is
[unique-attr value]"); the working form is a bare ident
(`[:db/add :proj/kala …]`, resolved by resolve-e's static path). The schema
example is fixed in the same commit as this probe.

## Re-running it

1. `mkdir -p /tmp/kala-probe && cp seed.clj task*.txt transactor.edn
   claude-mcp.json /tmp/kala-probe/ && clojure -M /tmp/kala-probe/seed.clj`
2. `clojure -M:transactor /tmp/kala-probe/transactor.edn &`
3. Point each harness's kala registration at
   `/tmp/kala-probe/<agent>.edn` (the seed writes them, client mode).
4. Gate: ask all four for `context`'s `:as-of` — four matching integers.
5. `task-quoted.txt` to each harness; expect QUERY/NOTE/SERVICE with
   `includes?` in the query.
