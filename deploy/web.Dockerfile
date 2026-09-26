# syntax=docker/dockerfile:1
# Public entry point: Caddy terminates HTTPS, serves the admin panel and the widget, and proxies the API.
# Build from the repository root: docker build -f deploy/web.Dockerfile --build-arg API_HOST=api.example.com .

FROM node:22-alpine AS admin
WORKDIR /src
COPY frontend-admin/package.json frontend-admin/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend-admin/ ./
# The admin panel calls the API at https://API_HOST (baked in at build time).
ARG API_HOST
RUN test -n "$API_HOST" || (echo "Build argument API_HOST is required" >&2; exit 1)
RUN VITE_API_BASE="https://${API_HOST}" npm run build

FROM caddy:2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=admin /src/dist /srv/admin
COPY widget/chat-widget.js widget/chat-widget.css /srv/widget/
