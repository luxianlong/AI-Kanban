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

  // Create a story: a named, ordered chain of tasks the worker runs one after
  // another. `steps` is [{ title, description }]. Returns the created todos.
  createStory: (name, steps) =>
    fetch(`${BASE}/stories`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, steps }),
    }).then(json),

  // Ask the server to decompose a free-text goal into ordered steps. Returns
  // { name, steps: [{ title, description }] } — ready to tweak and submit.
  suggestStory: (goal) =>
    fetch(`${BASE}/stories/suggest`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ goal }),
    }).then(json),

  // Existing stories that `text` looks related to, most relevant first. Used to
  // offer attaching a new task to a story instead of starting a fresh chain.
  relatedStories: (text) =>
    fetch(`${BASE}/stories/related?text=${encodeURIComponent(text)}`).then(json),

  // Append a task as the next step of an existing story.
  addStoryStep: (storyId, title, description = '') =>
    fetch(`${BASE}/stories/${storyId}/steps`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, description }),
    }).then(json),

  // Move a task to a given status. Used to promote a NEW task to the To Do queue
  // (status='PENDING') via the card button or a drag into the To Do column.
  updateStatus: (id, status) =>
    fetch(`${BASE}/todos/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
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

  todoLog: (id) => fetch(`${BASE}/todos/${id}/log`).then(json),

  workerStatus: () => fetch(`${BASE}/worker/status`).then(json),
}
