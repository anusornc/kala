#!/bin/bash
# Round 2: four agents, one memory store, staggered starts so later agents
# are likely to find earlier corrections — all four alive concurrently.
# Assumes: the store seeded (seed.clj), a transactor listening on 4334
# (transactor.edn), the four client-mode MCP configs in mcp-configs/, and
# each harness's kala MCP registration pointing at its config.
cd /tmp/kala-agents2/ops-repo || exit 1
T="$(cat /tmp/kala-agents2/task-multi.txt)"
R=/tmp/kala-agents2/transcript-round2
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
