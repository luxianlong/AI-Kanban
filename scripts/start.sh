#!/usr/bin/env bash
# Start (or restart) the whole app locally: backend on :8080, frontend dev on :5173.
# Builds first so you fail fast on a broken tree.
set -euo pipefail
source "$(dirname "$0")/env.sh"

bash "$REPO/scripts/build.sh"

echo "▶ Starting backend on :$BACKEND_PORT…"
kill_backend
setsid java $(backend_launch_args) < /dev/null > "$BACKEND_LOG" 2>&1 &
disown || true
wait_for_backend && echo "✔ backend up" || { echo "✗ backend failed — see $BACKEND_LOG"; exit 1; }

if ! curl -sf "http://localhost:$FRONTEND_PORT" >/dev/null 2>&1; then
  echo "▶ Starting frontend dev server on :$FRONTEND_PORT…"
  ( cd "$REPO/frontend" && setsid npm run dev < /dev/null > "$FRONTEND_LOG" 2>&1 & disown ) || true
  for _ in $(seq 1 30); do curl -sf "http://localhost:$FRONTEND_PORT" >/dev/null 2>&1 && break; sleep 1; done
fi
echo "✔ frontend up"

echo ""
echo "Open  http://localhost:$FRONTEND_PORT"
