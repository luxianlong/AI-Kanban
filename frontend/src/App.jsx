import { useEffect, useState, useCallback, useRef } from 'react'
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
  const [logs, setLogs] = useState({}) // id -> transcript text

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

  // Poll the todo list so status changes from the worker appear automatically.
  useEffect(() => {
    refresh()
    const t = setInterval(refresh, 4000)
    return () => clearInterval(t)
  }, [refresh])

  const fetchLog = useCallback(async (id) => {
    try {
      const { log } = await api.todoLog(id)
      setLogs((prev) => ({ ...prev, [id]: log }))
    } catch {
      /* transient — keep last log */
    }
  }, [])

  // Fast-poll the live log for any expanded, in-progress todo.
  const expandedRef = useRef(expanded)
  const todosRef = useRef(todos)
  expandedRef.current = expanded
  todosRef.current = todos
  useEffect(() => {
    const t = setInterval(() => {
      for (const id of expandedRef.current) {
        const todo = todosRef.current.find((x) => x.id === id)
        if (todo && todo.status === 'IN_PROGRESS') fetchLog(id)
      }
    }, 1500)
    return () => clearInterval(t)
  }, [fetchLog])

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

  async function remove(id, e) {
    e.stopPropagation()
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
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
        fetchLog(id) // load immediately on open
      }
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
          const running = t.status === 'IN_PROGRESS'
          const liveLog = logs[t.id]
          return (
            <li key={t.id} className={`todo${open ? ' open' : ''}`}>
              <div className="row" onClick={() => toggle(t.id)}>
                <span className={meta.className}>{meta.label}</span>
                <span className="todo-title">{t.title}</span>
                <div className="spacer" />
                <span className="chevron">{open ? '▾' : '▸'}</span>
                <button className="link danger" onClick={(e) => remove(t.id, e)}>
                  Delete
                </button>
              </div>

              {open && (
                <div className="detail">
                  {t.description && <div className="desc">{t.description}</div>}

                  {running && (
                    <div className="logblock">
                      <div className="loglabel">
                        <span className="pulse" /> Live session log
                      </div>
                      <LogView text={liveLog} autoscroll placeholder="Waiting for the session to start…" />
                    </div>
                  )}

                  {!running && (t.result || liveLog) && (
                    <div className="logblock">
                      <div className="loglabel">{t.status === 'FAILED' ? 'Failure detail' : 'Result'}</div>
                      <pre className="result">{t.result || '(no result)'}</pre>
                      {liveLog && (
                        <details className="transcript">
                          <summary>Full session log</summary>
                          <LogView text={liveLog} />
                        </details>
                      )}
                    </div>
                  )}

                  {!running && !t.result && !liveLog && (
                    <div className="desc muted">No session output yet.</div>
                  )}
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function LogView({ text, autoscroll = false, placeholder = '' }) {
  const ref = useRef(null)
  useEffect(() => {
    if (autoscroll && ref.current) {
      ref.current.scrollTop = ref.current.scrollHeight
    }
  }, [text, autoscroll])
  return (
    <pre className="result log" ref={ref}>
      {text && text.length ? text : placeholder}
    </pre>
  )
}
