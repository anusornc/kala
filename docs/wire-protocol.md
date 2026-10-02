# Wire Protocol v1

The transactor speaks a request/reply protocol over TCP. Version 1.

## Transport

- Each message is a **4-byte big-endian length** followed by that many bytes
  of UTF-8 EDN.
- Messages are maps. Values round-trip through EDN: `#inst` and `#uuid` use
  the default EDN readers; byte arrays travel as `#dos/b64 "<base64>"`.
- A clean stream EOF ends the connection. Errors are *replies*, not stream
  conditions.

## Handshake

Every connection starts with a `:connect` request carrying the client's
protocol version. A version mismatch is a structured error and the server
closes the connection.

    {:op :connect :protocol-version 1}
    {:status :ok :protocol-version 1 :t <current t>}

## Requests

| op | fields | reply body |
| --- | --- | --- |
| `:connect` | `:protocol-version` | `:protocol-version`, `:t` |
| `:db` | — | `:t` (the transactor's current t) |
| `:transact` | `:tx-data` (list-form ops and/or map-form entities) | `:t`, `:tx-data` (public datom maps), `:tempids` (incl. `:db/current-tx`) |
| `:q` | `:query` (data form or EDN string), `:args` (further `:in` inputs, in order), optional `:t`, optional `:history` | `:rows` |
| `:pull` | `:pattern`, `:eid`, optional `:t` / `:history` | `:doc` |
| `:datoms` | `:index`, `:components`, optional `:t` / `:history` | `:datoms` (public datom maps) |

`$` is always the transactor's db value — as-of `:t` when supplied, the
current db otherwise; `history` when requested. Further `:q` `:args` bind
the remaining `:in` symbols.

## Replies

    {:status :ok ...}
    {:status :error :error <namespaced kind> :message "..." :extra...}

Error kinds are the engine's typed kinds (`:transact/unique-conflict`,
`:transact/bad-value-type`, `:query/unknown-attribute`,
`:protocol/version-mismatch`, `:transactor/error`, ...). Writes that fail
leave the database untouched; clients may retry safely.

## Write ordering

The transactor is the database's single writer: concurrent `:transact`
requests serialize through one lock into a single total order of `t`.

## Durability

A `:transact` reply is sent only after the transaction's log entry is
durable (fsync + atomic rename in the storage adapter).
