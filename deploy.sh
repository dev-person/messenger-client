#!/usr/bin/env bash
# deploy.sh — run on the DEVELOPER machine
# Adds the SSH key to ssh-agent, then triggers a git-pull + docker rebuild on the server.
set -euo pipefail

SERVER="root@82.22.187.136"
SSH_KEY="$HOME/.ssh/id_rsa"
REMOTE_SCRIPT="/opt/messenger-server/scripts/server-deploy.sh"
BRANCH="${1:-main}"

echo "==> Adding SSH key to agent (passphrase will be prompted once)..."
eval "$(ssh-agent -s)"
ssh-add "$SSH_KEY"

echo "==> Deploying branch '$BRANCH' to $SERVER ..."
ssh "$SERVER" "bash $REMOTE_SCRIPT $BRANCH"

echo "==> Stopping ssh-agent..."
ssh-agent -k
