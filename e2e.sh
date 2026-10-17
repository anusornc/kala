#!/usr/bin/env bash
# Project-level E2E for Kala: the real user journeys, over real processes.
#
#   e2e.sh              # all four journeys
#   e2e.sh transactor   # boot the transactor as a process, drive it from a
#                       #   separate client process, SIGKILL it mid-life,
#                       #   restart on the same storage directory, and prove
#                       #   every acked transaction survived (durability
#                       #   before ack, across a process boundary)
#   e2e.sh release      # RELEASE_VERSION jar build: contents, pom, license,
#                       #   and the bench/demo/web exclusion guarantee
#   e2e.sh demo         # the asserted walkthrough: clojure -M:demo
#   e2e.sh web          # the web demo's API E2E: web/e2e.sh
#   e2e.sh mcp          # the MCP server over stdio: record a turn, SIGKILL
#                       #   the server, restart on the same session id, and
#                       #   prove durability and session resume
#
# Exits nonzero if any requested journey fails.

set -uo pipefail

JOURNEY=${1:-all}
pass=0
fail=0
GLOBAL_TX_PID=""

cleanup_tx() {
  if [ -n "$GLOBAL_TX_PID" ]; then
    kill -9 "$GLOBAL_TX_PID" 2>/dev/null
    wait "$GLOBAL_TX_PID" 2>/dev/null
  fi
}
trap cleanup_tx EXIT

ok() { pass=$((pass + 1)); echo "ok   $1"; }
bad() { fail=$((fail + 1)); echo "FAIL $1"; }

# run_expect <name> <expected substring> <cmd...> — rc must be 0 and the
# combined output must contain the expected substring; returns nonzero on
# failure so callers can keep evidence around
run_expect() {
  local name=$1 expected=$2
  shift 2
  local out rc
  out=$("$@" 2>&1)
  rc=$?
  if [ "$rc" -eq 0 ] && [[ "$out" == *"$expected"* ]]; then
    ok "$name"
    return 0
  fi
  bad "$name (rc=$rc, expected [$expected] in output)"
  echo "$out" | tail -8
  return 1
}

# ---- journey: transactor crash-recovery ---------------------------------------

journey_transactor() {
  local DIR PORT TX_PID
  DIR=$(mktemp -d /tmp/kala-project-e2e-XXXXXX)
  # a random port per run: a transactor left over from an earlier failed
  # run must never serve this one (that bug once ate a whole afternoon)
  if [ -n "${E2E_TX_PORT:-}" ]; then
    PORT=$E2E_TX_PORT
    case "$PORT" in
      '' | *[!0-9]*)
        echo "E2E_TX_PORT must be numeric, got: $PORT"
        exit 2
        ;;
    esac
  else
    PORT=$((20000 + RANDOM % 20000))
  fi
  cat > "$DIR/transactor.edn" <<EOF
{:port $PORT :storage :file :path "$DIR/data"}
EOF

  start_tx() {
    clojure -M:transactor "$DIR/transactor.edn" >>"$DIR/transactor.log" 2>&1 &
    TX_PID=$!
    GLOBAL_TX_PID=$TX_PID
  }
  wait_ready() {
    for _ in $(seq 1 60); do
      if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
        exec 3>&- 3<&-
        return 0
      fi
      kill -0 "$TX_PID" 2>/dev/null || return 1
      sleep 1
    done
    return 1
  }

  start_tx
  if wait_ready && kill -0 "$TX_PID" 2>/dev/null; then
    ok "transactor booted as a real process (port $PORT)"
  else
    bad "transactor did not boot"; tail -5 "$DIR/transactor.log"; return 1
  fi

  run_expect "client process seeds the store over the wire (schema, people, edge, cas, retract)" \
    "WRITE-OK" clojure -M:e2e write 127.0.0.1 "$PORT" || {
    echo "storage kept for debugging: $DIR"; return 1
  }

  # hard crash — no shutdown path, no flush hook: what was acked must
  # already be durable
  kill -9 "$TX_PID" 2>/dev/null
  wait "$TX_PID" 2>/dev/null
  ok "transactor killed with SIGKILL"

  start_tx
  if wait_ready && kill -0 "$TX_PID" 2>/dev/null; then
    ok "transactor restarted on the same storage directory"
  else
    bad "transactor did not restart"; tail -5 "$DIR/transactor.log"; return 1
  fi

  run_expect "restarted store: names, cas'd age, retraction, friend edge, as-of, history" \
    "VERIFY-OK" clojure -M:e2e verify 127.0.0.1 "$PORT" || {
    echo "storage kept for debugging: $DIR"; return 1
  }

  kill "$TX_PID" 2>/dev/null
  wait "$TX_PID" 2>/dev/null
  rm -rf "$DIR"
}

# ---- journey: release jar -------------------------------------------------------

journey_release() {
  local VERSION JAR contents
  VERSION=0.9.9-e2e
  if RELEASE_VERSION=$VERSION clojure -T:build jar >/dev/null 2>&1; then
    ok "jar builds from the tagged version"
  else
    bad "jar build failed"
    return 1
  fi
  JAR="target/kala-$VERSION.jar"
  if [ -f "$JAR" ]; then ok "jar exists at $JAR"; else bad "jar missing"; return 1; fi

  contents=$(jar tf "$JAR")
  # the release jar is a source jar: .clj sources, pom, license — no AOT
  for want in "kala/api.clj" "kala/core.clj" "kala/transactor.clj" "kala/client.clj" \
              "META-INF/maven/" "LICENSE"; do
    if grep -q "$want" <<<"$contents"; then ok "jar carries $want"; else bad "jar missing $want"; fi
  done
  if grep -q "kala/memory.clj" <<<"$contents"; then ok "jar carries kala/memory.clj"; else
    bad "jar missing kala/memory.clj"
  fi
  for forbid in "^bench/" "kala/web" "kala/demo" "kala/e2e" "kala/mcp"; do
    if grep -q "$forbid" <<<"$contents"; then bad "jar must not carry $forbid"; else
      ok "jar excludes $forbid"
    fi
  done
  rm -f "$JAR"

  # the MCP artifact: server + its codec, never the core
  if RELEASE_VERSION=$VERSION clojure -T:build mcp-jar >/dev/null 2>&1; then
    ok "mcp artifact builds"
  else
    bad "mcp artifact build failed"
    return 1
  fi
  local MCP_JAR mcp_contents mcp_pom
  MCP_JAR="target/kala-mcp-$VERSION.jar"
  if [ -f "$MCP_JAR" ]; then ok "mcp artifact exists at $MCP_JAR"; else bad "mcp jar missing"; return 1; fi
  mcp_contents=$(jar tf "$MCP_JAR")
  for want in "kala/mcp.clj" "META-INF/maven/" "LICENSE"; do
    if grep -q "$want" <<<"$mcp_contents"; then ok "mcp artifact carries $want"; else
      bad "mcp artifact missing $want"
    fi
  done
  for forbid in "kala/api.clj" "kala/memory.clj"; do
    if grep -q "$forbid" <<<"$mcp_contents"; then bad "mcp artifact must not carry $forbid"; else
      ok "mcp artifact excludes $forbid (kala comes from the dependency)"
    fi
  done
  if mcp_pom=$(unzip -p "$MCP_JAR" "META-INF/maven/io.github.anusornc/kala-mcp/pom.xml" 2>/dev/null) &&
    grep -q "io.github.anusornc" <<<"$mcp_pom" && grep -q "data.json" <<<"$mcp_pom"; then
    ok "mcp artifact pom declares kala and its codec"
  else
    bad "mcp artifact pom does not declare its dependencies"
  fi
  rm -f "$MCP_JAR"
}

# ---- journey: mcp server over stdio -----------------------------------------------
# A real server process, a real file store, a real session: record a turn,
# SIGKILL the server mid-life, restart on the same session id, and prove the
# resumed session's next turn is ordinal 2 — durability and resume in one go.

journey_mcp() {
  local DIR SRV waited
  DIR=$(mktemp -d /tmp/kala-mcp-e2e-XXXXXX)
  cat > "$DIR/mcp.edn" <<EOF
{:conn {:storage :file :path "$DIR/store"}
 :session "journey-7"}
EOF

  # turn 1: vocabulary + fact in ONE turn (ADR-0007 — schema registrations
  # apply before the other ops of their transaction). The server's stdin is
  # held open so the SIGKILL lands mid-life.
  mkfifo "$DIR/in"
  clojure -M:mcp "$DIR/mcp.edn" >"$DIR/out" 2>>"$DIR/server.log" <"$DIR/in" &
  SRV=$!
  exec 9>"$DIR/in"
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"record","arguments":{"tx-data":"[[:db/add \"note-attr\" :db/ident :proj/note] [:db/add \"note-attr\" :db/valueType :db.type/string] [:db/add \"note-attr\" :db/cardinality :db.cardinality/one] [:db/add \"p\" :proj/note \"journey\"]]"}}}' >&9
  waited=0
  while ! grep -q '"id":2' "$DIR/out" 2>/dev/null; do
    waited=$((waited + 1))
    if [ "$waited" -gt 60 ]; then
      bad "mcp server never answered the record"
      exec 9>&-; kill -9 "$SRV" 2>/dev/null
      tail -5 "$DIR/server.log"
      return 1
    fi
    sleep 1
  done
  kill -9 "$SRV" 2>/dev/null
  wait "$SRV" 2>/dev/null
  SRV=""
  exec 9>&-
  ok "mcp server recorded a turn and was SIGKILLed mid-life"

  # session 2: the same session id resumes; the vocabulary survived the
  # kill, so the resumed session's first fact is turn 2
  run_expect "restarted server: same session resumes, next turn is ordinal 2" \
    ":turn 2" bash -c "printf '%s\n' \
      '{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"record\",\"arguments\":{\"tx-data\":\"[[:db/add \\\"p\\\" :proj/note \\\"journey\\\"]]\"}}}' \
      | clojure -M:mcp '$DIR/mcp.edn' 2>>'$DIR/server.log'" \
    || { echo "storage kept for debugging: $DIR"; return 1; }

  run_expect "restarted server: the context document carries the durable fact" \
    "journey" bash -c "printf '%s\n' \
      '{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"context\",\"arguments\":{\"cap\":10}}}' \
      | clojure -M:mcp '$DIR/mcp.edn' 2>>'$DIR/server.log'" \
    || { echo "storage kept for debugging: $DIR"; return 1; }

  rm -rf "$DIR"
}

# ---- journeys: demo and web ------------------------------------------------------

journey_demo() {
  run_expect "asserted walkthrough passes end to end" "ALL PROOFS PASSED" clojure -M:demo
}

journey_web() {
  run_expect "web demo API E2E passes" "0 failed" web/e2e.sh
}

# ---- dispatch ---------------------------------------------------------------------

case "$JOURNEY" in
  transactor) journey_transactor ;;
  release) journey_release ;;
  demo) journey_demo ;;
  web) journey_web ;;
  mcp) journey_mcp ;;
  all)
    journey_transactor
    journey_release
    journey_demo
    journey_web
    journey_mcp
    ;;
  *)
    echo "unknown journey: $JOURNEY (use all|transactor|release|demo|web|mcp)"
    exit 2
    ;;
esac

echo "project e2e [$JOURNEY]: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
