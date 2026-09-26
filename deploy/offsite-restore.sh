#!/usr/bin/env bash
# Disaster recovery from the off-site copies.
#   deploy/offsite-restore.sh .env.production            list off-site backups
#   deploy/offsite-restore.sh .env.production <name>     download and decrypt <name> into ./backups/<name>
# Then restore it with: deploy/restore.sh .env.production <name>
# Works on a fresh server too: it only needs this repository, the env file (OFFSITE_* settings and the
# encryption password) and Docker.
set -euo pipefail
ENV_FILE=${1:?usage: deploy/offsite-restore.sh <env file> [backup name]}
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" --profile offsite)
mkdir -p backups

if [ $# -lt 2 ]; then
  echo "Off-site backups:"
  "${COMPOSE[@]}" run --rm --no-deps offsite list
else
  "${COMPOSE[@]}" run --rm --no-deps offsite fetch "$2"
  echo "Next: deploy/restore.sh $ENV_FILE $2"
fi
