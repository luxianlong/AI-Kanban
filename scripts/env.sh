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
# What this box's worker will accept. The DEFAULT is everything, because a checkout with one
# machine behind it should just work.
#
# Narrow it per machine in scripts/local.env, which is gitignored — never here. This file is
# tracked, so a capability dropped in it is dropped on every machine that clones the repo,
# which is the opposite of what splitting work across machines is for.
#
# Whatever the split, some worker somewhere has to declare `workflow`: a workflow run only
# advances while one does. With none, its nodes sit PENDING and the run stays open
# indefinitely — it does not fail, and nothing says why, because from the server's side that is
# indistinguishable from a queue waiting on a busy worker.
export WORKER_CAPABILITIES="${WORKER_CAPABILITIES:-general,workflow,repo:${KANBAN_REPO_NAME:-Dashboard}}"
export WORKER_LOG="${WORKER_LOG:-/tmp/kanban-worker.log}"

# How many worker processes to run on this box. One is the default and the old behaviour.
#
# A worker runs one Claude session at a time, on purpose — so this number is the board's
# whole capacity for doing two things at once, and since the server now hands out the
# independent branches of a single workflow run concurrently (a → b, c, d), it is also how
# wide a fan-out can actually go here. Three workers finish a three-way branch in one step's
# wall-clock; one worker still takes three, correctly but slowly.
#
# It costs what it sounds like: N workers is up to N Claude sessions billing at once. Raise it
# in scripts/local.env on a machine that has the work to fill it.
export WORKER_COUNT="${WORKER_COUNT:-1}"
# A typo here must not quietly leave the board with no worker at all: `seq 1 three` produces
# nothing, the start loop runs zero times, and everything afterwards still reports success.
case "$WORKER_COUNT" in
  ''|*[!0-9]*|0) echo "⚠ WORKER_COUNT='$WORKER_COUNT' is not a positive number — using 1." >&2
                 WORKER_COUNT=1 ;;
esac

export BACKEND_LOG="${BACKEND_LOG:-/tmp/kanban-backend.log}"
export FRONTEND_LOG="${FRONTEND_LOG:-/tmp/kanban-frontend.log}"
export JAR="$REPO/server/target/kanban-server-0.1.0.jar"

# Build the java launch args array shared by start/restart.
backend_launch_args() {
  local args=(
    -jar "$JAR"
    # Still the server's: the story classifier shells out, the docs/pages services read the
    # working directory, and the timeout is the ceiling it hands to workers. What is NOT here
    # any more is --worker.extra-args: the server launches no sessions, so the flags for one
    # belong to the worker (see worker_launch_args).
    --worker.enabled="$( [ "$WORKER_ENABLED" = "1" ] && echo true || echo false )"
    --worker.claude-command="$CLAUDE_CMD"
    --worker.working-directory="$WORKER_WORK_DIR"
    --worker.timeout-seconds="$WORKER_TIMEOUT"
    --agent.token="$KANBAN_WORKER_TOKEN"
  )
  printf '%s\n' "${args[@]}"
}

# What the Nth worker on this box is called. The first keeps the plain name, so a
# single-worker machine's logs read exactly as they always did.
worker_name_for() {
  [ "${1:-1}" = "1" ] && printf '%s' "$WORKER_NAME" || printf '%s-%s' "$WORKER_NAME" "$1"
}

# ...and where it writes. Same rule: worker 1 owns $WORKER_LOG.
worker_log_for() {
  [ "${1:-1}" = "1" ] && printf '%s' "$WORKER_LOG" || printf '%s-%s.log' "${WORKER_LOG%.log}" "$1"
}

# What the Nth worker will accept.
#
# Only the FIRST worker on a box declares `repo:<name>` — the extras get the same list with
# every repo capability struck out. A project-improvement task edits THIS working tree and
# runs the guarded deploy in it; two of them at once would be two Claude sessions editing one
# git checkout, branching over each other and racing to merge. Everything else is safe to
# double up: general tasks and workflow nodes each run in a scratch directory of their own.
worker_capabilities_for() {
  local n="${1:-1}" out="" cap
  [ "$n" = "1" ] && { printf '%s' "$WORKER_CAPABILITIES"; return 0; }
  local IFS=','
  for cap in $WORKER_CAPABILITIES; do
    case "$cap" in
      repo:*) continue ;;
      '') continue ;;
    esac
    out="${out:+$out,}$cap"
  done
  printf '%s' "$out"
}

# Build the java launch args array for the Nth worker (default: the first).
worker_launch_args() {
  local n="${1:-1}"
  local args=(
    -jar "$WORKER_JAR"
    --worker.server-url="http://localhost:$BACKEND_PORT"
    --worker.token="$KANBAN_WORKER_TOKEN"
    --worker.name="$(worker_name_for "$n")"
    --worker.capabilities="$(worker_capabilities_for "$n")"
    --worker.repo-directory="$WORKER_WORK_DIR"
    --worker.claude-command="$CLAUDE_CMD"
  )
  if [ "$WORKER_SKIP_PERMS" = "1" ]; then
    args+=(--worker.extra-args=--output-format,stream-json,--verbose,--dangerously-skip-permissions)
  fi
  printf '%s\n' "${args[@]}"
}

# Every worker JVM on this box, one pid per line. Ends in a printf, deliberately: under
# `set -euo pipefail` a bare `pgrep` pipeline returns pgrep's exit status when nothing
# matches, and `pids="$(worker_pids)"` would then abort the whole script — which is exactly
# what silently stopped start.sh before the worker launched.
worker_pids() {
  local pids
  pids="$(pgrep -f "java .*kanban-worker-.*\.jar" 2>/dev/null)" || true
  printf '%s' "$pids"
}

# The first one, for the callers that only want to know whether anything is up.
worker_pid() {
  printf '%s' "$(worker_pids | head -1)"
}

# Stop every worker on this box. They kill their own sessions and hand their leases back on
# the way down, so give them a moment: vanishing instead leaves cards looking busy until the
# leases expire. All of them and not just the first, because with WORKER_COUNT>1 a restart
# that stopped one would leave the rest running the jar the deploy just replaced.
kill_worker() {
  local pids waited=0 alive
  pids="$(worker_pids)"
  [ -n "$pids" ] || return 0
  echo "▶ Stopping worker(s): $(echo $pids)…"
  for pid in $pids; do kill "$pid" 2>/dev/null || true; done
  while :; do
    alive=""
    for pid in $pids; do
      if kill -0 "$pid" 2>/dev/null; then alive="$alive $pid"; fi
    done
    [ -n "$alive" ] || break
    if [ "$waited" -ge 30 ]; then
      for pid in $alive; do kill -9 "$pid" 2>/dev/null || true; done
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

# Start the workers, unless work is switched off entirely.
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
  local n
  for n in $(seq 1 "$WORKER_COUNT"); do
    echo "▶ Starting worker '$(worker_name_for "$n")' ($(worker_capabilities_for "$n"))…"
    setsid java $(worker_launch_args "$n") < /dev/null > "$(worker_log_for "$n")" 2>&1 &
    disown || true
  done
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
