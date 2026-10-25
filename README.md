# PDF Utils

A suite of online PDF tools, similar to iLovePDF or Smallpdf: merge, split, compress, JPG↔PDF, protect, unlock, rotate, organize, remove pages, watermark and page numbers.

- **backend/**: Go API and job worker, one binary (`pdfutils server|worker|migrate`)
- **frontend/**: React, Vite and Tailwind web app
- **backend/api/openapi.yaml**: the API contract, used to generate the Android and iOS clients
- **infra/**: Terraform for AWS: `bootstrap` (state bucket), `aws` (full production), `aws-lean` (low-cost production)
- **deploy/**: Docker Compose for local dev, Caddy config, and a non-AWS VPS stack
- **docs/**: development and production guides

```
Web / Mobile ──> Caddy ──> Go API ──┬─> Postgres  (users, files, jobs, usage)
                                    ├─> Redis     (job queue, rate limits)
                                    └─> S3 / R2   (files)
                     Go worker(s) ──> pdfcpu · Ghostscript · qpdf
```

## Getting started

| | |
|---|---|
| **Develop locally** | `make dev`, then open http://localhost:5173. Full guide: [docs/development.md](docs/development.md) |
| **Deploy to AWS (full)** | `make infra-bootstrap && make infra-init && make infra-apply`: t4g.medium, ECR, SES, CloudWatch, backups. About $35–40/month. Guide: [docs/production.md](docs/production.md) |
| **Deploy to AWS (lean)** | Same commands with `STACK=aws-lean`: t2.micro, Docker Hub, Gmail SMTP, no CloudWatch, optional backups. About $14/month, or under $1 on the free tier. Guide: [docs/production-lean.md](docs/production-lean.md) |
| **All commands** | `make help` |

## How it works

1. `POST /v1/uploads` stores files and returns their ids. File types are detected from content.
2. `POST /v1/tasks/{tool}` validates the options and queues a job.
3. Poll `GET /v1/jobs/{id}` until the job is `done`.
4. `GET /v1/files/{id}/link` returns a 15-minute download URL that needs no auth headers.

**Anonymous users** are identified by `X-Device-Id`, with the client IP as a fallback. Their files expire after 2 hours. When they sign up or log in, their recent files move into the account.

**Plans and limits** come from the `plans` table, not from code:

| Plan | Tasks/hour | Max file size | Files per task | Storage |
|------|------------|---------------|----------------|---------|
| anon | 10         | 50 MB         | 20             | 0       |
| free | 100        | 200 MB        | 50             | 1 GB    |
| pro (hidden) | 1000 | 500 MB      | 200            | 10 GB   |

To add a paid tier, insert a row into `plans` and set `users.plan`. No code changes are needed.

**History storage:** signed-in users' files are kept up to their storage quota, and the oldest are evicted first. Bucket lifecycle rules (created by Terraform; `deploy/s3-lifecycle.json` for non-AWS setups) handle the rest:

- `tmp/` (anonymous) objects expire after 1 day.
- `users/` objects move to **S3 Glacier Instant Retrieval** after 30 days. That costs about 68% less than Standard and still downloads in milliseconds. Flexible Retrieval and Deep Archive would take hours, which would break "download from history".

## Auth

- **Email and password:** bcrypt hashes, email verification, and password reset.
- **Google:**
  - Web uses the OAuth code flow with PKCE (`/v1/auth/google/start`).
  - Mobile posts a Google ID token to `/v1/auth/google/token`. Add the iOS and Android client ids to `GOOGLE_MOBILE_CLIENT_IDS`.
  - Register the redirect URI `https://<domain>/v1/auth/google/callback` (in dev: `http://localhost:5173/v1/auth/google/callback`).
- **Tokens:**
  - Access tokens are 15-minute JWTs.
  - Refresh tokens rotate on every use, and reusing an old one revokes every session.
  - Web keeps the refresh token in an httpOnly SameSite=Strict cookie; mobile gets it in the JSON response.

## Building the mobile apps

Generate clients from `backend/api/openapi.yaml`:

```sh
npx @openapitools/openapi-generator-cli generate -i backend/api/openapi.yaml -g kotlin -o mobile/android-client
npx @openapitools/openapi-generator-cli generate -i backend/api/openapi.yaml -g swift5 -o mobile/ios-client
```

Generate a random `X-Device-Id` once per install and send it on every request. Store the refresh token in Keychain or Keystore. Open `/files/{id}/link` URLs with the platform's download manager.
