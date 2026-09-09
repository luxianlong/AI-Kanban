// Folding a fresh poll of the runs into the ones already on screen. Split out of
// Workflows.jsx for the same reason workflowGraph.js was: it is a function of plain
// data, so it can be tested in bare node.
//
// The runs pane polls every 2.5s while anything is running. Naively that means
// `setExecutions(await api.listExecutions())` — a brand new array of brand new
// objects — so every card re-renders four times a minute whether or not it changed,
// and a step finishing in run #7 disturbs the output someone is reading in run #5.
// Reading a step's output is the slowest thing anyone does on this page, and it is
// exactly what the poll was trampling.
//
// So: keep the object that is already on screen whenever the poll brought back the
// same thing. Identity is the signal React uses — an unchanged execution passed to a
// memo'd card skips the re-render entirely, and the DOM the user is reading (with
// its scroll position, its text selection) is never touched.

/** Structural equality over the JSON the API returns. No cycles, no dates, no maps. */
export function sameValue(a, b) {
  if (a === b) return true
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a)) {
    if (a.length !== b.length) return false
    return a.every((item, i) => sameValue(item, b[i]))
  }
  const keys = Object.keys(a)
  if (keys.length !== Object.keys(b).length) return false
  return keys.every((k) => Object.prototype.hasOwnProperty.call(b, k) && sameValue(a[k], b[k]))
}

/**
 * The next executions list, reusing every object from `prev` that came back
 * unchanged — and reusing `prev` itself when the whole poll was a no-op, so the
 * page doesn't even re-render.
 *
 * A run that moved position counts as a change of the list (the cards are in a
 * different order on screen) but not of the run, so the moved card keeps its
 * identity and still doesn't re-render.
 */
export function mergeExecutions(prev, next) {
  if (!Array.isArray(next)) return prev
  if (!Array.isArray(prev)) return next
  const byId = new Map(prev.map((e) => [e.id, e]))
  let changed = prev.length !== next.length
  const merged = next.map((incoming, i) => {
    const old = byId.get(incoming.id)
    if (old && sameValue(old, incoming)) {
      if (prev[i] !== old) changed = true
      return old
    }
    changed = true
    return incoming
  })
  return changed ? merged : prev
}

/**
 * Whether the output on a step was produced by a prompt that has since been edited.
 *
 * A run holds its own copy of every prompt and lets a finished step's copy be
 * rewritten, so that one step can be re-run against a new wording without paying for
 * the graph above it. Between the edit and the re-run the panel shows a step reading
 * "Done", an answer, and a prompt box that asks something else — three things that
 * look like one story and are two. This is what the page labels instead.
 *
 * The stamps are parsed rather than compared as strings: both are ISO instants, but
 * their fractional seconds are only as long as they need to be, so "…:30Z" sorts
 * after "…:30.5Z" lexically and before it in fact.
 */
export function outputPredatesPrompt(run) {
  if (!run?.promptEditedAt || !run.finishedAt) return false
  return Date.parse(run.promptEditedAt) > Date.parse(run.finishedAt)
}

/**
 * Which step a run card should have open, given what it had open and what is
 * running now.
 *
 * A card follows the run by default — the running step is the one with a live log,
 * and on a run you just started it is the only thing worth looking at. But once the
 * user has picked a step themselves they are reading it, and the next step starting
 * must not yank the panel away. Following is therefore sticky-off: `following` goes
 * false on the first deliberate pick and only the user turns it back on.
 *
 * Returns the node id to open, or `openNodeId` unchanged when there is nothing to
 * follow — never null, so this can't close a panel on its own.
 */
export function followedNode(openNodeId, runningNodeId, following) {
  if (!following || !runningNodeId) return openNodeId
  return runningNodeId
}
