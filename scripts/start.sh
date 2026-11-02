#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose --env-file .env -f deploy/compose.yml up -d
cd backend
exec java -jar target/cuhkcampus-0.1.0.jar
