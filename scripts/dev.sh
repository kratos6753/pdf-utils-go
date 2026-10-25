#!/usr/bin/env bash
# Local development in one command: starts Postgres/Redis/S3 in Docker, then
# the API, worker and web app with live reload of the frontend. Ctrl-C stops
# everything except the infrastructure containers (`make dev-down` for those).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

missing=0
need() {
  if ! command -v "$1" >/dev/null; then
    echo "✗ $1 not found — $2"
    missing=1
  fi
}
need go "brew install go"
need node "brew install node"
need docker "install Docker Desktop"
need gs "brew install ghostscript"
need qpdf "brew install qpdf"
[ "$missing" = 0 ] || exit 1

echo "▸ Starting Postgres, Redis and S3 (SeaweedFS)…"
docker compose -f deploy/docker-compose.dev.yml up -d --wait

[ -f backend/.env ] || cp backend/.env.example backend/.env
[ -d frontend/node_modules ] || (cd frontend && npm install)
set -a
# shellcheck disable=SC1091
source backend/.env
set +a

trap 'trap - EXIT; kill 0' INT TERM EXIT

echo "▸ Starting API on :8080…"
(cd backend && exec go run ./cmd/pdfutils server) &
for _ in $(seq 1 60); do curl -sf localhost:8080/healthz >/dev/null && break; sleep 1; done

echo "▸ Starting worker…"
(cd backend && exec go run ./cmd/pdfutils worker) &

echo "▸ Starting web app on http://localhost:5173"
(cd frontend && exec npm run dev -- --strictPort) &
wait
