#!/usr/bin/env bash
# Restart the backend from the freshly built jar. Meant to be launched DETACHED
# (setsid) by deploy.sh: the running task is a child of the backend JVM, so the
# restart must survive that JVM being killed. The delay gives the worker time to
# record the current task's result before the process goes down.
set -uo pipefail
source "$(dirname "$0")/env.sh"

sleep "${RESTART_DELAY:-15}"

echo "$(date) restarting backend…"
kill_backend
setsid java $(backend_launch_args) < /dev/null >> "$BACKEND_LOG" 2>&1 &
disown || true

if wait_for_backend; then
  echo "$(date) backend back up on :$BACKEND_PORT"
else
  echo "$(date) WARNING: backend did not come back up — see $BACKEND_LOG"
fi
