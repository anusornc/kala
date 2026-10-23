#!/usr/bin/env bash
# E2E for the monitor (web/kala/monitor.clj): boots the real monitor on a
# scratch file store with the deterministic seed, drives every endpoint
# over real HTTP — statics, sessions, the graph (as-of, session filter,
# window), the head ping, the turn and entity drilldowns, the bad-param
# envelope — then transacts one turn through a second client connection
# and watches follow's head advance. Asserts each response; tears down.
# Exits nonzero on any failure.
#
# Run: web/monitor-e2e.sh   (E2E_PORT to override the default scratch port)

set -uo pipefail

PORT=${E2E_PORT:-8091}
BASE="http://127.0.0.1:$PORT"
LOG=$(mktemp /tmp/kala-monitor-e2e-XXXXXX.log)
STORE=$(mktemp -d /tmp/kala-monitor-e2e-store-XXXXXX)

pass=0
fail=0
SRV_PID=""

expect_eq() { # name actual expected
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1)); echo "FAIL $1: got [$2] want [$3]"
  fi
}

expect_contains() { # name haystack needle
  if [[ "$2" == *"$3"* ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1)); echo "FAIL $1: [$2] missing [$3]"
  fi
}

# jget <json> <python expr over m>   (the body IS the m envelope — the
# handler unwraps the endpoint's :m, the demo's exact convention)
jget() {
  python3 -c "import json,sys; m=json.load(sys.stdin); print($2)" <<<"$1"
}

code_of() { # <curl args...> -> http status
  curl -s -o /dev/null -w '%{http_code}' "$@"
}

cleanup() {
  if [ -n "$SRV_PID" ]; then kill "$SRV_PID" 2>/dev/null; fi
  rm -f "$LOG"
}
trap cleanup EXIT

# ---- boot --------------------------------------------------------------------

clojure -M:monitor --store "$STORE" --seed --port "$PORT" >"$LOG" 2>&1 &
SRV_PID=$!

ready=""
for _ in $(seq 1 60); do
  if curl -s -m 2 -o /dev/null "$BASE/api/head"; then ready=yes; break; fi
  if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ready" != "yes" ]; then
  echo "FAIL monitor did not become ready; log:"; cat "$LOG"; exit 1
fi
pass=$((pass + 1))

# ---- statics -------------------------------------------------------------------

body=$(curl -s "$BASE/")
expect_eq "index status" "$(code_of "$BASE/")" 200
expect_contains "index is the monitor page" "$body" "monitor"
expect_eq "monitor.js status" "$(code_of "$BASE/monitor.js")" 200
expect_eq "monitor.css status" "$(code_of "$BASE/monitor.css")" 200
expect_eq "unknown path 404" "$(code_of "$BASE/nope")" 404
expect_eq "unknown api path 404" "$(code_of "$BASE/api/nope")" 404

# ---- head: the seed lands at a known t ------------------------------------------

head=$(curl -s "$BASE/api/head")
expect_eq "head ok" "$(jget "$head" "m['ok']")" True
expect_eq "seeded store head" "$(jget "$head" "m['t']")" 10

# ---- the graph at head ------------------------------------------------------------

g=$(curl -s "$BASE/api/graph")
expect_eq "graph ok" "$(jget "$g" "m['ok']")" True
expect_eq "graph t at head" "$(jget "$g" "m['t']")" 10
expect_eq "every transaction is a turn" "$(jget "$g" "len(m['turns'])")" 10
expect_eq "three user entities" "$(jget "$g" "len(m['entities'])")" 3
expect_eq "nine edges" "$(jget "$g" "len(m['edges'])")" 9
expect_eq "kinds present" \
  "$(jget "$g" "sorted(set(e['kind'] for e in m['edges']))")" "[':assert', ':cite', ':ref', ':retract']"
expect_eq "session menu lists both" "$(jget "$g" "len(m['sessions'])")" 2
expect_contains "an entity label reads the store" "$g" 'Ada Lovelace'
P1=$(jget "$g" "[e['id'] for e in m['entities'] if e['label'] == 'Ada Lovelace'][0]")

# ---- as-of pins the derivation ------------------------------------------------------

g6=$(curl -s "$BASE/api/graph?as-of=6")
expect_eq "as-of t" "$(jget "$g6" "m['t']")" 6
expect_eq "as-of entities" "$(jget "$g6" "len(m['entities'])")" 1
expect_eq "as-of label is the pre-correction belief" "$(jget "$g6" "m['entities'][0]['label']")" "Ada"
expect_eq "as-of turns" "$(jget "$g6" "len(m['turns'])")" 6

# ---- session filter ------------------------------------------------------------------

gs=$(curl -s "$BASE/api/graph?session=run-a")
expect_eq "session filter turns" "$(jget "$gs" "len(m['turns'])")" 3
expect_eq "session filter still lists the menu" "$(jget "$gs" "len(m['sessions'])")" 2

# ---- window ---------------------------------------------------------------------------

gw=$(curl -s "$BASE/api/graph?window=2")
expect_eq "window keeps the recent-t tail" "$(jget "$gw" "len(m['turns'])")" 2

# ---- the turn drilldown ------------------------------------------------------------------

t6=$(curl -s "$BASE/api/turn?t=6")
expect_eq "turn ok" "$(jget "$t6" "m['ok']")" True
expect_eq "turn datoms" "$(jget "$t6" "len(m['datoms'])")" 4
expect_eq "the seed's first turn is all assertions" \
  "$(jget "$t6" "len([x for x in m['datoms'] if x[3] == False])")" 0
t7=$(curl -s "$BASE/api/turn?t=7")
expect_eq "correction turn datoms" "$(jget "$t7" "len(m['datoms'])")" 4
expect_eq "the correction's retraction rides the turn" \
  "$(jget "$t7" "len([x for x in m['datoms'] if x[3] == False])")" 1
tb=$(curl -s "$BASE/api/turn?t=abc")
expect_eq "bad t kind" "$(jget "$tb" "m['kind']")" :monitor/bad-param
expect_eq "bad t status" "$(code_of "$BASE/api/turn?t=abc")" 400

# ---- the entity drilldown: the correction trail ---------------------------------------------

trail=$(curl -s "$BASE/api/entity?id=$P1")
expect_eq "trail ok" "$(jget "$trail" "m['ok']")" True
expect_eq "trail length" "$(jget "$trail" "len(m['trail'])")" 5
expect_eq "the trail holds the retraction" \
  "$(jget "$trail" "len([x for x in m['trail'] if x[3] == False])")" 1
expect_eq "the trail holds the correction" \
  "$(jget "$trail" "len([x for x in m['trail'] if 'Ada Lovelace' in x[2]])")" 1
eb=$(curl -s "$BASE/api/entity?id=nope")
expect_eq "bad id kind" "$(jget "$eb" "m['kind']")" :monitor/bad-param

# ---- the cap scope param ------------------------------------------------------------------

gc=$(curl -s "$BASE/api/graph?cap=6")
expect_eq "cap bounds the drawing" "$(jget "$gc" "+ (len(m['turns']) + len(m['entities']))")" 6

# ---- follow: a second client commits, head advances --------------------------------------------

TXPORT=$(grep -oE 'transactor → 127.0.0.1: [0-9]+' "$LOG" | grep -oE '[0-9]+$')
if [ -z "$TXPORT" ]; then
  fail=$((fail + 1)); echo "FAIL transactor port not in the monitor log"
else
  clojure -M -e "(do (require '[kala.api :as api] '[kala.client :as client] '[kala.memory :as memory])
                     (def c (client/connect {:host \"127.0.0.1\" :port $TXPORT}))
                     (memory/record c \"run-a\" [{:db/id \"live\" :dev/note \"live probe\"}]))" \
    >"$LOG.writer" 2>&1
  if [ $? -ne 0 ]; then
    fail=$((fail + 1)); echo "FAIL the second client could not record; log:"; cat "$LOG.writer"
  else
    advanced=""
    for _ in $(seq 1 15); do
      t=$(curl -s "$BASE/api/head")
      if [ "$(jget "$t" "m['t']")" = 11 ]; then advanced=yes; break; fi
      sleep 1
    done
    expect_eq "follow head advanced" "$advanced" "yes"
    g11=$(curl -s "$BASE/api/graph")
    expect_eq "the new turn is drawn" "$(jget "$g11" "len(m['turns'])")" 11
    expect_contains "the probe's note is on the graph" "$g11" 'live probe'
  fi
  rm -f "$LOG.writer"
fi

# ---- the error envelope when the transactor is unreachable ---------------------------------------
# attach to a standalone transactor, prove reads work, kill it, and watch
# the next wire read come back as the error envelope (head still answers —
# it is the listener's atom, not a wire read)

TX2=8333
TSTORE=$(mktemp -d /tmp/kala-monitor-e2e-tx-XXXXXX)
printf '{:port %s :storage :file :path "%s"}\n' "$TX2" "$TSTORE" > "$TSTORE/config.edn"
clojure -M:transactor "$TSTORE/config.edn" >"$LOG.tx" 2>&1 &
TX_PID=$!
ready2=""
for _ in $(seq 1 60); do
  if grep -q "Transactor listening on $TX2" "$LOG.tx" 2>/dev/null; then ready2=yes; break; fi
  sleep 1
done
if [ "$ready2" != "yes" ]; then
  fail=$((fail + 1)); echo "FAIL the standalone transactor did not start; log:"; cat "$LOG.tx"
else
  MPORT=8094
  clojure -M:monitor --attach "127.0.0.1:$TX2" --port "$MPORT" >"$LOG.m2" 2>&1 &
  M2_PID=$!
  ready3=""
  for _ in $(seq 1 60); do
    if curl -s -m 2 -o /dev/null "http://127.0.0.1:$MPORT/api/head"; then ready3=yes; break; fi
    if ! kill -0 "$M2_PID" 2>/dev/null; then break; fi
    sleep 1
  done
  BASE2="http://127.0.0.1:$MPORT"
  if [ "$ready3" != "yes" ]; then
    fail=$((fail + 1)); echo "FAIL the attached monitor did not become ready; log:"; cat "$LOG.m2"
  else
    g=$(curl -s "$BASE2/api/graph")
    expect_eq "attached monitor reads the empty store" "$(jget "$g" "m['ok']")" True
    kill "$TX_PID"
    sleep 2
    dead=$(curl -s -m 5 "$BASE2/api/graph")
    expect_eq "unreachable transactor is the error envelope" "$(jget "$dead" "m['ok']")" False
    expect_contains "the envelope names the failure" "$dead" 'message'
    expect_eq "head still answers from the listener's atom" \
      "$(jget "$(curl -s "$BASE2/api/head")" "m['ok']")" True
    kill "$M2_PID" 2>/dev/null
  fi
  rm -f "$LOG.tx" "$LOG.m2"
fi

# ---- verdict ------------------------------------------------------------------------------------

echo "monitor e2e: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
