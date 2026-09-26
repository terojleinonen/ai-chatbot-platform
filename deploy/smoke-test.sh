#!/usr/bin/env bash
# Smoke test for a running production stack (docker-compose.prod.yml), checked from the outside over HTTPS.
# Usage: deploy/smoke-test.sh .env.production
# With *.localhost hosts (local/CI runs) Caddy uses its internal CA, so certificate checks are skipped;
# for real hosts they are enforced.
set -uo pipefail

ENV_FILE=${1:?usage: deploy/smoke-test.sh <env file>}
set -a; source "$ENV_FILE"; set +a

CURL=(curl -sS --max-time 15)
case "$API_HOST" in *.localhost)
  CURL+=(-k --resolve "$API_HOST:443:127.0.0.1" --resolve "$ADMIN_HOST:443:127.0.0.1"
         --resolve "$API_HOST:80:127.0.0.1" --resolve "$ADMIN_HOST:80:127.0.0.1") ;;
esac
API="https://$API_HOST"; ADMIN="https://$ADMIN_HOST"
failures=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1${2:+ — $2}"; failures=$((failures + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected $3, got $2"; fi; }

echo "Waiting for $API/actuator/health ..."
for _ in $(seq 1 90); do
  [ "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$API/actuator/health" 2>/dev/null)" = 200 ] && break
  sleep 2
done

check "backend health over HTTPS" "$("${CURL[@]}" "$API/actuator/health")" '{"status":"UP"}'
check "HTTP redirects to HTTPS" "$("${CURL[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' "http://$API_HOST/actuator/health")" \
  "308 https://$API_HOST/actuator/health"
hsts=$("${CURL[@]}" -D - -o /dev/null "$API/actuator/health" | tr -d '\r' | grep -i '^strict-transport-security:')
[ -n "$hsts" ] && pass "HSTS header present" || fail "HSTS header present"

csp=$("${CURL[@]}" -D - -o /dev/null "$ADMIN/" | tr -d '\r' | grep -i '^content-security-policy:')
[[ "$csp" == *"script-src 'self'"* && "$csp" == *"connect-src 'self' https://$API_HOST wss://$API_HOST"* ]] \
  && pass "admin panel sends a strict Content-Security-Policy" || fail "admin panel sends a strict Content-Security-Policy" "$csp"

admin_page=$("${CURL[@]}" "$ADMIN/")
[[ "$admin_page" == *"<title>AI Chatbot Admin</title>"* ]] && pass "admin panel served" || fail "admin panel served"
check "admin panel routes fall back to the SPA" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$ADMIN/tenants")" 200
bundle=$("${CURL[@]}" "$ADMIN/$(echo "$admin_page" | grep -oE 'assets/index-[^"]+\.js' | head -1)")
[[ "$bundle" == *"https://$API_HOST"* ]] && pass "admin panel built for https://$API_HOST" || fail "admin panel built for https://$API_HOST"
check "widget script served" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$API/widget/chat-widget.js")" 200
check "widget demo page not published" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$API/widget/demo.html")" 404

check "admin API requires login" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$API/tenants/list")" 401
token=$("${CURL[@]}" -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${ADMIN_USERNAME:-admin}\",\"password\":\"$ADMIN_PASSWORD\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$token" ] && pass "admin can log in" || fail "admin can log in"
auth=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')
check "CORS allows the admin panel" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X OPTIONS "$API/tenants/list" \
  -H "Origin: $ADMIN" -H 'Access-Control-Request-Method: GET' -H 'Access-Control-Request-Headers: authorization')" 200
check "CORS rejects other websites" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X OPTIONS "$API/tenants/list" \
  -H 'Origin: https://evil.example' -H 'Access-Control-Request-Method: GET')" 403

tenant=$("${CURL[@]}" -X POST "$API/tenants/create" "${auth[@]}" -d '{"name":"Smoke test"}')
tenant_id=$(echo "$tenant" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ -n "$tenant_id" ] && pass "tenant created" || fail "tenant created" "$tenant"
"${CURL[@]}" -o /dev/null -X POST "$API/faq/create" "${auth[@]}" \
  -d "{\"tenantId\":$tenant_id,\"question\":\"Is this a smoke test?\",\"answer\":\"Yes.\"}"
train=$("${CURL[@]}" -X POST "$API/faq/train/$tenant_id" "${auth[@]}")
[[ "$train" == *"Trained model for tenant $tenant_id"* ]] && pass "backend reaches the AI service" || fail "backend reaches the AI service" "$train"

# Only the web service may be published; the others must not listen on the host.
for port in 5432 8080 8081; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then fail "port $port not published on the host"; else pass "port $port not published on the host"; fi
done

echo
if [ "$failures" -gt 0 ]; then echo "$failures check(s) failed"; exit 1; fi
echo "All checks passed"
