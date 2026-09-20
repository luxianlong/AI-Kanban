#!/usr/bin/env bash
# Start (or restart) the whole app locally: backend on :8080, frontend dev on :5173.
# Builds first so you fail fast on a broken tree.
set -euo pipefail
source "$(dirname "$0")/env.sh"

bash "$REPO/scripts/build.sh"

echo "▶ Starting backend on :$BACKEND_PORT…"
backup_db
kill_backend
ensure_healthy_db
setsid java $(backend_launch_args) < /dev/null > "$BACKEND_LOG" 2>&1 &
disown || true
wait_for_backend && echo "✔ backend up" || { echo "✗ backend failed — see $BACKEND_LOG"; exit 1; }

# The server hands work out; a worker is what actually runs it. Without one the board still
# works — you can read it, file tasks, build workflows — but nothing is ever picked up.
start_worker

# "Up" means it answered at all. -f treats the Basic-Auth gate's 401 as a
# failure, which is exactly what a locked-down public server returns, so this
# checks for any HTTP status instead. -k because the certificate is issued to
# youraikanban.com and we are dialling 127.0.0.1.
frontend_up() {
  [ "$(curl -sk -o /dev/null -m 3 -w '%{http_code}' "$FRONTEND_URL" 2>/dev/null)" != "000" ]
}

if ! frontend_up; then
  echo "▶ Starting frontend dev server on :$FRONTEND_PORT…"
  ( cd "$REPO/frontend" && setsid npm run dev < /dev/null > "$FRONTEND_LOG" 2>&1 & disown ) || true
  for _ in $(seq 1 30); do frontend_up && break; sleep 1; done
fi
if frontend_up; then echo "✔ frontend up"; else
  echo "✗ frontend did NOT come up — see $FRONTEND_LOG"; exit 1
fi

echo ""
if [ "$WORKER_ENABLED" = "1" ] && [ -n "$KANBAN_WORKER_TOKEN" ]; then
  sleep 2
  # Nested ifs rather than `[ "$up" -gt 1 ] && echo`: under `set -euo pipefail` that
  # as the last statement of a branch fails the whole if-statement on a one-worker box,
  # and takes the script down with it.
  up="$(worker_pids | grep -c . || true)"
  if [ "$up" -eq 0 ]; then
    echo "✗ worker did NOT come up — see $WORKER_LOG"
  else
    echo "✔ worker '$WORKER_NAME' up  ($WORKER_CAPABILITIES)"
    if [ "$up" -gt 1 ]; then
      echo "✔ $up workers up — a run's independent branches can go $up wide"
    fi
  fi
fi

echo ""
echo "Open  $FRONTEND_URL"
