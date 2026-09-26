#!/usr/bin/env bash
# Runs inside the "backup" service (postgres image, so pg_dump matches the server version).
#   backup.sh            loop: back up now, then every BACKUP_INTERVAL_HOURS, pruning old backups
#   backup.sh now        one backup, then exit
#   backup.sh restore X  restore backup directory X (the backend and AI service must be stopped)
# Uses PGHOST/PGUSER/PGPASSWORD from the environment. Backups go to /backups/<UTC timestamp>/<db>.dump.
set -euo pipefail

DATABASES=(main_backend ai_microservice)
DIR=/backups
INTERVAL_HOURS=${BACKUP_INTERVAL_HOURS:-24}
KEEP_DAYS=${BACKUP_KEEP_DAYS:-14}

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"; }

backup_once() {
  local name tmp
  name=$(date -u +%Y%m%dT%H%M%SZ)
  tmp="$DIR/.incomplete-$name"
  mkdir -p "$tmp"
  for db in "${DATABASES[@]}"; do
    pg_dump --format=custom --no-owner --dbname="$db" --file="$tmp/$db.dump"
  done
  mv "$tmp" "$DIR/$name"   # only complete backups get a timestamp name
  log "created $name ($(du -sh "$DIR/$name" | cut -f1))"
}

prune() {
  # Timestamped directories older than KEEP_DAYS, plus leftovers from interrupted runs.
  find "$DIR" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +"$((KEEP_DAYS - 1))" -print -exec rm -rf {} + \
    | sed 's#.*/#removed old backup #' | while read -r line; do log "$line"; done
  find "$DIR" -mindepth 1 -maxdepth 1 -type d -name '.incomplete-*' -mmin +60 -exec rm -rf {} +
}

restore() {
  local name=${1:?usage: backup.sh restore <backup name>} src
  src="$DIR/$name"
  [ -d "$src" ] || { log "no backup $src"; exit 1; }
  for db in "${DATABASES[@]}"; do
    [ -f "$src/$db.dump" ] || { log "missing $src/$db.dump"; exit 1; }
  done
  for db in "${DATABASES[@]}"; do
    log "restoring $db from $name"
    # --clean --if-exists replaces existing objects; --single-transaction makes each database all-or-nothing.
    pg_restore --clean --if-exists --no-owner --single-transaction --dbname="$db" "$src/$db.dump"
  done
  log "restore of $name complete"
}

until pg_isready -q -d "${DATABASES[1]}"; do sleep 2; done

case "${1:-loop}" in
  now) backup_once ;;
  restore) restore "${2:-}" ;;
  loop)
    log "every ${INTERVAL_HOURS}h, keeping ${KEEP_DAYS} days, into $DIR"
    while true; do
      backup_once || log "BACKUP FAILED"
      prune
      sleep "$((INTERVAL_HOURS * 3600))"
    done ;;
  *) echo "usage: backup.sh [loop|now|restore <name>]" >&2; exit 2 ;;
esac
