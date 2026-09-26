# AI Chatbot Platform (Java + React + AI Microservice + Widget)

[![CI](https://github.com/terojleinonen/ai-chatbot-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/terojleinonen/ai-chatbot-platform/actions/workflows/ci.yml)

A multi-tenant FAQ chatbot platform:

- `backend/` — Spring Boot main backend (port 8080): tenants, FAQs, STOMP WebSocket chat
- `ai-microservice/` — Spring Boot AI engine (port 8081): per-tenant TF-IDF + cosine-similarity FAQ matching
- `frontend-admin/` — React + Vite + Tailwind admin panel (port 5173)
- `widget/` — Embeddable JavaScript chat widget for customer websites

## Architecture

```
 widget / admin chat ──STOMP──▶ backend :8080 ──REST + X-API-KEY──▶ ai-microservice :8081
 admin panel ─────────REST────▶    │                                     │
                                   ▼                                     ▼
                         Postgres main_backend                 Postgres ai_microservice
```

- The **backend** owns tenants and FAQs. Every FAQ create/update/delete/import pushes the tenant's
  full FAQ list to the AI service (`POST /ai/train/{tenantId}`), which replaces its copy and rebuilds
  that tenant's model. If the AI service is down the change is still saved; use **Retrain AI** later.
- The **AI microservice** keeps one in-memory `TenantModel` per tenant (loaded from its DB on startup).
  A question is answered with the FAQ whose question has the highest TF-IDF cosine similarity,
  if the score is above 0.2; otherwise it asks the user to rephrase.
- **Chat**: clients publish `{sessionId, widgetKey, content}` to `/app/chat.send` and subscribe to
  `/topic/replies/{sessionId}`. See "Public chat protection" below.
- **Schema**: both services manage their tables with Flyway migrations (`src/main/resources/db/migration`);
  Hibernate only validates. Databases created before Flyway are adopted automatically (baselined at version 1).

## Prerequisites

Java 17+, Maven, Node 18+, Docker (or your own PostgreSQL).

## Running locally

Both services need `SPRING_PROFILES_ACTIVE=dev` locally: it supplies development defaults for the database,
the AI key and the JWT secret. Without it they refuse to start until every production setting is provided.

```bash
# 1. Databases (main_backend + ai_microservice, user/pass)
docker compose up -d --wait

# 2. AI microservice
cd ai-microservice && SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

# 3. Backend (the first start creates the admin user; see "Authentication")
cd backend && SPRING_PROFILES_ACTIVE=dev ADMIN_PASSWORD=change-me mvn spring-boot:run

# 4. Admin panel → http://localhost:5173 (log in as admin / change-me)
cd frontend-admin && npm install && npm run dev

# 5. Widget demo → open http://localhost:3000/demo.html
npx serve widget
```

Then in the admin panel: create a tenant, add FAQs (or import a CSV with `question,answer` headers),
and test on the **Chat** page or with the widget: copy the tenant's widget key from the **Tenants** page and open
`http://localhost:3000/demo.html?widgetKey=<key>`.

## Tests and CI

```bash
cd backend && mvn verify          # unit + integration tests (in-memory H2, AI client mocked)
cd ai-microservice && mvn verify  # FAQ matcher tests
cd frontend-admin && npm ci && npm run build
node --check widget/chat-widget.js
```

### Browser end-to-end tests (`e2e/`)

Playwright tests drive the admin panel and the widget in Chromium against the real stack: Postgres, both
services, the built admin panel and the widget demo page. They cover login and sessions, tenants and FAQs, chat
over WebSocket (including session isolation), the embeddable widget, user management and revocation, per-tenant
permissions, and login rate limiting. Each test creates its own tenants and users.

```bash
docker compose up -d --wait                  # fresh database: the admin gets E2E_ADMIN_PASSWORD
(cd ai-microservice && mvn -DskipTests package)
(cd backend && mvn -DskipTests package)
(cd frontend-admin && npm ci && npm run build)
cd e2e && npm ci && npx playwright install chromium
CI=1 npx playwright test                     # starts both services, vite preview and the widget page itself
```

Without `CI`, Playwright reuses servers you already have running on ports 8080, 8081, 5173 and 3000. The backend
must then run with `SPRING_PROFILES_ACTIVE=dev`, `ADMIN_PASSWORD=e2e-admin-password` (or set `E2E_ADMIN_PASSWORD`
to your admin password), `LOGIN_RATE_LIMIT_WINDOW=10s` and `CHAT_MAX_MESSAGES_PER_IP=1000`, and the AI service with
`SPRING_PROFILES_ACTIVE=dev`. The widget test serves SockJS/STOMP from `e2e/node_modules` instead of jsDelivr.

### CI

GitHub Actions (`.github/workflows/ci.yml`) runs on every pull request and on pushes to `main`, with Java 17 and
Node 22: the Maven tests of both services, the admin panel build, the widget syntax check, the browser
end-to-end tests (Postgres via `docker compose`; report and traces uploaded when they fail), and the production
stack: it builds the Docker images, starts `docker-compose.prod.yml` with `*.localhost` hosts and runs
`deploy/smoke-test.sh` over HTTPS.

## Authentication

The backend uses Spring Security with stateless JWT bearer tokens:

- `POST /auth/login` with `{"username", "password"}` returns `{token, expiresAt, username}`.
  Send it as `Authorization: Bearer <token>` on every admin API call. Tokens are HS256-signed and
  expire after `JWT_TTL` (default 8h).
- Admin users live in the `admin_users` table with BCrypt-hashed passwords. On first start, when the table
  is empty, the backend creates `ADMIN_USERNAME` (default `admin`) with `ADMIN_PASSWORD`. If no password
  is set, it generates one and prints it once in the log (`Created admin user 'admin' with generated password: ...`).
  Changing `ADMIN_PASSWORD` later does not change an existing user's password; use the **Users** page.
- **Roles and tenant permissions:**
  - **Super admin** – manages users and creates tenants; can access every tenant. The bootstrap admin, and any
    admin that existed before roles were introduced, is a super admin.
  - **Tenant admin** – can only list and manage the tenants assigned to them (their FAQs, import/export,
    retraining, chat test). Cannot create tenants or manage users.

  Every tenant and FAQ endpoint checks access (403 otherwise). Roles and assignments are read from the database
  on each request, so changes apply immediately without signing in again. The chat WebSocket stays public.
- **Users page** (super admins): list admins with role and tenants, add users with a role and tenant
  assignments, edit another user's access, reset their password, or delete them. **My account** (everyone):
  change your own password (requires the current one; wrong guesses count toward the login rate limit). Usernames are
  case-insensitive, 3-50 characters (`a-z 0-9 . _ -`); passwords need at least 12 characters (max 72 bytes,
  the BCrypt limit). You cannot delete your own account or change your own access, so at least one super admin
  always remains.
- **Revocation:** each user has a token version that is embedded in their JWTs and checked on every request.
  Deleting a user, resetting their password, or changing your own password signs out their existing sessions
  immediately (when you change your own, your current session gets a fresh token).
- `/tenants/**`, `/faq/**`, `/users/**` and `/auth/me` require a token. The chat WebSocket endpoints (`/ws`, `/ws-chat`)
  are public, because website visitors use them through the widget.
- CORS for the REST API is limited to `CORS_ALLOWED_ORIGINS` (comma-separated, default the admin panel's
  `http://localhost:5173`).
- Failed logins are rate limited: 5 per client IP + username and 20 per client IP (any username) within a
  sliding 15-minute window. Further attempts get `429 Too Many Requests` with a `Retry-After` header, and the
  password is not checked while blocked. A successful login clears that user's counter for the IP. Usernames
  are not locked for every IP, so an attacker cannot lock the real admin out from elsewhere. Counters live in
  memory (per backend instance, reset on restart). Behind a reverse proxy, set
  `server.forward-headers-strategy=native` (or `framework`) so the limiter sees the real client IP instead of
  the proxy's.
- Set `JWT_SECRET` (32+ characters) anywhere beyond local development. Without it the backend signs with a
  random key, so every restart logs everyone out.

The admin panel stores the token in `localStorage`, attaches it to every request, and returns to the login page
when the token expires or the backend rejects it.

## Public chat protection

The chat endpoint is public (website visitors use it through the widget), so every message passes these checks
before the AI service is called:

- **Widget key:** each tenant has a random 32-character widget key (shown on the **Tenants** page) that the widget
  sends instead of the sequential tenant id. **Rotate key** issues a new one; widgets using the old key stop working.
- **Allowed websites:** a tenant can list the websites (origins, e.g. `https://www.example.com`) that may use its
  chat; the browser's `Origin` from the WebSocket handshake is checked against it. An empty list allows any
  website. The admin panel's own origin can always use the test chat.
- **Rate limit:** at most `CHAT_MAX_MESSAGES_PER_IP` messages (default 20) per client IP per
  `CHAT_RATE_LIMIT_WINDOW` (default 1 minute), in memory per backend instance.
- **Size limit:** messages longer than `CHAT_MAX_MESSAGE_LENGTH` (default 1000) characters are refused.

Visitors get a short explanation instead of an answer when a check fails.

## Configuration

Production settings have **no defaults for credentials**: each service refuses to start, listing the missing
environment variables, until they are set. `AI_API_KEY` and `JWT_SECRET` must be at least 32 characters, and the
public development values from the `dev` profile are rejected outside it. Generate secrets with
`openssl rand -hex 32`.

| Service | Variable | Required | Default (`dev` profile default) |
|---|---|---|---|
| backend, ai-microservice | `DB_URL`, `DB_USER`, `DB_PASSWORD` | yes | (local Postgres, `user` / `pass`) |
| backend, ai-microservice | `AI_API_KEY` (same value in both) | yes | (a public dev key) |
| backend, ai-microservice | `PORT` | | `8080` / `8081` |
| backend, ai-microservice | `SPRING_PROFILES_ACTIVE` | | `dev` for local development only |
| backend | `JWT_SECRET` | yes | (empty: random key per start) |
| backend | `CORS_ALLOWED_ORIGINS` (admin panel origin) | yes | (`http://localhost:5173`) |
| backend | `AI_BASE_URL` | | `http://localhost:8081` |
| backend | `JWT_TTL` | | `8h` |
| backend | `ADMIN_USERNAME` / `ADMIN_PASSWORD` | | `admin` / generated and logged |
| backend | `LOGIN_MAX_FAILURES_PER_USER_AND_IP` / `LOGIN_MAX_FAILURES_PER_IP` | | `5` / `20` |
| backend | `LOGIN_RATE_LIMIT_WINDOW` | | `15m` |
| backend | `CHAT_MAX_MESSAGES_PER_IP` / `CHAT_RATE_LIMIT_WINDOW` | | `20` / `1m` |
| backend | `CHAT_MAX_MESSAGE_LENGTH` | | `1000` |
| backend | `SERVER_FORWARD_HEADERS_STRATEGY` | behind a proxy | `native` in the production stack |
| backend, ai-microservice | `MANAGEMENT_PORT` (health + metrics, private) | | `9090` / `9091` |
| backend, ai-microservice | `LOG_FORMAT` | | `plain` (`json` in the production stack) |
| frontend-admin | `VITE_API_BASE` / `VITE_WS_URL` / `VITE_WIDGET_URL` | | `http://localhost:8080` / `ws://…/ws` / `…/widget/chat-widget.js` |

See `frontend-admin/.env.example`.

## Deploying to production

`docker-compose.prod.yml` runs the whole platform on one server behind [Caddy](https://caddyserver.com), which
terminates HTTPS with automatically issued and renewed Let's Encrypt certificates:

```
 internet ──443/80──▶ web (Caddy) ──▶ admin panel (static)     https://ADMIN_HOST
                         │        ──▶ widget files (static)    https://API_HOST/widget/chat-widget.js
                         └──────────▶ backend :8080            https://API_HOST  (REST, wss://, SockJS)
                private network:      backend ──▶ ai :8081 ──▶ postgres (both databases) ◀── backup (→ ./backups)
```

Only Caddy publishes ports; Postgres, the backend and the AI service are reachable only on the private Docker
network. Caddy redirects HTTP to HTTPS and sends HSTS and other security headers, including a strict
Content-Security-Policy for the admin panel (only its own scripts and styles; API calls only to `API_HOST`), which
limits what an injected script could do with the login token the admin panel keeps in `localStorage`. The admin panel and the API use
separate hostnames because their paths overlap.

1. Point DNS for both hostnames (e.g. `admin.example.com`, `api.example.com`) at the server and open ports 80 and
   443.
2. `cp deploy/production.env.example .env.production` and fill it in (secrets: `openssl rand -hex 32`).
   `.env.production` is git-ignored; keep it out of version control.
3. `docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build`
4. Check: `deploy/smoke-test.sh .env.production` (health, HTTPS redirect, HSTS, admin panel, widget, login,
   CORS, AI connectivity and that no internal ports are exposed), then log in at `https://ADMIN_HOST`.

### Monitoring

- **Health:** `https://API_HOST/actuator/health` (public, no details, includes the database) for load balancers
  and uptime monitors.
- **Metrics:** both services expose Prometheus metrics on a private management port (backend `9090`, AI service
  `9091`, set with `MANAGEMENT_PORT`) that is never published; Caddy returns 404 for `/actuator/*` other than
  health. Start the bundled Prometheus with `--profile monitoring`; its UI listens on the server's localhost only
  (`ssh -L 9090:localhost:9090 <server>`, then http://localhost:9090). Besides JVM, HTTP, database-pool and
  Flyway metrics there are:

  | Metric | Labels | Meaning |
  |---|---|---|
  | `chat_messages_total` | `outcome` = answered, rate_limited, empty, too_long, unknown_widget, origin_not_allowed | public chat messages |
  | `auth_logins_total` | `outcome` = success, failure, rate_limited | admin logins |
  | `ai_requests_seconds` | `operation` = reply, train; `outcome` = success, error | backend → AI service calls (latency histogram) |
  | `ai_replies_total` | `result` = answered, no_match, no_data | how often the AI found a matching FAQ |
  | `ai_tenant_models` | | tenant models loaded in the AI service |

- **Logs:** `LOG_FORMAT=json` (set in the production stack) writes one JSON object per line (`@timestamp`,
  `level`, `service`, `logger_name`, `message`, ...) for log collectors; the default is plain text.

### Backups

The `backup` service dumps both databases when it starts and then every `BACKUP_INTERVAL_HOURS` (default 24) into
`./backups/<UTC timestamp>/` on the host (`main_backend.dump`, `ai_microservice.dump`, PostgreSQL custom format),
and deletes backups older than `BACKUP_KEEP_DAYS` (default 14). A backup only gets its timestamp name once both
dumps are complete. Copy `./backups` off the server as well (e.g. a nightly `rsync` or object-storage sync), since
a backup on the same disk does not survive losing the server.

- Back up now: `docker compose -f docker-compose.prod.yml --env-file .env.production run --rm --no-deps backup now`
- Restore: `deploy/restore.sh .env.production <timestamp>` asks for confirmation, stops the backend and AI service,
  restores both databases (each in a single transaction), and starts them again; the AI service reloads its models
  from the restored data. Admin logins and chat are unavailable for the few seconds this takes.

Upgrades: `git pull` and repeat step 3; Flyway applies new migrations on startup. Keep the `pgdata` volume
(your data) and `caddy_data` (certificates) between deployments.
The `production-stack` CI job builds the images and runs the smoke test on every pull request.

## Embedding the widget

The **Tenants** page shows the exact embed code for each tenant (with **Copy embed code**):

```html
<script src="https://api.example.com/widget/chat-widget.js"></script>
<script>
  ChatWidget.init({ backendUrl: "https://api.example.com", widgetKey: "<the tenant's widget key>", title: "Support" });
</script>
```

`chat-widget.css` is loaded from the same folder as the script (override with `cssUrl`). Add the website to the
tenant's **Allowed websites** to stop other sites from using the tenant's chat.

## API reference

Backend (`:8080`)

`/tenants/**`, `/faq/**` and `/users/**` require `Authorization: Bearer <token>`. Errors return `{"message": ...}`.
**SA** = super admins only; tenant and FAQ endpoints require access to the tenant involved.
**Paged** lists take `?page=` (zero-based), `?size=` (default 50, max 200) and `?q=` (search), are sorted newest
first, and return `{items, page, size, total, totalPages}`. Input limits: FAQ question 1000 and answer 3000
characters, imports up to 5000 rows, tenant names 255 characters (400 with a message otherwise).

| Method | Path | Description |
|---|---|---|
| POST | `/auth/login` | `{username, password}` → `{token, expiresAt, username}` (public; 401 bad credentials, 429 rate limited) |
| GET | `/auth/me` | current user `{id, username, createdAt, role, tenantIds}` |
| GET | `/users` | **SA** **paged** admin users `{id, username, createdAt, role, tenantIds}`, `q` matches username |
| POST | `/users` | **SA** `{username, password, role, tenantIds}` → create user |
| PUT | `/users/{id}/access` | **SA** `{role, tenantIds}` — change another user's access (applies immediately) |
| PUT | `/users/{id}/password` | **SA** `{password}` — reset another user's password (signs them out) |
| PUT | `/users/me/password` | `{currentPassword, newPassword}` → fresh `{token, expiresAt, username}` |
| DELETE | `/users/{id}` | **SA** delete another user (signs them out) |
| POST | `/tenants/create` | **SA** `{name}` → tenant (with generated `widgetKey`) |
| GET | `/tenants/list` | **paged** tenants the user can access (all for super admins), incl. `widgetKey`, `allowedOrigins`; `q` matches name |
| GET | `/tenants/options` | every accessible tenant as `[{id, name, widgetKey}]`, sorted by name (for pickers) |
| PUT | `/tenants/{id}/settings` | `{allowedOrigins: ["https://…"]}` — websites allowed to use the chat (empty = any) |
| POST | `/tenants/{id}/widget-key` | rotate the widget key (old key stops working) |
| POST | `/faq/create` | `{tenantId, question, answer}` |
| GET | `/faq/list/{tenantId}` | **paged** tenant's FAQs; `q` matches question or answer |
| GET | `/faq/export/{tenantId}` | all of the tenant's FAQs (CSV export) |
| PUT | `/faq/{id}` | `{question, answer}` |
| DELETE | `/faq/{id}` | delete FAQ |
| POST | `/faq/import/{tenantId}` | `[{question, answer}]` — replaces all FAQs of the tenant |
| POST | `/faq/train/{tenantId}` | push current FAQs to the AI service |
| WS | `/ws` (native), `/ws-chat` (SockJS) | STOMP chat endpoints (public; see "Public chat protection") |
| GET | `/actuator/health` | health check incl. database (management port; public via Caddy, no details) |
| GET | `/actuator/prometheus` | Prometheus metrics (management port only, never public) |

AI microservice (`:8081`, `/ai/**` requires `X-API-KEY`)

| Method | Path | Description |
|---|---|---|
| POST | `/ai/reply` | `{tenantId, message}` → `{reply}` |
| POST | `/ai/train/{tenantId}` | `[{question, answer}]` — replace and retrain |
| GET | `/health` | health check (no key) |

## Limitations (template scope)

- Two fixed roles; there are no finer-grained permissions (e.g. read-only access).
- Rate limits (login and chat) and the AI models are held in memory per instance: the production stack runs one
  instance of each service; scaling out needs a shared store (e.g. Redis) or limits at the load balancer.
- The `Origin` check stops other websites from embedding a tenant's chat in browsers, but a non-browser client
  can send any `Origin`; the per-IP rate limit is what bounds such traffic.
- Answers are retrieval-only (best-matching FAQ), no generative model.
