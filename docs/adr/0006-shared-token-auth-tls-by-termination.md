# The wire authenticates with a shared token; TLS by termination

Issue #33 ("transport security") was deferred by ADR-0005 to v2. This ADR
records what v2 means: a shared-secret token checked at the `:connect`
handshake, and TLS delivered by terminating in front of the transactor —
not by native TLS sockets.

A transactor started with `:token "<secret>"` (a start! option and the
config file's `:token` key) requires every `:connect` to carry a matching
token; the comparison is constant time (`MessageDigest/isEqual`), an
unauthenticated `:connect` gets one structured `:protocol/unauthorized`
reply (no data keys — nothing to leak) and the connection closes, mirroring
the version-mismatch and #32 bad-frame disciplines. The token gates before
the version check so an unauthenticated probe learns nothing about the
server. `:listen` subscriptions inherit the token from the connection they
derive from — the client record carries it so the listener's fresh socket
authenticates identically. A tokenless transactor (the default) requires no
auth, so every existing development, demo, and E2E posture is unchanged.
Peer mode is untouched: the embedded library never crosses a socket.

Considered and rejected: native TLS sockets in transactor/client (a second
project's worth of work — key management, cert rotation, JVM SSLContext
plumbing — that reopens ADR-0005's scoping for the networked-service bar);
token auth without deployment guidance (deployers would be left guessing
about the plaintext hop); challenging the token mid-connection (the
handshake is the trust boundary; per-message auth is ceremony without a
threat the single-writer, trusted-network topology has).

Consequences: the README's "no transport security" constraint shrinks to
"transport security is opt-in and minimal" — what remains honestly deferred
is encryption itself. The wire protocol's `:connect` gains an optional
`:token` field and its `:protocol/unauthorized` kind rides the whitelist
plus fixture discipline like every kind before it. Operators generate the
secret themselves (e.g. `openssl rand -hex 32`); Kala never provisions or
stores credentials beyond the config the operator already owns. The
comparison is constant time in the token's *contents*; the compare does
not hide the token's length. Because the same static secret rides every
`:connect` in plaintext, the token's threat model is exactly "keep
unauthenticated peers off the transactor" — on the plaintext hop and on
the terminator→transactor hop it is replayable by anyone who already sees
that segment's traffic, which the TLS-termination deployment exists to
protect; rotation and env-var secret sourcing (`TRANSACTOR_TOKEN`) are
deliberately deferred, not decided against.

A post-implementation review found — and this ADR's original text missed —
that the gate only lived in the `:connect` handler: a client whose FIRST
message was anything else got the `:protocol/connect-required` refusal and
the connection *continued*, so skipping the handshake skipped the token
entirely (an unauthenticated write was demonstrated). The rule is now
structural rather than per-kind: on the first message of a connection, ANY
non-ok reply ends it. The handshake is the trust boundary; there is no
recovery past a first-message refusal. `start!` also validates the token
itself (a non-string or empty `:token` raises `:connect/bad-token` before
the server binds), so a poisoned config cannot degrade into a
half-functioning gate.
