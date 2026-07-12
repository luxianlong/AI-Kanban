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
  git checkout main
  git merge --no-ff "$BRANCH" -m "auto-deploy: merge $BRANCH"
  git push origin main 2>&1 || echo "(push skipped/failed — continuing with local deploy)"
else
  echo "▶ On main — deploying in place."
fi

echo "▶ Scheduling detached backend restart…"
setsid bash "$REPO/scripts/restart-backend.sh" < /dev/null >> /tmp/dashboard-deploy.log 2>&1 &
disown || true

echo "✔ DEPLOYED — the app will restart momentarily with the new code."
