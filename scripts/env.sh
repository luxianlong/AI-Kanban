#!/usr/bin/env bash
# Shared environment + config for the Dashboard run/deploy scripts.
# Source this at the top of the other scripts:  source "$(dirname "$0")/env.sh"
#
# Everything is overridable via environment variables so the scripts stay
# portable — the defaults just match a typical local setup.

# Repo root = parent of this scripts/ directory (resolve symlinks).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# Toolchain. Prefer an explicit full JDK 21 (needs javac; a headless JRE won't build).
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"

# Maven: use $MVN if set, else a local install, else the committed wrapper.
if [ -z "${MVN:-}" ]; then
  if [ -x "$HOME/.local/apache-maven-3.9.9/bin/mvn" ]; then
    MVN="$HOME/.local/apache-maven-3.9.9/bin/mvn"
  else
    MVN="$REPO/backend/mvnw"
  fi
fi
export MVN

# Claude Code CLI the worker launches.
export CLAUDE_CMD="${CLAUDE_CMD:-$(command -v claude || echo "$HOME/.npm-global/bin/claude")}"

# Ports.
export BACKEND_PORT="${BACKEND_PORT:-8080}"
export FRONTEND_PORT="${FRONTEND_PORT:-5173}"

# Worker runtime config. The working directory IS the repo, so improvement tasks
# can edit this project. skip-permissions lets the agent act unattended — only
# safe because it runs against this repo behind the guarded-deploy test gate.
export WORKER_WORK_DIR="${WORKER_WORK_DIR:-$REPO}"
export WORKER_TIMEOUT="${WORKER_TIMEOUT:-3600}"
export WORKER_POLL_MS="${WORKER_POLL_MS:-5000}"
export WORKER_SKIP_PERMS="${WORKER_SKIP_PERMS:-1}"

export BACKEND_LOG="${BACKEND_LOG:-/tmp/dashboard-backend.log}"
export FRONTEND_LOG="${FRONTEND_LOG:-/tmp/dashboard-frontend.log}"
export JAR="$REPO/backend/target/dashboard-backend-0.1.0.jar"

# Build the java launch args array shared by start/restart.
backend_launch_args() {
  local args=(
    -jar "$JAR"
    --worker.claude-command="$CLAUDE_CMD"
    --worker.working-directory="$WORKER_WORK_DIR"
    --worker.timeout-seconds="$WORKER_TIMEOUT"
    --worker.poll-interval-ms="$WORKER_POLL_MS"
  )
  if [ "$WORKER_SKIP_PERMS" = "1" ]; then
    args+=(--worker.extra-args=--output-format,stream-json,--verbose,--dangerously-skip-permissions)
  fi
  printf '%s\n' "${args[@]}"
}

# Kill whatever is listening on the backend port.
kill_backend() {
  local pid
  pid="$(ss -ltnp 2>/dev/null | grep ":$BACKEND_PORT" | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)"
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    sleep 3
    if ss -ltnp 2>/dev/null | grep -q ":$BACKEND_PORT"; then kill -9 "$pid" 2>/dev/null || true; sleep 2; fi
  fi
}

wait_for_backend() {
  for _ in $(seq 1 30); do
    curl -sf "http://localhost:$BACKEND_PORT/api/worker/status" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
