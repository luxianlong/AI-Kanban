#!/usr/bin/env bash
# Build + test the whole project. This is the guarded-deploy gate: it exits
# non-zero if the backend tests fail or either build fails, so a broken change
# never gets deployed.
set -euo pipefail
source "$(dirname "$0")/env.sh"

echo "▶ Building backend (runs tests)…"
( cd "$REPO/backend" && "$MVN" -q -B package )

echo "▶ Building frontend…"
( cd "$REPO/frontend" && npm run build --silent )

echo "✔ Build + tests passed."
