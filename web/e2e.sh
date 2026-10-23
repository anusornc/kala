#!/usr/bin/env bash
# E2E for the web demo (web/kala/web.clj): boots the real server on a
# scratch port with a fresh storage directory, drives every endpoint over
# real HTTP — statics, queries (including time travel and the unknown-
# attribute kind), transactions (success, structured CAS failure, the
# bad-op envelope), datoms, history, reopen durability — asserts each
# response, and tears down. Exits nonzero on any failure.
#
# Run: web/e2e.sh        (E2E_PORT to override the default scratch port)

set -uo pipefail

PORT=${E2E_PORT:-8123}
BASE="http://127.0.0.1:$PORT"
LOG=$(mktemp /tmp/kala-web-e2e-XXXXXX.log)

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

# jget <json> <python expr over m>
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

clojure -M:web "$PORT" >"$LOG" 2>&1 &
SRV_PID=$!

ready=""
for _ in $(seq 1 60); do
  if curl -s -m 2 -o /dev/null "$BASE/api/meta"; then ready=yes; break; fi
  if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ready" != "yes" ]; then
  echo "FAIL server did not become ready; log:"; cat "$LOG"; exit 1
fi
pass=$((pass + 1))

# ---- statics ------------------------------------------------------------------

body=$(curl -s "$BASE/")
expect_eq "index status" "$(code_of "$BASE/")" 200
expect_contains "index is the demo page" "$body" "Kala"
expect_eq "app.js status" "$(code_of "$BASE/app.js")" 200
expect_eq "styles.css status" "$(code_of "$BASE/styles.css")" 200
expect_eq "unknown path 404" "$(code_of "$BASE/nope")" 404
expect_eq "unknown api path 404" "$(code_of -X POST "$BASE/api/nope")" 404

# ---- meta ---------------------------------------------------------------------

meta=$(curl -s "$BASE/api/meta")
expect_eq "meta ok" "$(jget "$meta" "m['ok']")" True
expect_eq "meta t after seed" "$(jget "$meta" "m['t']")" 9
expect_eq "meta datoms" "$(jget "$meta" "m['datoms']")" 82
expect_contains "meta lists person/name" "$meta" ':person/name'
expect_contains "timeline labels t=2" "$meta" '+ :person/name'
expect_eq "timeline length" "$(jget "$meta" "len(m['timeline-t'])")" 9

# ---- queries -------------------------------------------------------------------

q_all='[:find ?n :where [_ :person/name ?n]]'
r=$(curl -s -X POST "$BASE/api/query" -d "$q_all")
expect_eq "query ok" "$(jget "$r" "m['ok']")" True
expect_eq "query rows at now" "$(jget "$r" "len(m['rows'])")" 5
expect_eq "query columns" "$(jget "$r" "m['columns']")" "['?n']"

# time travel: schema lands t=2..6; four people transact at t=7; dave at t=9
r=$(curl -s -X POST "$BASE/api/query?as-of=1" -d "$q_all")
expect_eq "as-of=1 raises the kind" "$(jget "$r" "m['kind']")" :query/unknown-attribute
expect_contains "the kind names the attribute" "$r" ':person/name'
r=$(curl -s -X POST "$BASE/api/query?as-of=6" -d "$q_all")
expect_eq "as-of=6 schema complete, no people" "$(jget "$r" "len(m['rows'])")" 0
r=$(curl -s -X POST "$BASE/api/query?as-of=7" -d "$q_all")
expect_eq "as-of=7 four people" "$(jget "$r" "len(m['rows'])")" 4
r=$(curl -s -X POST "$BASE/api/query?as-of=9" -d "$q_all")
expect_eq "as-of=9 five people" "$(jget "$r" "len(m['rows'])")" 5
r=$(curl -s -X POST "$BASE/api/query?as-of=99" -d "$q_all")
expect_eq "as-of beyond the log clamps to head" "$(jget "$r" "len(m['rows'])")" 5

# aggregate through the wire
r=$(curl -s -X POST "$BASE/api/query" -d '[:find (count ?p) :where [?p :person/name]]')
expect_eq "aggregate count" "$(jget "$r" "m['rows'][0][0]")" 5

# ---- transact -------------------------------------------------------------------

r=$(curl -s -X POST "$BASE/api/transact" -d '[[:db/add "eve" :person/name "Eve"] [:db/add "eve" :person/age 19]]')
expect_eq "transact ok" "$(jget "$r" "m['ok']")" True
expect_eq "transact advances t" "$(jget "$r" "m['t']")" 10
expect_contains "tempids resolve eve" "$r" 'eve\"'
EVE=$(jget "$r" "[s.rsplit(' ', 1)[1] for s in m['tempids'] if 'eve' in s][0]")

r=$(curl -s -X POST "$BASE/api/query" -d "$q_all")
expect_eq "six people after transact" "$(jget "$r" "len(m['rows'])")" 6

# the error envelope: a flat op vector is rejected with its kind and data
r=$(curl -s -X POST "$BASE/api/transact" -d '[:db/add "zoe" :person/name "Zoe"]')
expect_eq "flat op rejected" "$(jget "$r" "m['ok']")" False
expect_eq "flat op kind" "$(jget "$r" "m['kind']")" :transact/bad-op

# compare-and-swap: stale old fails with the structured kind; retry works
r=$(curl -s -X POST "$BASE/api/transact" -d "[[:db.fn/cas $EVE :person/age 30 31]]")
expect_eq "stale cas kind" "$(jget "$r" "m['kind']")" :transact/cas-failed
expect_contains "cas carries actual" "$r" '"actual"'
r=$(curl -s -X POST "$BASE/api/transact" -d "[[:db.fn/cas $EVE :person/age 19 20]]")
expect_eq "matching cas ok" "$(jget "$r" "m['ok']")" True

# retraction shows in history as a minus datom
r=$(curl -s -X POST "$BASE/api/transact" -d "[[:db/retract $EVE :person/age 20]]")
expect_eq "retract ok" "$(jget "$r" "m['ok']")" True
hist=$(curl -s "$BASE/api/history")
expect_contains "history holds the retraction" "$hist" '"-"'
datoms=$(curl -s "$BASE/api/datoms")
expect_contains "current datoms carry idents" "$datoms" ':person/name'

# ---- reopen durability ------------------------------------------------------------

r=$(curl -s -X POST "$BASE/api/reopen")
expect_eq "reopen ok" "$(jget "$r" "m['ok']")" True
expect_eq "reopen lands on last acked t" "$(jget "$r" "m['t']")" 12
r=$(curl -s -X POST "$BASE/api/query" -d "$q_all")
expect_eq "eve survives the reopen" "$(jget "$r" "len(m['rows'])")" 6
r=$(curl -s -X POST "$BASE/api/query?as-of=9" -d "$q_all")
expect_eq "as-of still bounded after reopen" "$(jget "$r" "len(m['rows'])")" 5

# ---- verdict ----------------------------------------------------------------------

echo "e2e: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
