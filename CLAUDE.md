# Working on the Kanban — agent guide

You are a Claude Code session launched **by this project's own worker** to resolve one
kanban task. You are running inside the repo at the path shown by `pwd`. This file
tells you how to (a) propose improvements, (b) implement them safely, and (c) let the
app redeploy itself. Follow it exactly.

The app is a kanban board backed by a Spring Boot API. Tasks flow
`PENDING → IN_PROGRESS → RESOLVED / FAILED`. The API is at `http://localhost:8080`.

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

   `deploy.sh` builds the backend (running tests) and frontend. **If tests/build fail it
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
