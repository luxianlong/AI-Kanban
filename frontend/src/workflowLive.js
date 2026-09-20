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
 * Whether the output on a step was produced by settings that have since been edited.
 *
 * A run holds its own copy of every step — prompt, effort and wiring — and lets a
 * finished step's copy be rewritten, so that one step can be re-run against a new
 * version of itself without paying for the graph above it. Between the edit and the
 * re-run the panel shows a step reading "Done", an answer, and boxes that ask
 * something else — three things that look like one story and are two. This is what
 * the page labels instead.
 *
 * A rename is deliberately not one of them, and the server is where that is decided:
 * it leaves the stamp alone, because the name is the one setting that cannot change a
 * character of what the session was asked to do.
 *
 * The stamps are parsed rather than compared as strings: both are ISO instants, but
 * their fractional seconds are only as long as they need to be, so "…:30Z" sorts
 * after "…:30.5Z" lexically and before it in fact.
 */
export function outputPredatesSettings(run) {
  if (!run?.settingsEditedAt || !run.finishedAt) return false
  return Date.parse(run.settingsEditedAt) > Date.parse(run.finishedAt)
}

/**
 * The steps whose output was computed from an input that has since changed —
 * because a step above them was re-run on its own, without them.
 *
 * Re-running one step alone is deliberately cheap: it redoes that step and leaves
 * the rest of the run standing. The cost is that the rest of the run is now an
 * answer to an earlier version of the question. Nothing about a row says so — it
 * still reads "Done", with an output that was right when it was written — so this
 * is what the page labels, the same way `outputPredatesSettings` labels a step whose
 * prompt moved out from under its answer.
 *
 * A step is stale when anything it transitively depends on finished after it did.
 * In a run nobody has touched that is impossible: a step cannot start until its
 * upstream has finished, so upstream stamps are always earlier. The comparison is
 * therefore only ever true because someone re-ran the upstream since. Propagation
 * is a fixpoint rather than one pass over the direct edges, because a step two
 * below the re-run one still finished after its own parent — it is stale by
 * inheritance, not by its own stamp.
 *
 * Returns a Set of node ids. Steps with no `finishedAt` (queued, running, or being
 * re-run right now) are never in it: there is no output on them to mislead anyone.
 */
export function nodesWithStaleInput(nodes) {
  const stale = new Set()
  if (!Array.isArray(nodes)) return stale
  const finishedAt = new Map()
  for (const n of nodes) {
    if (n?.finishedAt) finishedAt.set(n.nodeId, Date.parse(n.finishedAt))
  }
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes) {
      if (!node?.finishedAt || stale.has(node.nodeId)) continue
      const own = finishedAt.get(node.nodeId)
      const inherited = (node.dependsOn || []).some(
        (up) => stale.has(up) || (finishedAt.has(up) && finishedAt.get(up) > own),
      )
      if (inherited) {
        stale.add(node.nodeId)
        changed = true
      }
    }
  }
  return stale
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

/**
 * Which step a run started from — the one drawn and labelled as the entry.
 *
 * The run records its own entry, and that is the answer wherever it exists: a
 * topological order can legitimately put a different node first, so "the first row"
 * is a guess that is right most of the time, which is the worst kind. It stays as the
 * fallback only for runs recorded before the entry was snapshotted, where it is the
 * only answer available.
 */
export function entryNodeIdOf(execution) {
  if (!execution) return null
  return execution.entryNodeId ?? execution.nodes?.[0]?.nodeId ?? null
}

/** How often the runs pane re-reads the executions while something is still going. */
export const LIVE_POLL_MS = 2500
/** And once everything has settled, when the only news would come from elsewhere. */
export const IDLE_POLL_MS = 8000

/**
 * How often to poll the runs — or 0 for "don't", which is what a tab nobody is
 * looking at gets.
 *
 * A hidden tab's interval is not a poll. Chrome clamps a background timer to once a
 * second, drops it to once a *minute* after five minutes out of sight, and may
 * freeze the page altogether; a phone suspends the tab outright when you switch
 * apps. So the page you come back to is showing whatever it had when you looked
 * away, and it stays that way for as long as the browser feels like — which is the
 * whole of "the workflow page doesn't refresh itself". Nothing here can make a
 * background timer reliable, so this stops pretending: while the page is hidden it
 * does not poll at all, and the moment it is looked at again it refetches
 * everything (see the effect in Workflows.jsx). The board has always done that
 * — see App.jsx's focus/visibilitychange block — and this page never had it.
 *
 * That also means the poll is only ever timing the gap between two things the user
 * is watching happen, which is why it can stay as short as it is.
 */
export function pollInterval(anyLive, hidden) {
  if (hidden) return 0
  return anyLive ? LIVE_POLL_MS : IDLE_POLL_MS
}

/**
 * What a run's state is called on screen. Lives here rather than in the page because
 * the delete confirm has to use the same words the card does: a dialog that says
 * "SUCCEEDED" about a run labelled "Done" is asking about something else, and this
 * particular dialog is the last thing standing between somebody and work they cannot
 * get back.
 */
export function stateLabel(status) {
  return {
    PENDING: 'Queued',
    RUNNING: 'Running',
    SUCCEEDED: 'Done',
    FAILED: 'Failed',
    SKIPPED: 'Skipped',
    CANCELLED: 'Cancelled',
  }[status] || status
}

/**
 * The steps of a run that are waiting for the user to start them.
 *
 * `awaitingStart` is the server's own answer — this step is marked manual, its input is
 * ready, and nobody has released it — so the page never re-derives the rule that the work
 * source acts on. Nearly always one step, but not necessarily: two branches can both be
 * ready at once, and then both are waiting and each needs its own go-ahead.
 */
export function stepsAwaitingStart(execution) {
  return (execution?.nodes || []).filter((n) => n.awaitingStart)
}

/**
 * What a step's state is called on screen.
 *
 * A step waiting to be started is PENDING, the same as one whose turn has simply not come
 * — and on the page those are opposite situations. One will start by itself in a minute;
 * the other will sit there until somebody presses something, and "Queued" is exactly the
 * word that would stop them looking.
 */
export function nodeStateLabel(run) {
  if (run?.awaitingStart) return 'Waiting for you'
  return stateLabel(run?.status)
}

/**
 * What a run's state is called on the collapsed card, where the run's own status is
 * RUNNING while it is in fact waiting for a person and nothing is running at all.
 *
 * Short on purpose — it shares a pill with five other words and sits next to the step
 * count. The sentence naming the step is inside the card, on the button that ends the wait.
 */
export function executionStateLabel(execution) {
  if (isLive(execution) && stepsAwaitingStart(execution).length) return 'Waiting'
  return stateLabel(execution?.status)
}

/**
 * How long Delete stays out of reach after a run settles.
 *
 * Not a nicety. Until now Cancel and Delete were the same slot in the same row: a
 * running card offered "Cancel run" there, and the moment the run finished — which
 * the poll notices within 2.5s — the identical position became "Delete run", with the
 * same confirm-and-go behaviour and no undo. Pressing Cancel on a run that finished
 * while your hand was moving deleted it instead, and the confirm ("Delete this run and
 * everything it produced?") reads perfectly naturally to someone in the act of
 * cancelling. That is card #333's best explanation for run #199: SUCCEEDED, 1,399
 * characters of output, $0.1116, gone.
 *
 * The buttons are now in different places, which is the real fix. This is the second
 * half of it, for the click already in flight when the layout moved.
 */
export const SETTLE_GRACE_MS = 6000

/** Whether a run is still going, in the one place that decides it. */
export function isLive(execution) {
  return execution?.status === 'PENDING' || execution?.status === 'RUNNING'
}

/**
 * Whether Delete should be held back, given when this card last saw the run live.
 *
 * `settledAt` is null for a run that was already finished when the card was opened —
 * nobody was reaching for Cancel on that one, so it has nothing to be protected from
 * and Delete is available immediately.
 */
export function deleteHeldBack(settledAt, now) {
  if (settledAt == null) return false
  return now - settledAt < SETTLE_GRACE_MS
}

/**
 * What this run was called by hand, or '' if nobody called it anything.
 *
 * The server stores null for an unnamed run and never fills the field in with the
 * workflow's name, so "unnamed" survives the wire — which is the whole point: every
 * run of a workflow already carries that workflow's name, so a page that could not
 * tell a name from a fallback would have nothing left to tell two runs apart by.
 *
 * Trimmed here as well as on the server because the input is read while it is being
 * typed: a box holding one space must not make the row look renamed.
 */
export function runName(execution) {
  return (execution?.name || '').trim()
}

/**
 * The two pieces of the collapsed row's title: what to call the run, and what to say
 * after it.
 *
 * `showWorkflowName` is false inside a single workflow's panel, where repeating its
 * name on every row says nothing. A run with a name of its own is the case that
 * changes: the name goes where the workflow's name went, and the workflow's name
 * becomes the context behind it — but only in the list where rows from different
 * workflows sit together, since that is the only place it is still an answer to
 * anything.
 */
export function runRowTitle(execution, showWorkflowName) {
  const own = runName(execution)
  const workflow = execution?.workflowName || ''
  if (own) return { label: own, context: showWorkflowName ? workflow : '' }
  return { label: showWorkflowName ? workflow : '', context: '' }
}

/**
 * Where a step's answer came from, for the subtitle of the page that opens when one
 * is maximised — read on its own, away from the list, with nothing else to say which
 * run it belongs to.
 *
 * The workflow's name stays even on a named run. A run is named to be told apart from
 * its siblings, so on its own the name alone ("Tighter brief") answers the smaller
 * question and drops the larger one.
 */
export function runWhere(execution) {
  const own = runName(execution)
  const tail = `${execution?.workflowName || ''} · Run #${execution?.id}`
  return own ? `${own} · ${tail}` : tail
}

/**
 * The confirm shown before a run is destroyed.
 *
 * It names the run rather than describing the act, because "Delete this run and
 * everything it produced?" is true of every run and therefore tells you nothing about
 * the one under the cursor. The id is what is on the card, its own name is what you
 * know it by when it has one, and the state and the cost are how you recognise the run
 * you did NOT mean to be deleting — a "Done, $0.11" in a dialog you opened to tidy up
 * failures is the moment to stop.
 */
export function deleteRunPrompt(execution) {
  if (!execution) return 'Delete this run and everything it produced?'
  const parts = [stateLabel(execution.status)]
  const steps = execution.totalNodes
  if (steps) parts.push(`${steps} step${steps === 1 ? '' : 's'}`)
  if (execution.costUsd != null) parts.push(`$${execution.costUsd.toFixed(4)}`)
  const workflow = execution.workflowName ? ` of "${execution.workflowName}"` : ''
  // The name the user gave it comes first, because it is the one they know the run by
  // — the id in the dialog is not what they were reading when they reached for Delete.
  const own = runName(execution) ? ` "${runName(execution)}"` : ''
  return `Delete run #${execution.id}${own}${workflow} (${parts.join(', ')})?\n\n`
    + 'Its output cannot be recovered.'
}

/**
 * The confirm shown before a workflow — and every run it ever had — is destroyed.
 *
 * `total` comes from the server, not from the runs on screen. The panel lists at most
 * a page of runs, and the browser used to count that page: above the cap a workflow
 * with a hundred runs counted zero, so the dialog quietly dropped the clause about run
 * history altogether and then the cascade deleted all hundred. `null` means the count
 * could not be fetched, which is said out loud rather than rounded down to nothing.
 */
export function deleteWorkflowPrompt(name, total) {
  if (total == null) {
    return `Delete "${name}"?\n\nIts run history goes too, and this page could not reach `
      + 'the server to find out how much of it there is. Nothing deleted can be recovered.'
  }
  if (!total) return `Delete "${name}"?\n\nIt has no runs.`
  return `Delete "${name}" and its ${total} run${total === 1 ? '' : 's'}?\n\n`
    + 'The runs and everything they produced cannot be recovered.'
}

/**
 * The step whose answer is the run's answer — what the collapsed row's ⤢ opens.
 *
 * A run is a graph, so "the last one" is not simply the bottom of the list: the rows
 * are in a topological order, and any of several tails could be last in it. What ends
 * a run is the step nothing else reads — a sink. Among the sinks (there is usually
 * one, but a fan-out ends in several) the one that finished last is the one the run
 * came to rest on.
 *
 * Only steps that produced something of their own are eligible, and that is what
 * makes the answer useful mid-run and on a run that broke: while a run is going its
 * sinks have written nothing yet, and a run that failed at step three never reached
 * its sink at all — in both cases the rule falls back to the furthest step that did
 * produce something, so the button opens the failure that stopped the run rather than
 * nothing at all. The card words the button from `isLive` so it never calls that "the
 * final step".
 *
 * "Produced something" is narrower than "has an `error`": a SKIPPED or CANCELLED step
 * carries one too, and it is boilerplate about never having run ("Skipped: the node it
 * depends on did not succeed"). Those are exactly the sinks of a run that broke, so
 * counting them would hand every failed run its least informative row and bury the
 * failure that actually stopped it one step above.
 *
 * Returns the node run itself, or null when the run has produced nothing yet.
 */
export function finalStepOf(execution) {
  const nodes = execution?.nodes || []
  const spoke = nodes.filter((n) => n && (n.output || (n.error && n.status === 'FAILED')))
  if (!spoke.length) return null
  const feeds = new Set(nodes.flatMap((n) => n?.dependsOn || []))
  const sinks = spoke.filter((n) => !feeds.has(n.nodeId))
  return (sinks.length ? sinks : spoke).reduce((best, n) => (settledLater(n, best) ? n : best))
}

/**
 * Which of two steps came to rest later. `finishedAt` is the real answer; a step that
 * has output but no stamp (an older row) falls back to its place in the run order, so
 * the comparison is still total and never depends on array position.
 */
function settledLater(a, b) {
  const at = a.finishedAt ? Date.parse(a.finishedAt) : -Infinity
  const bt = b.finishedAt ? Date.parse(b.finishedAt) : -Infinity
  if (at !== bt) return at > bt
  return (a.orderIndex ?? 0) > (b.orderIndex ?? 0)
}
