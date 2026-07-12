import { useEffect, useState, useCallback, useRef } from 'react'
import { api } from './api.js'
import Markdown from './Markdown.jsx'

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
  const [notify, setNotify] = useState(() => notifyPref())
  const [toasts, setToasts] = useState([]) // in-app notifications (always shown)

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

  // Notify whenever a task crosses into a terminal state (Done / Needs
  // Attention). We remember each task's last-seen status; the very first poll
  // only seeds that map (so we never notify for tasks that were already
  // finished when the page loaded) and thereafter we notify on the transition
  // edge only. Two channels fire together:
  //   • an in-app toast — ALWAYS, so something visibly pops up even when native
  //     desktop notifications are muted, blocked, or OS Do-Not-Disturb is on;
  //   • a native desktop notification — only when the `notify` toggle is on and
  //     browser permission was granted (checked live via a ref).
  const notifyRef = useRef(notify)
  notifyRef.current = notify
  const toastSeq = useRef(0)
  const pushToast = useCallback((t) => {
    const key = ++toastSeq.current
    setToasts((cur) => [...cur, { key, id: t.id, status: t.status, title: t.title }])
    setTimeout(() => setToasts((cur) => cur.filter((x) => x.key !== key)), 7000)
  }, [])
  const dismissToast = useCallback((key) => {
    setToasts((cur) => cur.filter((x) => x.key !== key))
  }, [])
  const prevStatusRef = useRef(null)
  useEffect(() => {
    const prev = prevStatusRef.current
    if (prev) {
      for (const t of todos) {
        const before = prev.get(t.id)
        const terminal = t.status === 'RESOLVED' || t.status === 'FAILED'
        if (terminal && before && before !== t.status) {
          pushToast(t)
          playChime(t.status)
          if (notifyRef.current) notifyTaskDone(t)
        }
      }
    }
    prevStatusRef.current = new Map(todos.map((t) => [t.id, t.status]))
  }, [todos, pushToast])

  async function toggleNotify() {
    if (notify) {
      setNotify(false)
      setNotifyPref(false)
      return
    }
    if (typeof Notification === 'undefined') {
      setError('This browser does not support notifications.')
      return
    }
    let perm = Notification.permission
    if (perm === 'default') perm = await Notification.requestPermission()
    if (perm === 'granted') {
      setNotify(true)
      setNotifyPref(true)
    } else {
      setError('Notifications are blocked — enable them in your browser settings.')
    }
  }

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

  async function createStory(name, steps) {
    await api.createStory(name, steps)
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

  async function retryTodo(id) {
    try {
      await api.retryTodo(id)
      await refresh()
    } catch (e) {
      setError(e.message)
    }
  }

  const byStatus = (s) => todos.filter((t) => t.status === s)
  const selected = todos.find((t) => t.id === selectedId) || null
  const storyMeta = computeStoryMeta(todos)

  return (
    <div className="page">
      <Topbar worker={worker} total={todos.length} notify={notify} onToggleNotify={toggleNotify} />

      {error && <div className="error">⚠ {error}</div>}

      <div className="board">
        {COLUMNS.map((col) => {
          // Hide the "Needs Attention" column entirely when nothing has failed —
          // an empty terminal-error column is just noise on a healthy board.
          if (col.status === 'FAILED' && byStatus('FAILED').length === 0) return null
          return (
          <Column
            key={col.status}
            col={col}
            todos={byStatus(col.status)}
            logs={logs}
            storyMeta={storyMeta}
            onOpen={setSelectedId}
            onCreate={col.status === 'PENDING' ? createTodo : null}
            onCreateStory={col.status === 'PENDING' ? createStory : null}
          />
          )
        })}
      </div>

      {selected && (
        <Drawer
          todo={selected}
          log={logs[selected.id]}
          story={storyMeta[selected.id]}
          onClose={() => setSelectedId(null)}
          onDelete={() => removeTodo(selected.id)}
          onRetry={() => retryTodo(selected.id)}
        />
      )}

      <Toasts items={toasts} onDismiss={dismissToast} />
    </div>
  )
}

// In-app notification stack, bottom-right. This is the reliable channel: unlike
// native desktop notifications it needs no permission and shows even when the OS
// is muted, so a finished task always surfaces something on screen.
function Toasts({ items, onDismiss }) {
  if (!items.length) return null
  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {items.map((t) => {
        const done = t.status === 'RESOLVED'
        return (
          <div key={t.key} className={`toast ${done ? 'toast-done' : 'toast-fail'}`}>
            <span className="toast-icon" aria-hidden>{done ? '✓' : '⚠'}</span>
            <div className="toast-body">
              <div className="toast-title">{done ? 'Task done' : 'Task needs attention'}</div>
              <div className="toast-sub">#{t.id} {t.title}</div>
            </div>
            <button className="toast-close" onClick={() => onDismiss(t.key)} aria-label="Dismiss">×</button>
          </div>
        )
      })}
    </div>
  )
}

function Topbar({ worker, total, notify, onToggleNotify }) {
  const supported = typeof Notification !== 'undefined'
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
        {supported && (
          <button
            type="button"
            className={`notify-toggle ${notify ? 'on' : 'off'}`}
            onClick={onToggleNotify}
            aria-pressed={notify}
            title={notify ? 'Task-done notifications on — click to mute' : 'Notify me when a task finishes'}
          >
            <span aria-hidden>{notify ? '🔔' : '🔕'}</span>
            {notify ? 'Notifications on' : 'Notify me'}
          </button>
        )}
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

function Column({ col, todos, logs, storyMeta, onOpen, onCreate, onCreateStory }) {
  return (
    <section className={`column ${col.status.toLowerCase()}`}>
      <div className="col-head">
        <span className="col-accent" />
        <h2>{col.title}</h2>
        <span className="col-count">{todos.length}</span>
      </div>

      {onCreate && <Composer onCreate={onCreate} />}
      {onCreateStory && <StoryComposer onCreateStory={onCreateStory} />}

      <div className="col-body">
        {todos.length === 0 && !onCreate && <p className="col-empty">{col.hint}</p>}
        {todos.map((t) => (
          <Card
            key={t.id}
            todo={t}
            tail={tailLine(logs[t.id])}
            story={storyMeta[t.id]}
            onOpen={() => onOpen(t.id)}
          />
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

// Build a story chain: a name plus an ordered list of step titles. The worker
// runs the steps top-to-bottom, each waiting for the previous one to finish.
function StoryComposer({ onCreateStory }) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [steps, setSteps] = useState(['', ''])
  const [busy, setBusy] = useState(false)

  function setStep(i, value) {
    setSteps((cur) => cur.map((s, idx) => (idx === i ? value : s)))
  }
  function addStep() {
    setSteps((cur) => [...cur, ''])
  }
  function removeStep(i) {
    setSteps((cur) => (cur.length <= 1 ? cur : cur.filter((_, idx) => idx !== i)))
  }
  function reset() {
    setOpen(false)
    setName('')
    setSteps(['', ''])
  }

  const filled = steps.map((s) => s.trim()).filter(Boolean)
  const canSubmit = name.trim() && filled.length >= 1 && !busy

  async function submit(e) {
    e.preventDefault()
    if (!canSubmit) return
    setBusy(true)
    try {
      await onCreateStory(
        name.trim(),
        filled.map((title) => ({ title, description: '' })),
      )
      reset()
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button className="story-add" onClick={() => setOpen(true)} title="Chain several tasks into a story">
        <span className="story-add-icon">▤</span> New story
      </button>
    )
  }

  return (
    <form className="composer story-form" onSubmit={submit}>
      <input
        className="composer-title"
        placeholder="Story name (e.g. Ship dark mode)"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={200}
        autoFocus
      />
      <div className="story-steps">
        {steps.map((s, i) => (
          <div className="story-step-row" key={i}>
            <span className="story-step-num">{i + 1}</span>
            <input
              className="story-step-input"
              placeholder={`Step ${i + 1}`}
              value={s}
              onChange={(e) => setStep(i, e.target.value)}
              maxLength={200}
            />
            <button
              type="button"
              className="story-step-remove"
              onClick={() => removeStep(i)}
              disabled={steps.length <= 1}
              aria-label={`Remove step ${i + 1}`}
            >
              ×
            </button>
          </div>
        ))}
      </div>
      <button type="button" className="story-step-add" onClick={addStep}>+ Add step</button>
      <div className="composer-actions">
        <button type="submit" className="btn primary" disabled={!canSubmit}>
          {busy ? 'Creating…' : `Create story${filled.length ? ` · ${filled.length} steps` : ''}`}
        </button>
        <button type="button" className="btn ghost" onClick={reset}>Cancel</button>
      </div>
    </form>
  )
}

function Card({ todo, tail, story, onOpen }) {
  const running = todo.status === 'IN_PROGRESS'
  return (
    <button className={`card ${todo.status.toLowerCase()}`} onClick={onOpen}>
      {story && <StoryBadge story={story} />}
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
        {costMeta(todo) && <span className="card-cost">{costMeta(todo)}</span>}
        <span className="card-time">{relTime(todo.updatedAt)}</span>
      </div>
    </button>
  )
}

function Drawer({ todo, log, story, onClose, onDelete, onRetry }) {
  const running = todo.status === 'IN_PROGRESS'
  const canRetry = todo.status === 'FAILED' || todo.status === 'RESOLVED'
  const [retrying, setRetrying] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  async function retry() {
    if (retrying) return
    setRetrying(true)
    try {
      await onRetry()
    } finally {
      setRetrying(false)
    }
  }
  const logRef = useRef(null)
  useEffect(() => {
    if (running && logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [log, running])

  // Close on Escape and move focus to the close button when the drawer opens,
  // for basic keyboard / a11y support. Runs once on open — onClose is read
  // through a ref so live-log re-renders don't steal focus back to the button.
  const closeBtnRef = useRef(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  useEffect(() => {
    closeBtnRef.current?.focus()
    function onKeyDown(e) {
      if (e.key === 'Escape') onCloseRef.current()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Task detail">
        <div className="drawer-head">
          <span className={`badge ${todo.status.toLowerCase()}`}>{label(todo.status)}</span>
          <button ref={closeBtnRef} className="icon-btn" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <h2 className="drawer-title">{todo.title}</h2>
        <div className="drawer-meta">
          #{todo.id} · created {relTime(todo.createdAt)} · updated {relTime(todo.updatedAt)}
          {costMeta(todo) && <> · {costMeta(todo)}</>}
        </div>

        {story && (
          <div className="drawer-section">
            <div className="section-label">Story</div>
            <div className="drawer-story">
              <StoryBadge story={story} />
              <span className="drawer-story-name">{story.name}</span>
              {story.blocked && (
                <span className="drawer-story-note">waiting for earlier steps to finish</span>
              )}
            </div>
          </div>
        )}

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
            {todo.result
              // Agent results are Markdown; render them. Failure detail is usually
              // raw error/log text, so keep it monospaced and unparsed.
              ? (todo.status === 'FAILED'
                  ? <pre className="logbox result">{todo.result}</pre>
                  : <Markdown className="logbox result" text={todo.result} />)
              : <pre className="logbox result">(no result)</pre>}
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
          {canRetry && (
            <button className="btn primary" onClick={retry} disabled={retrying}>
              {retrying ? 'Retrying…' : todo.status === 'FAILED' ? 'Retry task' : 'Run again'}
            </button>
          )}
          {confirmingDelete ? (
            <span className="delete-confirm">
              <span className="delete-confirm-label">Delete? This can't be undone.</span>
              <button className="btn danger" onClick={onDelete}>Yes, delete</button>
              <button className="btn ghost" onClick={() => setConfirmingDelete(false)}>Cancel</button>
            </span>
          ) : (
            <button className="btn danger-ghost" onClick={() => setConfirmingDelete(true)}>Delete task</button>
          )}
        </div>
      </aside>
    </>
  )
}

// Compact chip shown on every task that belongs to a story: the chain name plus
// this task's position (e.g. "2/4"). Blocked steps (waiting on an earlier step)
// get a muted, dashed treatment so it's clear they aren't queued yet.
function StoryBadge({ story }) {
  return (
    <span className={`story-badge ${story.blocked ? 'blocked' : ''}`} title={`Story: ${story.name}`}>
      <span className="story-badge-icon" aria-hidden>▤</span>
      <span className="story-badge-name">{story.name}</span>
      <span className="story-badge-step">{story.step}/{story.total}</span>
    </span>
  )
}

// Derive per-task story info keyed by todo id: the chain's name, this task's
// 1-based position and length, and whether it's blocked — a PENDING step is
// blocked until every earlier step in its chain has resolved.
function computeStoryMeta(todos) {
  const groups = new Map()
  for (const t of todos) {
    if (!t.storyId) continue
    if (!groups.has(t.storyId)) groups.set(t.storyId, [])
    groups.get(t.storyId).push(t)
  }
  const meta = {}
  for (const steps of groups.values()) {
    steps.sort((a, b) => (a.storyOrder ?? 0) - (b.storyOrder ?? 0))
    steps.forEach((t, i) => {
      const earlierAllDone = steps.slice(0, i).every((s) => s.status === 'RESOLVED')
      meta[t.id] = {
        name: t.storyName || 'Story',
        step: i + 1,
        total: steps.length,
        blocked: t.status === 'PENDING' && !earlierAllDone,
      }
    })
  }
  return meta
}

// --- helpers ---

// Notification preference persists across reloads in localStorage. Wrapped in
// try/catch because storage can throw (private mode, disabled cookies).
const NOTIFY_KEY = 'dashboard.notify'

function notifyPref() {
  try {
    return localStorage.getItem(NOTIFY_KEY) === 'on' &&
      typeof Notification !== 'undefined' &&
      Notification.permission === 'granted'
  } catch {
    return false
  }
}

function setNotifyPref(on) {
  try {
    localStorage.setItem(NOTIFY_KEY, on ? 'on' : 'off')
  } catch {
    /* ignore — notifications just won't persist */
  }
}

// Pop a native desktop notification for a finished task. Guarded so it's a
// no-op if the API is missing or permission was revoked. `tag` dedupes so the
// same task never stacks multiple banners.
function notifyTaskDone(todo) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  const done = todo.status === 'RESOLVED'
  try {
    new Notification(done ? '✓ Task done' : '⚠ Task needs attention', {
      body: `#${todo.id} ${todo.title}`,
      tag: `dashboard-todo-${todo.id}`,
    })
  } catch {
    /* some browsers reject construction outside a SW; ignore */
  }
}

// A short synthesized chime so a finished task is *audible*, not just visible —
// the in-app toast otherwise pops silently. We generate the tone with the Web
// Audio API (no asset to ship, works offline) rather than an <audio> file. A
// rising two-note major third says "done"; a lower falling pair says "needs
// attention". Everything is wrapped in try/catch and the context is lazily
// created + resumed, so it's a harmless no-op where audio is blocked (e.g. no
// user gesture yet under browser autoplay rules, or in a headless test env).
let audioCtx = null
function playChime(status) {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return
    if (!audioCtx) audioCtx = new Ctx()
    if (audioCtx.state === 'suspended') audioCtx.resume()
    const now = audioCtx.currentTime
    const notes = status === 'RESOLVED' ? [659.25, 830.61] : [415.30, 311.13]
    notes.forEach((freq, i) => {
      const osc = audioCtx.createOscillator()
      const gain = audioCtx.createGain()
      osc.type = 'sine'
      osc.frequency.value = freq
      const start = now + i * 0.12
      const end = start + 0.18
      // Quick attack, smooth exponential decay — avoids the click a hard
      // gain cutoff would produce.
      gain.gain.setValueAtTime(0.0001, start)
      gain.gain.exponentialRampToValueAtTime(0.15, start + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, end)
      osc.connect(gain).connect(audioCtx.destination)
      osc.start(start)
      osc.stop(end + 0.02)
    })
  } catch {
    /* audio unsupported or blocked — the toast is still shown, so this is fine */
  }
}

function tailLine(log) {
  if (!log) return ''
  const lines = log.split('\n').map((l) => l.trim()).filter(Boolean)
  return lines.length ? lines[lines.length - 1] : ''
}

function firstLines(text, n) {
  if (!text) return ''
  return text.split('\n').filter((l) => l.trim()).slice(0, n).join(' ').slice(0, 160)
}

// Renders the agent run's runtime/cost as e.g. "12.3s · $0.0421". Either half
// may be missing (older tasks predate the columns), so we show whatever we have.
function costMeta(todo) {
  const parts = []
  if (todo.durationMs != null) parts.push(`${(todo.durationMs / 1000).toFixed(1)}s`)
  if (todo.costUsd != null) parts.push(`$${todo.costUsd.toFixed(4)}`)
  return parts.join(' · ')
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
