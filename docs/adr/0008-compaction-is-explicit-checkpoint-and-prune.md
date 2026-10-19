# ADR-0008: Compaction is explicit checkpoint-and-prune, with a history cutoff

Status: accepted (2026-10-18). Related: ADR-0005 (the RAM-bound ceiling this
answers), #35.

## Context

ADR-0005 documents the constraint honestly: a file store rebuilds every datom
of its log into memory at boot and has no compaction, so a deployed file db is
RAM-capped — the log only ever grows, and corrections that rewrite the same
fact a thousand times cost a thousand boot replays forever. The storage
protocol's segment half (put/get/list) was documented and tested but
production-dead.

Compaction is the deferred answer. The design question is not whether to
checkpoint (the segment half is the natural home) but **what pruning the log
costs**, because kala's identity is time travel: every read door is
addressable `:as-of t`, and history never loses a correction's trail.

## Decision

1. **Compaction is an explicit, opt-in call — `kala.api/compact`, peer mode
   only.** Never a background process, never a size trigger. Pruning history
   is a semantic choice an operator makes, not an automatic consequence of
   running long enough. (Client-mode/transactor compaction would need a wire
   op; deferred until the networked shape has a story for it.)

2. **The checkpoint lives in the segment half** under one fixed id
   (`storage/checkpoint-segment-id`), overwritten per compaction — checkpoints
   never accumulate. The payload is the live state as raw storage tuples
   (original tx coordinates kept), plus the user allocation counter
   **explicitly**: a live-state-only max cannot recover the ids of entities
   whose every datom was retracted, and re-use would corrupt identity.

3. **Checkpoint durable first, prune second, replay skips by range.** The put
   acks (fsynced) before any entry is deleted; replay starts after the
   checkpoint's base, so every crash window replays identically — a leftover
   pre-base entry from an interrupted prune is skipped by range, not guessed
   away. Both halves are idempotent; re-running finishes an interrupted job.

4. **The cost is a history cutoff, and it is loud.** Reads at or above the
   base are exact (checkpoint datoms keep their tx). Below the base:
   `:as-of` raises `:db/pruned-t` rather than silently misstating what the
   db believed, and `history` shows the surviving datoms plus the full
   post-compaction story. The trail to a pre-base correction is gone; its
   outcome is not.

5. **The monotonicity guard survives an emptied log.** A fully pruned log has
   no entries to derive the append floor from — the file adapter derives it
   from the checkpoint's base at open, the memory adapter keeps a floor, and
   `prune-log!` raises the held floor on both. Without this, a reopen after
   full pruning would let t collide with pruned history.

## Consequences

- Boot replay is bounded by the live set plus the tail, which is the point:
  the RAM ceiling becomes a function of real state, not of write history.
- The `as-of` read door gains an error kind (`:db/pruned-t`) and `history`
  gains a documented cutoff. Applications that require unlimited time travel
  simply never call `compact` — nothing about the default path changes.
- `prune-log!` joins the Storage protocol: third-party adapters must
  implement it (or fail loudly on compaction; the other five operations are
  unchanged).
- Backup interplay is clean: `kala.backup` exports the log, and a checkpoint
  is ordinary segment data in the directory an offline copy takes —
  docs/backup-and-migration.md covers both stories.
