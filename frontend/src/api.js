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
  // itself; it defaults to false (a general task) when omitted.
  createTodo: (title, description, projectImprovement = false) =>
    fetch(`${BASE}/todos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, description, projectImprovement }),
    }).then(json),

  // Create a story from a single one-line goal (plus optional description). The
  // server files one planner task; the worker decomposes the goal into step tasks
  // when it processes it. Returns the created planner todo.
  createStory: (name, description = '') =>
    fetch(`${BASE}/stories`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description }),
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
  // transitions; see retryTodo for requeuing a finished task.
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

  // Requeue a finished (FAILED/RESOLVED) task: flip status back to PENDING so
  // the worker re-picks it on the next poll. Clears the old result by default
  // for a clean slate; pass keepResult=true to preserve the prior output.
  retryTodo: (id, { keepResult = false } = {}) =>
    fetch(`${BASE}/todos/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(keepResult ? { status: 'PENDING' } : { status: 'PENDING', result: '' }),
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

  // The manifest ids of docs the user has already opened. Diffed against the docs
  // manifest to size the "new docs" badge — server-side so it's the same on every
  // device the user browses from. A doc is marked read by the static docs page
  // itself when the user actually opens it (POST /api/docs/reads from
  // scripts/build-docs-site.mjs), not by the board SPA — the board only reads.
  listDocReads: () => fetch(`${BASE}/docs/reads`).then(json),
}
