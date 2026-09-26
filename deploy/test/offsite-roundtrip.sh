#!/usr/bin/env bash
# TEST ONLY: checks the off-site backup round trip against the throwaway S3 server in offsite-s3.override.yml.
# Usage: deploy/test/offsite-roundtrip.sh <env file>   (the production stack must be running; the env file needs
# no OFFSITE_* settings, they are set here for the test server)
set -euo pipefail
ENV_FILE=${1:?usage: deploy/test/offsite-roundtrip.sh <env file>}
export OFFSITE_S3_PROVIDER=Other OFFSITE_S3_ENDPOINT=http://s3test:9000 OFFSITE_S3_BUCKET=roundtrip \
       OFFSITE_S3_ACCESS_KEY_ID=testkey OFFSITE_S3_SECRET_ACCESS_KEY=testsecret-0123456789 \
       OFFSITE_ENCRYPTION_PASSWORD=roundtrip-test-password-0123
C=(docker compose -f docker-compose.prod.yml -f deploy/test/offsite-s3.override.yml --env-file "$ENV_FILE" --profile offsite)
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; exit 1; }

"${C[@]}" up -d s3test >/dev/null 2>&1
"${C[@]}" exec -T s3test mkdir -p /data/roundtrip
name=$("${C[@]}" run --rm --no-deps backup now 2>/dev/null | sed -n 's/.*created \([0-9TZ]*\).*/\1/p')
[ -n "$name" ] && pass "backup $name created" || fail "backup created"
sums() { sha256sum "backups/$1"/*.dump | sed 's#backups/[^/]*/##'; }
before=$(sums "$name")

"${C[@]}" run --rm --no-deps offsite sync >/dev/null 2>&1 && pass "encrypted sync (upload verified with cryptcheck)" || fail "sync"
"${C[@]}" run --rm --no-deps offsite list 2>/dev/null | grep -qx "$name" && pass "backup listed off-site" || fail "backup listed off-site"
raw=$("${C[@]}" exec -T s3test sh -c 'find /data/roundtrip -type f')
[ -n "$raw" ] && ! echo "$raw" | grep -q "$name" && pass "file and directory names are encrypted" || fail "names encrypted"
"${C[@]}" exec -T s3test sh -c '! grep -rl PGDMP /data/roundtrip' >/dev/null && pass "contents are encrypted (no PostgreSQL dump header)" \
  || fail "contents encrypted"

# ./backups is written by root inside the containers, so delete through a container too.
"${C[@]}" run --rm --no-deps --entrypoint rm backup -rf "/backups/$name" >/dev/null 2>&1
[ ! -e "backups/$name" ] || fail "local copy removed"
"${C[@]}" run --rm --no-deps offsite fetch "$name" >/dev/null 2>&1 && pass "fetched and decrypted" || fail "fetch"
[ "$(sums "$name")" = "$before" ] && pass "fetched files identical to the originals (sha256)" || fail "fetched files identical"
if "${C[@]}" run --rm --no-deps -e OFFSITE_ENCRYPTION_PASSWORD=a-different-password-99 offsite list 2>/dev/null | grep -q "$name"; then
  fail "a wrong password must not decrypt"
else
  pass "a wrong password cannot decrypt"
fi
echo "Off-site round trip passed"
