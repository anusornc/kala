#!/bin/bash
# Harness-level MCP probe: can each CLI launch the kala server and call one
# tool? Run before the real trial — round 1 lost qwen to a server that never
# started, and nobody noticed until its transcript came back.
cd /tmp/kala-agents2/ops-repo || exit 1
P='Using the kala MCP server, call its `context` tool once with no arguments and reply with ONLY the integer value of :as-of from the result. No other text, no other tools.'
OUT=/tmp/kala-agents2/probe

( claude -p "$P" --mcp-config /tmp/kala-agents2/claude-mcp.json --strict-mcp-config --dangerously-skip-permissions --output-format text > $OUT-claude.txt 2>&1; echo "exit=$?" >> $OUT-claude.txt ) &
( grok -p "$P" --always-approve > $OUT-grok.txt 2>&1; echo "exit=$?" >> $OUT-grok.txt ) &
( agy -p "$P" --dangerously-skip-permissions --mode auto --print-timeout 240s > $OUT-agy.txt 2>&1; echo "exit=$?" >> $OUT-agy.txt ) &
( qwen -p "$P" --yolo > $OUT-qwen.txt 2>&1; echo "exit=$?" >> $OUT-qwen.txt ) &
wait
for a in claude grok agy qwen; do echo "=== $a ==="; tail -c 600 $OUT-$a.txt; echo; done
