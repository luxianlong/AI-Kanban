# Dashboard architecture — topology

A visual map of how the Claude Code Dashboard is put together, rendered with
[Mermaid](https://mermaid.js.org/). GitHub renders these diagrams inline; in an
editor use a Mermaid preview extension.

The app is a kanban board where you create **todo items** and a scheduled
**Claude Code session** picks them up, resolves them, and writes the result
back. A todo flows `PENDING → IN_PROGRESS → RESOLVED` (or `FAILED`).

- **Frontend** — React + Vite kanban board (`frontend/`).
- **Backend** — Java 21 + Spring Boot: REST API, H2 file store, a `@Scheduled`
  worker (`backend/`).
- **Worker** — shells out to the `claude` CLI (`-p` / headless) one todo at a
  time and records what the session produced.
- **Deploy** — work tasks commit on a branch and call the guarded
  `scripts/deploy.sh`, which only merges + restarts if the build and tests pass.

---

## 1. System topology (runtime)

How the running pieces talk to each other and where state lives.

```mermaid
flowchart LR
    user([User / Browser])

    subgraph frontend["Frontend — React + Vite"]
        react["App.jsx<br/>kanban board<br/>polls every 4s"]
        vite["Vite dev server<br/>:5173<br/>proxies /api → :8080"]
    end

    subgraph backend["Backend — Spring Boot :8080"]
        todoCtl["TodoController<br/>/api/todos"]
        storyCtl["StoryController<br/>/api/stories"]
        workerCtl["WorkerStatusController<br/>/api/worker/status"]
        repo["TodoRepository<br/>(Spring Data JPA)"]
        worker["ClaudeWorkerService<br/>@Scheduled poll"]
        livelog["LiveLogRegistry<br/>(in-memory transcripts)"]
    end

    h2[("H2 file DB<br/>data/dashboard.mv.db")]
    cli["claude CLI<br/>claude -p (headless)"]
    repofiles["Repo working dir<br/>(git branch + code)"]
    deploy["scripts/deploy.sh<br/>build · test · merge · restart"]

    user --> react
    react --> vite
    vite -->|"/api/*"| todoCtl
    vite -->|"/api/*"| storyCtl
    vite -->|"/api/*"| workerCtl

    todoCtl --> repo
    storyCtl --> repo
    workerCtl -.reads config.-> worker
    todoCtl -.reads.-> livelog
    repo <--> h2

    worker --> repo
    worker --> livelog
    worker -->|"spawns process"| cli
    cli -->|"edits files / runs tests"| repofiles
    cli -->|"bash scripts/deploy.sh"| deploy
    deploy -->|"restart backend"| backend
```

---

## 2. Backend components

Spring beans and how they wire together.

```mermaid
flowchart TB
    app["DashboardApplication<br/>@SpringBootApplication<br/>@EnableScheduling"]

    subgraph web["Web layer (REST)"]
        todoCtl["TodoController<br/>GET/POST/PATCH/DELETE /api/todos<br/>GET /api/todos/{id}/log"]
        storyCtl["StoryController<br/>GET/POST /api/stories"]
        workerCtl["WorkerStatusController<br/>GET /api/worker/status"]
    end

    subgraph domain["Domain / persistence"]
        entity["Todo (@Entity)<br/>+ TodoStatus enum"]
        dtos["TodoDtos<br/>request/response records"]
        repo["TodoRepository<br/>JpaRepository&lt;Todo,Long&gt;"]
    end

    subgraph workerpkg["Worker"]
        worker["ClaudeWorkerService<br/>@Scheduled poll()"]
        props["WorkerProperties<br/>@ConfigurationProperties(worker.*)"]
        livelog["LiveLogRegistry<br/>@Component"]
    end

    db[("H2 file DB")]

    app --> web
    app --> worker
    todoCtl --> repo
    todoCtl --> livelog
    storyCtl --> repo
    workerCtl --> props
    worker --> repo
    worker --> props
    worker --> livelog
    repo --> entity
    repo --> db
    web --> dtos
    entity --> dtos
```

---

## 3. Todo lifecycle (state machine)

The status a card carries as it flows left→right across the board columns
**To Do → In Progress → Done → Needs Attention**.

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /api/todos<br/>(or story step created)

    PENDING --> IN_PROGRESS: worker picks up<br/>(FIFO, not story-blocked)
    IN_PROGRESS --> RESOLVED: CLI exit 0<br/>+ no fail sentinel
    IN_PROGRESS --> FAILED: non-zero exit / timeout /<br/>[[TASK_FAILED]] / worker error

    IN_PROGRESS --> PENDING: requeued on restart<br/>(orphan recovery)

    RESOLVED --> PENDING: retry (PATCH status=PENDING)
    FAILED --> PENDING: retry (PATCH status=PENDING)

    RESOLVED --> [*]
    FAILED --> [*]

    note right of PENDING
        Board column: To Do
    end note
    note right of IN_PROGRESS
        Board column: In Progress
        (live log ticker)
    end note
    note right of RESOLVED
        Board column: Done
    end note
    note right of FAILED
        Board column: Needs Attention
    end note
```

---

## 4. Worker poll cycle

What one `@Scheduled` tick does (default: every 15s in `application.yml`,
overridden to 5s by the run scripts).

```mermaid
sequenceDiagram
    autonumber
    participant Sched as Spring Scheduler
    participant W as ClaudeWorkerService
    participant R as TodoRepository
    participant DB as H2
    participant L as LiveLogRegistry
    participant C as claude CLI

    Sched->>W: poll() (fixedDelay)
    W->>W: enabled? tryLock tick?
    W->>R: findByStatus(PENDING) asc
    R->>DB: query
    W->>W: drop story-blocked steps
    Note over W: take batchSize (default 1)
    W->>R: save(todo, IN_PROGRESS, processedAt)
    W->>L: start + append "Picked up #id"
    W->>C: claude -p "<prompt>" (stream-json)
    loop each stream event
        C-->>W: system / assistant / user / result
        W->>L: append rendered line
    end
    C-->>W: exit code (+ cost, duration)
    alt exit 0 and no [[TASK_FAILED]]
        W->>R: save RESOLVED + result
    else timeout / non-zero / sentinel
        W->>R: save FAILED + message
    end
    R->>DB: persist
```

---

## 5. Stories — sequential task chains

A **story** is a named, ordered chain of tasks. Each step is a normal todo
tagged with a shared `storyId` and a `storyOrder`; the worker runs them one at
a time and only starts a step once every earlier step is `RESOLVED`.

```mermaid
flowchart LR
    subgraph create["POST /api/stories"]
        req["CreateStoryRequest<br/>name + steps[]"]
    end

    req -->|"assign shared storyId (UUID)<br/>storyOrder 0..n"| s0

    subgraph chain["Story chain (one storyId)"]
        direction LR
        s0["Step 0<br/>storyOrder=0"] --> s1["Step 1<br/>storyOrder=1"] --> s2["Step 2<br/>storyOrder=2"]
    end

    s0 -. "must be RESOLVED<br/>before" .-> s1
    s1 -. "must be RESOLVED<br/>before" .-> s2

    note1["isBlockedByStory(): a PENDING step is<br/>skipped while any earlier step is not RESOLVED.<br/>A failed step halts the chain."]
    note2["appendStoryContext(): each step's prompt<br/>carries prior steps' results (≤4000 chars each)<br/>so the chain builds on itself."]

    chain -.-> note1
    chain -.-> note2
```

---

## 6. Guarded deploy flow

How a **work task** ships a change. The test gate means broken code can never
reach the running app.

```mermaid
flowchart TD
    start(["Work task on branch<br/>auto/&lt;taskId&gt;-slug<br/>change committed"]) --> deploy["bash scripts/deploy.sh"]
    deploy --> build["scripts/build.sh<br/>mvn -q -B package (runs tests)<br/>+ npm run build"]

    build -->|"fail"| abort["Print [[DEPLOY_ABORTED]]<br/>exit 1"]
    abort --> failsent["Agent ends reply with<br/>[[TASK_FAILED]]"]
    failsent --> attention["Card → Needs Attention<br/>branch left for human review"]

    build -->|"pass"| onmain{"on main?"}
    onmain -->|"no"| merge["git checkout main<br/>merge --no-ff branch<br/>push origin main"]
    onmain -->|"yes"| inplace["deploy in place"]
    merge --> restart
    inplace --> restart

    restart["setsid restart-backend.sh (detached)<br/>sleep, kill :8080, relaunch jar,<br/>wait_for_backend"]
    restart --> deployed(["✔ DEPLOYED<br/>new code live"])
```

---

## 7. REST API surface

| Method | Path | Purpose |
| ------ | ---- | ------- |
| `GET` | `/api/todos` | List all todos, newest first |
| `GET` | `/api/todos/{id}` | Get one todo |
| `GET` | `/api/todos/{id}/log` | Live session transcript for a todo |
| `POST` | `/api/todos` | Create a todo (→ `PENDING`) |
| `PATCH` | `/api/todos/{id}` | Update status/result (worker + UI retry) |
| `DELETE` | `/api/todos/{id}` | Delete a todo (and clear its live log) |
| `GET` | `/api/stories` | List stories with ordered steps |
| `POST` | `/api/stories` | Create a story (named ordered chain) |
| `GET` | `/api/worker/status` | Worker config (enabled, interval, batch, timeout) |
| `GET` | `/h2-console` | H2 database web console |

---

_Generated as documentation of the current design (task #81). Source of truth
remains the code under `backend/` and `frontend/`._
