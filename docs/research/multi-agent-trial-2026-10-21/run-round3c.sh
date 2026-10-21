#!/bin/bash
# Round 3c: the round-2 staggered runner, the round-2 coordination paragraph
# plus one sentence ("your account goes in :proj/note and every session's
# note is kept"), against a store whose :proj/note is cardinality-many
# (seed-3c.clj). Success signal: context :current shows four notes after all
# four record — one voice per session — instead of only the last one.
# Assumes: the store seeded with seed-3c.clj, a transactor listening on
# 4334, the four client-mode MCP configs installed, probe.sh answered four 10s.
cd /tmp/kala-agents2/ops-repo || exit 1
T="$(cat /tmp/kala-agents2/task-multi-3c.txt)"
R=/tmp/kala-agents2/transcript-3c
STAMP() { date -u +%H:%M:%SZ; }

run() { # name, command...
  local name=$1; shift
  echo "[$(STAMP)] start $name" >> $R-timeline.txt
  "$@" > "$R-$name.txt" 2>&1
  echo "exit=$?" >> "$R-$name.txt"
  echo "[$(STAMP)] end   $name" >> $R-timeline.txt
}

: > $R-timeline.txt
run claude claude -p "$T" --mcp-config /tmp/kala-agents2/claude-mcp.json --strict-mcp-config --dangerously-skip-permissions --output-format text &
sleep 45
run grok grok -p "$T" --always-approve &
sleep 45
run agy agy -p "$T" --dangerously-skip-permissions --print-timeout 900s &
sleep 45
run qwen qwen -p "$T" --yolo &
wait
echo "[$(STAMP)] all done" >> $R-timeline.txt
