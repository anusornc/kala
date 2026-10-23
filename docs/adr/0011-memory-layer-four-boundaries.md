# ADR-0011: The memory layer's four boundaries — no in-store vectors, no in-store extraction, no cloud, no silent decay

Status: accepted (2026-10-22). Related: ADR-0008 (compaction is explicit,
never automatic), ADR-0010 (the MCP surface stays data-only), the
competitor survey `docs/research/agent-memory-landscape.md`, the recall
probe `docs/research/recall-probe-2026-10-22/`.

## Context

The primary-source survey of the agent-memory field (Letta, Zep/Graphiti,
mem0, LangGraph, the MCP reference server) found kala alone on provenance,
as-of reads, and schema-enforced queries — and alone in *lacking* the
commodity half of the field: embedding retrieval, LLM extraction
pipelines, hosted distribution, decay. The four harnesses that run kala's
own trials (Claude Code, Grok, AGY, Qwen Code) were consulted on each gap
(2026-10-22; the build-now slice they named — lexical query builtins —
landed in `a073bd1`, measured by the recall probe). All four and the
maintainer converged on the same verdict: these gaps are not missing
features, they are boundaries. What needed recording is the *no* — a
future contributor holding the survey will otherwise "fix" kala into a
worse mem0.

## Decision

Four boundaries, in decreasing exactness:

1. **No embeddings or vector index in the store.** Exact datoms are the
   product; kala's read doors are index slices, which kNN is not. The
   query allowlist gains pure string predicates (`includes?`,
   `starts-with?`, `ends-with?`) for lexical recall and stops there —
   regex stays out because agent-written patterns are a ReDoS surface on
   the transactor. If semantic recall is ever *evidenced* as needed (the
   recall probe is the measuring harness), the kala-shaped path is
   caller-supplied vectors as datom values behind a pure similarity
   predicate, brute-force first, an out-of-process sidecar at most —
   never a model or an ANN index inside the store.

2. **No extraction or consolidation pipeline inside kala.** A belief is
   an agent's write. A consolidator is just another agent: its own
   session, explicit `record`, `:memory/source` citing the turns it
   derived from — the primitive already exists, and round 3's qwen played
   the role live. kala ships the recipe, not the pipeline; an LLM inside
   the store would make writes non-deterministic and mint datoms whose
   provenance cites a pipeline instead of a turn.

3. **No hosted cloud, no first-party SDK matrix.** MCP is the universal
   SDK — anything speaking it reaches kala with no binding. Distribution
   is the standalone jar (`build/mcp-uberjar`) and the registry listing;
   Python/TS clients wait until someone actually fails to speak MCP.

4. **No silent decay.** TTL-as-deletion is a silent retraction and
   duplicates the one pruning kala allows — compaction, explicit, opt-in,
   loud about what it pruned (ADR-0008, ADR-0010). Recency is a read-time
   concern: `context`'s `:min-t` bounds the view without touching the
   log.

The alternatives rejected are the four things themselves; each is the
field's default, which is exactly why the boundary needed a record.

## Consequences

- kala is, deliberately, a worse mem0. That is the position: the survey's
  four verified gaps (provenance, as-of, schema-enforced queries, and now
  lexical-plus-as-of recall) are the ones kala owns; the commodity gaps
  are conceded on purpose.
- Any of these four being revisited requires a new ADR superseding this
  one, with evidence — not a convenience PR.
- The build-now slice this verdict produced is shipped and measured;
  everything on the far side of a boundary stays on the far side until
  the evidence says otherwise.
