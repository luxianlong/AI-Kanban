# Claude Code Dashboard

A dashboard where you create **todo items** and a scheduled **Claude Code session**
regularly picks them up and tries to resolve them — writing the result back for you to read.

- **Backend** — Java 21 + Spring Boot (REST API, H2 database, scheduled worker)
- **Frontend** — React + Vite
- **Worker** — a `@Scheduled` service that shells out to the `claude` CLI in headless
  (`-p` / print) mode, one todo at a time, and records what the session produced.

```
 ┌──────────┐   POST /api/todos    ┌──────────────┐   poll PENDING    ┌────────────────────┐
 │  React   │ ───────────────────▶ │ Spring Boot  │ ────────────────▶ │ ClaudeWorkerService│
 │  (Vite)  │ ◀─── GET /api/todos  │  + H2 store  │ ◀── write result  │  claude -p "..."   │
 └──────────┘   (polls every 4s)   └──────────────┘   RESOLVED/FAILED └────────────────────┘
```

A todo flows: `PENDING → IN_PROGRESS → RESOLVED` (or `FAILED`).

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
| `worker.working-directory` | `~/dashboard-work` | The directory each session runs in (its sandbox/repo). |
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
| `DELETE` | `/api/todos/{id}` | Delete. |
| `GET` | `/api/worker/status` | Worker config (enabled, interval, ...). |

H2 console (dev): <http://localhost:8080/h2-console> (JDBC URL from `application.yml`).

---

## Tests

```bash
cd backend && ./mvnw test
```

Covers the API with the worker disabled (no Claude process is launched).

## Build for production

```bash
cd frontend && npm run build      # emits frontend/dist/
cd ../backend && ./mvnw package   # emits backend/target/dashboard-backend-*.jar
```

Serve `frontend/dist/` from any static host (or copy it into
`backend/src/main/resources/static/`), and run the jar with `java -jar`.

---

## Roadmap (post-v1)

- Auth / multi-user todos
- Stream live worker logs into the UI instead of only the final result
- Retry / cancel controls per todo
- Persist per-run transcripts and cost/token usage
