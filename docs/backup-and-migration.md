# Backup and migration

Three ways to get a store's data out and back, all built on the storage
protocol — none of them need a new protocol surface, and every one is
peer-side: they read the log the same way `connect` does.

## Offline copy (zero tooling)

A backup is a copy of the store directory **while no writer runs**. The
file backend acks a transaction only after its log entry is fsynced, so a
quiesced directory is always a complete store: stop the process (or the
transactor), copy the directory, restart. Restore is "put the copy back
and open it."

There is no in-flight-safe copy yet: copying a directory a writer is
actively appending to can capture a torn state, and the copy is then not
guaranteed to open. Use the export tool when the writer must keep running.

## Export / import (the migration format)

`kala.backup` dumps the log as EDN — one entry per line, the raw storage
shape `{:t t :datoms [[e a v tx added] ...]}` — and re-imports it into any
Storage adapter:

```clojure
(require '[kala.api :as api] '[kala.backup :as backup])

(def conn (api/connect {:storage :file :path "/var/data/mydb"}))

; export the whole log (or a {:from-t a :to-t b} window)
(backup/export-log (api/db-storage (api/db conn)) "/backups/mydb-2026-10-18.edn")
; => {:entries 42, :from-t 2, :to-t 43}

; restore into fresh storage — entity ids, schema, and t all replay
(backup/import-log (kala.storage.file/open-storage "/var/data/restored")
                   "/backups/mydb-2026-10-18.edn")
```

Because entries carry raw entity ids and attr ids (no ident resolution),
the restored store replays to the identical db value: same entity ids,
same `t`, same as-of and history reads. Values whose stored class has no
readable EDN literal ride tagged literals — `#kala/float`, `#kala/bytes`
(base64), `#kala/uri` — and an unknown tag fails the import rather than
silently corrupting a value. `clojure.edn`'s refusal to read unknown tags
is the trust boundary; only these three kala tags are expected.

Two properties worth knowing:

- **Append-only discipline.** Import appends through the same
  monotonicity guard as live writes: importing a file whose entries
  overlap the target's last t raises `:storage/non-monotonic-t`. An
  import can extend a store, never overwrite one.
- **Idempotent shape.** Exporting an imported store is byte-identical to
  the original export — a copied file verifies against its source.

## Verify

```clojure
(backup/verify-storage a b {:from-t 0 :to-t 43})
; => {:match? true, :entries 42, :from-t 2, :to-t 43}
; or {:match? false, :reason :entry-count | :t-sequence | :entry-content, ...}
```

Byte arrays compare by content — two independently loaded logs never
share array instances — so a match means the logs are the same data, not
the same objects. After an offline copy, verifying the copy against the
live store is the one-command answer to "did the backup work."

## Copy between backends

```clojure
(backup/copy-storage (api/db-storage (api/db file-conn))
                     (kala.storage.memory/empty-storage))
```

Export to a temporary file and import, in one call — migration from file
to memory, memory to file, or file to file. The target continues its own
t sequence; the same monotonicity guard applies.
