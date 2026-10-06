#!/usr/bin/env bash
# Starts or stops the local development stack: Postgres, AI service, backend, admin panel and widget demo.
# Usage: scripts/run-local.sh up|down|status
#
# - Postgres runs from docker-compose.yml when `docker compose` works, otherwise as a plain container with the same
#   settings (e.g. Colima with the old Python docker-compose, which cannot run `up --wait`).
# - Colima is started when Docker is not reachable and `colima` is installed.
# - ANTHROPIC_API_KEY is taken from the environment, or from ~/.anthropic_key (a file with an
#   `export ANTHROPIC_API_KEY=...` line). Without it the AI service answers by keyword matching.
# - Logs and PIDs go to .run/ (ignored by git). The widget is served on WIDGET_PORT (default 3001).
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUN_DIR="$ROOT/.run"
WIDGET_PORT=${WIDGET_PORT:-3001}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-change-me}
PG_CONTAINER=ai-chatbot-postgres
mkdir -p "$RUN_DIR"

die() { echo "error: $*" >&2; exit 1; }

port_in_use() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

wait_for() { # <description> <seconds> <command...>
  local what=$1 limit=$2; shift 2
  for _ in $(seq 1 "$limit"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  die "timed out waiting for $what (see $RUN_DIR/*.log)"
}

start_docker() {
  docker info >/dev/null 2>&1 && return 0
  command -v colima >/dev/null 2>&1 || die "Docker is not running and colima is not installed"
  echo "Starting Colima (the first start can take several minutes)..."
  colima start >"$RUN_DIR/colima.log" 2>&1 || die "colima start failed, see $RUN_DIR/colima.log"
}

start_postgres() {
  start_docker
  if docker compose version >/dev/null 2>&1; then
    (cd "$ROOT" && docker compose up -d --wait) || die "docker compose up failed"
    return 0
  fi
  if docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
    docker start "$PG_CONTAINER" >/dev/null
  else
    docker run -d --name "$PG_CONTAINER" \
      -e POSTGRES_USER=user -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=main_backend \
      -p 5432:5432 \
      -v "$ROOT/docker/init-databases.sql:/docker-entrypoint-initdb.d/init-databases.sql:ro" \
      -v ai-chatbot-pgdata:/var/lib/postgresql/data postgres:16 >/dev/null || die "could not start Postgres"
  fi
  wait_for "Postgres" 60 docker exec "$PG_CONTAINER" pg_isready -h 127.0.0.1 -U user -d ai_microservice
}

# start <name> <port> <dir> <command...>: runs in the background, records the PID, waits for the port.
start_service() {
  local name=$1 port=$2 dir=$3; shift 3
  port_in_use "$port" && die "port $port is already in use, so $name cannot start (try: scripts/run-local.sh status)"
  (cd "$ROOT/$dir" && exec "$@") >"$RUN_DIR/$name.log" 2>&1 &
  echo $! >"$RUN_DIR/$name.pid"
  echo "Starting $name on :$port..."
  wait_for "$name on :$port" 180 bash -c "lsof -nP -iTCP:$port -sTCP:LISTEN"
}

up() {
  if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -f "$HOME/.anthropic_key" ]; then
    # shellcheck disable=SC1091
    source "$HOME/.anthropic_key"
  fi
  [ -n "${ANTHROPIC_API_KEY:-}" ] || echo "note: ANTHROPIC_API_KEY is not set, so the AI service will use keyword matching"
  export ANTHROPIC_API_KEY SPRING_PROFILES_ACTIVE=dev ADMIN_PASSWORD

  start_postgres
  start_service ai-service 8081 ai-microservice mvn spring-boot:run
  start_service backend 8080 backend mvn spring-boot:run
  [ -d "$ROOT/frontend-admin/node_modules" ] || (cd "$ROOT/frontend-admin" && npm install)
  start_service admin 5173 frontend-admin npm run dev
  start_service widget "$WIDGET_PORT" . npx --yes serve widget -l "$WIDGET_PORT"

  cat <<EOF

Running:
  Admin panel  http://localhost:5173   (admin / $ADMIN_PASSWORD)
  Widget demo  http://localhost:$WIDGET_PORT/demo?widgetKey=<key from the Tenants page>
               (use /demo, not /demo.html: serve's redirect drops the query string)
Stop with: scripts/run-local.sh down
EOF
}

down() {
  local pidfile pid
  for pidfile in "$RUN_DIR"/*.pid; do
    [ -e "$pidfile" ] || continue
    pid=$(cat "$pidfile")
    pkill -TERM -P "$pid" 2>/dev/null; kill "$pid" 2>/dev/null
    rm -f "$pidfile"
  done
  # mvn spring-boot:run forks the app; stop those by main class.
  pkill -f com.demo.ai.AiMicroserviceApplication 2>/dev/null
  pkill -f com.demo.backend.BackendApplication 2>/dev/null
  if docker info >/dev/null 2>&1; then
    if docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
      docker stop "$PG_CONTAINER" >/dev/null
    else
      (cd "$ROOT" && docker compose stop 2>/dev/null)
    fi
  fi
  echo "Stopped the services and Postgres. Colima (if used) is left running: colima stop"
}

status() {
  local p
  for p in 5432 8081 8080 5173 "$WIDGET_PORT"; do
    if port_in_use "$p"; then echo ":$p in use"; else echo ":$p free"; fi
  done
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) status ;;
  *) die "usage: scripts/run-local.sh up|down|status" ;;
esac
