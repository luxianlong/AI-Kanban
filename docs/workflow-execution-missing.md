# Where the missing workflow executions went

**Card #333 — "workflow execution is missing".** Investigated 2026-09-10 against the
live board, the running jar, the backend log and all 20 rotated database snapshots.
No application code was changed.

## The answer, first

**Nothing is losing your runs. Something is deleting them — and the app cannot tell you
which, because it never writes it down.**

Thirty workflow executions that existed on this board earlier today no longer exist.
That is real: I did not infer it, I read them out of the pre-restart database
snapshots. Together they carry **$24.74** of recorded Claude spend and every article
they produced.

But the executions panel is not hiding anything, and no row was lost to a crash. Every
one of the thirty was removed by one of the two delete paths the code has. The reason
this reads as "missing" rather than "deleted" is that **one of those paths is a button
that changes into a different button underneath your cursor**, and **neither path
leaves a single line in the log**.

## What "missing" is not

Two dead ends, ruled out with evidence, because both look damning at first glance.

**The panel is honest.** The live API returns 29 runs; the page renders 29 cards with
matching ids. Every execution row in the database has a workflow row that still exists
— zero orphans. Nothing is filtered away, nothing is truncated today.

**The gaps in the run numbers are not lost runs.** Run ids jump: `#169` is followed by
`#193`, `#120` by `#153`. That looks like twenty-three runs and thirty-two runs quietly
vanishing. It isn't:

```
IDENTITY_CACHE = 32   (WORKFLOW_EXECUTIONS.ID, read from the live database)
```

H2 hands out ids in blocks of 32 and burns what is left of a block when the store is
reopened. `161 + 32 = 193` exactly. The backend log corroborates it independently:
between the log's first line at 06:58 and now, the ids that ever appear are
161, 163–169, then 193. **Ids 170–192 are never mentioned by anything — they were never
used by a run at all.**

So: don't read a gap in the numbering as a loss. Read the snapshots.

## What actually removes a run

There are exactly two code paths, and that is the whole list:

| Path | What it destroys |
| --- | --- |
| `DELETE /api/workflows/executions/{id}` | that one run and all its node runs |
| `DELETE /api/workflows/{id}` | the workflow **and every run it ever had** |

`WorkflowService.deleteExecutionRows` is the common bottom of both. **Neither writes a
log line.** That is the finding that made this card expensive to answer: every other
destructive action on this board announces itself —

```
com.kanban.docs.DocsController : Deleted document 'article-97f03be6' (2 path(s))
```

— while the deletion of a workflow run, which throws away real money and unrecoverable
output, is completely silent. When a run goes missing there is no way for the user, or
for a session like this one, to distinguish "you deleted it" from "we lost it". I had
to reconstruct today's history out of twenty rotated `.mv.db` files to answer the
question at all.

## The mechanism: Cancel run and Delete run are the same button

This is the part I can demonstrate rather than argue.

An execution card renders one button in the first slot of its action row, chosen by
whether the run is live:

```jsx
{live ? (
  <button className="btn ghost" onClick={cancel}>      Cancel run  </button>
) : (
  <button className="btn danger-ghost" onClick={remove}> Delete run </button>
)}
```
<small>`frontend/src/Workflows.jsx:1335`</small>

The panel polls every **2.5 s** while anything is running (`LIVE_POLL_MS`). The moment
a poll notices the run has finished, the button in that slot stops being *stop this*
and becomes *destroy this*.

Measured on the live site, same card, same DOM position:

| | state | first button | class | position |
| --- | --- | --- | --- | --- |
| before | Running | **Cancel run** | `btn ghost` | x=1167, y=182 |
| after | Done | **Delete run** | `btn danger-ghost` | x=1167, y=182 |

Identical `x` and `y`; the width moves by 3 px. There is no layout shift to catch your
eye, no disabled interval, no undo. A click aimed at *Cancel run* lands on *Delete run*
whenever the run finishes inside the poll window — and the confirmation it raises,

> **Delete this run and everything it produced?**

reads perfectly naturally to somebody in the act of cancelling. Of course you want to
throw away what it produced; you are cancelling it. It does not name the run, does not
say "permanently", and does not say the run has already finished successfully.

### The case that fits it exactly

`#199` is the cleanest missing run on the board, and the only one that was **SUCCEEDED**
when it disappeared:

```
11:56:35.6  #199 created          AI Writing (copy)
11:56:37.3  #199 started
11:56:49.5  #200 started          same workflow, 12 s later
11:57:23.1  #199 finished: SUCCEEDED   (1,399 chars of output, $0.1116)
11:57:30    database snapshot     -> contains #199
   ...
now         live database         -> #199 and its node run #532 are gone
```

`#198` before it and `#200` after it both survive, and their workflow was never deleted,
so no cascade touched it. Two runs of the same one-step workflow started twelve seconds
apart is somebody pressing Run twice by accident; the natural next move is to cancel one
of them. The one that vanished is the one that had **already finished** — the one whose
button had flipped.

I cannot prove the click, because nothing records it. That is the point of the previous
section.

## The other path: deleting a workflow takes its whole history

`WorkflowService.delete` cascades to every run of the workflow, by design and with a
comment saying so. That is defensible. What is not defensible is the warning:

```js
const runs = executions.filter((e) => e.workflowId === draft.id).length
const warning = runs
  ? `Delete "${draft.name}" and its ${runs} run${runs > 1 ? 's' : ''}?`
  : `Delete "${draft.name}"?`
```
<small>`frontend/src/Workflows.jsx:270`</small>

`executions` is the client's copy of a list the **server caps at fifty**, and the client
asks for it **unscoped**:

```java
private static final int EXECUTION_PAGE = 50;        // WorkflowService:50
...
List<WorkflowExecution> page = found.size() > EXECUTION_PAGE
        ? found.subList(0, EXECUTION_PAGE) : found;
```

(confirmed as `bipush 50` / `subList(0, 50)` inside the jar the running JVM was launched
from), while `Workflows.jsx:112` calls `api.listExecutions()` **with no workflow id** and
filters to the open workflow in the browser at line 332.

So once the board holds more than fifty runs in total, a workflow whose runs are older
than the newest fifty gets `runs === 0`, and the dialog quietly drops the clause
entirely — it asks `Delete "X"?` and says nothing about run history at all. Then the
server deletes every one of them.

The same cap has a second effect: the panel in **This one** scope will show
*"No runs yet. Save a workflow and press Run to start one."* for a workflow that
demonstrably has runs. The server already supports the correct call and the client
never makes it:

```
GET /api/workflows/executions?workflowId=c1e451ef-…   ->  6 runs: [51, 44, 43, 42, 4, 3]
GET /api/workflows/executions                          ->  29 runs (newest 50, all workflows)
```

Neither of these bites *today* — the board is at 29 runs. It created 209 run ids in two
days.

**This path did fire today.** Between the 03:16 and 04:20 snapshots, twenty-four runs
were destroyed by workflow deletions: all of `文章-Low` (#52–#62), all of `文章-Low2`
(#63–#116), and four runs of a duplicate `公众号文章`. Runs **#109, #110, #113, #115 and
#116** are among them — the very runs step 29 of this story analysed line by line a few
hours earlier. They are not recoverable from the board.

## Full accounting of what went missing today

Reconstructed from 20 snapshots spanning 2026-09-09 23:32 → 2026-09-10 11:57, plus the
live database.

| | runs | how |
| --- | --- | --- |
| destroyed with their workflow (cascade) | 24 | `文章-Low`, `文章-Low2`, a duplicate `公众号文章` |
| destroyed individually, workflow still standing | 6 | #159, #166, #167, #168, #169, #199 |
| **total gone** | **30** | **$24.74 of recorded spend** |
| surviving now | 29 | |

Of the six individual deletions, four (#159, #166, #167, #169) were **FAILED** runs —
consistent with somebody tidying up after the 08:43 failure wave, which is exactly what
the button is for. **#168 was SUCCEEDED** and **#199 was SUCCEEDED**, and those two are
the ones worth explaining.

## Two adjacent findings, both real

**Every deploy restart aborts the JVM's shutdown hook.** `deploy.sh` rebuilds
`kanban-server-0.1.0.jar` on top of the file the running JVM has open, then SIGTERMs it.
The shutdown hook then needs classes it has not loaded yet, from a jar whose contents
have been replaced underneath it:

```
11:57:30.993 WARN  destroy method failed on bean 'entityManagerFactory':
             NoClassDefFoundError: .../SpringBeanContainer$SpringContainedBean
11:57:30.996 INFO  HikariPool-1 - Shutdown completed.
Exception in thread "SpringApplicationShutdownHook"
             NoClassDefFoundError: org/apache/catalina/Lifecycle$SingleUse
```

Both restarts in the current log — 08:11:14 and 11:57:30 — died this way, identically.
H2 therefore never gets an orderly close. This did **not** cause today's losses (I
checked row by row: the only rows missing across the 11:57 restart are #199 and its node
run, and a *later* write from 11:57:28 survived, which rules out a rollback of the store
tail). But it is precisely the scenario `scripts/env.sh` records as having destroyed the
whole database on 2026-08-29, and it is firing on every single deploy.

**`Workflow.updatedAt` does not move when you edit the steps.** `replace()` swaps the
node and edge rows wholesale but only assigns `description` and `entryNodeId` on the
workflow row itself, so if neither changed the row is not dirty and `@PreUpdate` never
runs. Proven: `AI Writing (copy)` reports `updatedAt == createdAt == 11:42:16` while its
graph went from one node to three. The list's timestamp is wrong, and it cost me an hour
— I briefly had good reason to believe runs were being created with steps missing.

## What would fix this

Roughly in order of how much they buy:

1. **Separate the two buttons.** *Cancel run* and *Delete run* must not occupy the same
   slot. Keep Delete in a fixed position of its own, or leave the slot empty for a beat
   after a run settles. This is the one that actually loses work.
2. **Log every deletion**, exactly as the docs controller does — the run's id, its
   workflow, its status, its cost. One line. It makes this class of question answerable.
3. **Name what is being destroyed in the confirm**: `Delete run #199 (Done, $0.11)? Its
   output cannot be recovered.`
4. **Scope the executions request.** Pass `workflowId` when the panel is in *This one*
   scope, and page the panel properly instead of silently truncating at fifty. Until
   then the delete-workflow warning must count runs from the server, not from a capped
   client list.
5. **Stop overwriting the jar under the running JVM** — build to a new path and swap
   after the old process is down.

None of these were implemented under this card; it was scoped to finding out where the
runs went.
