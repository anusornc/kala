# Wire Protocol v1

The transactor speaks a request/reply protocol over TCP. Version 1.

## Transport

- Each message is a **4-byte big-endian length** followed by that many bytes
  of UTF-8 EDN.
- Messages are maps. Values round-trip through EDN: `#inst` and `#uuid` use
  the default EDN readers; byte arrays travel as `#dos/b64 "<base64>"`.
- A clean stream EOF ends the connection. Errors are *replies*, not stream
  conditions. A frame that arrives whole but cannot be read as EDN is
  refused with a `:protocol/bad-frame` reply (no data keys — the reader
  error rides `:message`) and the server closes the connection.

## Handshake

Every connection starts with a `:connect` request carrying the client's
protocol version; any other first message gets a
`:protocol/connect-required` error. A version mismatch is a structured
error and the server closes the connection.

    {:op :connect :protocol-version 1}
    {:status :ok :protocol-version 1 :t <current t>}

## Requests

| op | fields | reply body |
| --- | --- | --- |
| `:connect` | `:protocol-version` | `:protocol-version`, `:t` |
| `:db` | — | `:t` (the transactor's current t) |
| `:transact` | `:tx-data` (list-form ops and/or map-form entities) | `:t`, `:tx-data` (public datom maps), `:tempids` (incl. `:db/current-tx`) |
| `:with` | `:tx-data`, optional `:t` / `:since-t` / `:history` / `:db-ref` | `:t`, `:db-ref`, `:tx-data`, `:tempids` |
| `:q` | `:query` (data form or EDN string), `:args` (the remaining `:in` inputs after `$`, in order), optional `:t` / `:since-t` / `:history` / `:db-ref` | `:rows` |
| `:pull` | `:pattern`, `:eid`, optional `:t` / `:since-t` / `:history` / `:db-ref` | `:doc` |
| `:datoms` | `:index`, `:components`, optional `:t` / `:since-t` / `:history` / `:db-ref` | `:datoms` (public datom maps) |
| `:listen` | — | `:listening`, then push notifications (see below) |

`$` is the db value the request names — as-of `:t` when supplied, a
`:since-t` view, `history` when requested, the `:db-ref` speculative value
when supplied, and the current db otherwise. Further `:q` `:args` bind the
remaining `:in` symbols.

## Speculative transactions

`:with` applies `:tx-data` against the named db value and returns the
result plus a `:db-ref` handle. Reads (q, pull, datoms) accept `:db-ref`
to address the speculative value. The transactor keeps a bounded window of
recent speculative values; nothing is committed.

## Replies

    {:status :ok ...}
    {:status :error :error <namespaced kind> :message "..." :extra...}

Error kinds are the engine's typed kinds (`:transact/unique-conflict`,
`:transact/cas-failed`, `:transact/bad-value-type`,
`:query/unknown-attribute`, `:protocol/version-mismatch`,
`:protocol/bad-frame`, `:transactor/error`, ...). Writes that fail leave
the database untouched; clients may retry safely.

## Notifications

After `:connect`, a connection may send `:listen`. The server acks with
`{:status :ok :listening true}` and then pushes one message per committed
transaction:

    {:notification :tx-completed :t <the new t>}

Listen sockets carry only pushes after the ack; ordering matches the
commit order. Broken listener sockets are dropped silently.

## Write ordering

The transactor is the database's single writer: concurrent write requests
serialize through one lock into a single total order of `t`. Which ops are
write ops is declared in the protocol's op table (currently only
`:transact`); the lock keys off that declaration, not per-op code.

## Durability

A `:transact` reply is sent only after the transaction's log entry is
durable (fsync + atomic rename in the storage adapter).
