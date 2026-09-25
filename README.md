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
- **Chat**: clients publish `{sessionId, tenantId, content}` to `/app/chat.send` and subscribe to
  `/topic/replies/{sessionId}`.

## Prerequisites

Java 17+, Maven, Node 18+, Docker (or your own PostgreSQL).

## Running locally

```bash
# 1. Databases (main_backend + ai_microservice, user/pass)
docker compose up -d

# 2. AI microservice
cd ai-microservice && mvn spring-boot:run

# 3. Backend (the first start creates the admin user; see "Authentication")
cd backend && ADMIN_PASSWORD=change-me mvn spring-boot:run

# 4. Admin panel → http://localhost:5173 (log in as admin / change-me)
cd frontend-admin && npm install && npm run dev

# 5. Widget demo → open http://localhost:3000/demo.html
npx serve widget
```

Then in the admin panel: create a tenant, add FAQs (or import a CSV with `question,answer` headers),
and test on the **Chat** page or with the widget (set `tenantId` in `widget/demo.html`).

## Tests and CI

```bash
cd backend && mvn verify          # unit + integration tests (in-memory H2, AI client mocked)
cd ai-microservice && mvn verify  # FAQ matcher tests
cd frontend-admin && npm ci && npm run build
node --check widget/chat-widget.js
```

GitHub Actions (`.github/workflows/ci.yml`) runs the same checks on every pull request and on pushes to `main`,
with Java 17 and Node 22. None of them need Postgres or a running AI service.

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

## Configuration

| Service | Variable | Default |
|---|---|---|
| backend, ai-microservice | `DB_URL`, `DB_USER`, `DB_PASSWORD` | local Postgres, `user` / `pass` |
| backend, ai-microservice | `PORT` | `8080` / `8081` |
| backend, ai-microservice | `AI_API_KEY` (must match) | `MY_INTERNAL_AI_KEY` |
| backend | `AI_BASE_URL` | `http://localhost:8081` |
| backend | `JWT_SECRET` (32+ chars) | random per start |
| backend | `JWT_TTL` | `8h` |
| backend | `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / generated and logged |
| backend | `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` |
| backend | `LOGIN_MAX_FAILURES_PER_USER_AND_IP` / `LOGIN_MAX_FAILURES_PER_IP` | `5` / `20` |
| backend | `LOGIN_RATE_LIMIT_WINDOW` | `15m` |
| frontend-admin | `VITE_API_BASE` / `VITE_WS_URL` | `http://localhost:8080` / `ws://localhost:8080/ws` |

See `frontend-admin/.env.example`.

## Embedding the widget

```html
<script src="https://your-cdn/chat-widget.js"></script>
<script>
  ChatWidget.init({ backendUrl: "https://api.example.com", tenantId: 1, title: "Support" });
</script>
```

`chat-widget.css` is loaded from the same folder as the script (override with `cssUrl`).

## API reference

Backend (`:8080`)

`/tenants/**`, `/faq/**` and `/users/**` require `Authorization: Bearer <token>`. Errors return `{"message": ...}`.
**SA** = super admins only; tenant and FAQ endpoints require access to the tenant involved.

| Method | Path | Description |
|---|---|---|
| POST | `/auth/login` | `{username, password}` → `{token, expiresAt, username}` (public; 401 bad credentials, 429 rate limited) |
| GET | `/auth/me` | current user `{id, username, createdAt, role, tenantIds}` |
| GET | `/users` | **SA** admin users `[{id, username, createdAt, role, tenantIds}]` |
| POST | `/users` | **SA** `{username, password, role, tenantIds}` → create user |
| PUT | `/users/{id}/access` | **SA** `{role, tenantIds}` — change another user's access (applies immediately) |
| PUT | `/users/{id}/password` | **SA** `{password}` — reset another user's password (signs them out) |
| PUT | `/users/me/password` | `{currentPassword, newPassword}` → fresh `{token, expiresAt, username}` |
| DELETE | `/users/{id}` | **SA** delete another user (signs them out) |
| POST | `/tenants/create` | **SA** `{name}` → tenant |
| GET | `/tenants/list` | tenants the user can access (all for super admins) |
| POST | `/faq/create` | `{tenantId, question, answer}` |
| GET | `/faq/list/{tenantId}` | tenant's FAQs |
| PUT | `/faq/{id}` | `{question, answer}` |
| DELETE | `/faq/{id}` | delete FAQ |
| POST | `/faq/import/{tenantId}` | `[{question, answer}]` — replaces all FAQs of the tenant |
| POST | `/faq/train/{tenantId}` | push current FAQs to the AI service |
| WS | `/ws` (native), `/ws-chat` (SockJS) | STOMP chat endpoints (public) |

AI microservice (`:8081`, `/ai/**` requires `X-API-KEY`)

| Method | Path | Description |
|---|---|---|
| POST | `/ai/reply` | `{tenantId, message}` → `{reply}` |
| POST | `/ai/train/{tenantId}` | `[{question, answer}]` — replace and retrain |
| GET | `/health` | health check (no key) |

## Limitations (template scope)

- Two fixed roles; there are no finer-grained permissions (e.g. read-only access).
- Login rate limiting is in memory: with several backend instances each counts separately, so use a shared
  store (e.g. Redis) or limit at the load balancer when scaling out.
- The public chat endpoint does not verify that a widget's `tenantId` belongs to the embedding site.
- Answers are retrieval-only (best-matching FAQ), no generative model.
- The AI models are held in memory per instance; run a single AI instance or add a shared cache.
