# ADR-0010: Client-mode compaction rides the wire behind a transactor opt-in

Status: accepted (2026-10-21). Resolves ADR-0008's deferral of
client-mode/transactor compaction. Related: ADR-0008 (checkpoint-and-prune),
ADR-0006 (the shared token — the trust boundary this decision layers onto),
#52.

## Context

ADR-0008 shipped compaction as peer-only `api/compact`. In a networked
deployment the transactor owns the storage, so a client calling `compact`
gets `:client/peer-only` — the only way to bound boot replay today is
stopping the transactor and compacting the directory as a peer. The wire op
itself is uncontroversial: one declared op, served under the single-writer
lock, the same `{:t :datoms :pruned}` report. What needed settling first was
the trust posture, because compaction is the one client-reachable effect
that is irreversible. Every op on the wire today keeps history — even a
retractEntity leaves the full trail — while pruning removes every datom
below the compaction base. The shared token's trust level is "everything
but irreversibility", and a tokenless transactor (the default) means no
auth at all.

## Decision

1. **One new wire op, `:compact`.** Declared in the op table with
   `:is-write? true`, so the connection loop serializes it through the
   single-writer lock: the checkpoint is taken from the transactor's
   current db and no concurrent transact's append races the prune, which
   makes the report's `:t` exact. The handler calls `compaction/compact!`
   on the transactor's own peer connection. Listeners get no
   notification — compaction is not a transaction and consumes no t.

2. **The op is refused unless the transactor was started with
   `:compact true`** (a `start!` option and the config file's `:compact`
   key; the default is refusal). The refusal is the structured kind
   `:compact/disabled`. The flag gates the wire only: peer-mode
   `api/compact` is unchanged, including the transactor's own connection.

3. **The MCP surface stays data-only — no compact tool.** The operator
   path is the transactor's config, not the agent surface; the issue that
   filed this worried precisely about an agent harness triggering history
   pruning.

The alternatives considered: an operator token (a second secret; only
connections that handshook with it may compact) buys per-connection
granularity and a home for future privileged ops, but doubles the
credential surface ADR-0006 deliberately kept minimal; "any authenticated
client" keeps the op table uniform, but it hands every harness client the
one irreversible op. The flag is ADR-0008's own principle — compaction is
explicit and opt-in, never automatic — applied at the deployment level:
the safe default means nothing on the network can prune history unless the
operator said so.

## Consequences

- A networked deployment bounds boot replay without the
  stop-the-transactor dance: start it with `:compact true`, call
  `api/compact` from any client, done — the report's `:t` is exact because
  the op holds the write lock.
- The token's trust level is now precisely stated: a token holder can
  destroy live data but not the trail, unless the operator widened the
  deployment with `:compact true`.
- If per-connection granularity is ever genuinely needed, the flag
  generalizes into an operator credential without reopening the op's
  shape or its place in the table.
- `wire/error-data-keys` gains `:compact/disabled`; the parity matrix
  triggers it client-side only — the peer has no flag to trip.
