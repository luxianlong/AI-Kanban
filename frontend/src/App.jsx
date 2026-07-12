import { useEffect, useState, useCallback, useRef } from 'react'
import { api } from './api.js'

// The board's columns, in flow order. The worker moves a card left→right by
// updating its status, so the board mirrors the task's real lifecycle.
const COLUMNS = [
  { status: 'PENDING', title: 'To Do', hint: 'No tasks queued' },
  { status: 'IN_PROGRESS', title: 'In Progress', hint: 'Nothing running right now' },
  { status: 'RESOLVED', title: 'Done', hint: 'Completed tasks land here' },
  { status: 'FAILED', title: 'Needs Attention', hint: 'Nothing needs attention' },
]

export default function App() {
  const [todos, setTodos] = useState([])
  const [worker, setWorker] = useState(null)
  const [error, setError] = useState(null)
  const [logs, setLogs] = useState({}) // id -> transcript text (in-progress + open card)
  const [selectedId, setSelectedId] = useState(null)

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
      /* transient — keep last */
    }
  }, [])

  // Keep live logs flowing for every in-progress card (powers the on-card
  // ticker) plus whichever card is open in the drawer.
  const todosRef = useRef(todos)
  const selectedRef = useRef(selectedId)
  todosRef.current = todos
  selectedRef.current = selectedId
  useEffect(() => {
    const tick = () => {
      const ids = new Set(
        todosRef.current.filter((t) => t.status === 'IN_PROGRESS').map((t) => t.id),
      )
      if (selectedRef.current != null) ids.add(selectedRef.current)
      ids.forEach(fetchLog)
    }
    tick()
    const t = setInterval(tick, 1500)
    return () => clearInterval(t)
  }, [fetchLog])

  async function createTodo(title, description) {
    await api.createTodo(title, description)
    await refresh()
  }

  async function removeTodo(id) {
    try {
      await api.deleteTodo(id)
      if (selectedId === id) setSelectedId(null)
      await refresh()
    } catch (e) {
      setError(e.message)
    }
  }

  const byStatus = (s) => todos.filter((t) => t.status === s)
  const selected = todos.find((t) => t.id === selectedId) || null

  return (
    <div className="page">
      <Topbar worker={worker} total={todos.length} />

      {error && <div className="error">⚠ {error}</div>}

      <div className="board">
        {COLUMNS.map((col) => (
          <Column
            key={col.status}
            col={col}
            todos={byStatus(col.status)}
            logs={logs}
            onOpen={setSelectedId}
            onCreate={col.status === 'PENDING' ? createTodo : null}
          />
        ))}
      </div>

      {selected && (
        <Drawer
          todo={selected}
          log={logs[selected.id]}
          onClose={() => setSelectedId(null)}
          onDelete={() => removeTodo(selected.id)}
        />
      )}
    </div>
  )
}

function Topbar({ worker, total }) {
  return (
    <header className="topbar">
      <div className="brand">
        <span className="mark" aria-hidden />
        <div>
          <h1>Dashboard</h1>
          <p className="tagline">Queue a task — a Claude Code agent picks it up and works it.</p>
        </div>
      </div>
      <div className="status">
        {worker && (
          <span className={`worker-pill ${worker.enabled ? 'on' : 'off'}`}>
            <span className="dot" />
            {worker.enabled
              ? `Agent online · checks every ${Math.round(worker.pollIntervalMs / 1000)}s`
              : 'Agent paused'}
          </span>
        )}
        <span className="count-pill">{total} {total === 1 ? 'task' : 'tasks'}</span>
      </div>
    </header>
  )
}

function Column({ col, todos, logs, onOpen, onCreate }) {
  return (
    <section className={`column ${col.status.toLowerCase()}`}>
      <div className="col-head">
        <span className="col-accent" />
        <h2>{col.title}</h2>
        <span className="col-count">{todos.length}</span>
      </div>

      {onCreate && <Composer onCreate={onCreate} />}

      <div className="col-body">
        {todos.length === 0 && !onCreate && <p className="col-empty">{col.hint}</p>}
        {todos.map((t) => (
          <Card key={t.id} todo={t} tail={tailLine(logs[t.id])} onOpen={() => onOpen(t.id)} />
        ))}
      </div>
    </section>
  )
}

function Composer({ onCreate }) {
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    const t = title.trim()
    if (!t || busy) return
    setBusy(true)
    try {
      await onCreate(t, desc.trim())
      setTitle('')
      setDesc('')
      setOpen(false)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="composer" onSubmit={submit}>
      <input
        className="composer-title"
        placeholder="Add a task…"
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        onFocus={() => setOpen(true)}
        maxLength={200}
      />
      {open && (
        <>
          <textarea
            className="composer-desc"
            placeholder="Details / context for the agent (optional)"
            value={desc}
            onChange={(e) => setDesc(e.target.value)}
            rows={3}
          />
          <div className="composer-actions">
            <button type="submit" className="btn primary" disabled={!title.trim() || busy}>
              {busy ? 'Adding…' : 'Add task'}
            </button>
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                setOpen(false)
                setDesc('')
              }}
            >
              Cancel
            </button>
          </div>
        </>
      )}
    </form>
  )
}

function Card({ todo, tail, onOpen }) {
  const running = todo.status === 'IN_PROGRESS'
  return (
    <button className={`card ${todo.status.toLowerCase()}`} onClick={onOpen}>
      <div className="card-title">{todo.title}</div>
      {todo.description && !running && <div className="card-desc">{todo.description}</div>}

      {running && (
        <div className="ticker">
          <span className="ticker-pulse" />
          <span className="ticker-text">{tail || 'Starting session…'}</span>
        </div>
      )}

      {todo.status === 'RESOLVED' && todo.result && (
        <div className="card-result">{firstLines(todo.result, 2)}</div>
      )}
      {todo.status === 'FAILED' && (
        <div className="card-fail">{firstLines(todo.result, 2) || 'Failed'}</div>
      )}

      <div className="card-foot">
        <span className="card-id">#{todo.id}</span>
        <span className="card-time">{relTime(todo.updatedAt)}</span>
      </div>
    </button>
  )
}

function Drawer({ todo, log, onClose, onDelete }) {
  const running = todo.status === 'IN_PROGRESS'
  const logRef = useRef(null)
  useEffect(() => {
    if (running && logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [log, running])

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Task detail">
        <div className="drawer-head">
          <span className={`badge ${todo.status.toLowerCase()}`}>{label(todo.status)}</span>
          <button className="icon-btn" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <h2 className="drawer-title">{todo.title}</h2>
        <div className="drawer-meta">
          #{todo.id} · created {relTime(todo.createdAt)} · updated {relTime(todo.updatedAt)}
        </div>

        {todo.description && (
          <div className="drawer-section">
            <div className="section-label">Details</div>
            <p className="drawer-desc">{todo.description}</p>
          </div>
        )}

        {running && (
          <div className="drawer-section grow">
            <div className="section-label live">
              <span className="ticker-pulse" /> Live session
            </div>
            <pre className="logbox" ref={logRef}>
              {log && log.length ? log : 'Waiting for the session to start…'}
            </pre>
          </div>
        )}

        {!running && (todo.result || log) && (
          <div className="drawer-section grow">
            <div className="section-label">{todo.status === 'FAILED' ? 'Failure detail' : 'Result'}</div>
            <pre className="logbox result">{todo.result || '(no result)'}</pre>
            {log && (
              <details className="transcript">
                <summary>Full session log</summary>
                <pre className="logbox">{log}</pre>
              </details>
            )}
          </div>
        )}

        {!running && !todo.result && !log && (
          <p className="drawer-desc muted">No session output yet.</p>
        )}

        <div className="drawer-foot">
          <button className="btn danger-ghost" onClick={onDelete}>Delete task</button>
        </div>
      </aside>
    </>
  )
}

// --- helpers ---

function tailLine(log) {
  if (!log) return ''
  const lines = log.split('\n').map((l) => l.trim()).filter(Boolean)
  return lines.length ? lines[lines.length - 1] : ''
}

function firstLines(text, n) {
  if (!text) return ''
  return text.split('\n').filter((l) => l.trim()).slice(0, n).join(' ').slice(0, 160)
}

function label(status) {
  return (
    {
      PENDING: 'To Do',
      IN_PROGRESS: 'In Progress',
      RESOLVED: 'Done',
      FAILED: 'Needs Attention',
    }[status] || status
  )
}

function relTime(iso) {
  if (!iso) return ''
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`
  return `${Math.floor(diff / 86400)}d ago`
}
