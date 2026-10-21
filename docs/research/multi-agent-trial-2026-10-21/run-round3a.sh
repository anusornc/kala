#!/bin/bash
# Round 3a: simultaneous starts — the 45 s sleeps are dropped, the four
# agents launch back to back, so none can rely on finding an earlier turn.
# The task routes the target change through :db.fn/cas. Success signal: at
# most one successful target change in the store; the cas-failed refusals
# live in transcripts/ only (CAS refusals never write).
# Assumes: the store seeded, a transactor listening on 4334, the four
# client-mode MCP configs installed, probe.sh already answered four 10s.
cd /tmp/kala-agents2/ops-repo || exit 1
T="$(cat /tmp/kala-agents2/task-cas.txt)"
R=/tmp/kala-agents2/transcript-3a
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
run grok grok -p "$T" --always-approve &
run agy agy -p "$T" --dangerously-skip-permissions --print-timeout 900s &
run qwen qwen -p "$T" --yolo &
wait
echo "[$(STAMP)] all done" >> $R-timeline.txt
