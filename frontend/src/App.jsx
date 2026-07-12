import { useEffect, useState, useCallback } from 'react'
import { api } from './api.js'

const STATUS_META = {
  PENDING: { label: 'Pending', className: 'badge pending' },
  IN_PROGRESS: { label: 'In progress', className: 'badge progress' },
  RESOLVED: { label: 'Resolved', className: 'badge resolved' },
  FAILED: { label: 'Failed', className: 'badge failed' },
}

export default function App() {
  const [todos, setTodos] = useState([])
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [worker, setWorker] = useState(null)
  const [error, setError] = useState(null)
  const [expanded, setExpanded] = useState(() => new Set())

  const refresh = useCallback(async () => {
    try {
      const [list, status] = await Promise.all([api.listTodos(), api.workerStatus()])
      setTodos(list)
      setWorker(status)
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }, [])

  // Poll so status changes from the Claude worker show up automatically.
  useEffect(() => {
    refresh()
    const t = setInterval(refresh, 4000)
    return () => clearInterval(t)
  }, [refresh])

  async function submit(e) {
    e.preventDefault()
    if (!title.trim()) return
    try {
      await api.createTodo(title.trim(), description.trim())
      setTitle('')
      setDescription('')
      await refresh()
    } catch (e) {
      setError(e.message)
    }
  }

  async function remove(id) {
    try {
      await api.deleteTodo(id)
      await refresh()
    } catch (e) {
      setError(e.message)
    }
  }

  function toggle(id) {
    setExpanded((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  return (
    <div className="app">
      <header>
        <h1>Claude Code Dashboard</h1>
        <p className="sub">
          Create a todo, and a scheduled Claude Code session will try to resolve it.
          {worker && (
            <span className={worker.enabled ? 'worker on' : 'worker off'}>
              {worker.enabled
                ? ` Worker on · polling every ${Math.round(worker.pollIntervalMs / 1000)}s`
                : ' Worker off'}
            </span>
          )}
        </p>
      </header>

      <form onSubmit={submit} className="create">
        <input
          type="text"
          placeholder="What needs doing?"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          maxLength={200}
        />
        <textarea
          placeholder="Details / context for Claude (optional)"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={3}
        />
        <button type="submit">Add todo</button>
      </form>

      {error && <div className="error">⚠ {error}</div>}

      <ul className="todos">
        {todos.length === 0 && <li className="empty">No todos yet. Add one above.</li>}
        {todos.map((t) => {
          const meta = STATUS_META[t.status] ?? STATUS_META.PENDING
          const open = expanded.has(t.id)
          return (
            <li key={t.id} className="todo">
              <div className="row">
                <span className={meta.className}>{meta.label}</span>
                <span className="todo-title">{t.title}</span>
                <div className="spacer" />
                {t.result && (
                  <button className="link" onClick={() => toggle(t.id)}>
                    {open ? 'Hide result' : 'Show result'}
                  </button>
                )}
                <button className="link danger" onClick={() => remove(t.id)}>
                  Delete
                </button>
              </div>
              {t.description && <div className="desc">{t.description}</div>}
              {open && t.result && <pre className="result">{t.result}</pre>}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
