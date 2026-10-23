# Kala

A clean-room, EPL-1.0 reimplementation of Datomic's architecture on the JVM: a single-writer transactor, an embedded peer library, a remote client, and Datalog query, pull, and time-travel reads over pluggable storage.

## Language

### Core model

**Datom**:
One atomic fact: an entity, an attribute, a value, a transaction, and an added flag.
_Avoid_: row, record, fact

**db value**:
An immutable value holding the indexes, schema, and idents as of one t. All reads work on db values and never mutate them.
_Avoid_: snapshot, database handle

**connection**:
A mutable reference to a database's current db value. Transactions swap it.
_Avoid_: session, conn handle

**raw datom**:
A datom whose attribute slot holds the attribute's entity id rather than its ident — the shape kept in log entries and indexes; reads shape raw datoms back to idents.
_Avoid_: internal datom, attr-id datom

**t**:
The transaction counter. Every transaction takes the next t; time-travel reads are addressed by t or a transaction entity id.
_Avoid_: timestamp, tx number

**partition**:
The entity-id space an id belongs to: bootstrap (schema entities and their enums), user (transacted entities), or tx (transaction entities). Every entity id packs its partition with its allocation position into one positive long; kala.eid owns that packing.
_Avoid_: eid space, id prefix

**transaction entity**:
The entity created for each transaction, carrying :db/txInstant.
_Avoid_: tx metadata, epoch

**read door**:
One of the read entry points over db values — datoms, seek-datoms, pull, entity navigation, query patterns. Every read door resolves entity references the same way: a keyword through the registry's ident map, an integer as itself.
_Avoid_: accessor, surface

### Memory

**memory**:
The knowledge from an agent's runs held as datoms in a kala database under the memory schema.
_Avoid_: knowledge base, brain, store

**session**:
One run of an agent recording memory; the datoms it writes fall within its t range.
_Avoid_: run, conversation

**turn**:
One recording step of a session — exactly one transaction; the transaction entity is its record.
_Avoid_: event, step, episode

**belief state**:
What memory held as of one t: a db value read as the agent's view at that point.
_Avoid_: truth, world state, snapshot

**spine**:
The three built-in memory idents — :memory/session, :memory.session/id, :memory/source — that attribute every turn to its session and let a derivation cite its turn. The memory namespace is reserved to the layer; every other attribute in memory is the developer's vocabulary.
_Avoid_: memory schema, provenance schema

**turn listing**:
The step index of one session: ordered [t, turn ordinal, txInstant] triples, one per turn. It is how "step 12" becomes a t; the context document keeps its txInstant out.
_Avoid_: session log, turn table

**context document**:
One readable-EDN belief-state map assembled for an LLM: :current content capped in facts — opt-in :with-t stamps each fact's asserting t, :min-t bounds it to facts asserted since — :corrections the trail of the entities shown, :turns the [t, turn] pairs — everything read as of one t, never carrying a wall clock.
_Avoid_: prompt, summary, pull doc

### Schema

**attribute**:
A typed, named property an entity can carry, itself defined as data: an entity with a :db/ident and a :db/valueType.
_Avoid_: field, property, column

**ident**:
The keyword naming a schema entity. Every attribute has one; an entity with only an ident is an enum. An ident names exactly one entity: registering an ident that is already held resolves to that holder, never to a second one.
_Avoid_: keyword id, name

**enum**:
An entity that registers an ident without a :db/valueType: resolvable as a keyword, never an attribute.
_Avoid_: ident-only entity, keyword entity

**schema registry**:
The db value's derived maps that resolve idents to entity ids and hold each attribute's definition — registered from schema entities and pruned when their :db/ident datom is retracted.
_Avoid_: schema cache, catalog, schema table

### Transactions

**op**:
One transaction-side operation inside tx-data: an add, a retract, a retract-entity, a compare-and-swap, or a transaction-function call. The head of an op is either a reserved op kind or the ident of a stored transaction function. Distinct from a wire op.
_Avoid_: statement, command

**transaction function**:
A stored function invoked inside the serialized transaction: an entity whose ident names it and whose :db/fn attribute holds EDN source, evaluated on the transactor. It receives the db value as of its call position — earlier operations of the same transaction already applied — plus its arguments unresolved, and returns nil or further transaction data spliced at that position.
_Avoid_: stored procedure, tx fn

### Access modes

**peer**:
The embedded access mode: the full read/write API runs in-process against storage.
_Avoid_: embedded mode, local mode

**client**:
The remote access mode: the same public API, but every operation travels the wire to a live transactor.
_Avoid_: remote peer, driver

**transactor**:
The single writer that serializes transactions across processes and owns the storage log.
_Avoid_: server, writer

### Storage

**log entry**:
One append-log record: a t plus the raw datoms transacted at that t. The storage log is an ordered sequence of log entries; rebuilding a database replays it — from the compaction base when the store has been compacted.
_Avoid_: segment, batch

**segment**:
Content-addressed data stored under a unique id via the storage protocol's put/get/list. A segment is not a log entry: it never records a transaction. The checkpoint is the one segment kala itself writes.
_Avoid_: blob, chunk

**checkpoint**:
The live state as of one t, written as a segment under the reserved checkpoint id so that a rebuild replays checkpoint + tail instead of the whole log. Its datoms keep their original tx; the user allocation counter rides along explicitly.
_Avoid_: snapshot, image, dump

**compaction base**:
The t of the store's checkpoint. Replay is exact at and above it; below it the log is pruned, so as-of raises :db/pruned-t there and history holds only the survivors.
_Avoid_: cutoff, horizon, watermark

**compaction**:
The explicit maintenance call (api/compact) that writes a checkpoint for the current t and prunes every log entry at or below it. Opt-in by design — never automatic.
_Avoid_: vacuum, GC, squash

**storage backend**:
A pluggable implementation of the storage protocol (memory, local file) behind the log.
_Avoid_: adapter, driver

### Query engine

**clause**:
One element of a :where section: a pattern, a predicate, a function expression, or a rule call.
_Avoid_: statement, triple

**pattern**:
A vector clause matching datoms by entity, attribute, and value slots, each slot a variable, a constant, or a wildcard.
_Avoid_: triple pattern, datom clause

**solution**:
One binding map from variables to values flowing through the where clauses; a query result is a relation of solutions.
_Avoid_: row, binding set

**slice**:
The contiguous run of one index matching a prefix of components.
_Avoid_: range, scan

### Wire

**wire op**:
One request kind a client may send the transactor, each declared once with its request and reply shape. Distinct from an op, which is transaction-side.
_Avoid_: command, RPC, message

**error kind**:
The keyword classifying a raised error, carried as :kala/error. The same kinds reach peer and client, and a kind determines which of its data keys may ride the wire.
_Avoid_: error code, error type
