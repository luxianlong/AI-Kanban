# Ultracode and the workflow Effort picker

**Short answer.** Ultracode is missing from the picker because the picker was built from
`claude --help`, and `--help` under-documents the flag: it prints
`--effort <level> (low, medium, high, xhigh, max)` and does not mention ultracode. But
**`--effort ultracode` is accepted by the CLI** — I measured it. So the option is absent
by mistake, not by design, and putting it back is one enum constant plus one array entry.

Until that ships, you can get most of the way there today with **no code change and no
deploy**: set the step's Effort to `xhigh` and put the word `ultracode` in the step's
prompt.

---

## 1. What ultracode actually is

It is **not** a sixth effort level. The CLI's own settings schema describes it as:

> Enable ultracode for the session: **xhigh effort plus standing dynamic-workflow
> orchestration**. Session-scoped — typically provided via `--settings` or the
> `apply_flag_settings` control.

Two things bundled together:

1. **xhigh effort** — the level the picker already offers.
2. **Standing dynamic-workflow orchestration** — a standing instruction to author and run
   a multi-agent Workflow for every substantive task by default. Without ultracode, the
   Workflow tool is present but opt-in; with it, the opt-in is permanent for the session.

That second half is why ultracode is a *setting* and not a level, and why the `--effort`
help text never grew a sixth entry.

## 2. Why the picker has five entries

Two independent reasons, and both are worth knowing:

- **`workflowDraft.js:13-16`** says the list is "the same one `EffortLevel.java` accepts",
  and `EffortLevel`'s javadoc says it is "the CLI's `--effort` levels". When that card
  (#277) was verified, the check performed was `claude --help` → five values → picker has
  five values → match. The check was done correctly against a source that is incomplete.
- **Ultracode was in this app and was deliberately removed.** Card #275 gave the *entry*
  step `--settings '{"ultracode":true}'`; card #279 reverted it (commit `afadbb2`, "Run
  the first step like every other step"), on the reasoning that what a session gets should
  be decided by what the author wrote for that step, not by its position in the graph.

Note that #279's reasoning is an argument **for** a per-step ultracode option, not against
one. It objected to ultracode being applied automatically to whichever step happened to be
first. A value in the Effort picker is the opposite: the author chooses it, per step.

## 3. The measurement

Four sessions, identical but for the flag, `--output-format json`, each asked to report
whether a system-reminder says ultracode is on. `cache_creation_input_tokens` is the
independent corroboration — turning ultracode on injects the standing orchestration
instructions, and that shows up as tokens whether the model self-reports honestly or not.

| invocation | self-report | cache_creation tokens | stderr |
|---|---|---|---|
| `--effort xhigh` | `ULTRA_OFF` | 9,145 | — |
| **`--effort ultracode`** | **`ULTRA_ON`** | **15,413** | — |
| `--settings '{"ultracode":true}'` | `ULTRA_ON` | 15,413 | — |
| `--effort banana` | `ULTRA_OFF` | 9,144 | `Warning: Unknown --effort value 'banana' — ignoring it and using the default effort. Valid values: low, medium, high, xhigh, max.` |

Three things this pins down:

- **`--effort ultracode` is a real accepted value**, not silently ignored. The `banana`
  row is the control: an unknown value takes the warning path and falls back to the
  default. `ultracode` takes neither.
- **It is byte-for-byte the same session as the settings key** — 15,413 tokens both ways.
  So `--effort ultracode` is not an approximation of card #275's mechanism; it is that
  mechanism, reached through a flag instead of a JSON blob.
- **`--help` is wrong**, or at least incomplete — and so is the warning text, which lists
  five values on a CLI that accepts six.

I also ran it in the exact shape a workflow node uses, with the read fence alongside:

```
claude --settings '{"permissions":{"blockReadsOutsideWorkingDirectories":true}}' \
       --effort ultracode -p '...'
```

→ `ULTRA_ON`, 15,388 tokens. **The two do not collide.** This matters: `--settings` takes
a single value and a second occurrence replaces the first, which is why card #276 had to
merge the fence and ultracode into one blob. Going through `--effort` sidesteps that
entirely — the fence keeps the settings blob to itself.

## 4. How to enable it today, without touching the app

**Set the step's Effort to `xhigh`, and include the word `ultracode` in the step's prompt.**

Both halves are pure user data — a `<select>` and a textarea on the workflow editor page.
No branch, no build, no restart.

The keyword is a documented CLI trigger: including `ultracode` in a prompt opts that turn
into the Workflow tool. Measured:

| invocation | ultracode reminder | Workflow opt-in | cache_creation |
|---|---|---|---|
| plain prompt | no | no | 9,127 |
| `ultracode` in prompt | no | **yes** | 9,133 |
| `ultracode` in prompt + `--effort xhigh` | no | **yes** | 9,177 |
| `--effort ultracode` | **yes** | **yes** | 15,442 |

**Be clear about what this does and does not buy you.** The keyword gets you the Workflow
opt-in for that turn — which, combined with `xhigh`, is the part most people actually want.
It does **not** install the standing orchestration directive: the ~6,300-token difference
in the last row is real and is not reproduced by the keyword. Under the keyword the step
*may* use a workflow; under real ultracode it is told to use one by default.

There is also a global route — adding `--effort ultracode` to `worker.extra-args` in
`application.yml`. **Don't.** That applies to every board task and every workflow node at
once, which is exactly the "one effort for the whole graph" that per-node effort existed
to fix, and it costs a restart.

## 5. The actual fix, when someone takes the card

Smaller than it looks, because `EffortLevel` derives the CLI spelling from the constant
name and `--effort ultracode` is what the CLI wants:

- **`EffortLevel.java`** — add `ULTRACODE` after `MAX`. `cliValue()` yields `"ultracode"`,
  `parse()` accepts it, `allowed()` lists it, and `sessionArgsFor` emits
  `--effort ultracode`. Nothing else on the backend changes.
- **`workflowDraft.js:16`** — add `'ultracode'` to `EFFORT_LEVELS`. The picker renders it.
- **Exactly one test breaks**: `WorkflowRunnerServiceTest:322`, which asserts
  `allowed()` `isEqualTo("low, medium, high, xhigh, max")`. The other two assertions on
  that string (`WorkflowApiTest:924`, `WorkflowRunnerServiceTest:368`) use
  `containsString` / `hasMessageContaining` and still pass, since the old list is a prefix
  of the new one. The loop test at `:309` covers the new constant for free.

Three things worth pinning while making the change:

- **A test that the flag is really accepted.** The whole reason the option is missing is
  that `--help` was trusted. The class's existing comment already warns that a wrong
  spelling is "ignored silently" — that failure mode is exactly what let this through, and
  it deserves a check against the CLI rather than against the help text.
- **Ultracode is not universally available.** The CLI gates it — "not available for this
  session (dynamic workflows are off, or the model / your plan…)" — and on a session where
  it is unavailable, `--effort ultracode` is rejected as an invalid argument rather than
  downgraded. It worked here on the default model; it will not work everywhere. A node set
  to `ultracode` should fail loudly and legibly, not mysteriously.
- **Say what it costs in the UI.** The other five levels change how hard one session
  thinks. This one also changes *how many sessions there are* — sub-agents are in-process,
  so it is paid in tokens and wall-clock rather than in the ~270 MB per extra process
  measured in the parallel-workers analysis. Still, it is not "one notch above `max`", and
  a picker that lists it sixth in a row of five will read as if it is.

## 6. If you like workflows

Two different things share the name and it is easy to conflate them:

- **This board's workflows** — the DAG of steps on the Workflows page. Each step is one
  Claude session, wired by edges, with its own prompt, effort level and scratch directory.
- **The CLI's dynamic workflows** — what ultracode turns on *inside* a single step: that
  one session spawns a fan-out of sub-agents to answer its own prompt.

They compose rather than compete. Setting a step to ultracode means that step becomes a
small multi-agent orchestration of its own, inside one node of your graph. The natural
place for it is a step that has to go find and reconcile material — not a step handed a
draft to tighten, which has nothing to fan out over.
