#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose --env-file .env -f deploy/compose.yml up -d
(cd backend && ./mvnw -B -ntp -Dmaven.repo.local=../.tools/m2 spring-boot:run) &
backend_pid=$!
(cd frontend && npm run dev) &
frontend_pid=$!
trap 'kill "$backend_pid" "$frontend_pid" 2>/dev/null || true' EXIT INT TERM
wait
