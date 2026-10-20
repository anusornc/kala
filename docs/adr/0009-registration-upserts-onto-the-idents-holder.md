# ADR-0009: Registration upserts onto the ident's existing holder

Status: accepted (2026-10-20). Supersedes ADR-0007's "registration never
upserts" boundary. Related: ADR-0007 (schema-first phase), #53, the
2026-10-20 four-agent memory trial
(docs/research/multi-agent-memory-trial.md).

## Context

ADR-0007 decided that a schema-registration tempid mints its own entity and
never upserts, because the ident it claims may be retracted from its current
holder later in the same transaction ("retract here, define elsewhere") —
upserting there would hand the old entity's identity to the new
registration. The four-agent trial then hit the other side of that coin:
a store held two entities with the ident `:proj/runbook` (a vocabulary
attribute and a data entity), the registry resolved the ident
order-dependently, and peer and client `entid` answered differently.

Isolating it showed the boundary's cost is not the trial's exotic shape —
it is the ordinary one. Re-transacting an unchanged schema transaction (the
standard idempotent-schema habit) mints a second attribute entity, the
registry flips to it, and **every datom of the old attribute disappears
from queries**: "alice" was simply gone after a schema rerun. The data
stays in the log under the old attr-id; nothing errors; nothing tells you.
`kala.memory/install` only survives because it resolves each ident before
registering — an application-layer guard every user would have to know to
write. With duplicates possible, peer `entid` (registry, last-registered)
and client `entid` (query, first row) also answered differently on the same
store.

## Decision

1. **A registration tempid resolves to the entity already holding the
   ident** — Datomic semantics. Re-running a schema transaction is a
   no-op: card-one adds of equal values produce no datoms, the ident keeps
   exactly one holder, and registry resolution stops being
   order-dependent. Peer and client `entid` agree again because there is
   nothing left to disagree about.

2. **The ADR-0007 exception survives, narrowed to its case.** When the
   same transaction retracts the ident from its current holder —
   `[:db/retract e :db/ident v]` or a retractEntity of the holder — the
   tempid still mints a fresh entity. The retraction is detected up front
   from the written ops; a transaction function that splices such a
   retraction is out of its reach, and there net-state-wins (ADR-0003)
   gives the retraction the last word.

3. **An upsert-registration cannot change the attribute's value type.**
   Datomic treats `:db/valueType` as immutable, and the accident this
   catches is re-running an *edited* schema file: old data keeps the old
   type's datoms while the attribute validates a different one. Raising
   `:transact/schema-conflict` turns the silent invisibility into a named
   failure at the transaction. Cardinality and unique alterations keep
   following the ordinary rules; the direct eid-addressed alteration path
   is untouched.

## Consequences

- `kala.memory/install`'s resolve-before-register guard becomes redundant
  (kept — harmless, and it reads clearly).
- The registry's "last touched wins" behaviour becomes unreachable: one
  holder per ident is now an invariant the transactor upholds, not an
  accident of write order.
- Schema transactions are safe to replay on boot, which is what agent
  harnesses actually do.

## Note on the record tool's EDN edge

The same trial surfaced that `record`'s `tx-data`, parsed with a
first-form-only read, silently dropped every top-level form after the
first. The MCP edge now reads all forms: several op vectors wrap into the
one vector `record` wants, anything else raises with the expected shape.
