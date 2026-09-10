# Working on the Kanban — agent guide

You are a Claude Code session launched **by this project's own worker** to resolve one
kanban task. You are running inside the repo at the path shown by `pwd`. This file
tells you how to (a) propose improvements, (b) implement them safely, and (c) let the
app redeploy itself. Follow it exactly.

The app is a kanban board backed by a Spring Boot API. Tasks flow
`PENDING → IN_PROGRESS → RESOLVED / FAILED`. The API is at `http://localhost:8080`.

It is a Maven aggregator of three modules: `server/` (board API, H2 database, frontend
host), `worker/` (claims work over the server's agent API and runs the Claude session)
and `protocol/` (the wire types they share). Build from the repo root — `scripts/build.sh`
runs the whole aggregator, so a change that breaks the worker fails the deploy gate.

**You are running inside a worker.** The server does not launch sessions; it hands work out
over `/api/agent` and a worker process claims it. Two consequences for you:

- The session you are in was given a working directory by the server. A task about improving
  this project runs in the repo (that is what the worker's `repo:Dashboard` capability
  means); anything else runs in a throwaway directory and cannot see the repo at all.
- Anything you change about how a result is *judged* belongs on the SERVER, in
  `TaskOutcomeService` / `NodeOutcomeService` — never in the worker. A worker jar on another
  machine is the one thing `deploy.sh` cannot update, so it is never allowed to hold an
  opinion about what a result means.

**Exposing the UI publicly** (tunnel + password gate): see
[docs/public-tunnel.md](docs/public-tunnel.md) — how to put this app on a public URL
via `cloudflared` (quick tunnel or stable named tunnel), gated by the Basic-Auth
middleware in `frontend/vite.config.js` (creds in the gitignored
`frontend/.public-auth.json`). Start/stop commands, what survives a restart, and
the security caveats are all there.

## Two kinds of task

### 1. Planner tasks — "think about improvements" (spawn work, don't change code)

If the task asks you to brainstorm / plan / suggest improvements to the kanban board:

1. Explore the repo and think about what would genuinely make it better (UX, reliability,
   features, tests, docs). Prefer small, independently shippable changes.
2. File **at most 5** concrete follow-up tasks by POSTing each to the API:

   ```bash
   curl -s -X POST http://localhost:8080/api/todos \
     -H 'Content-Type: application/json' \
     -d '{"title":"<short imperative title>","description":"<what to change and why; note it was proposed by task #<your id>>"}'
   ```

3. **Do not** create another planner/"think about improvements" task, and do not edit code
   yourself in a planner task. Summarise what you filed, then stop.

Hard limits (prevent runaway loops): ≤5 new tasks per planner; planners never spawn
planners; before filing, `GET http://localhost:8080/api/todos` and skip anything that
duplicates an existing open task.

### 2. Work tasks — implement one improvement (change code, then guarded-deploy)

If the task asks you to implement/fix/add something concrete:

1. Create a branch:  `git checkout -b auto/<taskId>-short-slug`
2. Make the change. Keep it focused and match the surrounding code style.
3. Add or update tests when it makes sense — the deploy gate runs `mvn` tests.
4. Commit:  `git add -A && git commit -m "<what changed>"`
5. Deploy through the guarded gate:

   ```bash
   bash scripts/deploy.sh
   ```

   `deploy.sh` builds the Java modules (running tests) and frontend. **If tests/build fail it
   ABORTS** — nothing is merged, nothing restarts.

6. Read the deploy output:
   - On `✔ DEPLOYED` — summarise what changed; the app will restart itself shortly. Done.
   - On `[[DEPLOY_ABORTED]]` — do **not** try to force it. Leave the work branch in place
     for a human to review, then end your reply with a line containing exactly
     `[[TASK_FAILED]]` and a one-line reason (e.g. the failing test). This moves the card
     to **Needs Attention** instead of Done.

Never merge to `main` yourself or restart the server by hand — `deploy.sh` owns that.
Never use `git push --force`. Work tasks must not create new tasks.

## The failure sentinel

For **any** task you cannot complete, end your reply with a line containing exactly:

```
[[TASK_FAILED]] <brief reason>
```

The worker looks for this and marks the task FAILED (Needs Attention) even though the CLI
exited normally. Only use it when the task truly can't be done.

## Safety notes

- Prefer additive, reversible changes. Don't touch auth, delete data, or rewrite the
  worker's own deploy/restart machinery unless the task explicitly asks.
- The build gate is your safety net, so make sure tests actually cover risky changes.
- Keep secrets out of the repo.
