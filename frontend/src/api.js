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

  createTodo: (title, description) =>
    fetch(`${BASE}/todos`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, description }),
    }).then(json),

  // Create a story: a named, ordered chain of tasks the worker runs one after
  // another. `steps` is [{ title, description }]. Returns the created todos.
  createStory: (name, steps) =>
    fetch(`${BASE}/stories`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, steps }),
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
