#!/usr/bin/env bash
# Restores both databases from a backup made by the "backup" service.
# Usage: deploy/restore.sh .env.production <backup name>   (a directory name under ./backups, e.g. 20260926T010000Z)
# Stops the backend and AI service during the restore (the chat is unavailable meanwhile) and starts them again;
# the AI service reloads its models from the restored data.
set -euo pipefail
ENV_FILE=${1:?usage: deploy/restore.sh <env file> <backup name>}
NAME=${2:?usage: deploy/restore.sh <env file> <backup name>}
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE")

[ -d "backups/$NAME" ] || { echo "No backup backups/$NAME. Available:"; ls backups; exit 1; }
read -r -p "Replace ALL current data with backup $NAME? Type 'restore' to continue: " answer
[ "$answer" = restore ] || { echo "Aborted."; exit 1; }

"${COMPOSE[@]}" stop backend ai
"${COMPOSE[@]}" run --rm --no-deps backup restore "$NAME"
"${COMPOSE[@]}" start ai backend
echo "Restored $NAME."
