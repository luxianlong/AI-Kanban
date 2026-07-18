#!/usr/bin/env bash
# Build + test the whole project. This is the guarded-deploy gate: it exits
# non-zero if the backend tests fail or either build fails, so a broken change
# never gets deployed.
set -euo pipefail
source "$(dirname "$0")/env.sh"

# GATE: the live H2 database must NEVER be tracked in git. It's runtime state,
# so a checkout/merge/revert during deploy would roll the on-disk DB back to a
# stale committed snapshot — silently wiping every story/task created since
# (this is exactly how the 潜规则 story was lost; see tasks #270 and #272).
# .gitignore alone can't guarantee this: `git add -f data/kanban.mv.db` bypasses
# it. So we actively fail the deploy gate if any H2 db file is tracked, forcing
# a human to `git rm --cached` it before anything can merge or restart.
echo "▶ Verifying no H2 database files are tracked in git…"
TRACKED_DB="$(cd "$REPO" && git ls-files -- '*.mv.db' '*.trace.db' '*.lock.db' 'data/*.db')"
if [ -n "$TRACKED_DB" ]; then
  echo "✖ These H2 database files are tracked in git but must never be committed:"
  echo "$TRACKED_DB" | sed 's/^/    /'
  echo "  The live DB is runtime state — a git checkout/merge would clobber it (task #270)."
  echo "  Fix: git rm --cached <file>   (the file stays on disk; it's already in .gitignore)."
  exit 1
fi
echo "✔ No database files tracked in git."

echo "▶ Building backend (runs tests)…"
( cd "$REPO/backend" && "$MVN" -q -B package )

echo "▶ Building frontend…"
( cd "$REPO/frontend" && npm run build --silent )

echo "✔ Build + tests passed."
