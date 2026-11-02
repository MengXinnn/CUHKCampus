#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f .env ]; then
  cp .env.example .env
  python3 - <<'PY'
from pathlib import Path
import base64, secrets
p = Path('.env')
text = p.read_text().replace('CAMPUS_DATABASE_PASSWORD=\n', 'CAMPUS_DATABASE_PASSWORD=' + secrets.token_hex(24) + '\n')
text = text.replace('CAMPUS_ENCRYPTION_KEY=\n', 'CAMPUS_ENCRYPTION_KEY=' + base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
p.write_text(text)
p.chmod(0o600)
PY
fi
docker compose --env-file .env -f deploy/compose.yml up -d
cd frontend
npm ci
npm run build
cd ../backend
./mvnw -B -ntp -Dmaven.repo.local=../.tools/m2 package
