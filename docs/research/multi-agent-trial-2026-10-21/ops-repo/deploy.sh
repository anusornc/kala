#!/usr/bin/env bash
# deploy.sh <target> — must run from the repo root
set -e
TARGET="${1:-}"
if [ -z "$TARGET" ]; then echo "usage: deploy.sh <target>"; exit 2; fi
if ! grep -qx "$TARGET" infra/targets.txt; then
  echo "deploy failed: unknown target '$TARGET' — check DEPLOY.md"
  exit 1
fi
echo "$(date -u +%FT%TZ) deployed to $TARGET" >> infra/deployments.log
echo "DEPLOY-OK $TARGET"
