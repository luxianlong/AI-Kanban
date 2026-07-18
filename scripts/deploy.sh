#!/usr/bin/env bash
# Guarded auto-deploy. Called by an improvement task after it has committed its
# change on a work branch:  bash scripts/deploy.sh
#
#   1. Build + run tests (scripts/build.sh). If anything fails → ABORT, nothing
#      is merged or restarted, exit non-zero (the agent then marks the task
#      failed with the [[TASK_FAILED]] sentinel).
#   2. If green and on a work branch → merge into main and push.
#   3. Trigger a DETACHED, delayed backend restart so the new code goes live
#      without the restart killing the still-finishing task.
#
# The test gate means broken code can never reach the running app.
set -uo pipefail
source "$(dirname "$0")/env.sh"

cd "$REPO"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "▶ Guarded deploy from branch '$BRANCH'"

if ! bash "$REPO/scripts/build.sh"; then
  echo "[[DEPLOY_ABORTED]] build or tests failed — not merging, not deploying."
  exit 1
fi

if [ "$BRANCH" != "main" ]; then
  echo "▶ Tests green — merging '$BRANCH' into main…"

  # SAFETY: the live H2 database (data/) is runtime state and must NEVER be
  # clobbered by a git operation. It used to be tracked in git, so a
  # checkout/merge/revert would silently roll the on-disk DB back to a stale
  # snapshot — wiping every story/task created since (see task #270). The DB is
  # untracked now, but we snapshot it before touching git and restore it after
  # the merge so the live on-disk data always wins, no matter what git does.
  DB_SNAPSHOT=""
  if [ -d "$REPO/data" ]; then
    DB_SNAPSHOT="$(mktemp -d)/data"
    mkdir -p "$DB_SNAPSHOT"
    cp -a "$REPO/data/." "$DB_SNAPSHOT/" 2>/dev/null || true
  fi

  git checkout main
  git merge --no-ff "$BRANCH" -m "auto-deploy: merge $BRANCH"
  git push origin main 2>&1 || echo "(push skipped/failed — continuing with local deploy)"

  if [ -n "$DB_SNAPSHOT" ] && [ -d "$DB_SNAPSHOT" ]; then
    mkdir -p "$REPO/data"
    cp -a "$DB_SNAPSHOT/." "$REPO/data/" 2>/dev/null || true
    rm -rf "$(dirname "$DB_SNAPSHOT")" 2>/dev/null || true
    echo "▶ Live database preserved across the merge."
  fi
else
  echo "▶ On main — deploying in place."
fi

echo "▶ Scheduling detached backend restart…"
setsid bash "$REPO/scripts/restart-backend.sh" < /dev/null >> /tmp/kanban-deploy.log 2>&1 &
disown || true

echo "✔ DEPLOYED — the app will restart momentarily with the new code."
