#!/bin/bash
# Round 3b: the round-2 staggered runner unchanged (45 s sleeps, all four
# alive concurrently) — but the task is task.txt verbatim: no coordination
# paragraph, no mention of the other sessions, no cite instruction. The
# measure is what emerges unprompted.
# Assumes: the store seeded, a transactor listening on 4334, the four
# client-mode MCP configs installed, probe.sh already answered four 10s.
cd /tmp/kala-agents2/ops-repo || exit 1
T="$(cat /tmp/kala-agents2/task.txt)"
R=/tmp/kala-agents2/transcript-3b
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
