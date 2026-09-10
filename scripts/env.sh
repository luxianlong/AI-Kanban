#!/usr/bin/env bash
# Shared environment + config for the Kanban run/deploy scripts.
# Source this at the top of the other scripts:  source "$(dirname "$0")/env.sh"
#
# Everything is overridable via environment variables so the scripts stay
# portable — the defaults just match a typical local setup.

# Repo root = parent of this scripts/ directory (resolve symlinks).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# Optional local overrides, gitignored. Somewhere to park a setting for the duration of
# a working session without editing a tracked file — and, unlike an inline environment
# variable, it survives the restarts that deploy.sh schedules on its own. Delete the
# file to go back to the defaults below.
[ -f "$REPO/scripts/local.env" ] && source "$REPO/scripts/local.env"

# Toolchain. Prefer an explicit full JDK 21 (needs javac; a headless JRE won't build).
# Probe the usual locations instead of trusting one hardcoded path: pointing
# JAVA_HOME at a directory that doesn't exist silently falls through to whatever
# `java` happens to be on PATH, which defeats the point of pinning it.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
  for _jdk in "$HOME/.local/jdk-21" /usr/lib/jvm/java-21-openjdk-amd64 \
              /usr/lib/jvm/openjdk-21 /usr/lib/jvm/java-1.21.0-openjdk-amd64; do
    if [ -x "$_jdk/bin/javac" ]; then JAVA_HOME="$_jdk"; break; fi
  done
  unset _jdk
fi
export JAVA_HOME="${JAVA_HOME:-}"
[ -n "$JAVA_HOME" ] && [ -d "$JAVA_HOME/bin" ] && export PATH="$JAVA_HOME/bin:$PATH"

# Maven: use $MVN if set, else a local install, else the committed wrapper.
if [ -z "${MVN:-}" ]; then
  if [ -x "$HOME/.local/apache-maven-3.9.9/bin/mvn" ]; then
    MVN="$HOME/.local/apache-maven-3.9.9/bin/mvn"
  else
    MVN="$REPO/mvnw"
  fi
fi
export MVN

# Claude Code CLI the worker launches.
export CLAUDE_CMD="${CLAUDE_CMD:-$(command -v claude || echo "$HOME/.npm-global/bin/claude")}"

# Ports.
export BACKEND_PORT="${BACKEND_PORT:-8080}"
# The frontend serves HTTPS on :443 when the certificate is present, and falls
# back to plain http on :5173 when it is not (a laptop checkout) — see the TLS
# block in frontend/vite.config.js. Derive both from the same file the dev
# server reads, so a health check can never probe a port nothing is bound to.
export TLS_CERT_FILE="${TLS_CERT_FILE:-$HOME/certs/youraikanban.com.crt}"
if [ -f "$TLS_CERT_FILE" ]; then
  export FRONTEND_PORT="${FRONTEND_PORT:-443}"
  export FRONTEND_URL="${FRONTEND_URL:-https://127.0.0.1:$FRONTEND_PORT}"
else
  export FRONTEND_PORT="${FRONTEND_PORT:-5173}"
  export FRONTEND_URL="${FRONTEND_URL:-http://127.0.0.1:$FRONTEND_PORT}"
fi

# Live H2 database directory. MUST be absolute: the JDBC url in application.yml
# reads this, and a relative path would resolve against each launcher's cwd
# (repo root for `java -jar`, server/ for `mvnw spring-boot:run`) — which
# silently opened two different DB files and made the board "revert" after a
# restart. Pinning it here keeps every launch on the one real database.
export KANBAN_DATA_DIR="${KANBAN_DATA_DIR:-$REPO/data}"

# Rotated pre-restart database backups. On 2026-08-29 the live DB was lost with no
# backup of any kind: a SIGKILL landed while H2 was compacting-and-truncating
# kanban.mv.db on shutdown, so the file was shortened before the replacement chunks
# were ever flushed. No rollback point survived, because every consistent snapshot
# needed at least one chunk that the truncation had already destroyed. One copy taken
# before each restart would have made that a five-minute fix, so we always take one.
export KANBAN_BACKUP_DIR="${KANBAN_BACKUP_DIR:-$REPO/backups}"
export KANBAN_BACKUP_KEEP="${KANBAN_BACKUP_KEEP:-20}"

# How long the backend gets to shut down cleanly before we resort to SIGKILL.
# This was 3 seconds, which the worker could never meet (a tick can sit in
# Process#waitFor for worker.timeout-seconds), so EVERY restart took the SIGKILL
# path and each one was a dice roll on corrupting the database. Keep this above
# spring.lifecycle.timeout-per-shutdown-phase in application.yml (45s).
export BACKEND_KILL_GRACE="${BACKEND_KILL_GRACE:-60}"

# Worker runtime config. The working directory IS the repo, so improvement tasks
# can edit this project. skip-permissions lets the agent act unattended — only
# safe because it runs against this repo behind the guarded-deploy test gate.
export WORKER_WORK_DIR="${WORKER_WORK_DIR:-$REPO}"
# Per-session hard timeout. This overrides worker.timeout-seconds in application.yml,
# which is why worker.background-wait-seconds is left unset there and derived from
# whatever timeout is in force: pinning it in the yml made a session live for an hour
# while the dynamic workflow it started was killed at 25 minutes.
export WORKER_TIMEOUT="${WORKER_TIMEOUT:-3600}"
export WORKER_POLL_MS="${WORKER_POLL_MS:-5000}"
export WORKER_SKIP_PERMS="${WORKER_SKIP_PERMS:-1}"
# Master switch for picking up work at all — both the board's task worker and the
# workflow runner. Set to 0 (usually via scripts/local.env) while the repo itself is
# being refactored: the worker runs sessions IN this repo with --dangerously-skip-permissions,
# so a task picked up mid-change can deploy or restart a half-migrated tree.
export WORKER_ENABLED="${WORKER_ENABLED:-1}"

# Shared secret remote workers authenticate with. Empty by default, which leaves the agent API
# switched OFF (it answers 503) — the right failure for a missing credential on endpoints that
# are exempt from the site password. Put a real value in scripts/local.env, which is gitignored:
#   KANBAN_WORKER_TOKEN=$(openssl rand -base64 32)
export KANBAN_WORKER_TOKEN="${KANBAN_WORKER_TOKEN:-}"

# --- The worker process ------------------------------------------------------------------
#
# The board no longer runs sessions itself: the server hands work out over /api/agent and a
# WORKER process claims it, runs `claude`, and reports back. So one worker has to be running
# for anything on the board to happen, and start.sh launches one here alongside the server.
#
# This local worker declares the repo, which is what makes it eligible for project-improvement
# tasks — those edit this working tree and run the guarded deploy, so they can only run on a
# box that has it. Workers on other machines declare only general/workflow and pick up
# everything else. Start as many as you like, anywhere; they need no coordination, because the
# server hands each unit out exactly once.
export WORKER_JAR="$REPO/worker/target/kanban-worker-0.1.0.jar"
export WORKER_NAME="${WORKER_NAME:-$(hostname -s 2>/dev/null || echo local)}"
export WORKER_CAPABILITIES="${WORKER_CAPABILITIES:-general,workflow,repo:${KANBAN_REPO_NAME:-Dashboard}}"
export WORKER_LOG="${WORKER_LOG:-/tmp/kanban-worker.log}"

export BACKEND_LOG="${BACKEND_LOG:-/tmp/kanban-backend.log}"
export FRONTEND_LOG="${FRONTEND_LOG:-/tmp/kanban-frontend.log}"
export JAR="$REPO/server/target/kanban-server-0.1.0.jar"

# Build the java launch args array shared by start/restart.
backend_launch_args() {
  local args=(
    -jar "$JAR"
    --worker.claude-command="$CLAUDE_CMD"
    --worker.working-directory="$WORKER_WORK_DIR"
    --worker.timeout-seconds="$WORKER_TIMEOUT"
    --worker.poll-interval-ms="$WORKER_POLL_MS"
    --agent.token="$KANBAN_WORKER_TOKEN"
  )
  if [ "$WORKER_ENABLED" != "1" ]; then
    args+=(--worker.enabled=false)
  fi
  if [ "$WORKER_SKIP_PERMS" = "1" ]; then
    args+=(--worker.extra-args=--output-format,stream-json,--verbose,--dangerously-skip-permissions)
  fi
  printf '%s\n' "${args[@]}"
}

# Build the java launch args array for the worker.
worker_launch_args() {
  local args=(
    -jar "$WORKER_JAR"
    --worker.server-url="http://localhost:$BACKEND_PORT"
    --worker.token="$KANBAN_WORKER_TOKEN"
    --worker.name="$WORKER_NAME"
    --worker.capabilities="$WORKER_CAPABILITIES"
    --worker.repo-directory="$WORKER_WORK_DIR"
    --worker.claude-command="$CLAUDE_CMD"
  )
  if [ "$WORKER_SKIP_PERMS" = "1" ]; then
    args+=(--worker.extra-args=--output-format,stream-json,--verbose,--dangerously-skip-permissions)
  fi
  printf '%s\n' "${args[@]}"
}

# Ends in a printf, deliberately. Under `set -euo pipefail` a bare `pgrep | head` pipeline
# returns pgrep's exit status when nothing matches, and `pid="$(worker_pid)"` would then abort
# the whole script — which is exactly what silently stopped start.sh before the worker launched.
worker_pid() {
  local pid
  pid="$(pgrep -f "java .*kanban-worker-.*\.jar" 2>/dev/null | head -1)" || true
  printf '%s' "$pid"
}

# Stop the worker. It kills its own session and hands the lease back on the way down, so give
# it a moment: vanishing instead leaves the card looking busy until the lease expires.
kill_worker() {
  local pid waited=0
  pid="$(worker_pid)"
  [ -n "$pid" ] || return 0
  echo "▶ Stopping worker (pid $pid)…"
  kill "$pid" 2>/dev/null || return 0
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge 30 ]; then
      kill -9 "$pid" 2>/dev/null || true
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

# Start the worker, unless work is switched off entirely.
start_worker() {
  if [ "$WORKER_ENABLED" != "1" ]; then
    echo "▶ Worker disabled (WORKER_ENABLED=0) — the board will not run anything."
    return 0
  fi
  if [ -z "$KANBAN_WORKER_TOKEN" ]; then
    echo "✖ No KANBAN_WORKER_TOKEN set — the agent API is off and the worker cannot claim."
    echo "  Put one in scripts/local.env:  KANBAN_WORKER_TOKEN=\$(openssl rand -base64 32)"
    return 0
  fi
  kill_worker
  echo "▶ Starting worker '$WORKER_NAME' ($WORKER_CAPABILITIES)…"
  setsid java $(worker_launch_args) < /dev/null > "$WORKER_LOG" 2>&1 &
  disown || true
}

# PID of the running backend. Prefer matching the jar: during a graceful shutdown
# Tomcat closes the listener FIRST, so a port-based lookup reports "gone" while the
# JVM is still flushing H2 — and launching a second JVM against the same database
# file is its own way to corrupt it.
backend_pid() {
  local pid
  # Both names on purpose: the module was renamed backend -> server, and a restart
  # that straddles the change still has to find the jar it is replacing. Dropping the
  # old name here would leave the running JVM alive while a second one opened the same
  # H2 file, which is how the database gets corrupted.
  pid="$(pgrep -f "kanban-(backend|server)-.*\.jar" 2>/dev/null | head -1)"
  if [ -z "$pid" ]; then
    pid="$(ss -ltnp 2>/dev/null | grep ":$BACKEND_PORT" | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)"
  fi
  printf '%s' "$pid"
}

# Stop the backend, giving it a real chance to shut down cleanly first.
kill_backend() {
  local pid grace="${BACKEND_KILL_GRACE:-60}" waited=0
  pid="$(backend_pid)"
  [ -n "$pid" ] || return 0

  echo "▶ Stopping backend (pid $pid); allowing ${grace}s for a clean shutdown…"
  kill "$pid" 2>/dev/null || return 0

  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$grace" ]; then
      echo "✖ Backend did not exit after ${grace}s — sending SIGKILL."
      echo "  WARNING: SIGKILL during H2's shutdown compaction is what corrupts the"
      echo "  database. A pre-restart backup is in $KANBAN_BACKUP_DIR."
      kill -9 "$pid" 2>/dev/null || true
      sleep 2
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "✔ Backend stopped cleanly after ${waited}s."
}

# Newest h2 jar in the local Maven repo, used to probe a database file.
h2_jar() {
  ls -1 "$HOME"/.m2/repository/com/h2database/h2/*/h2-*.jar 2>/dev/null | tail -1
}

# True if H2 can actually open this .mv.db file. Probes a COPY: opening a store
# rewrites it, and we must never let a health check mutate the live database.
db_opens() {
  local file="$1" jar="$2" tmp rc=0
  tmp="$(mktemp -d)"
  cp "$file" "$tmp/probe.mv.db" || { rm -rf "$tmp"; return 1; }
  java -cp "$jar" org.h2.tools.Shell -url "jdbc:h2:file:$tmp/probe" \
       -user sa -password "" -sql "SELECT 1" >/dev/null 2>&1 || rc=1
  rm -rf "$tmp"
  return $rc
}

# Snapshot the live database before we touch the process.
#
# This copies a file that may still be open. That is fine and deliberate: MVStore's
# on-disk format is crash-consistent, so the copy is no worse than what a power cut
# would leave, and H2 recovers from that. What it is NOT is a copy of a file that
# some other process is midway through TRUNCATING — which is exactly why the backup
# is taken here, before the shutdown that does the truncating.
backup_db() {
  local db="$KANBAN_DATA_DIR/kanban.mv.db" stamp dest
  if [ ! -f "$db" ]; then
    echo "▶ No database at $db yet — nothing to back up."
    return 0
  fi
  stamp="$(date +%Y%m%d-%H%M%S)"
  dest="$KANBAN_BACKUP_DIR/kanban-$stamp.mv.db"
  mkdir -p "$KANBAN_BACKUP_DIR"
  # Write to a .part name and rename, so an interrupted copy can never be mistaken
  # for a good backup by the restore path below.
  if cp "$db" "$dest.part" && mv "$dest.part" "$dest"; then
    echo "▶ Database backed up to $dest ($(du -h "$dest" | cut -f1))."
  else
    echo "✖ WARNING: database backup FAILED — continuing, but the restart is unprotected."
    rm -f "$dest.part"
    return 0
  fi
  # Rotate: keep the newest $KANBAN_BACKUP_KEEP.
  ls -1t "$KANBAN_BACKUP_DIR"/kanban-*.mv.db 2>/dev/null \
    | tail -n +"$((KANBAN_BACKUP_KEEP + 1))" | while read -r old; do rm -f "$old"; done
}

# Verify the live database before launching, and fall back to the newest good backup
# if it is unreadable. Without this the backend just silently stays down (it did, for
# hours, on 2026-08-29) and the deploy log's only clue is "did not come back up".
# Call this while the backend is STOPPED.
ensure_healthy_db() {
  local db="$KANBAN_DATA_DIR/kanban.mv.db" jar quarantine candidate
  [ -f "$db" ] || return 0          # fresh install; Hibernate creates the schema
  jar="$(h2_jar)"
  if [ -z "$jar" ]; then
    echo "▶ (no h2 jar in ~/.m2 — skipping database verification)"
    return 0
  fi

  if db_opens "$db" "$jar"; then
    echo "✔ Database opens cleanly."
    return 0
  fi

  echo "✖ Live database is CORRUPT and cannot be opened: $db"
  quarantine="$KANBAN_DATA_DIR/corrupt-$(date +%Y%m%d-%H%M%S).mv.db"
  mv "$db" "$quarantine"
  echo "  Quarantined to $quarantine"

  for candidate in $(ls -1t "$KANBAN_BACKUP_DIR"/kanban-*.mv.db 2>/dev/null); do
    if db_opens "$candidate" "$jar"; then
      cp "$candidate" "$db"
      echo "✔ Restored the newest good backup: $candidate"
      return 0
    fi
    echo "  (backup $candidate is unreadable too — trying an older one)"
  done

  echo "✖ No usable backup found. Starting on an EMPTY database."
  echo "  The corrupt file is kept at $quarantine."
}

wait_for_backend() {
  for _ in $(seq 1 30); do
    curl -sf "http://localhost:$BACKEND_PORT/api/worker/status" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
