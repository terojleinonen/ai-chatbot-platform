# AI Chatbot Platform (Java + React + AI Microservice + Widget)

[![CI](https://github.com/terojleinonen/ai-chatbot-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/terojleinonen/ai-chatbot-platform/actions/workflows/ci.yml)

A multi-tenant FAQ chatbot platform:

- `backend/` — Spring Boot main backend (port 8080): tenants, FAQs, STOMP WebSocket chat
- `ai-microservice/` — Spring Boot AI engine (port 8081): answers questions with Claude from each tenant's FAQs
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
- The **AI microservice** answers each question with Claude, grounded in the tenant's FAQs (see "Answers from
  Claude" below). It keeps each tenant's FAQs in memory (loaded from its DB on startup), indexed with TF-IDF for
  picking relevant FAQs and for the keyword-matching fallback.
- **Chat**: clients publish `{sessionId, widgetKey, content}` to `/app/chat.send` and subscribe to
  `/topic/replies/{sessionId}`. Each reply streams there as `{sessionId, replyId, delta}` messages (text to append
  as Claude writes it), then `{sessionId, replyId, reply, done: true}` with the complete text, which replaces the
  deltas; replies that are not streamed (keyword matching, errors, rejected messages) are just the final message.
  See "Public chat protection" below.
- **Schema**: both services manage their tables with Flyway migrations (`src/main/resources/db/migration`);
  Hibernate only validates. Databases created before Flyway are adopted automatically (baselined at version 1).

## Answers from Claude

The AI service sends the tenant's FAQs to Claude as the system prompt, with instructions to answer only from
them, and the customer's message as the user turn. Claude replies in the customer's language; when the FAQs don't
cover the question it says so ("I'm not sure yet...") instead of guessing.

- **Setup:** set `ANTHROPIC_API_KEY` for the AI service (a key from https://console.anthropic.com). Without it the
  service logs a warning and falls back to **keyword matching**: the FAQ whose question has the highest TF-IDF
  cosine similarity (above 0.2) is returned verbatim. Tests, CI and local development work without a key.
- **Failures:** if a Claude call fails (network, rate limit, overload, no data for 20 seconds, or the whole reply
  taking longer than `AI_LLM_TIMEOUT`, after `AI_LLM_MAX_RETRIES` retries) or the reply can't be shown (refused,
  cut off at `AI_LLM_MAX_TOKENS`), that question is answered by keyword matching, so the chat keeps working. `ai_replies_total{source="keyword"}` rising
  while a key is set means Claude calls are failing; the AI service logs why.
- **Which FAQs are sent:** all of a tenant's FAQs when they total at most `AI_LLM_MAX_CONTEXT_CHARS` characters
  (default 24000, roughly 6k tokens). That prompt is identical for every question to the tenant until its FAQs
  change, so it is prompt-cached (for 5 minutes after each use; `claude-opus-5` caches prompts from 512 tokens):
  questions within that window read it from the cache at a tenth of the input price. Larger
  FAQ sets send the most relevant FAQs (by TF-IDF similarity to the question) that fit, which varies per question
  and is not cached.
- **Streaming:** replies appear in the widget and the admin panel's test chat word by word as Claude writes them
  (Claude → AI service `/ai/reply/stream` → backend → STOMP). Claude's "not covered" marker is never streamed. If
  Claude fails part-way through a reply, the final message replaces the partial text with the keyword-matching
  answer.
- **Conversation history:** the backend remembers each chat's last `CHAT_HISTORY_MAX_EXCHANGES` (default 10)
  question/answer exchanges and sends them with every new question, so Claude understands follow-ups ("and on
  weekends?"); the relevant-FAQ selection for large FAQ sets also looks at the two previous questions. A chat is
  forgotten `CHAT_HISTORY_TTL` (default 30 minutes) after its last message, and a new one starts with every page
  load. History lives where the rate limits do (`RATE_LIMIT_STORE`): in memory, or in Redis so every backend
  instance sees it. Set `CHAT_HISTORY_MAX_EXCHANGES=0` to answer each message on its own. Keyword matching ignores
  history.
- **Model and cost:** `AI_LLM_MODEL` defaults to `claude-opus-5` ($5 / $25 per million input / output tokens) at
  `AI_LLM_EFFORT=low`, which suits short FAQ answers. A question to a tenant with 6k tokens of FAQs costs about
  $0.03 uncached, or well under a cent when the FAQs are cached, plus the answer (~100-300 output tokens).
  `claude-sonnet-5` ($2 / $10) or `claude-haiku-4-5` ($1 / $5, set `AI_LLM_EFFORT=` empty, as Haiku does not
  support effort) are cheaper options. Token usage is in the `ai_llm_tokens_total` metric. History adds the
  earlier exchanges to each question's input (a few hundred tokens for a typical chat, uncached).
- **Privacy:** customer messages, the chat's recent history and the tenant's FAQs are sent to the Anthropic API;
  the history is kept (in memory or Redis) only until the chat expires.

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
  are not locked for every IP, so an attacker cannot lock the real admin out from elsewhere. Counters are shared by all backend instances when
  `RATE_LIMIT_STORE=redis` (as in the production stack), otherwise kept in memory per instance. Behind a reverse proxy, set
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
  `CHAT_RATE_LIMIT_WINDOW` (default 1 minute), shared by all backend instances when `RATE_LIMIT_STORE=redis`.
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
| backend | `CHAT_HISTORY_MAX_EXCHANGES` / `CHAT_HISTORY_TTL` | | `10` / `30m` |
| backend | `RATE_LIMIT_STORE` / `REDIS_URL` | `REDIS_URL` if `redis` | `memory` (`redis` + `redis://redis:6379` in the production stack) |
| backend | `SERVER_FORWARD_HEADERS_STRATEGY` | behind a proxy | `native` in the production stack |
| backend, ai-microservice | `MANAGEMENT_PORT` (health + metrics, private) | | `9090` / `9091` |
| ai-microservice | `ANTHROPIC_API_KEY` | for Claude answers | (empty: keyword matching) |
| ai-microservice | `AI_LLM_MODEL` / `AI_LLM_EFFORT` | | `claude-opus-5` / `low` |
| ai-microservice | `AI_LLM_MAX_TOKENS` / `AI_LLM_MAX_CONTEXT_CHARS` | | `2048` / `24000` |
| ai-microservice | `AI_LLM_TIMEOUT` (whole reply) / `AI_LLM_MAX_RETRIES` | | `60s` / `1` |
| ai-microservice | `ANTHROPIC_BASE_URL` | | Anthropic API |
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
                                                                   ./backups ──▶ offsite (encrypted) ──▶ S3 bucket
                                      backend ──▶ redis (shared rate-limit counters)
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
  | `ai_replies_total` | `result` = answered, no_match, no_data; `source` = llm, keyword, none | how often the AI could answer, and whether Claude or the keyword fallback did |
  | `ai_llm_requests_seconds` | `model`; `outcome` = answered, no_answer, refusal, truncated, empty, error | Claude API calls (latency histogram) |
  | `ai_llm_tokens_total` | `model`; `type` = input, output, cache_read, cache_write | Claude token usage (drives cost) |
  | `ai_tenant_models` | | tenant models loaded in the AI service |

- **Logs:** `LOG_FORMAT=json` (set in the production stack) writes one JSON object per line (`@timestamp`,
  `level`, `service`, `logger_name`, `message`, ...) for log collectors; the default is plain text.

### Running several instances

The backend and AI service can each run more than one instance:
`docker compose -f docker-compose.prod.yml --env-file .env.production up -d --scale backend=2 --scale ai=2`.

- **Load balancing:** Caddy finds every backend instance via DNS and keeps each client IP on one instance (SockJS
  fallback transports need all requests of a chat session on the same instance, and cookies are unreliable for a
  widget embedded on other sites). If an instance stops, its clients move to another within seconds.
- **Rate limits and chat history:** the production stack keeps login and chat counters, and chat history, in Redis
  (`RATE_LIMIT_STORE=redis`), so all instances enforce one shared limit and continue each other's chats. Without
  Redis (`RATE_LIMIT_STORE=memory`, the default outside the production stack) each instance counts separately and
  a chat that moves to another instance loses its history.
- **AI models:** each retrain bumps the tenant's model version in the database; every AI instance checks it before
  answering and reloads a tenant's model that another instance retrained.
- **Metrics:** Prometheus discovers all instances via DNS, so each shows up as its own target.

### Backups

The `backup` service dumps both databases when it starts and then every `BACKUP_INTERVAL_HOURS` (default 24) into
`./backups/<UTC timestamp>/` on the host (`main_backend.dump`, `ai_microservice.dump`, PostgreSQL custom format),
and deletes backups older than `BACKUP_KEEP_DAYS` (default 14). A backup only gets its timestamp name once both
dumps are complete. A backup on the same disk does not survive losing the server, so also enable off-site copies.

- Back up now: `docker compose -f docker-compose.prod.yml --env-file .env.production run --rm --no-deps backup now`
- Restore: `deploy/restore.sh .env.production <timestamp>` asks for confirmation, stops the backend and AI service,
  restores both databases (each in a single transaction), and starts them again; the AI service reloads its models
  from the restored data. Admin logins and chat are unavailable for the few seconds this takes.

### Off-site copies

The optional `offsite` service copies every completed backup to S3-compatible object storage (AWS S3, Backblaze B2,
Cloudflare R2, Wasabi, Hetzner, MinIO, ...) with [rclone](https://rclone.org):

- **Encrypted before upload** (rclone crypt: file contents and names) with `OFFSITE_ENCRYPTION_PASSWORD`; the
  service refuses to run without it, so the storage provider only ever holds ciphertext. Keep the password
  somewhere other than the server: without it the copies cannot be decrypted.
- **Verified:** after each upload every file is checked against the local copy (`rclone cryptcheck`).
- Runs every `OFFSITE_INTERVAL_MINUTES` (default 60) and keeps `OFFSITE_KEEP_DAYS` (default 30, longer than the
  local 14) in the bucket; `0` never deletes anything, for buckets with object lock or lifecycle rules, which also
  protects the copies if the server itself is compromised.
- **Monitoring:** the service turns `unhealthy` in `docker compose ps` when its last successful sync is older than
  three intervals; sync errors are logged and retried.

Set up: create a bucket and an access key limited to it, fill in the `OFFSITE_*` settings in `.env.production`,
then `docker compose -f docker-compose.prod.yml --env-file .env.production --profile offsite up -d`.

Disaster recovery (works on a new server with this repository, the env file and Docker):

```bash
deploy/offsite-restore.sh .env.production              # list off-site backups
deploy/offsite-restore.sh .env.production <timestamp>  # download and decrypt into ./backups/<timestamp>
deploy/restore.sh .env.production <timestamp>          # restore it
```

The CI `production-stack` job runs `deploy/test/offsite-roundtrip.sh` against a throwaway S3 server: encrypted
upload, names and contents unreadable in the bucket, download identical to the original, wrong password rejected.

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
| POST | `/ai/reply` | `{tenantId, message, history?: [{question, answer}]}` → `{reply}` (history: earlier exchanges, oldest first, at most 20) |
| POST | `/ai/reply/stream` | same request → newline-delimited JSON: `{delta}` lines as Claude writes, then `{reply, done: true}` (the complete reply, which replaces the deltas) |
| POST | `/ai/train/{tenantId}` | `[{question, answer}]` — replace and retrain |
| GET | `/health` | health check (no key) |

## Limitations (template scope)

- Two fixed roles; there are no finer-grained permissions (e.g. read-only access).
- The `Origin` check stops other websites from embedding a tenant's chat in browsers, but a non-browser client
  can send any `Origin`; the per-IP rate limit is what bounds such traffic.
