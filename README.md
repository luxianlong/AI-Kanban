# AI Kanban

A kanban board where you create **todo items** and a scheduled **Claude Code session**
regularly picks them up and tries to resolve them — writing the result back for you to read.

- **Frontend** — React + Vite, styled as a **kanban board**: tasks are cards that flow
  left→right across **To Do → In Progress → Done → Needs Attention** as the agent works.
  In-Progress cards show a live "thinking" ticker; click any card for a detail drawer with
  the full live session log or final result.
- **Backend** — Java 21 + Spring Boot (REST API, H2 database, scheduled worker)
- **Worker** — a `@Scheduled` service that shells out to the `claude` CLI in headless
  (`-p` / print) mode, one todo at a time, and records what the session produced.

```
 ┌──────────┐   POST /api/todos    ┌──────────────┐   poll PENDING    ┌────────────────────┐
 │  React   │ ───────────────────▶ │ Spring Boot  │ ────────────────▶ │ ClaudeWorkerService│
 │  (Vite)  │ ◀─── GET /api/todos  │  + H2 store  │ ◀── write result  │  claude -p "..."   │
 └──────────┘   (polls every 4s)   └──────────────┘   RESOLVED/FAILED └────────────────────┘
```

A todo flows: `PENDING → IN_PROGRESS → RESOLVED` (or `FAILED`).

See **[docs/architecture.md](docs/architecture.md)** for a full topology of the
design — system, components, the todo lifecycle, the worker poll cycle, stories,
and the guarded deploy — rendered as Mermaid diagrams.

> 📌 **Sample output — 新加坡旅游攻略 (Singapore travel guide)**:
> a complete, publish-ready travel guide the board's agent built end-to-end across a
> 5-step story (visa/flights/budget → attractions & itineraries → food & shopping →
> accommodation & practical tips → final proofread). A good example of what a chained
> story produces. Read it as the **[source Markdown](docs/singapore-travel-guide.md)**,
> or as a **styled webpage** at **`/singapore.html`** when the app is running
> (generated from the Markdown by [`scripts/build-singapore-page.mjs`](scripts/build-singapore-page.mjs)
> on every frontend build).

> 📄 **Result format — every doc is a web page.** When a task produces a
> substantial result, we commit it as Markdown under [`docs/`](docs/) and the build
> renders it into a browsable page so the end web user can read it without a Markdown
> viewer. Open **`/docs.html`** (also reachable from the **Docs** button in the board
> header) for an index of every document; each `docs/<name>.md` is served at
> `/docs/<name>.html`, with tables, nested lists and even Mermaid diagrams rendered.
> The renderer ([`scripts/build-docs-site.mjs`](scripts/build-docs-site.mjs), sharing the
> converter in [`scripts/lib/md-to-html.mjs`](scripts/lib/md-to-html.mjs)) runs on every
> frontend build, so the pages can never drift from the source Markdown.

---

## Prerequisites

- **JDK 21** (a full JDK, not a headless JRE — you need `javac` to build)
- **Node 18+** and npm
- **[Claude Code CLI](https://docs.claude.com/en/docs/claude-code)** installed and
  authenticated (`claude --version` should work). This is what the worker invokes.

Maven is **not** required — the backend ships a Maven wrapper (`./mvnw`).

---

## Run it

### 1. Backend (port 8080)

```bash
cd backend
./mvnw spring-boot:run
```

On first run this creates a file-based H2 database under `backend/data/` (todos
survive restarts). The worker starts polling ~10s after boot.

### 2. Frontend (port 5173)

```bash
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>. Vite proxies `/api/*` to the backend on :8080, so
there's a single origin in dev.

Create a todo, and within one poll interval the status flips to **In progress**,
then **Resolved** with the Claude session's output attached.

**Click any todo to expand it.** While it's *In progress*, the expanded view shows
a **live session log** — the Claude session's reasoning, tool calls, and results
streaming in as they happen (polled every ~1.5s). When it finishes you get the
final **Result**, with the full session transcript available under "Full session log".

---

## How the worker works

`ClaudeWorkerService` runs on a fixed schedule (`worker.poll-interval-ms`, default 15s):

1. Load `PENDING` todos oldest-first (FIFO).
2. Move up to `worker.batch-size` of them to `IN_PROGRESS`.
3. For each, run `claude <extra-args> -p "<prompt built from the todo>"` inside
   `worker.working-directory`, with a `worker.timeout-seconds` hard timeout.
4. Store stdout as the result and set `RESOLVED`, or `FAILED` on non-zero exit / timeout.

A `ReentrantLock` guarantees ticks never overlap, so a long-running session simply
delays the next poll rather than spawning parallel Claude processes.

Output is consumed with `--output-format stream-json`, so each event (assistant text,
tool call, tool result, final result) is rendered to a readable line and appended to
an in-memory transcript (`LiveLogRegistry`) as it arrives — that's what the live log
shows. On restart, any todo still marked `IN_PROGRESS` (its process died with the old
JVM) is automatically requeued to `PENDING`.

### Configuration (`backend/src/main/resources/application.yml`)

| Key | Default | Meaning |
| --- | --- | --- |
| `worker.enabled` | `true` | Master switch. `false` = never launch Claude. |
| `worker.claude-command` | `claude` | Path to the CLI (use an absolute path if not on the service `PATH`). |
| `worker.extra-args` | `--output-format text` | Args inserted before the prompt. |
| `worker.working-directory` | `~/kanban-work` | The directory each session runs in (its sandbox/repo). |
| `worker.timeout-seconds` | `300` | Per-item hard timeout. |
| `worker.batch-size` | `1` | Max items handled per tick. |
| `worker.poll-interval-ms` | `15000` | Poll cadence. |

Any key can be overridden at launch, e.g.:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--worker.claude-command=/abs/path/to/claude --worker.poll-interval-ms=5000"
```

### Letting Claude actually *do* things

By default the session runs with normal permissions, so it will answer/plan but
won't edit files or run commands unattended. To let it act autonomously, point
`worker.working-directory` at a **trusted, sandboxed** repo and add
`--dangerously-skip-permissions` to `worker.extra-args`. Understand the risk before
enabling this — the session will run tools without prompting.

---

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/todos` | List todos (newest first). |
| `POST` | `/api/todos` | Create `{ "title": "...", "description": "..." }`. |
| `GET` | `/api/todos/{id}` | Fetch one. |
| `GET` | `/api/todos/{id}/log` | Live session transcript (poll while in progress). |
| `PATCH` | `/api/todos/{id}` | Update `{ "status": "...", "result": "..." }`. |
| `POST` | `/api/todos/{id}/rerun` | Run a finished task again as a **copy**: queues a new PENDING task built from it (attachments included) and leaves the original and its result alone. Optional `{ "title": "...", "description": "..." }` edits the copy's wording; omit a field to keep the original's. |
| `DELETE` | `/api/todos/{id}` | Delete. |
| `GET` | `/api/stories` | List stories with their ordered steps. |
| `POST` | `/api/stories` | Create a story `{ "name": "...", "steps": [{ "title": "...", "description": "..." }] }`. |
| `GET` | `/api/worker/status` | Worker config (enabled, interval, ...). |
| `GET` | `/api/workflows` | List workflows with their full graph. |
| `POST` | `/api/workflows` | Create one from a whole graph: `{ "name": "...", "nodes": [{ "id": "...", "name": "...", "prompt": "..." }], "edges": [{ "from": "...", "to": "..." }], "entryNodeId": "..." }`. `entryNodeId` may be omitted when exactly one node has no incoming edge. |
| `PUT` | `/api/workflows/{id}` | Replace name, description and whole graph. |
| `DELETE` | `/api/workflows/{id}` | Delete the workflow, its graph and its runs. |
| `POST` | `/api/workflows/{id}/run` | Queue a run: `{ "input": "..." }` (optional) is handed to the entry node. |
| `GET` | `/api/workflows/executions` | Runs, newest first. `?workflowId=` narrows to one workflow. |
| `GET` | `/api/workflows/executions/{id}` | One run with every node's input, output and status. |
| `POST` | `/api/workflows/executions/{id}/cancel` | Stop a run: kill the live node session and close the run out. |
| `DELETE` | `/api/workflows/executions/{id}` | Delete a run. |
| `GET` | `/api/workflows/node-runs/{id}/log` | Live transcript of one node's session (poll while it runs). |
| `GET` | `/api/workflows/favorites` | Favourite steps, by name — each `{ id, name, prompt, effort, updatedAt }`. |
| `POST` | `/api/workflows/favorites` | Keep a step: `{ "name": "...", "prompt": "...", "effort": "low" }`. A name already on the shelf is **replaced**, not duplicated. |
| `DELETE` | `/api/workflows/favorites/{id}` | Take a step off the shelf. Workflows built from it keep their own copy. |

**Public (no password) — delegated tasks:**

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/public/tasks` | Open delegated tasks. `?status=` takes `open` (default), `all`, or one state. |
| `GET` | `/api/public/tasks/{id}` | One delegated task. |
| `POST` | `/api/public/tasks/{id}/claim` | Take it: `{ "worker": "my-bot" }` → the task plus a `token`. |
| `POST` | `/api/public/tasks/{id}/status` | Report it: `{ "status": "RESOLVED", "result": "...", "token": "..." }`. |

H2 console (dev): <http://localhost:8080/h2-console> (JDBC URL from `application.yml`).

### Delegated ("代理") tasks — work handed to an outside worker

Tick **Delegate to an outside worker** when adding a task (or `POST /api/todos` with
`"delegated": true`) and this server will never run it. The Claude worker skips it
entirely; instead it is published on the `/api/public/tasks` API above, which is the
one task surface exempt from the site password, so anyone who can reach the board can
poll for work:

```bash
# 1. find open work
curl https://<host>/api/public/tasks

# 2. take it (optional, but it stops two workers doing the same job)
curl -X POST https://<host>/api/public/tasks/42/claim \
     -H 'Content-Type: application/json' -d '{"worker":"my-bot"}'
# -> { "task": {...}, "token": "…" }   keep the token

# 3. report the outcome — it lands on the board like any other result
curl -X POST https://<host>/api/public/tasks/42/status \
     -H 'Content-Type: application/json' \
     -d '{"status":"RESOLVED","result":"here is the answer","token":"…"}'
```

Only tasks explicitly flagged `delegated` exist on that API — every other id answers
`404`, so the private board never leaks through it, and nothing there can create or
delete a task. Claiming mints a one-time token that is then required to report the
outcome, so a passer-by can't overwrite the claimer's answer; an *unclaimed* task
accepts a report from anyone. Reporting `PENDING` hands the task back and releases the
claim for the next worker.

### Stories — chaining tasks

A **story** is a named, ordered chain of tasks. Creating one via `POST /api/stories`
(or the **New story** button in the To Do column) spins up a normal todo per step,
each tagged with a shared `storyId` and its position in the chain. The worker runs a
story **one step at a time, top to bottom**: a step stays queued until every earlier
step in its chain has RESOLVED, so later steps can safely build on earlier results —
and the chain halts if a step fails. Each card shows a `Story · n/total` badge; blocked
steps are dimmed until their turn. Standalone todos are unaffected (their story fields
are `null`).

### Workflows — a DAG of prompts

A **story** is a straight line of tasks. A **workflow** is the same idea as a
*graph*: nodes fan out and join back up, so one step's answer can feed two, and two
answers can feed one. Open it from **Workflows** in the board header (or
`/#/workflows`) — the graph editor and its runs sit on the same page.

```
                 ┌── Bull case ──┐
   Collect ──────┤               ├────── Verdict
                 └── Bear case ──┘
```

Each node holds a prompt you write. Every **workflow execution** starts at the
single entry node, which receives whatever text you type when starting the run;
each later node runs once every node feeding it has succeeded.

The editor *is* that picture: the DAG is drawn left→right in run order, and picking
a box opens that step's prompt underneath it — one step at a time, so the shape of
the graph stays visible while you edit. Connections are made with the **Input from**
chips on the open step rather than by dragging lines; a step that is downstream is
never offered, because that would close a loop. Each run redraws the same graph with
every box coloured by how that step went, and picking a box there opens its input,
output and live log.

**Nodes do not share context.** Every node is a brand-new headless session —
nothing is resumed, and no conversation history crosses an edge. The only thing a
node inherits is the *output text* of the node(s) feeding it, pasted into its
prompt as an `INPUT` block above its own instructions. A node with two parents gets
both, each labelled with the node it came from. That is what makes a workflow
composable: what a node does depends only on its own prompt plus the input it was
handed, never on something an earlier node happened to mention.

The runner works one node per tick and then yields, so a long graph never starves
the board's task queue; both share the single scheduler thread, so exactly one
Claude process is alive at a time. If a node fails (or prints `[[TASK_FAILED]]`),
only what depends on it is skipped — an independent branch still finishes, and the
run is recorded as failed with every node's output still readable.

An execution snapshots each node's name, prompt and dependencies when it starts, so
editing or deleting the workflow afterwards never rewrites what a past run did.

**Favourite steps.** A step worth using again can be kept: the ★ on its card saves
its name, prompt and effort level to a shelf shared by every workflow, and the row
of chips under **Add step** inserts any of them into the open graph as a new step,
wired to the last one like any other. It is a *copy* — editing the inserted step
never rewrites the favourite, and re-saving the favourite never reaches into graphs
already built from it. Position and connections are not part of one: those belong to
the graph you are putting it into. Saving under a name already on the shelf replaces
that favourite (the editor asks first), and the ★ shows filled while a favourite by
that step's name exists.

---

## Tests

```bash
cd backend && ./mvnw test
```

Covers the API with the worker disabled (no Claude process is launched).

## Build for production

```bash
cd frontend && npm run build      # emits frontend/dist/
cd ../backend && ./mvnw package   # emits backend/target/kanban-backend-*.jar
```

Serve `frontend/dist/` from any static host (or copy it into
`backend/src/main/resources/static/`), and run the jar with `java -jar`.

---

## Roadmap (post-v1)

- Auth / multi-user todos
- Stream live worker logs into the UI instead of only the final result
- Retry / cancel controls per todo
- Persist per-run transcripts and cost/token usage
