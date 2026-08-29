import { useEffect, useState, useCallback, useRef, useMemo } from 'react'
import { api } from './api.js'
import Markdown from './Markdown.jsx'
import Icon from './Icon.jsx'
import { buildDocIndex, linkifyDocRefs } from './docLinks.js'

// The board's status columns, in flow order (left→right). This is the HORIZONTAL
// axis of the matrix; the vertical axis is one row per story. A task starts queued
// in To Do (there's no separate "New"/staging step), then the worker moves it
// right by updating its status, mirroring the task's real lifecycle.
const COLUMNS = [
  { status: 'PENDING', title: 'To Do', hint: 'No tasks queued' },
  { status: 'IN_PROGRESS', title: 'In Progress', hint: 'Nothing running right now' },
  { status: 'RESOLVED', title: 'Done', hint: 'Completed tasks land here' },
  { status: 'FAILED', title: 'Needs Attention', hint: 'Nothing needs attention' },
]

export default function App() {
  const [todos, setTodos] = useState([])
  // Persisted stories (from /api/stories) — includes empty ones whose task chain
  // has been cleared, so an empty story keeps its row on the board until deleted.
  const [stories, setStories] = useState([])
  const [worker, setWorker] = useState(null)
  const [error, setError] = useState(null)
  const [logs, setLogs] = useState({}) // id -> transcript text (in-progress + open card)
  const [selectedId, setSelectedId] = useState(null)
  const [notify, setNotify] = useState(() => notifyPref())
  const [toasts, setToasts] = useState([]) // in-app notifications (always shown)
  // Unread finished tasks: every task that crossed into a terminal state (Done /
  // Needs Attention) and hasn't been opened yet. This is now a server-side
  // property of the task (`t.unread`) rather than a per-browser localStorage list,
  // so the badge is identical on every device the user opens the board from. We
  // derive the notification-center list from the live todos, newest-finished first.
  const [showComposer, setShowComposer] = useState(false) // mobile: the "+" toggles the composers
  // Count of docs the user hasn't opened yet — drives the red badge on the "Docs"
  // link so a newly-produced report (e.g. the 美伊战争 analysis) is noticeable.
  const [docsNew, setDocsNew] = useState(0)
  // The full docs manifest — used to rewrite internal doc-file references in task
  // results (e.g. `docs/foo.md`) into links to the published page users can open.
  const [docsManifest, setDocsManifest] = useState([])
  const docIndex = useMemo(() => buildDocIndex(docsManifest), [docsManifest])

  // The unread notification list, derived from the server-side `unread` flag on
  // each todo (newest-finished first). Shaped for the notification center, which
  // renders id/status/title and a relative finish time (`updatedAt`, the moment
  // the task last changed — i.e. when it flipped terminal).
  const unread = useMemo(
    () =>
      todos
        .filter((t) => t.unread)
        .map((t) => ({ id: t.id, status: t.status, title: t.title, at: t.updatedAt }))
        .sort((a, b) => new Date(b.at) - new Date(a.at)),
    [todos],
  )

  const refresh = useCallback(async () => {
    try {
      const [list, storyList, status] = await Promise.all([
        api.listTodos(),
        api.listStories(),
        api.workerStatus(),
      ])
      setTodos(list)
      setStories(storyList)
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

  // The 4-second poll is only a fallback. A status change made outside this tab —
  // in another open board tab, or by the worker on the server — would otherwise
  // take up to a full poll interval to show up here. Refresh at once instead:
  //   • when this tab regains focus / becomes visible, so worker/server-side
  //     changes are current the moment you look at the board;
  //   • when a sibling tab signals it just mutated a task, so promoting/moving a
  //     card in one tab updates every other open board immediately (instant
  //     cross-tab sync via BroadcastChannel) rather than on its next poll.
  const channelRef = useRef(null)
  useEffect(() => {
    const onFocus = () => refresh()
    const onVisible = () => { if (!document.hidden) refresh() }
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisible)
    let ch = null
    if (typeof BroadcastChannel !== 'undefined') {
      ch = new BroadcastChannel('kanban')
      // Peers only ever post 'mutated'; this handler just re-fetches (it never
      // re-broadcasts), so there's no echo loop between tabs.
      ch.onmessage = (e) => { if (e.data === 'mutated') refresh() }
      channelRef.current = ch
    }
    return () => {
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onVisible)
      if (ch) ch.close()
      channelRef.current = null
    }
  }, [refresh])

  // Tell other open board tabs a task just changed here so they refresh at once
  // (see the BroadcastChannel effect above). No-op where the API is unavailable.
  const broadcastMutation = useCallback(() => {
    channelRef.current?.postMessage('mutated')
  }, [])

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

  // Clearing an unread notification writes through to the server (so the badge
  // clears on every device), with an optimistic local update so the badge reacts
  // instantly; the next poll reconciles with server truth. markRead is a no-op on
  // a task that isn't currently unread, so opening any card is cheap.
  const markRead = useCallback((id) => {
    let wasUnread = false
    setTodos((cur) =>
      cur.map((t) => {
        if (t.id === id && t.unread) {
          wasUnread = true
          return { ...t, unread: false }
        }
        return t
      }),
    )
    if (wasUnread) api.markTodoRead(id).catch(() => {})
  }, [])
  const markAllRead = useCallback(() => {
    setTodos((cur) => cur.map((t) => (t.unread ? { ...t, unread: false } : t)))
    api.markAllTodosRead().catch(() => {})
  }, [])

  // Load the docs manifest and flag any doc the user hasn't opened yet. We
  // re-check when the tab regains focus so returning from the Docs page (which is
  // a full navigation, not an SPA route) clears the badge. The manifest is a
  // build artifact (frontend/public/docs-manifest.json) regenerated on every
  // deploy, so it always mirrors what's actually on /docs.html.
  useEffect(() => {
    let cancelled = false
    const check = async () => {
      try {
        // Which docs are "new" is the manifest minus what the user has already
        // seen. "Seen" is tracked server-side (api.listDocReads) rather than in
        // this browser's storage, so the badge count matches across devices.
        const [res, seenIds] = await Promise.all([
          fetch('/docs-manifest.json', { cache: 'no-store' }),
          api.listDocReads().catch(() => []),
        ])
        if (!res.ok) return
        const docs = await res.json()
        if (cancelled || !Array.isArray(docs)) return
        setDocsManifest(docs)
        const ids = docs.map((d) => d && d.id).filter(Boolean)
        const seen = new Set(Array.isArray(seenIds) ? seenIds : [])
        setDocsNew(ids.filter((id) => !seen.has(id)).length)
      } catch {
        /* no manifest / offline — just show no badge */
      }
    }
    check()
    const onFocus = () => check()
    window.addEventListener('focus', onFocus)
    return () => { cancelled = true; window.removeEventListener('focus', onFocus) }
  }, [])

  // Opening the Docs *index* is browsing, not reading — so it no longer marks every
  // doc seen (that made an opened doc keep looking "NEW", and hid the per-doc "new"
  // marker; task #357). Each doc records itself read when actually opened (see
  // scripts/build-docs-site.mjs), so the badge counts docs you haven't opened yet;
  // the focus/mount `check()` above reconciles it with server truth on return.

  const prevStatusRef = useRef(null)
  useEffect(() => {
    const prev = prevStatusRef.current
    if (prev) {
      for (const t of todos) {
        const before = prev.get(t.id)
        const terminal = t.status === 'RESOLVED' || t.status === 'FAILED'
        if (terminal && before && before !== t.status) {
          // The server already flags the task unread on this transition; here we
          // only fire the transient, per-session channels (toast/chime/native
          // notification). The persistent badge comes from `t.unread`.
          pushToast(t)
          playChime(t.status)
          if (notifyRef.current) notifyTaskDone(t)
        }
      }
    }
    prevStatusRef.current = new Map(todos.map((t) => [t.id, t.status]))
  }, [todos, pushToast])

  // Opening a task's detail counts as reading it — clear its unread mark on the
  // server. Covers both routes into the drawer: clicking the topbar notification
  // list, and clicking the task's card on the board. (A no-op unless it's unread.)
  useEffect(() => {
    if (selectedId != null) markRead(selectedId)
  }, [selectedId, markRead])

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
        todosRef.current
          // A delegated task runs outside this server — there is no session log
          // to stream, so don't poll one for it.
          .filter((t) => t.status === 'IN_PROGRESS' && !t.delegated)
          .map((t) => t.id),
      )
      if (selectedRef.current != null) ids.add(selectedRef.current)
      ids.forEach(fetchLog)
    }
    tick()
    const t = setInterval(tick, 1500)
    return () => clearInterval(t)
  }, [fetchLog])

  async function createTodo(title, description, projectImprovement, attachmentIds, delegated) {
    await api.createTodo(title, description, projectImprovement, attachmentIds, delegated)
    await refresh()
    broadcastMutation()
    setShowComposer(false)
  }

  async function createStory(name) {
    await api.createStory(name)
    await refresh()
    broadcastMutation()
    setShowComposer(false)
  }

  async function removeTodo(id) {
    try {
      await api.deleteTodo(id)
      if (selectedId === id) setSelectedId(null)
      await refresh()
      broadcastMutation()
    } catch (e) {
      setError(e.message)
    }
  }

  async function retryTodo(id) {
    try {
      await api.retryTodo(id)
      await refresh()
      broadcastMutation()
    } catch (e) {
      setError(e.message)
    }
  }

  // Stop the Claude session running on an in-progress task. Confirmed first
  // because it discards the in-flight run; the backend kills the process and the
  // worker marks the task FAILED, from where it can be requeued.
  async function stopTodo(id) {
    if (typeof window !== 'undefined'
        && !window.confirm('Stop the running session for this task? Its current run will be discarded.')) {
      return
    }
    try {
      await api.stopTodo(id)
      await refresh()
      broadcastMutation()
    } catch (e) {
      setError(e.message)
    }
  }

  // Manually file a task under a story (or unassign it: storyId = null).
  async function setTaskStory(id, storyId) {
    try {
      await api.setStory(id, storyId)
      await refresh()
      broadcastMutation()
    } catch (e) {
      setError(e.message)
    }
  }

  // Delete a whole story (and its tasks). Confirmed first because it's
  // destructive — an empty story is removed outright, a populated one takes its
  // tasks with it.
  async function removeStory(storyId, name, taskCount) {
    const detail = taskCount > 0
      ? `Delete the story “${name}” and its ${taskCount} task${taskCount === 1 ? '' : 's'}? This can't be undone.`
      : `Delete the empty story “${name}”?`
    if (typeof window !== 'undefined' && !window.confirm(detail)) return
    try {
      await api.deleteStory(storyId)
      await refresh()
      broadcastMutation()
    } catch (e) {
      setError(e.message)
    }
  }

  const selected = todos.find((t) => t.id === selectedId) || null
  const storyMeta = computeStoryMeta(todos)
  // Existing stories (id + name), for the drawer's manual story picker — includes
  // empty stories so a task can be filed into one that has no tasks yet.
  const storyList = stories.map((s) => ({ storyId: s.storyId, name: s.name }))
  // Matrix rows: one per story (newest first) — including empty stories whose
  // chain has been cleared — plus a single "No story" row for standalone tasks.
  // Columns: the statuses, hiding "Needs Attention" until something actually
  // fails so a healthy board isn't cluttered by an empty one.
  const rows = computeRows(todos, stories)
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
        unread={unread}
        onOpenUnread={setSelectedId}
        onMarkAllRead={markAllRead}
        docsNew={docsNew}
        onAdd={isMobile ? () => setShowComposer((v) => !v) : null}
        addOpen={showComposer}
      />

      {error && <div className="error"><Icon name="alert" size={15} /> {error}</div>}

      {(!isMobile || showComposer) && (
        <div className="board-toolbar">
          <Composer onCreate={createTodo} />
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
          // The story's name label + its status cells are wrapped in ONE subgrid
          // block that carries a single continuous tint + rounded border, so the
          // whole story reads as one light background rectangle with its title at
          // the top-left and every task card sitting on that rectangle (rather than
          // a caption plus four separate boxes). `subgrid` keeps each cell aligned
          // under its status header, and the shared row keeps the cells equal-height.
          const cells = columns.map((col) => (
            <MatrixCell
              key={col.status}
              col={col}
              todos={row.items.filter((t) => t.status === col.status)}
              logs={logs}
              storyMeta={storyMeta}
              tint={tint}
              onOpen={setSelectedId}
            />
          ))
          return (
            <div
              key={row.key}
              className={`story-block ${row.isStandalone ? 'standalone' : 'storied'}`}
              style={tint}
            >
              <StorySwimlane
                row={row}
                tint={tint}
                onDelete={
                  row.isStandalone
                    ? null
                    : () => removeStory(row.storyId, row.name, row.items.length)
                }
              />
              {cells}
            </div>
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
          docIndex={docIndex}
          onSetStory={(storyId) => setTaskStory(selected.id, storyId)}
          onClose={() => setSelectedId(null)}
          onDelete={() => removeTodo(selected.id)}
          onRetry={() => retryTodo(selected.id)}
          onStop={() => stopTodo(selected.id)}
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

function Topbar({ notify, onToggleNotify, unread, onOpenUnread, onMarkAllRead, docsNew, onAdd, addOpen }) {
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
        <NotificationCenter
          unread={unread}
          onOpen={onOpenUnread}
          onMarkAllRead={onMarkAllRead}
        />
        <a
          className={`docs-link ${docsNew > 0 ? 'has-new' : ''}`}
          href="/docs.html"
          title={
            docsNew > 0
              ? `${docsNew} new document${docsNew > 1 ? 's' : ''} — browse reports the agent produced`
              : 'Browse reports & documents the agent produced, rendered as web pages'
          }
        >
          <Icon name="doc" size={16} />
          <span className="label">Docs</span>
          {docsNew > 0 && (
            <span
              className="docs-badge"
              aria-label={`${docsNew} new document${docsNew > 1 ? 's' : ''}`}
            >
              {docsNew > 99 ? '99+' : docsNew}
            </span>
          )}
        </a>
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

// The topbar notification center: a bell carrying a red count badge of unread
// finished tasks (see App's `unread`). Clicking it opens a dropdown listing those
// tasks newest-first; clicking one opens its detail (which marks it read), and a
// "Mark all read" clears the lot. The badge is the persistent counterpart to the
// transient toast — a completed task stays findable here after its toast fades.
function NotificationCenter({ unread, onOpen, onMarkAllRead }) {
  const [open, setOpen] = useState(false)
  const count = unread.length
  const rootRef = useRef(null)

  // Close on an outside click or Escape, like a typical menu. Only wired while
  // open so we're not holding listeners the rest of the time.
  useEffect(() => {
    if (!open) return
    const onDown = (e) => { if (!rootRef.current?.contains(e.target)) setOpen(false) }
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const openTask = (id) => {
    onOpen(id)
    setOpen(false)
  }

  return (
    <div className="notif" ref={rootRef}>
      <button
        type="button"
        className={`notif-btn ${count ? 'has-unread' : ''}`}
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={count ? `${count} unread finished task${count === 1 ? '' : 's'}` : 'No unread tasks'}
        title={count ? `${count} unread finished task${count === 1 ? '' : 's'}` : 'Finished tasks show up here'}
      >
        <Icon name="bell" size={16} />
        {count > 0 && <span className="notif-badge">{count > 99 ? '99+' : count}</span>}
      </button>

      {open && (
        <div className="notif-panel" role="menu">
          <div className="notif-head">
            <span className="notif-title">Finished tasks</span>
            {count > 0 && (
              <button type="button" className="notif-clear" onClick={onMarkAllRead}>
                Mark all read
              </button>
            )}
          </div>
          {count === 0 ? (
            <p className="notif-empty">You're all caught up — finished tasks will appear here.</p>
          ) : (
            <ul className="notif-list">
              {unread.map((u) => {
                const done = u.status === 'RESOLVED'
                return (
                  <li key={u.id}>
                    <button
                      type="button"
                      className="notif-item"
                      role="menuitem"
                      onClick={() => openTask(u.id)}
                    >
                      <span className={`notif-dot ${done ? 'done' : 'fail'}`} aria-hidden="true">
                        <Icon name={done ? 'check' : 'alert'} size={13} />
                      </span>
                      <span className="notif-item-body">
                        <span className="notif-item-title">{u.title}</span>
                        <span className="notif-item-sub">
                          #{u.id} · {done ? 'Done' : 'Needs attention'} · {relTime(new Date(u.at).toISOString())}
                        </span>
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      )}
    </div>
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
function StorySwimlane({ row, tint, onDelete }) {
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
  const empty = row.items.length === 0
  return (
    <div
      className={`swimlane storied ${row.status.toLowerCase()}`}
      style={{ ...tint, gridColumn: '1 / -1' }}
    >
      <span className="swimlane-name" title={row.name}>{row.name}</span>
      {planning && <span className="swimlane-sub">Planning…</span>}
      {planFailed && <span className="swimlane-sub">Plan failed — open the card</span>}
      {empty && !row.planner && <span className="swimlane-sub">No tasks — add one or delete the story</span>}
      {onDelete && (
        <button
          type="button"
          className="swimlane-delete"
          onClick={onDelete}
          title="Delete this story"
          aria-label={`Delete story ${row.name}`}
        >
          <Icon name="delete" size={14} />
        </button>
      )}
    </div>
  )
}

// One cell of the matrix: the tasks of a given row that are currently in this
// column's status. All columns are worker-driven — the board reflects each task's
// lifecycle rather than being reordered by hand.
function MatrixCell({ col, todos, logs, storyMeta, tint, onOpen }) {
  return (
    <div
      className={`matrix-cell ${col.status.toLowerCase()} ${tint ? 'storied' : ''}`}
      style={tint}
    >
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
  )
}

// ---- Mobile layout -------------------------------------------------------
// The desktop matrix (story-rows × status-columns) is far too wide for a phone,
// so on narrow screens App renders this instead: a scrollable status-tab bar
// picks ONE status, and that status's cards are listed vertically, grouped under
// their story. Reuses the same Card component and the same rows/columns data.
function MobileBoard({ rows, columns, logs, storyMeta, onOpen, onDelete }) {
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

function Composer({ onCreate }) {
  const [title, setTitle] = useState('')
  const [desc, setDesc] = useState('')
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  // Whether this task is about improving the Kanban project itself. Off by
  // default: a plain task is a general task, not aimed at the project.
  const [projectImprovement, setProjectImprovement] = useState(false)
  // Whether to hand this task OUT instead of running it here (代理任务). The
  // worker never touches a delegated task; it sits in To Do and is published on
  // the public /api/public/tasks feed for an outside worker to pick up and
  // report back on. ON by default: most tasks filed here are meant for an
  // outside worker, so the checkbox starts ticked and is unticked to run the
  // task on this server instead.
  const [delegated, setDelegated] = useState(true)
  // Images already uploaded for this (not yet created) task. Each was stored
  // server-side the moment it was picked, so we have a real URL to preview and
  // an id to hand to createTodo. See useImageUploads.
  const images = useImageUploads()

  function clear() {
    setTitle('')
    setDesc('')
    setProjectImprovement(false)
    setDelegated(true)
    images.reset()
    setOpen(false)
  }

  // File the task as a standalone item. The composer no longer suggests or
  // attaches to an existing story — assign a task to a story afterwards from
  // its detail drawer if needed.
  async function submit(e) {
    e.preventDefault()
    const t = title.trim()
    if (!t || busy) return
    setBusy(true)
    try {
      await onCreate(t, desc.trim(), projectImprovement, images.ids(), delegated)
      // The task now owns the uploads — forget them without deleting the files.
      images.forget()
      clear()
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className={`composer ${images.dragging ? 'dropping' : ''}`}
      onSubmit={submit}
      onDragOver={images.onDragOver}
      onDragLeave={images.onDragLeave}
      onDrop={(e) => { setOpen(true); images.onDrop(e) }}
    >
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
            placeholder="Details / context for the agent (optional) — you can paste a screenshot here"
            value={desc}
            onChange={(e) => setDesc(e.target.value)}
            onPaste={images.onPaste}
            rows={3}
          />
          <ImageAttacher images={images} />
          <label className="composer-flag" title="Mark this task as one that improves the Kanban project itself (its code, docs, tests). Off = a general task.">
            <input
              type="checkbox"
              checked={projectImprovement}
              onChange={(e) => setProjectImprovement(e.target.checked)}
            />
            Improves this Kanban project itself
          </label>
          <label className="composer-flag" title="Delegated (代理) task: this server never runs it. It is published on the public API (/api/public/tasks) for an outside worker to pick up, do, and report back on.">
            <input
              type="checkbox"
              checked={delegated}
              onChange={(e) => setDelegated(e.target.checked)}
            />
            Delegate to an outside worker (public API)
          </label>
          <div className="composer-actions">
            <button type="submit" className="btn primary" disabled={!title.trim() || busy || images.uploading}>
              {busy ? 'Adding…' : images.uploading ? 'Uploading…' : 'Add task'}
            </button>
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                setOpen(false)
                setDesc('')
                images.reset()
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

// Uploading images for a task that doesn't exist yet.
//
// Each picked file is sent to the server straight away (POST /api/attachments),
// which stores it on disk and returns an id + url. That's what lets the composer
// show a real thumbnail, and it's what the agent later opens — the point of the
// feature is that the image is readable *server-side*, not just in the browser.
// Submitting the task claims the uploads by id; cancelling deletes them again so
// abandoned picks don't pile up.
function useImageUploads() {
  const [items, setItems] = useState([]) // [{ id, fileName, url, sizeBytes }]
  const [uploading, setUploading] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [error, setError] = useState(null)

  const MAX = 10 // matches the server's per-task cap

  async function add(fileList) {
    const files = Array.from(fileList || []).filter((f) => f.type.startsWith('image/'))
    if (!files.length) return
    setError(null)
    setUploading(true)
    try {
      for (const file of files) {
        let full = false
        setItems((prev) => {
          full = prev.length >= MAX
          return prev
        })
        if (full) {
          setError(`At most ${MAX} images per task.`)
          break
        }
        try {
          const saved = await api.uploadAttachment(file)
          setItems((prev) => (prev.length >= MAX ? prev : [...prev, saved]))
        } catch (e) {
          setError(uploadError(e, file))
        }
      }
    } finally {
      setUploading(false)
    }
  }

  async function remove(id) {
    setItems((prev) => prev.filter((i) => i.id !== id))
    try {
      await api.deleteAttachment(id)
    } catch {
      // The thumbnail is gone from the composer either way; a stray file is
      // swept up by the server's unclaimed-upload cleanup.
    }
  }

  // Cancelled composer: drop the uploads server-side too.
  function reset() {
    setItems((prev) => {
      prev.forEach((i) => api.deleteAttachment(i.id).catch(() => {}))
      return []
    })
    setError(null)
  }

  // Submitted: the task owns the files now, so just clear our list.
  function forget() {
    setItems([])
    setError(null)
  }

  function onPaste(e) {
    const files = Array.from(e.clipboardData?.files || [])
    if (files.some((f) => f.type.startsWith('image/'))) {
      e.preventDefault()
      add(files)
    }
  }

  function onDragOver(e) {
    if (!Array.from(e.dataTransfer?.types || []).includes('Files')) return
    e.preventDefault()
    setDragging(true)
  }

  function onDragLeave() {
    setDragging(false)
  }

  function onDrop(e) {
    if (!e.dataTransfer?.files?.length) return
    e.preventDefault()
    setDragging(false)
    add(e.dataTransfer.files)
  }

  return {
    items,
    uploading,
    dragging,
    error,
    add,
    remove,
    reset,
    forget,
    onPaste,
    onDragOver,
    onDragLeave,
    onDrop,
    ids: () => items.map((i) => i.id),
  }
}

// The server answers a rejected upload with a plain-text reason (wrong type, too
// big); surface that rather than a bare status line.
function uploadError(e, file) {
  const detail = String(e?.message || '')
  const match = detail.match(/"message"\s*:\s*"([^"]+)"/)
  return `${file.name}: ${match ? match[1] : detail || 'upload failed'}`
}

// Thumbnail strip + "Attach image" button shown inside the composer.
function ImageAttacher({ images }) {
  const inputRef = useRef(null)
  return (
    <div className="attach">
      <div className="attach-row">
        <button
          type="button"
          className="btn ghost attach-btn"
          onClick={() => inputRef.current?.click()}
          title="Attach an image the agent should look at (or paste / drop one here)"
        >
          <Icon name="image" size={14} /> Attach image
        </button>
        <span className="attach-hint">or paste / drop a screenshot</span>
        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          multiple
          hidden
          onChange={(e) => {
            images.add(e.target.files)
            e.target.value = '' // let the same file be picked again
          }}
        />
      </div>
      {images.error && <p className="attach-error">{images.error}</p>}
      {(images.items.length > 0 || images.uploading) && (
        <ul className="attach-thumbs">
          {images.items.map((img) => (
            <li key={img.id} className="attach-thumb">
              <img src={img.url} alt={img.fileName} />
              <button
                type="button"
                className="attach-remove"
                onClick={() => images.remove(img.id)}
                aria-label={`Remove ${img.fileName}`}
                title="Remove"
              >
                ×
              </button>
            </li>
          ))}
          {images.uploading && <li className="attach-thumb pending">…</li>}
        </ul>
      )}
    </div>
  )
}

// Declare a story: a name, and nothing else. A story is purely a category to
// group tasks under — creating one files no task, so the new story appears on the
// board as an empty row that tasks land in later (auto-classified when created, or
// filed by hand from a task's drawer).
function StoryComposer({ onCreateStory }) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)

  function reset() {
    setOpen(false)
    setName('')
  }

  const canSubmit = name.trim() && !busy

  async function submit(e) {
    e.preventDefault()
    if (!canSubmit) return
    setBusy(true)
    try {
      await onCreateStory(name.trim())
      reset()
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button className="story-add" onClick={() => setOpen(true)} title="Name a category to group tasks under — no tasks are created">
        <span className="story-add-icon"><Icon name="story" size={16} /></span> New story
      </button>
    )
  }

  return (
    <form className="composer story-form" onSubmit={submit}>
      <input
        className="composer-title"
        placeholder="Name a story / category (e.g. Dark mode)"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={200}
        autoFocus
      />
      <p className="story-hint">
        Just a category — no tasks are created. New tasks that match it are filed here automatically,
        or pick this story from any task's details.
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
// contain nested interactive content.
function Card({ todo, tail, story, onOpen }) {
  const running = todo.status === 'IN_PROGRESS'
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
      className={`card ${todo.status.toLowerCase()}`}
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={onKeyDown}
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
      {todo.delegated && (
        <span className="card-tag delegated" title={todo.delegatedTo
          ? `Delegated task — claimed by ${todo.delegatedTo} through the public API`
          : 'Delegated task — this server never runs it; an outside worker picks it up from the public API'}>
          <Icon name="delegate" size={12} /> {todo.delegatedTo || 'Delegated'}
        </span>
      )}
      {todo.attachments?.length > 0 && (
        <span className="card-tag image" title={`${todo.attachments.length} attached image(s) the agent will read`}>
          <Icon name="image" size={12} /> {todo.attachments.length}
        </span>
      )}
      <div className="card-title">{todo.title}</div>
      {showDesc && <div className="card-desc">{todo.description}</div>}

      {running && (
        <div className="ticker">
          <span className="ticker-pulse" />
          <span className="ticker-text">
            {todo.delegated
              ? `Claimed by ${todo.delegatedTo || 'an outside worker'} — running elsewhere`
              : tail || 'Starting session…'}
          </span>
        </div>
      )}

      {todo.status === 'FAILED' && (
        <div className="card-fail">{firstLines(todo.result, 2) || 'Failed'}</div>
      )}

      <div className="card-foot">
        <span className="card-id">#{todo.id}</span>
        {costMeta(todo) && <span className="card-cost">{costMeta(todo)}</span>}
        <span className="card-time">{relTime(todo.updatedAt)}</span>
      </div>
    </div>
  )
}

function Drawer({ todo, log, story, stories = [], docIndex, onSetStory, onClose, onDelete, onRetry, onStop }) {
  const running = todo.status === 'IN_PROGRESS'
  const canRetry = todo.status === 'FAILED' || todo.status === 'RESOLVED'
  const [retrying, setRetrying] = useState(false)
  const [stopping, setStopping] = useState(false)

  async function retry() {
    if (retrying) return
    setRetrying(true)
    try {
      await onRetry()
    } finally {
      setRetrying(false)
    }
  }

  async function stop() {
    if (stopping) return
    setStopping(true)
    try {
      await onStop()
    } finally {
      setStopping(false)
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
            {running && onStop && !todo.delegated && (
              <button
                className="icon-btn danger"
                onClick={stop}
                disabled={stopping}
                title="Stop the running session"
                aria-label="Stop task"
              >
                <Icon name="stop" />
              </button>
            )}
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

        {todo.delegated && (
          <div className="drawer-section">
            <div className="section-label">Delegated</div>
            <p className="drawer-delegated">
              {todo.delegatedTo
                ? `Claimed by ${todo.delegatedTo} ${relTime(todo.delegateClaimedAt)}.`
                : 'Waiting for an outside worker to pick it up.'}
              {' '}This server never runs it — it is published on the public API,
              and whoever does the work reports the outcome back there.
            </p>
            <code className="drawer-delegated-api">GET /api/public/tasks · POST /api/public/tasks/{todo.id}/status</code>
          </div>
        )}

        {todo.description && (
          <div className="drawer-section">
            <div className="section-label">Details</div>
            <p className="drawer-desc">{todo.description}</p>
          </div>
        )}

        {todo.attachments?.length > 0 && (
          <div className="drawer-section">
            <div className="section-label">
              Attached images ({todo.attachments.length})
            </div>
            <ul className="attach-thumbs">
              {todo.attachments.map((img) => (
                <li key={img.id} className="attach-thumb">
                  {/* Full size in a new tab — the thumbnail is only a preview. */}
                  <a href={img.url} target="_blank" rel="noreferrer" title={img.fileName}>
                    <img src={img.url} alt={img.fileName} />
                  </a>
                </li>
              ))}
            </ul>
            <p className="attach-note">The agent reads these files as part of the task.</p>
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
              // raw error/log text, so keep it monospaced and unparsed. For a
              // successful result we first rewrite any internal doc-file references
              // (docs/foo.md, …) into links to the published page the user can open.
              ? (todo.status === 'FAILED'
                  ? <pre className="logbox result">{todo.result}</pre>
                  : <Markdown className="logbox result" text={linkifyDocRefs(todo.result, docIndex)} />)
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
//
// `stories` is the persisted-story list (/api/stories); any story with no tasks
// isn't in the todo-derived set, so it's merged in as an empty row here. That's
// what keeps an empty story on the board instead of vanishing with its last task.
function computeRows(todos, stories = []) {
  const populated = computeStories(todos)
  const seen = new Set(populated.map((s) => s.storyId))
  const empty = stories
    .filter((s) => !seen.has(s.storyId))
    .map((s) => ({
      storyId: s.storyId,
      name: s.name || 'Story',
      planner: null,
      steps: [],
      items: [],
      done: 0,
      total: 0,
      status: 'PENDING',
      newest: new Date(s.createdAt || 0).getTime(),
    }))
  const rows = [...populated, ...empty]
    .sort((a, b) => b.newest - a.newest)
    .map((s) => ({ ...s, key: s.storyId, isStandalone: false }))
  const standalone = todos.filter((t) => !t.storyId)
  if (standalone.length) {
    rows.push({ key: '__standalone', name: 'No story', isStandalone: true, items: standalone, steps: standalone })
  }
  return rows
}

// Roll a story's steps up to one status for its pill: anything failed needs
// attention; all resolved is done; anything running is in progress; otherwise
// it's queued (To Do).
function rollupStatus(steps) {
  if (steps.some((s) => s.status === 'FAILED')) return 'FAILED'
  if (steps.every((s) => s.status === 'RESOLVED')) return 'RESOLVED'
  if (steps.some((s) => s.status === 'IN_PROGRESS')) return 'IN_PROGRESS'
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

// Both the unread finished-task list and the "docs seen" set used to live in this
// browser's localStorage. They're now server-side properties (a task's `unread`
// flag and the /api/docs/reads set), so the badges stay consistent across every
// device the same user opens the board from — see App's `unread` memo and the
// docs-manifest effect. Only the notify preference below is genuinely per-browser.

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
