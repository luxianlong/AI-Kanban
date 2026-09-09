#!/usr/bin/env bash
# Shared environment + config for the Kanban run/deploy scripts.
# Source this at the top of the other scripts:  source "$(dirname "$0")/env.sh"
#
# Everything is overridable via environment variables so the scripts stay
# portable — the defaults just match a typical local setup.

# Repo root = parent of this scripts/ directory (resolve symlinks).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"

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
    MVN="$REPO/backend/mvnw"
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
# (repo root for `java -jar`, backend/ for `mvnw spring-boot:run`) — which
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
export WORKER_TIMEOUT="${WORKER_TIMEOUT:-3600}"
export WORKER_POLL_MS="${WORKER_POLL_MS:-5000}"
export WORKER_SKIP_PERMS="${WORKER_SKIP_PERMS:-1}"

export BACKEND_LOG="${BACKEND_LOG:-/tmp/kanban-backend.log}"
export FRONTEND_LOG="${FRONTEND_LOG:-/tmp/kanban-frontend.log}"
export JAR="$REPO/backend/target/kanban-backend-0.1.0.jar"

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

# PID of the running backend. Prefer matching the jar: during a graceful shutdown
# Tomcat closes the listener FIRST, so a port-based lookup reports "gone" while the
# JVM is still flushing H2 — and launching a second JVM against the same database
# file is its own way to corrupt it.
backend_pid() {
  local pid
  pid="$(pgrep -f "kanban-backend-.*\.jar" 2>/dev/null | head -1)"
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
