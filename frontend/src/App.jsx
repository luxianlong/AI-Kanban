import { useEffect, useState, useCallback, useRef, Fragment } from 'react'
import { api } from './api.js'
import Markdown from './Markdown.jsx'
import Icon from './Icon.jsx'

// The board's status columns, in flow order (left→right). This is the HORIZONTAL
// axis of the matrix; the vertical axis is one row per story. A task starts in
// NEW (staged), the user promotes it to To Do, then the worker moves it right by
// updating its status, mirroring the task's real lifecycle.
const COLUMNS = [
  { status: 'NEW', title: 'New', hint: 'Staged — promote to To Do when ready' },
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
  const [showComposer, setShowComposer] = useState(false) // mobile: the "+" toggles the composers

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

  async function createTodo(title, description, projectImprovement) {
    await api.createTodo(title, description, projectImprovement)
    await refresh()
    setShowComposer(false)
  }

  async function createStory(name, description) {
    await api.createStory(name, description)
    await refresh()
    setShowComposer(false)
  }

  // Attach a brand-new task as the next step of an existing story instead of
  // letting it start its own chain — the UI half of "attach task to related story".
  async function attachToStory(storyId, title, description) {
    await api.addStoryStep(storyId, title, description)
    await refresh()
    setShowComposer(false)
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

  // Manually file a task under a story (or unassign it: storyId = null).
  async function setTaskStory(id, storyId) {
    try {
      await api.setStory(id, storyId)
      await refresh()
    } catch (e) {
      setError(e.message)
    }
  }

  // Move a task to a new status. Optimistically flip it locally first so the card
  // jumps columns immediately (no 4s poll lag), then reconcile from the server.
  async function moveTask(id, status) {
    setTodos((cur) => cur.map((t) => (t.id === id ? { ...t, status } : t)))
    try {
      await api.updateStatus(id, status)
    } catch (e) {
      setError(e.message)
    }
    await refresh()
  }
  // Promote a NEW (staged) task into the To Do queue so the worker can pick it up.
  const promoteToDo = (id) => moveTask(id, 'PENDING')

  const selected = todos.find((t) => t.id === selectedId) || null
  const storyMeta = computeStoryMeta(todos)
  // Existing stories (id + name), for the drawer's manual story picker.
  const storyList = computeStories(todos).map((s) => ({ storyId: s.storyId, name: s.name }))
  // Matrix rows: one per story (newest first) plus a single "No story" row for
  // standalone tasks. Columns: the statuses, hiding "Needs Attention" until
  // something actually fails so a healthy board isn't cluttered by an empty one.
  const rows = computeRows(todos)
  const columns = COLUMNS.filter(
    (c) => c.status !== 'FAILED' || todos.some((t) => t.status === 'FAILED'),
  )
  // Phones can't use the wide story×status matrix — swap to a stacked layout.
  const isMobile = useIsMobile()

  return (
    <div className="page">
      <Topbar
        notify={notify}
        onToggleNotify={toggleNotify}
        onAdd={isMobile ? () => setShowComposer((v) => !v) : null}
        addOpen={showComposer}
      />

      {error && <div className="error"><Icon name="alert" size={15} /> {error}</div>}

      {(!isMobile || showComposer) && (
        <div className="board-toolbar">
          <Composer onCreate={createTodo} onAttach={attachToStory} />
          <StoryComposer onCreateStory={createStory} />
        </div>
      )}

      {isMobile ? (
        <MobileBoard
          rows={rows}
          columns={columns}
          logs={logs}
          storyMeta={storyMeta}
          onOpen={setSelectedId}
          onPromote={promoteToDo}
          onDelete={removeTodo}
        />
      ) : (
      <div
        className="matrix"
        style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(220px, 1fr))` }}
      >
        {columns.map((col) => (
          <div key={col.status} className={`matrix-colhead ${col.status.toLowerCase()}`}>
            <span className="col-accent" />
            <h2>{col.title}</h2>
            <span className="col-count">
              {todos.filter((t) => t.status === col.status).length}
            </span>
          </div>
        ))}

        {rows.length === 0 && (
          <div className="matrix-empty" style={{ gridColumn: '1 / -1' }}>
            No tasks yet — add one above, or start a story.
          </div>
        )}

        {rows.map((row) => {
          // Each real story gets its own stable color; the whole row is tinted
          // with it so the story reads as a colored background rectangle behind
          // its task cards, rather than looking like a card itself. The synthetic
          // "No story" row stays neutral so loose tasks are visibly not a story.
          const color = row.isStandalone ? null : storyColor(row.storyId)
          const tint = color
            ? { '--story-tint': color.tint, '--story-edge': color.edge, '--story-ink': color.ink }
            : undefined
          return (
            <Fragment key={row.key}>
              <StorySwimlane row={row} tint={tint} />
              {columns.map((col) => (
                <MatrixCell
                  key={col.status}
                  col={col}
                  todos={row.items.filter((t) => t.status === col.status)}
                  logs={logs}
                  storyMeta={storyMeta}
                  tint={tint}
                  onOpen={setSelectedId}
                  onPromote={promoteToDo}
                  onDropTask={moveTask}
                />
              ))}
            </Fragment>
          )
        })}
      </div>
      )}

      {selected && (
        <Drawer
          todo={selected}
          log={logs[selected.id]}
          story={storyMeta[selected.id]}
          stories={storyList}
          onSetStory={(storyId) => setTaskStory(selected.id, storyId)}
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
            <span className="toast-icon"><Icon name={done ? 'check' : 'alert'} size={16} /></span>
            <div className="toast-body">
              <div className="toast-title">{done ? 'Task done' : 'Task needs attention'}</div>
              <div className="toast-sub">#{t.id} {t.title}</div>
            </div>
            <button className="toast-close" onClick={() => onDismiss(t.key)} aria-label="Dismiss"><Icon name="close" size={16} /></button>
          </div>
        )
      })}
    </div>
  )
}

function Topbar({ notify, onToggleNotify, onAdd, addOpen }) {
  const supported = typeof Notification !== 'undefined'
  return (
    <header className="topbar">
      <div className="brand">
        <span className="mark" aria-hidden />
        <div>
          <h1>Kanban</h1>
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
            <Icon name={notify ? 'bell' : 'bellOff'} size={16} />
            <span className="label">{notify ? 'Notifications on' : 'Notify me'}</span>
          </button>
        )}
        {/* Mobile: a single "+" reveals the task/story composers, which are hidden
            by default to reclaim the space the two rows used to take. */}
        {onAdd && (
          <button
            type="button"
            className={`add-toggle ${addOpen ? 'on' : ''}`}
            onClick={onAdd}
            aria-pressed={addOpen}
            aria-label={addOpen ? 'Close create panel' : 'Create a task or story'}
            title="Create a task or story"
          >
            <Icon name={addOpen ? 'close' : 'plus'} size={20} />
          </button>
        )}
      </div>
    </header>
  )
}

// A story rendered as a slim, light caption label above its row of tinted cells.
// It used to be a tall full-width band carrying the name + a status pill + a
// done/total progress bar, but that "spent too much space for the story row";
// now it's just a thin name tag in the story's own ink, sitting on the same tint
// as the cells beneath so the label + cells together read as one light colored
// rectangle. The status/progress it used to show are still on the cards and in
// the drawer. The synthetic "No story" tag stays neutral so loose tasks are
// visibly not a story.
function StorySwimlane({ row, tint }) {
  if (row.isStandalone) {
    return (
      <div className="swimlane standalone" style={{ gridColumn: '1 / -1' }}>
        <span className="swimlane-name">No story</span>
      </div>
    )
  }
  // Before the worker has produced any steps there's nothing on the cells to show
  // the story's state, so the label keeps a tiny planner hint (planning / failed).
  const plannerActive = row.steps.length === 0 && row.planner
  const planning = plannerActive && (row.planner.status === 'PENDING' || row.planner.status === 'IN_PROGRESS')
  const planFailed = plannerActive && row.planner.status === 'FAILED'
  return (
    <div
      className={`swimlane storied ${row.status.toLowerCase()}`}
      style={{ ...tint, gridColumn: '1 / -1' }}
    >
      <span className="swimlane-name" title={row.name}>{row.name}</span>
      {planning && <span className="swimlane-sub">Planning…</span>}
      {planFailed && <span className="swimlane-sub">Plan failed — open the card</span>}
    </div>
  )
}

// One cell of the matrix: the tasks of a given row that are currently in this
// column's status. The To Do (PENDING) cell is a drop target so a NEW card can
// be dragged in to promote it; other columns are worker-driven and inert to drops.
function MatrixCell({ col, todos, logs, storyMeta, tint, onOpen, onPromote, onDropTask }) {
  const [over, setOver] = useState(false)
  const isDropTarget = col.status === 'PENDING'
  const dropProps = isDropTarget
    ? {
        onDragOver: (e) => {
          e.preventDefault()
          e.dataTransfer.dropEffect = 'move'
          setOver(true)
        },
        onDragLeave: () => setOver(false),
        onDrop: (e) => {
          e.preventDefault()
          setOver(false)
          const id = Number(e.dataTransfer.getData('text/todo-id'))
          if (id) onDropTask(id, 'PENDING')
        },
      }
    : {}
  return (
    <div
      className={`matrix-cell ${col.status.toLowerCase()} ${tint ? 'storied' : ''} ${over ? 'drag-over' : ''}`}
      style={tint}
      {...dropProps}
    >
      {todos.map((t) => (
        <Card
          key={t.id}
          todo={t}
          tail={tailLine(logs[t.id])}
          story={storyMeta[t.id]}
          onOpen={() => onOpen(t.id)}
          onPromote={onPromote}
        />
      ))}
    </div>
  )
}

// ---- Mobile layout -------------------------------------------------------
// The desktop matrix (story-rows × status-columns) is far too wide for a phone,
// so on narrow screens App renders this instead: a scrollable status-tab bar
// picks ONE status, and that status's cards are listed vertically, grouped under
// their story. Reuses the same Card component and the same rows/columns data.
function MobileBoard({ rows, columns, logs, storyMeta, onOpen, onPromote, onDelete }) {
  const countFor = (status) =>
    rows.reduce((n, r) => n + r.items.filter((t) => t.status === status).length, 0)

  const [picked, setPicked] = useState(null)
  // Default to the first status that actually has cards (so a phone opens on
  // something non-empty); fall back to the first column. If `picked` points at a
  // now-hidden column (FAILED vanishes when nothing's failing), fall back too.
  const fallback = columns.find((c) => countFor(c.status) > 0)?.status || columns[0].status
  const activeCol = columns.find((c) => c.status === picked)
  const status = activeCol ? activeCol.status : fallback
  const hint = (columns.find((c) => c.status === status) || columns[0]).hint

  const groups = rows
    .map((r) => ({ row: r, items: r.items.filter((t) => t.status === status) }))
    .filter((g) => g.items.length)

  return (
    <div className="mobile-board">
      <div className="status-tabs" role="tablist" aria-label="Task status">
        {columns.map((col) => {
          const on = col.status === status
          return (
            <button
              key={col.status}
              role="tab"
              aria-selected={on}
              className={`status-tab ${col.status.toLowerCase()} ${on ? 'active' : ''}`}
              onClick={() => setPicked(col.status)}
            >
              <span className="status-tab-label">{col.title}</span>
              <span className="status-tab-count">{countFor(col.status)}</span>
            </button>
          )
        })}
      </div>

      <div className="mobile-list">
        {groups.length === 0 ? (
          <p className="col-empty">{hint}</p>
        ) : (
          groups.map((g) => {
            // Hide the group header only when everything is standalone (a single
            // "No story" group would just be noise); otherwise it anchors context.
            const showHead = !(g.row.isStandalone && groups.length === 1)
            return (
              <div key={g.row.key} className="mobile-group">
                {showHead && (
                  <div className="mobile-group-head">
                    <span className="mobile-group-name">{g.row.name}</span>
                    <span className="mobile-group-count">{g.items.length}</span>
                  </div>
                )}
                {g.items.map((t) => (
                  <SwipeToDelete key={t.id} onDelete={() => onDelete(t.id)}>
                    <Card
                      todo={t}
                      tail={tailLine(logs[t.id])}
                      story={storyMeta[t.id]}
                      onOpen={() => onOpen(t.id)}
                      onPromote={onPromote}
                    />
                  </SwipeToDelete>
                ))}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}

// Swipe-left-to-delete wrapper for mobile cards. Dragging the card left reveals a
// red Delete button on the right; releasing past halfway latches it open. A tap
// that follows a drag (or a tap while open) is swallowed so it doesn't also open
// the card's drawer — instead it closes the row. Touch-only; on desktop the card
// behaves normally (delete lives in the drawer).
function SwipeToDelete({ onDelete, children }) {
  const WIDTH = 88 // px of Delete button revealed when fully open
  const [dx, setDx] = useState(0)
  const startX = useRef(null)
  const openRef = useRef(false)
  const movedRef = useRef(false)

  function onTouchStart(e) {
    startX.current = e.touches[0].clientX
    movedRef.current = false
  }
  function onTouchMove(e) {
    if (startX.current == null) return
    const delta = e.touches[0].clientX - startX.current
    if (Math.abs(delta) > 6) movedRef.current = true
    // base offset depends on whether it's already latched open
    const next = (openRef.current ? -WIDTH : 0) + delta
    setDx(Math.max(-WIDTH - 16, Math.min(0, next))) // clamp: only leftward, small rubber-band
  }
  function onTouchEnd() {
    const open = dx < -WIDTH / 2
    openRef.current = open
    setDx(open ? -WIDTH : 0)
    startX.current = null
  }
  // If a drag happened, or the row is open, swallow the click so it neither opens
  // the drawer nor leaks through — and close an open row on tap.
  function onClickCapture(e) {
    if (movedRef.current || openRef.current) {
      e.preventDefault()
      e.stopPropagation()
      movedRef.current = false
      if (openRef.current) {
        openRef.current = false
        setDx(0)
      }
    }
  }

  return (
    <div className="swipe-row">
      {/* Only mount the red action while actually swiped — otherwise it flashes
          behind cards as they animate in when you switch status tabs. */}
      {dx < 0 && (
        <button
          className="swipe-delete"
          style={{ width: WIDTH }}
          onClick={(e) => { e.stopPropagation(); onDelete() }}
          aria-label="Delete task"
        >
          Delete
        </button>
      )}
      <div
        className="swipe-content"
        style={{ transform: `translateX(${dx}px)` }}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onClickCapture={onClickCapture}
      >
        {children}
      </div>
    </div>
  )
}

// Track a CSS media query in React so we can swap whole layouts (not merely
// restyle) below a breakpoint. Kept in sync with the 720px breakpoint in the CSS.
function useIsMobile(query = '(max-width: 720px)') {
  const [match, setMatch] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(query).matches,
  )
  useEffect(() => {
    const mq = window.matchMedia(query)
    const onChange = () => setMatch(mq.matches)
    mq.addEventListener('change', onChange)
    onChange()
    return () => mq.removeEventListener('change', onChange)
  }, [query])
  return match
}

function Composer({ onCreate, onAttach }) {
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  // Whether this task is about improving the Kanban project itself. Off by
  // default: a plain task is a general task, not aimed at the project.
  const [projectImprovement, setProjectImprovement] = useState(false)
  // Existing stories this task looks related to (top match first), so we can
  // offer to attach it to an existing chain instead of starting a fresh one.
  const [related, setRelated] = useState([])

  // As the user types, ask the server which existing stories the task looks
  // related to. Debounced so we don't fire on every keystroke, and skipped
  // until there's enough text to be meaningful.
  useEffect(() => {
    if (!onAttach) return
    const text = `${title} ${desc}`.trim()
    if (text.length < 4) {
      setRelated([])
      return
    }
    const t = setTimeout(async () => {
      try {
        setRelated(await api.relatedStories(text))
      } catch {
        setRelated([])
      }
    }, 400)
    return () => clearTimeout(t)
  }, [title, desc, onAttach])

  function clear() {
    setTitle('')
    setDesc('')
    setRelated([])
    setProjectImprovement(false)
    setOpen(false)
  }

  async function submit(e) {
    e.preventDefault()
    const t = title.trim()
    if (!t || busy) return
    setBusy(true)
    try {
      await onCreate(t, desc.trim(), projectImprovement)
      clear()
    } finally {
      setBusy(false)
    }
  }

  // Attach the drafted task to an existing story as its next step.
  async function attach(story) {
    const t = title.trim()
    if (!t || busy || !onAttach) return
    setBusy(true)
    try {
      await onAttach(story.storyId, t, desc.trim())
      clear()
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
          {onAttach && related.length > 0 && (
            <div className="composer-related">
              <span className="composer-related-label">Looks related to:</span>
              {related.map((s) => (
                <button
                  key={s.storyId}
                  type="button"
                  className="composer-related-chip"
                  disabled={busy}
                  onClick={() => attach(s)}
                  title={`Add this task as the next step of "${s.name}"`}
                >
                  <Icon name="story" size={12} /> {s.name}
                </button>
              ))}
            </div>
          )}
          <label className="composer-flag" title="Mark this task as one that improves the Kanban project itself (its code, docs, tests). Off = a general task.">
            <input
              type="checkbox"
              checked={projectImprovement}
              onChange={(e) => setProjectImprovement(e.target.checked)}
            />
            Improves this Kanban project itself
          </label>
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

// Declare a story from a single one-line goal. No steps are entered here — after
// it's created, the worker picks up the story and breaks the goal into concrete
// step tasks (staged as New for you to promote). Mirrors the task composer: a
// single input that expands to reveal an optional details field.
function StoryComposer({ onCreateStory }) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [desc, setDesc] = useState('')
  const [busy, setBusy] = useState(false)

  function reset() {
    setOpen(false)
    setName('')
    setDesc('')
  }

  const canSubmit = name.trim() && !busy

  async function submit(e) {
    e.preventDefault()
    if (!canSubmit) return
    setBusy(true)
    try {
      await onCreateStory(name.trim(), desc.trim())
      reset()
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button className="story-add" onClick={() => setOpen(true)} title="Describe a goal — the worker breaks it into tasks">
        <span className="story-add-icon"><Icon name="story" size={16} /></span> New story
      </button>
    )
  }

  return (
    <form className="composer story-form" onSubmit={submit}>
      <input
        className="composer-title"
        placeholder="Describe a story / goal (e.g. Ship dark mode)"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={200}
        autoFocus
      />
      <textarea
        className="composer-desc"
        placeholder="Extra context for the worker's planning (optional)"
        value={desc}
        onChange={(e) => setDesc(e.target.value)}
        rows={2}
      />
      <p className="story-hint">
        The worker will pick this up and break it into tasks — they'll appear on this story's row as New.
      </p>
      <div className="composer-actions">
        <button type="submit" className="btn primary" disabled={!canSubmit}>
          {busy ? 'Creating…' : 'Create story'}
        </button>
        <button type="button" className="btn ghost" onClick={reset}>Cancel</button>
      </div>
    </form>
  )
}

// A task card. The body opens the detail drawer (click, or Enter/Space for
// keyboard). It's a <div role="button"> rather than a <button> so it can legally
// contain the inner "→ To Do" button (nested buttons are invalid HTML). NEW cards
// are draggable (drag into the To Do column to promote) and carry the promote button.
function Card({ todo, tail, story, onOpen, onPromote }) {
  const running = todo.status === 'IN_PROGRESS'
  const isNew = todo.status === 'NEW'
  const resolved = todo.status === 'RESOLVED'
  // Done cards are historical: the *result* is what matters, and full context is
  // one click away in the drawer. Dropping the description keeps them compact so
  // the Done column stays tidy instead of crowding (and overlapping) when a story
  // accumulates many completed tasks.
  const showDesc = todo.description && !running && !resolved
  function onKeyDown(e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onOpen()
    }
  }

  // Done tasks render as a slim single-line "completed" row instead of a full
  // card: just a check + the one-line title. The id / runtime / cost that used to
  // trail the row were noise on the board (per "#193, 322.9s, $xx is useless") —
  // they live in the detail drawer now. Each row is a fixed, uniform height, so a
  // story that accumulates many finished tasks stays a tidy, scannable list rather
  // than a stack of tall cards that crowds — and used to overlap — the Done column.
  if (resolved) {
    return (
      <div
        className="card resolved done-row"
        role="button"
        tabIndex={0}
        onClick={onOpen}
        onKeyDown={onKeyDown}
        title={todo.title}
      >
        <span className="done-check" aria-hidden="true">
          <Icon name="check" size={13} />
        </span>
        <span className="done-title">{todo.title}</span>
      </div>
    )
  }

  return (
    <div
      className={`card ${todo.status.toLowerCase()} ${isNew ? 'draggable' : ''}`}
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={onKeyDown}
      draggable={isNew}
      onDragStart={
        isNew
          ? (e) => {
              e.dataTransfer.setData('text/todo-id', String(todo.id))
              e.dataTransfer.effectAllowed = 'move'
            }
          : undefined
      }
    >
      {story && <StoryBadge story={story} showName={false} />}
      {todo.storyPlanner && (
        <span className="card-tag planner" title="Story planner — the worker breaks this story's goal into tasks">
          <Icon name="planner" size={12} /> Planning
        </span>
      )}
      {todo.projectImprovement && (
        <span className="card-tag project" title="This task improves the Kanban project itself">
          <Icon name="project" size={12} /> Project
        </span>
      )}
      <div className="card-title">{todo.title}</div>
      {showDesc && <div className="card-desc">{todo.description}</div>}

      {running && (
        <div className="ticker">
          <span className="ticker-pulse" />
          <span className="ticker-text">{tail || 'Starting session…'}</span>
        </div>
      )}

      {todo.status === 'FAILED' && (
        <div className="card-fail">{firstLines(todo.result, 2) || 'Failed'}</div>
      )}

      {isNew && onPromote && (
        <button
          type="button"
          className="card-promote"
          title="Move this task to the To Do queue so the agent can pick it up"
          onClick={(e) => {
            e.stopPropagation()
            onPromote(todo.id)
          }}
        >
          → To Do
        </button>
      )}

      <div className="card-foot">
        <span className="card-id">#{todo.id}</span>
        {costMeta(todo) && <span className="card-cost">{costMeta(todo)}</span>}
        <span className="card-time">{relTime(todo.updatedAt)}</span>
      </div>
    </div>
  )
}

function Drawer({ todo, log, story, stories = [], onSetStory, onClose, onDelete, onRetry }) {
  const running = todo.status === 'IN_PROGRESS'
  const canRetry = todo.status === 'FAILED' || todo.status === 'RESOLVED'
  const [retrying, setRetrying] = useState(false)

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

  // Close on Escape and move focus into the drawer when it opens, for basic
  // keyboard / a11y support. Focus the panel itself (not a button) so no control
  // lights up with a focus ring just from opening. onClose is read through a ref
  // so live-log re-renders don't re-run this.
  const drawerRef = useRef(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  useEffect(() => {
    drawerRef.current?.focus()
    function onKeyDown(e) {
      if (e.key === 'Escape') onCloseRef.current()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Task detail" tabIndex={-1} ref={drawerRef}>
        <div className="drawer-head">
          <span className={`badge ${todo.status.toLowerCase()}`}>{label(todo.status)}</span>
          <div className="drawer-actions">
            {canRetry && (
              <button
                className="icon-btn"
                onClick={retry}
                disabled={retrying}
                title={todo.status === 'FAILED' ? 'Retry task' : 'Run again'}
                aria-label="Run again"
              >
                <Icon name="run" />
              </button>
            )}
            <button
              className="icon-btn danger"
              onClick={onDelete}
              title="Delete task"
              aria-label="Delete task"
            >
              <Icon name="delete" />
            </button>
            <button className="icon-btn" onClick={onClose} aria-label="Close">
              <Icon name="close" />
            </button>
          </div>
        </div>

        <h2 className="drawer-title">{todo.title}</h2>
        <div className="drawer-meta">
          #{todo.id} · created {relTime(todo.createdAt)} · updated {relTime(todo.updatedAt)}
          {costMeta(todo) && <> · {costMeta(todo)}</>}
        </div>

        {(story || onSetStory) && (
          <div className="drawer-section">
            <div className="section-label">Story</div>
            {story && (
              <div className="drawer-story">
                <StoryBadge story={story} />
                {story.blocked && (
                  <span className="drawer-story-note">waiting for earlier steps to finish</span>
                )}
              </div>
            )}
            {onSetStory && (
              <select
                className="story-select"
                value={todo.storyId || ''}
                onChange={(e) => onSetStory(e.target.value || null)}
                aria-label="Assign this task to a story"
              >
                <option value="">No story (standalone)</option>
                {stories.map((s) => (
                  <option key={s.storyId} value={s.storyId}>{s.name}</option>
                ))}
              </select>
            )}
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
      </aside>
    </>
  )
}

// Compact chip shown on every task that belongs to a story: the chain name plus
// this task's position (e.g. "2/4"). Blocked steps (waiting on an earlier step)
// get a muted, dashed treatment so it's clear they aren't queued yet.
function StoryBadge({ story, showName = true }) {
  return (
    <span className={`story-badge ${story.blocked ? 'blocked' : ''}`} title={`Story: ${story.name}`}>
      <span className="story-badge-icon"><Icon name="story" size={12} /></span>
      {showName && <span className="story-badge-name">{story.name}</span>}
      <span className="story-badge-step">{story.step}/{story.total}</span>
    </span>
  )
}

// Derive per-task story info keyed by todo id: the chain's name, this task's
// 1-based position and length, and whether it's blocked — a PENDING step is
// blocked until every earlier step in its chain has resolved. The story's planner
// seed is skipped (it isn't a numbered step — it gets its own "Planning" tag).
function computeStoryMeta(todos) {
  const groups = new Map()
  for (const t of todos) {
    if (!t.storyId || t.storyPlanner) continue
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

// Gather the story-tagged todos into whole stories for the matrix rows. Each entry
// separates the planner seed from the real (numbered) steps: `steps` drives the
// row's progress/status, while `items` (planner + steps) are the cards the cells
// distribute across the status columns. Newest story first.
function computeStories(todos) {
  const groups = new Map()
  for (const t of todos) {
    if (!t.storyId) continue
    if (!groups.has(t.storyId)) groups.set(t.storyId, [])
    groups.get(t.storyId).push(t)
  }
  const stories = []
  for (const [storyId, raw] of groups) {
    const planner = raw.find((t) => t.storyPlanner) || null
    const steps = raw.filter((t) => !t.storyPlanner).sort((a, b) => (a.storyOrder ?? 0) - (b.storyOrder ?? 0))
    const annotated = steps.map((t, i) => ({
      ...t,
      blocked: t.status === 'PENDING' && !steps.slice(0, i).every((s) => s.status === 'RESOLVED'),
    }))
    const done = annotated.filter((s) => s.status === 'RESOLVED').length
    stories.push({
      storyId,
      name: (planner && planner.storyName) || steps[0]?.storyName || 'Story',
      planner,
      steps: annotated,
      items: [...(planner ? [planner] : []), ...annotated],
      done,
      total: annotated.length,
      status: storyRollup(annotated, planner),
      newest: Math.max(...raw.map((s) => new Date(s.createdAt || 0).getTime())),
    })
  }
  stories.sort((a, b) => b.newest - a.newest)
  return stories
}

// Assemble the matrix rows: one row per story (newest first), then a single
// "No story" row gathering every standalone task, if any exist. Each row exposes
// `items` — the cards the cells distribute across the status columns.
function computeRows(todos) {
  const rows = computeStories(todos).map((s) => ({ ...s, key: s.storyId, isStandalone: false }))
  const standalone = todos.filter((t) => !t.storyId)
  if (standalone.length) {
    rows.push({ key: '__standalone', name: 'No story', isStandalone: true, items: standalone, steps: standalone })
  }
  return rows
}

// Roll a story's steps up to one status for its pill: anything failed needs
// attention; all resolved is done; anything running is in progress; all-staged
// is still New; otherwise it's queued (To Do).
function rollupStatus(steps) {
  if (steps.some((s) => s.status === 'FAILED')) return 'FAILED'
  if (steps.every((s) => s.status === 'RESOLVED')) return 'RESOLVED'
  if (steps.some((s) => s.status === 'IN_PROGRESS')) return 'IN_PROGRESS'
  if (steps.every((s) => s.status === 'NEW')) return 'NEW'
  return 'PENDING'
}

// A story's overall status. Once it has real steps, roll those up. Before that
// (only the planner exists) reflect the planner: running/queued reads as
// in-progress (planning), failed as failed.
function storyRollup(steps, planner) {
  if (steps.length) return rollupStatus(steps)
  if (planner) {
    if (planner.status === 'FAILED') return 'FAILED'
    if (planner.status === 'PENDING' || planner.status === 'IN_PROGRESS') return 'IN_PROGRESS'
    return planner.status
  }
  return 'PENDING'
}

// --- helpers ---

// A stable, distinct color per story. On the desktop board each story's whole row
// is tinted with its color so the story reads as a colored background rectangle
// behind its task cards (task cards stay white) — visually a story is a *zone*,
// not another card. Soft, calm hues so the fill sits quietly under the cards.
// `tint` = row fill, `edge` = border/accent, `ink` = the story name text.
const STORY_COLORS = [
  { tint: '#dce9fe', edge: '#93b8f7', ink: '#2f5bb7' }, // blue
  { tint: '#ebe0fc', edge: '#c1a5f0', ink: '#6a45c0' }, // violet
  { tint: '#d8f2e3', edge: '#91d6b3', ink: '#1c8a52' }, // green
  { tint: '#fde3ce', edge: '#f5bd8a', ink: '#b5641c' }, // orange
  { tint: '#fcdbe8', edge: '#f2a9c6', ink: '#b23a6b' }, // pink
  { tint: '#d6eef5', edge: '#92d1e0', ink: '#1c7d94' }, // teal
  { tint: '#f6e7c4', edge: '#e0c073', ink: '#8a6a1c' }, // gold
  { tint: '#dfe3ee', edge: '#b3bcd0', ink: '#4a5670' }, // slate
]

// Hash the storyId to a stable palette index so a story keeps the same color
// across renders and reloads (no per-session randomness).
function storyColor(storyId) {
  if (!storyId) return null
  const s = String(storyId)
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return STORY_COLORS[h % STORY_COLORS.length]
}

// Notification preference persists across reloads in localStorage. Wrapped in
// try/catch because storage can throw (private mode, disabled cookies).
const NOTIFY_KEY = 'kanban.notify'

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
      tag: `kanban-todo-${todo.id}`,
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
      NEW: 'New',
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
