#!/usr/bin/env bash
# deploy.sh — run on the DEVELOPER machine
# Triggers git pull + docker rebuild on the server via SSH.
set -euo pipefail

SERVER="root@82.22.187.136"
REMOTE_SCRIPT="/opt/messenger-server/scripts/server-deploy.sh"
BRANCH="${1:-main}"

echo "==> Deploying branch '$BRANCH' to $SERVER ..."
ssh -o ServerAliveInterval=15 -o ServerAliveCountMax=40 "$SERVER" "bash $REMOTE_SCRIPT $BRANCH"
