# Development setup

Everything runs on your machine. Postgres, Redis and S3-compatible storage run in Docker; the API, worker and web app run natively so changes reload fast.

## 1. Install prerequisites (once)

macOS:

```sh
brew install go node ghostscript qpdf
# plus Docker Desktop: https://www.docker.com/products/docker-desktop/
```

Linux (Debian/Ubuntu): install Go 1.27+, Node 24+, Docker, then `sudo apt install ghostscript qpdf`.

`make dev` checks for these and tells you what's missing.

## 2. Run it

```sh
make dev
```

This one command:

1. starts Postgres, Redis and SeaweedFS (S3) via `deploy/docker-compose.dev.yml`
2. creates `backend/.env` from `backend/.env.example` if it doesn't exist
3. installs frontend packages on first run
4. starts the **API** (:8080, applies DB migrations), the **worker**, and the **web app** at **http://localhost:5173**

Open http://localhost:5173. `Ctrl-C` stops the app processes; `make dev-down` stops the containers (data is kept in Docker volumes; `docker compose -f deploy/docker-compose.dev.yml down -v` wipes it).

How the pieces talk in dev:

```
browser ─▶ Vite :5173 ──/v1 proxy──▶ API :8080 ─┬─▶ Postgres :5432
                                                ├─▶ Redis :6379  ◀── worker (go run … worker)
                                                └─▶ SeaweedFS S3 :8333
```

The web app and API share an origin through the Vite proxy, just like production behind Caddy, so auth cookies behave the same way.

## 3. Day-to-day

| Task | Command |
|---|---|
| Unit tests, lint, build | `make test` |
| End-to-end (API script + Playwright browser tests) | `make e2e` (with `make dev` running) |
| Install the Playwright browser (first e2e run) | `cd frontend && npx playwright install chromium` |
| Backend only, after editing Go code | restart `make dev` (Go isn't hot-reloaded) |
| Inspect the database | `docker exec -it pdfutils-dev-postgres-1 psql -U pdfutils` |
| Reset rate limits while testing | `docker exec pdfutils-dev-redis-1 sh -c 'redis-cli --scan --pattern "rl:*" \| xargs -r redis-cli del'` |

**Emails.** With `SMTP_ADDR` empty (the default), verification and password-reset emails are printed to the API log, including their links.

**Google sign-in in dev (optional).** Create an OAuth client (see [production.md](production.md#google-sign-in-optional)) with the redirect URI `http://localhost:5173/v1/auth/google/callback`, then set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `backend/.env`. The "Continue with Google" button only appears once these are set.

## 4. Project layout

```
backend/
  cmd/pdfutils/          main: `server`, `worker`, `migrate`
  internal/api/          HTTP handlers, auth, rate limiting
  internal/pdf/          the PDF tools (one Tool per operation) + tests
  internal/worker/       job processing and file cleanup
  internal/store/        Postgres queries + migrations/
  internal/service/      file storage and quota logic shared by API and worker
  api/openapi.yaml       API contract for web and mobile clients
frontend/src/
  lib/tools.ts           tool registry (UI for every tool is driven from here)
  pages/ToolPage.tsx     upload → options → process → result flow
infra/                   Terraform for AWS (see production.md)
scripts/                 dev.sh, e2e.sh
```

**Adding a tool:** implement it in `backend/internal/pdf/` (register it in an `init()`), add a test, add its name to the `ToolName` enum in `api/openapi.yaml` (a test enforces this), and add an entry to `frontend/src/lib/tools.ts`.
