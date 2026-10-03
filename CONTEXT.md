# DatomicOpenSource

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

**t**:
The transaction counter. Every transaction takes the next t; time-travel reads are addressed by t or a transaction entity id.
_Avoid_: timestamp, tx number

**transaction entity**:
The entity created for each transaction, carrying :db/txInstant.
_Avoid_: tx metadata, epoch

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
One append-log record: a t plus the raw datoms transacted at that t. The storage log is an ordered sequence of log entries; rebuilding a database replays it.
_Avoid_: segment, batch

**segment**:
Content-addressed data stored under a unique id via the storage protocol's put/get/list. A segment is not a log entry and never holds transaction facts.
_Avoid_: blob, chunk

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
