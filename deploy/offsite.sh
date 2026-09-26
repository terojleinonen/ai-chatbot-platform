#!/bin/sh
# Copies completed database backups (./backups/<timestamp>/) to S3-compatible object storage, encrypted with
# rclone crypt before upload. Runs in the "offsite" service (rclone image). Subcommands:
#   offsite.sh            loop: sync now, then every OFFSITE_INTERVAL_MINUTES
#   offsite.sh sync       one sync, then exit
#   offsite.sh list       list backups in off-site storage
#   offsite.sh fetch X    download (and decrypt) backup X into /backups/X
#   offsite.sh health     healthy if the last successful sync is recent (Docker healthcheck)
set -eu

INTERVAL_MINUTES=${OFFSITE_INTERVAL_MINUTES:-60}
KEEP_DAYS=${OFFSITE_KEEP_DAYS:-30}
STATE=/state/last-success

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) offsite: $*"; }
fail() { log "ERROR: $*"; exit 1; }

configure() {
  : "${OFFSITE_S3_BUCKET:?set OFFSITE_S3_BUCKET}"
  : "${OFFSITE_S3_ACCESS_KEY_ID:?set OFFSITE_S3_ACCESS_KEY_ID}"
  : "${OFFSITE_S3_SECRET_ACCESS_KEY:?set OFFSITE_S3_SECRET_ACCESS_KEY}"
  # Backups contain customer data and password hashes: never upload them unencrypted.
  [ ${#OFFSITE_ENCRYPTION_PASSWORD} -ge 16 ] 2>/dev/null \
    || fail "OFFSITE_ENCRYPTION_PASSWORD must be set (at least 16 characters); backups are only uploaded encrypted"

  # rclone remotes defined through environment variables (no config file):
  export RCLONE_CONFIG=/dev/null
  #   s3store:  the bucket;  offsite: crypt layer on top of it (file contents and names encrypted)
  export RCLONE_CONFIG_S3STORE_TYPE=s3
  export RCLONE_CONFIG_S3STORE_PROVIDER="${OFFSITE_S3_PROVIDER:-Other}"
  export RCLONE_CONFIG_S3STORE_ACCESS_KEY_ID="$OFFSITE_S3_ACCESS_KEY_ID"
  export RCLONE_CONFIG_S3STORE_SECRET_ACCESS_KEY="$OFFSITE_S3_SECRET_ACCESS_KEY"
  export RCLONE_CONFIG_S3STORE_REGION="${OFFSITE_S3_REGION:-}"
  export RCLONE_CONFIG_S3STORE_ENDPOINT="${OFFSITE_S3_ENDPOINT:-}"
  # The bucket must already exist; this way the access key needs no bucket-level permissions.
  export RCLONE_CONFIG_S3STORE_NO_CHECK_BUCKET=true
  export RCLONE_CONFIG_OFFSITE_TYPE=crypt
  export RCLONE_CONFIG_OFFSITE_REMOTE="s3store:${OFFSITE_S3_BUCKET}/${OFFSITE_S3_PREFIX:-ai-chatbot-backups}"
  export RCLONE_CONFIG_OFFSITE_FILENAME_ENCRYPTION=standard
  export RCLONE_CONFIG_OFFSITE_DIRECTORY_NAME_ENCRYPTION=true
  RCLONE_CONFIG_OFFSITE_PASSWORD=$(rclone obscure "$OFFSITE_ENCRYPTION_PASSWORD")
  export RCLONE_CONFIG_OFFSITE_PASSWORD
  # Salt derived from the password, so the same password always decrypts (e.g. on a new server).
  RCLONE_CONFIG_OFFSITE_PASSWORD2=$(rclone obscure "salt:$OFFSITE_ENCRYPTION_PASSWORD")
  export RCLONE_CONFIG_OFFSITE_PASSWORD2
}

sync_once() {
  # Only completed backups (timestamp-named directories), never .incomplete-* ones.
  set -- --include "/20*/**" --stats-one-line --stats-log-level NOTICE
  if [ "$KEEP_DAYS" -gt 0 ]; then set -- "$@" --max-age "${KEEP_DAYS}d"; fi
  rclone copy /backups offsite: "$@"
  # Verify every uploaded file against the local copy through the encryption layer.
  rclone cryptcheck /backups offsite: --one-way "$@"
  if [ "$KEEP_DAYS" -gt 0 ]; then
    rclone delete offsite: --min-age "${KEEP_DAYS}d"
    rclone rmdirs offsite: --leave-root
  fi
  mkdir -p "$(dirname "$STATE")" && date +%s > "$STATE"
  log "synced; off-site backups: $(rclone lsf offsite: --dirs-only | tr -d / | tr '\n' ' ')"
}

case "${1:-loop}" in
  health)
    [ -f "$STATE" ] || exit 1
    age=$(( $(date +%s) - $(cat "$STATE") ))
    [ "$age" -lt $(( INTERVAL_MINUTES * 60 * 3 )) ] ;;
  sync) configure; sync_once ;;
  list) configure; rclone lsf offsite: --dirs-only | tr -d / ;;
  fetch)
    configure
    name=${2:?usage: offsite.sh fetch <backup name>}
    [ ! -e "/backups/$name" ] || fail "/backups/$name already exists"
    rclone copy "offsite:$name" "/backups/.incomplete-$name"
    mv "/backups/.incomplete-$name" "/backups/$name"
    log "fetched $name into ./backups/$name" ;;
  loop)
    configure
    log "every ${INTERVAL_MINUTES}m to s3://${OFFSITE_S3_BUCKET}/${OFFSITE_S3_PREFIX:-ai-chatbot-backups} (encrypted), keeping ${KEEP_DAYS} days"
    while true; do
      sync_once || log "SYNC FAILED (will retry)"
      sleep $(( INTERVAL_MINUTES * 60 ))
    done ;;
  *) echo "usage: offsite.sh [loop|sync|list|fetch <name>|health]" >&2; exit 2 ;;
esac
