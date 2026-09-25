# AI Chatbot Platform (Java + React + AI Microservice + Widget)

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

# 3. Backend
cd backend && mvn spring-boot:run

# 4. Admin panel → http://localhost:5173 (password: demo123)
cd frontend-admin && npm install && npm run dev

# 5. Widget demo → open http://localhost:3000/demo.html
npx serve widget
```

Then in the admin panel: create a tenant, add FAQs (or import a CSV with `question,answer` headers),
and test on the **Chat** page or with the widget (set `tenantId` in `widget/demo.html`).

## Configuration

| Service | Variable | Default |
|---|---|---|
| backend, ai-microservice | `DB_URL`, `DB_USER`, `DB_PASSWORD` | local Postgres, `user` / `pass` |
| backend, ai-microservice | `PORT` | `8080` / `8081` |
| backend, ai-microservice | `AI_API_KEY` (must match) | `MY_INTERNAL_AI_KEY` |
| backend | `AI_BASE_URL` | `http://localhost:8081` |
| frontend-admin | `VITE_ADMIN_PASSWORD` | `demo123` |
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

| Method | Path | Description |
|---|---|---|
| POST | `/tenants/create` | `{name}` → tenant |
| GET | `/tenants/list` | all tenants |
| POST | `/faq/create` | `{tenantId, question, answer}` |
| GET | `/faq/list/{tenantId}` | tenant's FAQs |
| PUT | `/faq/{id}` | `{question, answer}` |
| DELETE | `/faq/{id}` | delete FAQ |
| POST | `/faq/import/{tenantId}` | `[{question, answer}]` — replaces all FAQs of the tenant |
| POST | `/faq/train/{tenantId}` | push current FAQs to the AI service |
| WS | `/ws` (native), `/ws-chat` (SockJS) | STOMP endpoints |

AI microservice (`:8081`, `/ai/**` requires `X-API-KEY`)

| Method | Path | Description |
|---|---|---|
| POST | `/ai/reply` | `{tenantId, message}` → `{reply}` |
| POST | `/ai/train/{tenantId}` | `[{question, answer}]` — replace and retrain |
| GET | `/health` | health check (no key) |

## Limitations (template scope)

- Admin login is a client-side password check only; the backend REST API is unauthenticated.
  Add real auth (e.g. Spring Security + JWT) before production.
- Answers are retrieval-only (best-matching FAQ), no generative model.
- The AI models are held in memory per instance; run a single AI instance or add a shared cache.
