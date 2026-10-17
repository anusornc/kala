# Schema registrations apply before everything else in their transaction

A transaction that registers fresh vocabulary and uses it in the same
transaction — an attribute's `:db/ident`/`:db/valueType`/`:db/cardinality`
ops followed by datoms carrying that attribute — is the shape both real
MCP-client agents wrote on their first attempt, and Datomic admits it.
Kala's transactor now admits it too (2026-10-17, from the #44
agent-trial findings): the op pass runs in two phases. Adds that write a
bootstrap schema attribute (`:db/ident`, `:db/valueType`,
`:db/cardinality`, `:db/unique`, `:db/isComponent`, `:db/doc`, `:db/fn`)
onto a **string-tempid entity** apply first, `derive-schema` re-derives
the registry, and then every other op applies. The final derive sees all
datoms of both phases, so ADR-0003's net-state-wins prune keeps working.

Three boundaries are deliberate:

- **Only fresh registrations phase-split.** A schema write onto an
  existing entity (retraction + re-assertion, renames) keeps its written
  order — that is exactly ADR-0003's `same-tx-attribute-redefinition`
  territory, and re-ordering it breaks net-state-wins.
- **Registration never upserts.** The schema phase runs with the upsert
  machinery off: a registration tempid mints its own entity, because the
  unique value it claims may still be held by an entity whose retraction
  lands later in the same transaction (`retract here, define elsewhere`)
  — upserting there would hand the old entity's identity to the new
  registration.
- **The pass does not re-partition mid-flight.** A transaction function
  whose returned ops register vocabulary still lands in the data phase —
  same-tx use of that vocabulary stays a next-turn affair. And an entity
  map that mixes registration keys with use of an attribute registered in
  the *same map* still fails (`parse-entity-map` validates against the
  registry it was given); register in one map, use in another.

Replay needs no special case: the log keeps each transaction's datoms in
phase order, and replay re-derives per entry, so a rebuilt db registers
before it uses, the same as the live pipeline.
