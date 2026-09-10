// Thin wrapper over the backend REST API. In dev, Vite proxies /api to :8080.
const BASE = '/api'

async function json(res) {
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`${res.status} ${res.statusText} ${text}`.trim())
  }
  return res.status === 204 ? null : res.json()
}

export const api = {
  listTodos: () => fetch(`${BASE}/todos`).then(json),

  // `projectImprovement` marks the task as one that improves the Kanban project
  // itself; it defaults to false (a general task) when omitted. `attachmentIds`
  // are ids from uploadAttachment — images the new task claims as agent input.
  // `delegated` marks a 代理 task: the worker never runs it; it is published on
  // the public /api/public/tasks feed for an outside worker to do and report on.
  createTodo: (title, description, projectImprovement = false, attachmentIds = [], delegated = false) =>
    fetch(`${BASE}/todos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, description, projectImprovement, attachmentIds, delegated }),
    }).then(json),

  // Upload one image for a task-to-be. The file is stored server-side right away
  // (so the composer can show a thumbnail); the returned id is passed to
  // createTodo, which binds it to the task the agent then receives.
  uploadAttachment: (file) => {
    const form = new FormData()
    form.append('file', file)
    return fetch(`${BASE}/attachments`, { method: 'POST', body: form }).then(json)
  },

  // Drop an uploaded image again (removed in the composer, or no longer wanted).
  deleteAttachment: (id) =>
    fetch(`${BASE}/attachments/${id}`, { method: 'DELETE' }).then(json),

  // Declare a story — just a named category for tasks. It creates NO task, so the
  // new story lands on the board empty; tasks join it afterwards (auto-classified
  // on creation, or filed by hand from the drawer). Returns the created story.
  createStory: (name) =>
    fetch(`${BASE}/stories`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    }).then(json),

  // List every story with its ordered steps, newest chain first. Includes stories
  // that currently have NO tasks (their container persists) so an empty story
  // still shows on the board.
  listStories: () => fetch(`${BASE}/stories`).then(json),

  // Delete a story and all of its tasks. This is the explicit, user-driven way to
  // remove a story (an empty one won't disappear on its own).
  deleteStory: (storyId) =>
    fetch(`${BASE}/stories/${storyId}`, { method: 'DELETE' }).then(json),

  // Ask the server to decompose a free-text goal into ordered steps. Returns
  // { name, steps: [{ title, description }] } — ready to tweak and submit.
  suggestStory: (goal) =>
    fetch(`${BASE}/stories/suggest`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ goal }),
    }).then(json),

  // Move a task to a given status (generic PATCH helper). The worker drives most
  // transitions; see rerunTodo for running a finished task again.
  updateStatus: (id, status) =>
    fetch(`${BASE}/todos/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    }).then(json),

  // Manually file a task under a story, or unassign it (storyId = null). Appends
  // the task to the end of the chosen story's chain; status is left unchanged.
  setStory: (id, storyId) =>
    fetch(`${BASE}/todos/${id}/story`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ storyId: storyId || null }),
    }).then(json),

  deleteTodo: (id) =>
    fetch(`${BASE}/todos/${id}`, { method: 'DELETE' }).then(json),

  // Stop the running Claude session for an in-progress task (user-requested). The
  // backend kills the live process and the worker records the task as FAILED
  // ("Stopped by user request"), from where it can be requeued. Only valid while
  // the task is actually running — otherwise the server responds 409.
  stopTodo: (id) =>
    fetch(`${BASE}/todos/${id}/stop`, { method: 'POST' }).then(json),

  // Run a finished (FAILED/RESOLVED) task again as a *copy*: the server queues a
  // new PENDING task built from this one and leaves the original — and its
  // result — alone, so the previous attempt stays readable. title/description are
  // the user's edits from the re-run dialog; omit either to keep the original's.
  rerunTodo: (id, { title, description } = {}) =>
    fetch(`${BASE}/todos/${id}/rerun`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, description }),
    }).then(json),

  // Clear a finished task's unread-notification flag (server-side, so the badge
  // stays in sync across every device). Called when its detail is opened.
  markTodoRead: (id) =>
    fetch(`${BASE}/todos/${id}/read`, { method: 'POST' }).then(json),

  // Clear every unread finished-task notification at once ("mark all read").
  markAllTodosRead: () =>
    fetch(`${BASE}/todos/read-all`, { method: 'POST' }).then(json),

  todoLog: (id) => fetch(`${BASE}/todos/${id}/log`).then(json),

  workerStatus: () => fetch(`${BASE}/worker/status`).then(json),

  // --- Workflows: a DAG of prompt tasks, and the runs it produces ---------
  // Each node of the graph runs as its own fresh Claude session; the only thing
  // that crosses an edge is the upstream node's output text. See Workflows.jsx.

  listWorkflows: () => fetch(`${BASE}/workflows`).then(json),

  getWorkflow: (id) => fetch(`${BASE}/workflows/${id}`).then(json),

  // Create a workflow from a whole graph. `nodes` are { id, name, prompt, x, y }
  // with editor-chosen ids, `edges` are { from, to } referencing those ids, and
  // `entryNodeId` may be omitted when exactly one node has no incoming edge.
  createWorkflow: (graph) =>
    fetch(`${BASE}/workflows`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(graph),
    }).then(json),

  // Replace a workflow's name, description and whole graph. The editor holds the
  // working copy and saves it wholesale, so there is no partial-update path.
  saveWorkflow: (id, graph) =>
    fetch(`${BASE}/workflows/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(graph),
    }).then(json),

  // Copy a workflow's graph into a new one, named "<name> (copy)". The copy is
  // made server-side from the stored rows — not from the editor's draft — so it
  // carries fields the editor doesn't round-trip, and it starts with no runs.
  // Returns the copy.
  duplicateWorkflow: (id) =>
    fetch(`${BASE}/workflows/${id}/duplicate`, { method: 'POST' }).then(json),

  // Delete a workflow, its graph and its run history.
  deleteWorkflow: (id) =>
    fetch(`${BASE}/workflows/${id}`, { method: 'DELETE' }).then(json),

  // Queue a run. `input` is optional starting text handed to the entry node —
  // what lets one workflow be re-run against a different subject.
  runWorkflow: (id, input) =>
    fetch(`${BASE}/workflows/${id}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ input }),
    }).then(json),

  // Runs of every workflow, newest first — or of one, with a workflowId.
  listExecutions: (workflowId) =>
    fetch(`${BASE}/workflows/executions${workflowId ? `?workflowId=${encodeURIComponent(workflowId)}` : ''}`)
      .then(json),

  // How many runs that scope really holds, and where the list above stops. The list
  // is capped, so its length is not an answer to "how many runs would deleting this
  // destroy?" — and that question is asked immediately before destroying them.
  countExecutions: (workflowId) =>
    fetch(`${BASE}/workflows/executions/count${workflowId ? `?workflowId=${encodeURIComponent(workflowId)}` : ''}`)
      .then(json),

  // Stop a run in flight: kills the live node session and closes the run out.
  cancelExecution: (executionId) =>
    fetch(`${BASE}/workflows/executions/${executionId}/cancel`, { method: 'POST' }).then(json),

  // Queue a finished run again from the step that broke. The steps that succeeded
  // keep their output, so this pays only for the failure and what was blocked
  // behind it — not for the whole graph a second time. 409 if the run is still
  // going, or if there is nothing in it left to retry.
  retryExecution: (executionId) =>
    fetch(`${BASE}/workflows/executions/${executionId}/retry`, { method: 'POST' }).then(json),

  // Run one step of a finished run again — including one that succeeded. By default
  // every step downstream of it goes too, whose input that step's output is; pass
  // `{ downstream: false }` to redo just that step and leave the rest of the run with
  // what it produced. The steps above it keep their output either way. Returns the
  // whole run, since re-queueing it changes its state too. 409 if the run is still
  // going, if the entry never reached that step, or — single-step only — if what it
  // reads from didn't succeed, so there is no input to hand it.
  retryNodeRun: (nodeRunId, { downstream = true } = {}) =>
    fetch(`${BASE}/workflows/node-runs/${nodeRunId}/retry?downstream=${downstream}`, {
      method: 'POST',
    }).then(json),

  // Rewrite one step's prompt inside one finished run. The workflow keeps its own
  // text — this is the run's private copy, which is what makes trying a wording cost
  // one session (edit here, then retryNodeRun) instead of a whole graph. Returns the
  // whole run. 409 while the run is live, or on a step the entry never reached.
  editNodeRunPrompt: (nodeRunId, prompt) =>
    fetch(`${BASE}/workflows/node-runs/${nodeRunId}/prompt`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt }),
    }).then(json),

  // Rewrite ALL of one step's settings inside one finished run — name, prompt, effort
  // and which of the run's steps feed it. The wider form of the call above, and what
  // makes a run debuggable rather than only re-wordable: "was this worth ultracode"
  // and "should it have read step two as well" are not changes of wording. The whole
  // settings object goes every time, the same wholesale-replace the graph editor does.
  // Returns the whole run — rewiring renumbers the steps around it. 409 while the run
  // is live or on a step the entry never reached; 400 for wiring that would loop,
  // dangle, or cut the step off from where the run started.
  editNodeRun: (nodeRunId, settings) =>
    fetch(`${BASE}/workflows/node-runs/${nodeRunId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings),
    }).then(json),

  // Add a step to a finished run that the workflow never had — the debugging move the
  // two calls above can't make, when the answer to "why did it come out like that" is
  // a step that is MISSING. It lands queued and starts nothing; the step's own re-run
  // button is what spends a session. `inputs` must name at least one step in the run.
  insertNodeRun: (executionId, step) =>
    fetch(`${BASE}/workflows/executions/${executionId}/node-runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(step),
    }).then(json),

  // Take a step out of a run, splicing the chain shut behind it: whatever read the
  // removed step now reads what it read. Its output goes with it, so ask first.
  // Returns the run rather than nothing, since the steps around it are rewired.
  deleteNodeRun: (nodeRunId) =>
    fetch(`${BASE}/workflows/node-runs/${nodeRunId}`, { method: 'DELETE' }).then(json),

  deleteExecution: (executionId) =>
    fetch(`${BASE}/workflows/executions/${executionId}`, { method: 'DELETE' }).then(json),

  // --- Favourite steps: a shelf of steps to reuse -------------------------
  // A step kept aside (name + prompt + effort), inserted into any workflow as a new
  // node. Insertion is a COPY made in the editor, so there is no "insert" call here:
  // the graph is saved the way it always is, and the two go their separate ways.

  listFavoriteSteps: () => fetch(`${BASE}/workflows/favorites`).then(json),

  // Keep a step for later. Saving under a name already on the shelf REPLACES that
  // favourite (case-insensitively) rather than adding a second one the picker can't
  // tell apart — so ask the user first; the server just does as it's told.
  saveFavoriteStep: (step) =>
    fetch(`${BASE}/workflows/favorites`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(step),
    }).then(json),

  // Take a step off the shelf. Workflows built from it keep their own copy.
  deleteFavoriteStep: (id) =>
    fetch(`${BASE}/workflows/favorites/${id}`, { method: 'DELETE' }).then(json),

  // Live transcript of one node's session — poll it while that node runs.
  nodeRunLog: (nodeRunId) => fetch(`${BASE}/workflows/node-runs/${nodeRunId}/log`).then(json),

  // The manifest ids of docs the user has already opened. Diffed against the docs
  // manifest to size the "new docs" badge — server-side so it's the same on every
  // device the user browses from. A doc is marked read by the static docs page
  // itself when the user actually opens it (POST /api/docs/reads from
  // scripts/build-docs-site.mjs), not by the board SPA — the board only reads.
  listDocReads: () => fetch(`${BASE}/docs/reads`).then(json),
}
