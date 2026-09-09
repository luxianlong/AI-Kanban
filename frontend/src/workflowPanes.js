// Which of the workflow page's three panes are folded away, and what that does to
// the grid. Split out of Workflows.jsx for the same reason workflowGraph.js was:
// it is arithmetic over plain data, so it can be tested in bare node.

/** Width of a folded pane — a spine wide enough for a chevron and vertical label. */
export const STRIP_W = '44px'

/** The panes, left to right. `key` is what the collapse state is keyed by. */
export const PANES = [
  { key: 'list', label: 'Workflows' },
  { key: 'editor', label: 'Editor' },
  { key: 'runs', label: 'Executions' },
]

export const NONE_COLLAPSED = { list: false, editor: false, runs: false }

/**
 * The three grid tracks for `.wf-body`, given which panes are folded.
 *
 * A folded pane gives up its width, and one of the survivors has to take it or the
 * page ends in dead space. The editor wants it first (it holds the graph, the whole
 * reason for folding the others), then the runs pane; the list only stretches when
 * it is all that is left, and even then it keeps a cap so a column of two-line
 * buttons doesn't span the window.
 */
export function paneColumns(collapsed) {
  const grower = !collapsed.editor ? 'editor'
    : !collapsed.runs ? 'runs'
      : !collapsed.list ? 'list' : null
  return [
    collapsed.list ? STRIP_W : grower === 'list' ? 'minmax(232px, 520px)' : '232px',
    collapsed.editor ? STRIP_W : 'minmax(0, 1fr)',
    collapsed.runs ? STRIP_W
      : grower === 'runs' ? 'minmax(320px, 1fr)' : 'minmax(320px, 420px)',
  ]
}

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------
// Which panes you fold is a per-browser habit, not a property of the workflow, so
// it lives in localStorage next to the notification preference. Wrapped in
// try/catch because storage throws in private mode.

const KEY = 'kanban.wf.panes'

/** Keeps only known keys and real booleans, so a hand-edited value can't wedge the page. */
export function normalizeCollapsed(raw) {
  const out = { ...NONE_COLLAPSED }
  if (raw && typeof raw === 'object') {
    for (const { key } of PANES) if (raw[key] === true) out[key] = true
  }
  return out
}

export function readCollapsed(storage = globalThis.localStorage) {
  try {
    return normalizeCollapsed(JSON.parse(storage.getItem(KEY)))
  } catch {
    return { ...NONE_COLLAPSED }
  }
}

export function writeCollapsed(collapsed, storage = globalThis.localStorage) {
  try {
    storage.setItem(KEY, JSON.stringify(normalizeCollapsed(collapsed)))
  } catch {
    /* ignore — the panes just won't remember */
  }
}
