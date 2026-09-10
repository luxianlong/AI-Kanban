#!/usr/bin/env bash
# Restart the backend from the freshly built jar. Meant to be launched DETACHED
# (setsid) by deploy.sh: the running task is a child of the backend JVM, so the
# restart must survive that JVM being killed. The delay gives the worker time to
# record the current task's result before the process goes down.
set -uo pipefail
source "$(dirname "$0")/env.sh"

sleep "${RESTART_DELAY:-15}"

echo "$(date) restarting backend…"

# Snapshot BEFORE stopping: shutdown is the dangerous moment (H2 compacts and
# truncates the store as it closes), so this is the copy worth having.
backup_db
kill_backend
# ...and refuse to launch onto a database that no longer opens; fall back to the
# newest good backup instead of leaving the app silently down.
ensure_healthy_db

setsid java $(backend_launch_args) < /dev/null >> "$BACKEND_LOG" 2>&1 &
disown || true

if wait_for_backend; then
  echo "$(date) backend back up on :$BACKEND_PORT"
else
  echo "$(date) WARNING: backend did not come back up — see $BACKEND_LOG"
fi

# Restart the worker too, so a deploy actually ships new worker code. A worker is a separate
# process and would otherwise keep running the jar it started with — which is fine while it is
# only this box, and exactly the problem the protocol version guards on machines a deploy
# cannot reach.
start_worker
