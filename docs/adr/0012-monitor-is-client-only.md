# ADR-0012: The monitor is client-only — never a peer

Status: accepted (2026-10-23). Related: ADR-0004 (the no-sandboxing EDN
posture the web demo shares), ADR-0011 (the memory layer's boundaries the
monitor inherits).

## Context

The second web app — the monitor, which draws a store's transactions as a
knowledge graph of entity and turn nodes — needs read access to a store
that a live transactor may already hold. Kala's core invariant is the
single writer: opening a peer on a file store a transactor owns is the
one move that breaks it. The demo (`web/kala/web.clj`) sidesteps this by
being a peer on its own throwaway temp-dir store; a monitor pointed at a
real store cannot.

## Decision

The monitor speaks client mode and nothing else: every read travels the
wire to a live transactor, the peer path does not exist in the monitor's
code. Offline exploration of a fixture (`store-3.edn` and friends) goes
through a transactor started on that fixture — the transactor is how kala
lets anyone own a store; the monitor never becomes a second owner.

Three reasons, in order of weight:

1. **The footgun disappears by construction, not discipline.** No peer
   path in the monitor means no code path can violate single-writer; a
   dual-mode monitor would need a rule ("peer only when no transactor
   holds the store") enforced forever by attention.
2. **Live monitoring is already wire-native.** `client/listen` pushes
   `{:notification :tx-completed :t t}` for every commit — the monitor's
   follow/freeze postures are thin over a channel that exists, and the
   full as-of range of the log is readable through the same connection.
3. **The two apps split the two access modes.** The demo shows the peer
   experience; the monitor shows the client experience. Together they
   cover both without overlapping.

The alternative considered and rejected: a dual-mode monitor (client
against a live system, peer for fixtures). It buys offline convenience at
the cost of shipping the exact footgun the product's docs warn about.

## Consequences

- A monitor without a transactor shows nothing; starting a transactor on
  the target store is a prerequisite, and monitor docs must say so.
- Fixtures are viewed through a transactor every time — no special
  offline mode to build or maintain.
- The monitor inherits client-mode limits by construction: no
  `entity/touch` (peer-only), and compaction stays refused (`:compact`
  defaults off per ADR-0010; a read-only UI never needs it).
