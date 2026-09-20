// node src/workflowLive.test.mjs
import assert from 'node:assert/strict'
import {
  IDLE_POLL_MS, LIVE_POLL_MS, SETTLE_GRACE_MS, deleteHeldBack, deleteRunPrompt,
  deleteWorkflowPrompt, entryNodeIdOf, executionStateLabel, finalStepOf, followedNode, isLive,
  mergeExecutions, nodeStateLabel, nodesWithStaleInput, outputPredatesSettings, pollInterval,
  sameValue, stateLabel, stepsAwaitingStart,
} from './workflowLive.js'

const node = (over = {}) => ({
  id: 1, nodeId: 'a', nodeName: 'Step', status: 'SUCCEEDED', output: 'hi', dependsOn: [], ...over,
})
const run = (over = {}) => ({
  id: 5, workflowId: 2, workflowName: 'W', status: 'SUCCEEDED', doneNodes: 1, totalNodes: 1,
  createdAt: '2026-09-09T00:00:00Z', nodes: [node()], ...over,
})

// --- sameValue ------------------------------------------------------------

assert.ok(sameValue(run(), run()))
assert.ok(sameValue(null, null))
assert.ok(!sameValue(null, {}))
assert.ok(!sameValue({ a: 1 }, { a: '1' }))
// A key present on one side only is a difference even when its value is undefined:
// `{a:1}` and `{a:1,b:undefined}` are not the same row.
assert.ok(!sameValue({ a: 1 }, { a: 1, b: undefined }))
assert.ok(!sameValue([1, 2], [1, 2, 3]))
// An array and an object with the same keys are not equal.
assert.ok(!sameValue([1], { 0: 1 }))
// The difference that matters most is buried two levels down.
assert.ok(!sameValue(run(), run({ nodes: [node({ output: 'hi there' })] })))

// --- mergeExecutions ------------------------------------------------------

// A poll that brought back exactly what is on screen keeps the array itself, so
// React doesn't even re-render the page.
const screen = [run({ id: 5 }), run({ id: 4 })]
assert.equal(mergeExecutions(screen, [run({ id: 5 }), run({ id: 4 })]), screen)

// One run advanced: the list is new, the advanced card is new, and every other card
// is the *same object* — which is what lets a memo'd card skip re-rendering while
// its neighbour updates.
const advanced = mergeExecutions(screen, [
  run({ id: 5, status: 'RUNNING', doneNodes: 0, nodes: [node({ status: 'RUNNING', output: null })] }),
  run({ id: 4 }),
])
assert.notEqual(advanced, screen)
assert.notEqual(advanced[0], screen[0])
assert.equal(advanced[1], screen[1], 'the run that did not change kept its identity')

// A new run appears on top: the ones underneath are untouched objects.
const grown = mergeExecutions(screen, [run({ id: 6 }), run({ id: 5 }), run({ id: 4 })])
assert.equal(grown.length, 3)
assert.equal(grown[1], screen[0])
assert.equal(grown[2], screen[1])

// A run deleted elsewhere shrinks the list rather than being kept alive.
const shrunk = mergeExecutions(screen, [run({ id: 4 })])
assert.deepEqual(shrunk.map((e) => e.id), [4])
assert.equal(shrunk[0], screen[1])

// Same runs, different order: the array must be new (the cards moved) but each card
// keeps its identity (nothing about the run itself changed).
const reordered = mergeExecutions(screen, [run({ id: 4 }), run({ id: 5 })])
assert.notEqual(reordered, screen)
assert.equal(reordered[0], screen[1])
assert.equal(reordered[1], screen[0])

// First poll, and a failed poll that yielded no array.
assert.deepEqual(mergeExecutions(undefined, screen), screen)
assert.equal(mergeExecutions(screen, null), screen)
assert.deepEqual(mergeExecutions([], []), [])

// --- followedNode ---------------------------------------------------------

// Following: the running step is the open one, and it moves along with the run.
assert.equal(followedNode(null, 'a', true), 'a')
assert.equal(followedNode('a', 'b', true), 'b')

// The user picked a step, so following is off: the next step starting must not take
// the panel they are reading.
assert.equal(followedNode('a', 'b', false), 'a')

// Nothing running (the run finished, or it hasn't started): never closes what is
// open, whichever way following is set.
assert.equal(followedNode('a', undefined, true), 'a')
assert.equal(followedNode('a', null, false), 'a')
assert.equal(followedNode(null, undefined, true), null)

// --- outputPredatesSettings -------------------------------------------------

// An untouched prompt: whatever is on the row is what this prompt produced.
assert.equal(outputPredatesSettings(node({ finishedAt: '2026-09-09T10:00:00Z' })), false)

// Edited after the step finished — the answer on screen is the old prompt's.
assert.equal(outputPredatesSettings(node({
  finishedAt: '2026-09-09T10:00:00Z',
  settingsEditedAt: '2026-09-09T10:05:00Z',
})), true)

// Edited, then re-run: the new answer is the new prompt's, so the label goes away.
assert.equal(outputPredatesSettings(node({
  finishedAt: '2026-09-09T10:10:00Z',
  settingsEditedAt: '2026-09-09T10:05:00Z',
})), false)

// Queued again and not finished yet: nothing on the row to be stale about.
assert.equal(outputPredatesSettings(node({
  status: 'PENDING', finishedAt: null, settingsEditedAt: '2026-09-09T10:05:00Z',
})), false)

// Fractional seconds — the reason these are parsed and not string-compared. An edit
// half a second later sorts BEFORE the finish as text ('.' < 'Z') and after it in fact.
assert.equal(outputPredatesSettings(node({
  finishedAt: '2026-09-09T10:00:00Z',
  settingsEditedAt: '2026-09-09T10:00:00.5Z',
})), true)

// --- nodesWithStaleInput --------------------------------------------------

// A chain a → b → c, run once, top to bottom. `at` is minutes past the hour.
const chain = (aMin, bMin, cMin) => [
  node({ nodeId: 'a', dependsOn: [], finishedAt: `2026-09-09T10:0${aMin}:00Z` }),
  node({ nodeId: 'b', dependsOn: ['a'], finishedAt: `2026-09-09T10:0${bMin}:00Z` }),
  node({ nodeId: 'c', dependsOn: ['b'], finishedAt: `2026-09-09T10:0${cMin}:00Z` }),
]

// A run nobody has touched: every step finished after the step it reads from,
// because it could not have started before. Nothing is stale, ever.
assert.deepEqual([...nodesWithStaleInput(chain(1, 2, 3))], [])

// "b" re-run on its own at :09. "c" is now describing b's previous output — and so is
// nothing else, because "a" is above the change.
assert.deepEqual([...nodesWithStaleInput(chain(1, 9, 3))], ['c'])

// The fixpoint is the point: re-running "a" alone leaves "b" stale by its own stamp
// and "c" stale only by inheritance — c finished after b, so a single pass over the
// direct edges would call it fine.
assert.deepEqual([...nodesWithStaleInput(chain(9, 2, 3))].sort(), ['b', 'c'])

// The re-run step itself is not stale — it is the thing everything else is behind.
assert.equal(nodesWithStaleInput(chain(1, 9, 3)).has('b'), false)

// Being re-run right now: no finishedAt, so nothing on the row to mislabel, and it
// doesn't drag its downstream in either (that is settled when it finishes).
assert.deepEqual([...nodesWithStaleInput([
  node({ nodeId: 'a', dependsOn: [], finishedAt: '2026-09-09T10:01:00Z' }),
  node({ nodeId: 'b', dependsOn: ['a'], status: 'PENDING', finishedAt: null }),
  node({ nodeId: 'c', dependsOn: ['b'], finishedAt: '2026-09-09T10:03:00Z' }),
])], [])

// Parallel branches are not compared with each other — only with what they read. "c"
// finishing after "b" says nothing about either; both read "a", which is older.
assert.deepEqual([...nodesWithStaleInput([
  node({ nodeId: 'a', dependsOn: [], finishedAt: '2026-09-09T10:01:00Z' }),
  node({ nodeId: 'b', dependsOn: ['a'], finishedAt: '2026-09-09T10:02:00Z' }),
  node({ nodeId: 'c', dependsOn: ['a'], finishedAt: '2026-09-09T10:03:00Z' }),
  node({ nodeId: 'd', dependsOn: ['b', 'c'], finishedAt: '2026-09-09T10:04:00Z' }),
])], [])

// A join goes stale from either side: re-running just "c" leaves the merge below it
// reading half an input that has moved.
assert.deepEqual([...nodesWithStaleInput([
  node({ nodeId: 'a', dependsOn: [], finishedAt: '2026-09-09T10:01:00Z' }),
  node({ nodeId: 'b', dependsOn: ['a'], finishedAt: '2026-09-09T10:02:00Z' }),
  node({ nodeId: 'c', dependsOn: ['a'], finishedAt: '2026-09-09T10:09:00Z' }),
  node({ nodeId: 'd', dependsOn: ['b', 'c'], finishedAt: '2026-09-09T10:04:00Z' }),
])], ['d'])

// Same fractional-second trap as outputPredatesSettings: '.5Z' sorts before 'Z' as text
// and after it in fact, so these are parsed rather than compared as strings.
assert.deepEqual([...nodesWithStaleInput([
  node({ nodeId: 'a', dependsOn: [], finishedAt: '2026-09-09T10:00:00.5Z' }),
  node({ nodeId: 'b', dependsOn: ['a'], finishedAt: '2026-09-09T10:00:00Z' }),
])], ['b'])

// Junk in, empty set out — the panel calls this on whatever the poll last returned.
assert.deepEqual([...nodesWithStaleInput(undefined)], [])
assert.deepEqual([...nodesWithStaleInput([])], [])

// --- entryNodeIdOf ---------------------------------------------------------

// The run's own record wins over position. This is the case the old "first row"
// guess got wrong: a topological order can put a node the entry feeds ahead of the
// entry itself, and the wrong step would then be drawn and labelled as the entry.
assert.equal(entryNodeIdOf({ entryNodeId: 'b', nodes: [node({ nodeId: 'a' }), node({ nodeId: 'b' })] }), 'b')

// Runs recorded before the entry was snapshotted have no answer to give, so the
// first row is the only one available.
assert.equal(entryNodeIdOf({ nodes: [node({ nodeId: 'a' }), node({ nodeId: 'b' })] }), 'a')

// Junk in, null out — the panel calls this on whatever the poll last returned.
assert.equal(entryNodeIdOf(undefined), null)
assert.equal(entryNodeIdOf({ nodes: [] }), null)

// --- pollInterval ----------------------------------------------------------

// The two rates the visible page runs at: fast while a run is moving, slow once
// there is nothing left to watch and the only news would come from another tab.
assert.equal(pollInterval(true, false), LIVE_POLL_MS)
assert.equal(pollInterval(false, false), IDLE_POLL_MS)
assert.ok(LIVE_POLL_MS < IDLE_POLL_MS)

// Hidden is 0 — "don't" — and it outranks having something live, which is the whole
// point: the run that is worth polling for is exactly the one whose tab gets left
// open in the background for half an hour. A background timer is throttled to once a
// minute and may be frozen outright, so the page comes back stale either way; the
// honest version stops polling and refetches when it is looked at again.
assert.equal(pollInterval(true, true), 0)
assert.equal(pollInterval(false, true), 0)

// Falsy but not 0 for every case the page should actually be polling in — the caller
// tests this value, so a rate that read as "don't" would silently stop the page.
assert.ok(pollInterval(true, false) > 0)
assert.ok(pollInterval(false, false) > 0)

// --- isLive / stateLabel ---------------------------------------------------

// The one place that decides whether a run is still going. Both PENDING and RUNNING
// count: a queued run has a lane waiting for it, and Cancel is the button for it.
assert.ok(isLive({ status: 'PENDING' }))
assert.ok(isLive({ status: 'RUNNING' }))
for (const settled of ['SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED']) {
  assert.ok(!isLive({ status: settled }))
}
assert.ok(!isLive(undefined))

// The label the confirm has to agree with the card about.
assert.equal(stateLabel('SUCCEEDED'), 'Done')
assert.equal(stateLabel('RUNNING'), 'Running')
// An unknown state shows itself rather than disappearing.
assert.equal(stateLabel('WHAT'), 'WHAT')

// --- deleteHeldBack --------------------------------------------------------

// A run that was already finished when the card was opened has no Cancel click on its
// way to it, so Delete works straight away.
assert.equal(deleteHeldBack(null, 1000), false)
assert.equal(deleteHeldBack(undefined, 1000), false)

// A run that settled under the cursor holds Delete for the grace, then releases it.
assert.equal(deleteHeldBack(1000, 1000), true)
assert.equal(deleteHeldBack(1000, 1000 + SETTLE_GRACE_MS - 1), true)
assert.equal(deleteHeldBack(1000, 1000 + SETTLE_GRACE_MS), false)
assert.equal(deleteHeldBack(1000, 1000 + SETTLE_GRACE_MS + 5000), false)

// The grace must outlast the poll that reveals the run has settled — otherwise the
// button can go live in the same instant the label changes, which is the bug.
assert.ok(SETTLE_GRACE_MS > LIVE_POLL_MS)

// --- deleteRunPrompt -------------------------------------------------------

// The case card #333 is about: a finished, billed run that someone is about to delete
// while meaning to cancel it. Every fact that would stop them is in the dialog.
const doomed = deleteRunPrompt(run({ id: 199, workflowName: 'AI Writing', costUsd: 0.1116 }))
assert.match(doomed, /#199/)
assert.match(doomed, /AI Writing/)
assert.match(doomed, /Done/)
assert.match(doomed, /1 step\b/)
assert.match(doomed, /\$0\.1116/)
assert.match(doomed, /cannot be recovered/)
// Never the raw enum: the card says "Done", so the dialog must too.
assert.ok(!doomed.includes('SUCCEEDED'))

assert.match(deleteRunPrompt(run({ totalNodes: 3, costUsd: 2.5 })), /3 steps/)

// A run nobody was billed for says nothing about cost rather than "$null" — and a
// never-started run has no cost recorded at all.
const free = deleteRunPrompt(run({ id: 7, costUsd: null, status: 'FAILED' }))
assert.match(free, /#7/)
assert.match(free, /Failed/)
assert.ok(!free.includes('$'))
assert.ok(!free.includes('null'))

// $0 is a real answer and stays in — 0 is not "unknown".
assert.match(deleteRunPrompt(run({ costUsd: 0 })), /\$0\.0000/)

// Called on whatever the poll last returned, so it must not throw on nothing.
assert.match(deleteRunPrompt(undefined), /Delete this run/)

// --- deleteWorkflowPrompt --------------------------------------------------

// The number comes from the server, and the dialog says it. This is the path that
// destroyed 24 of the 30 runs lost on 2026-09-10.
assert.match(deleteWorkflowPrompt('Article', 74), /"Article" and its 74 runs\?/)
assert.match(deleteWorkflowPrompt('Article', 1), /its 1 run\?/)
assert.ok(!deleteWorkflowPrompt('Article', 1).includes('1 runs'))
assert.match(deleteWorkflowPrompt('Article', 74), /cannot be recovered/)

// Genuinely no runs: say that, rather than leaving it ambiguous with the case below.
assert.match(deleteWorkflowPrompt('Article', 0), /no runs/)

// Count unknown is NOT the same as zero, and must not be quietly rendered as it —
// that silent downgrade is exactly what the capped client-side count used to do.
const unknown = deleteWorkflowPrompt('Article', null)
assert.match(unknown, /run history goes too/)
assert.match(unknown, /could not reach/)
assert.ok(!unknown.includes('no runs'))
assert.notEqual(unknown, deleteWorkflowPrompt('Article', 0))

// --- a run waiting for the user -------------------------------------------

// Parked in front of a manual step: the run's own status is RUNNING and nothing is
// running. Both labels have to say so, or the card reads as a run getting on with it.
const parked = run({
  status: 'RUNNING',
  doneNodes: 1,
  totalNodes: 2,
  nodes: [
    node({ nodeId: 'a', nodeName: 'Collect' }),
    node({
      id: 2, nodeId: 'b', nodeName: 'Publish', status: 'PENDING', output: null,
      dependsOn: ['a'], manualStart: true, awaitingStart: true,
    }),
  ],
})
assert.deepEqual(stepsAwaitingStart(parked).map((n) => n.nodeName), ['Publish'])
assert.equal(executionStateLabel(parked), 'Waiting')
assert.equal(nodeStateLabel(parked.nodes[1]), 'Waiting for you')
// The step that has run keeps its own word.
assert.equal(nodeStateLabel(parked.nodes[0]), 'Done')

// A queued step that has simply not got its turn yet is NOT waiting for anybody, and
// must keep saying "Queued" — the two are opposite situations on the page.
assert.equal(nodeStateLabel({ status: 'PENDING', manualStart: true, awaitingStart: false }),
  'Queued')
assert.deepEqual(stepsAwaitingStart(run({ status: 'RUNNING' })), [])
assert.equal(executionStateLabel(run({ status: 'RUNNING' })), 'Running')

// Two branches can arrive at once, and then each needs its own go-ahead.
const twoWaiting = run({
  status: 'RUNNING',
  nodes: [
    node({ nodeId: 'a', nodeName: 'Collect' }),
    node({ id: 2, nodeId: 'b', nodeName: 'Publish', status: 'PENDING', awaitingStart: true }),
    node({ id: 3, nodeId: 'c', nodeName: 'Post', status: 'PENDING', awaitingStart: true }),
  ],
})
assert.equal(stepsAwaitingStart(twoWaiting).length, 2)

// A finished run is waiting for nobody, whatever a leftover row claims — the server
// clears the flag, and the label must not promise a button the run can't offer.
assert.equal(executionStateLabel({ ...parked, status: 'SUCCEEDED' }), 'Done')

// Called on whatever the poll last returned, so neither may throw on nothing.
assert.deepEqual(stepsAwaitingStart(undefined), [])
assert.equal(nodeStateLabel(undefined), undefined)

// --- finalStepOf ----------------------------------------------------------

// A chain: the answer is the step nothing reads, not the last row of the list.
const endsAtSink = run({
  nodes: [
    node({ id: 1, nodeId: 'a', nodeName: 'Collect', finishedAt: '2026-09-09T00:01:00Z' }),
    node({ id: 2, nodeId: 'b', nodeName: 'Write', dependsOn: ['a'],
      finishedAt: '2026-09-09T00:02:00Z' }),
  ],
})
assert.equal(finalStepOf(endsAtSink).nodeId, 'b')

// ...and it stays the answer when the rows arrive in some other order, which a
// topological sort is free to do.
assert.equal(finalStepOf(run({ nodes: [...endsAtSink.nodes].reverse() })).nodeId, 'b')

// A fan-out ends in two sinks. The one that finished last is where the run came to rest.
const fan = run({
  nodes: [
    node({ id: 1, nodeId: 'a', finishedAt: '2026-09-09T00:01:00Z' }),
    node({ id: 2, nodeId: 'b', nodeName: 'Post', dependsOn: ['a'], orderIndex: 1,
      finishedAt: '2026-09-09T00:02:00Z' }),
    node({ id: 3, nodeId: 'c', nodeName: 'Publish', dependsOn: ['a'], orderIndex: 2,
      finishedAt: '2026-09-09T00:03:00Z' }),
  ],
})
assert.equal(finalStepOf(fan).nodeName, 'Publish')

// A step that produced nothing is never the answer, however late it is in the graph:
// the run failed at 'a', so 'b' never ran and the failure is what there is to read.
// 'b' has an `error` — every skipped step does — but it is boilerplate about not having
// run, and taking it would bury the one row on the card that says what went wrong.
const broke = run({
  status: 'FAILED',
  nodes: [
    node({ id: 1, nodeId: 'a', status: 'FAILED', output: null, error: 'boom\ntrace',
      finishedAt: '2026-09-09T00:01:00Z' }),
    node({ id: 2, nodeId: 'b', status: 'SKIPPED', output: null, dependsOn: ['a'],
      error: 'Skipped: the node it depends on ("a") did not succeed.',
      finishedAt: '2026-09-09T00:01:01Z' }),
  ],
})
assert.equal(finalStepOf(broke).nodeId, 'a')

// Same for a run somebody stopped: "Cancelled by user." on the step that was killed is
// not the run's answer — the output of the steps that did finish is.
const stopped = run({
  status: 'CANCELLED',
  nodes: [
    node({ id: 1, nodeId: 'a', nodeName: 'Draft', finishedAt: '2026-09-09T00:01:00Z' }),
    node({ id: 2, nodeId: 'b', nodeName: 'Publish', status: 'CANCELLED', output: null,
      error: 'Cancelled by user.', dependsOn: ['a'], finishedAt: '2026-09-09T00:02:00Z' }),
  ],
})
assert.equal(finalStepOf(stopped).nodeName, 'Draft')

// But a cancelled step that got far enough to write something keeps it: the test is
// what the step produced, not what state it landed in.
const partial = run({
  status: 'CANCELLED',
  nodes: [
    node({ id: 1, nodeId: 'a', finishedAt: '2026-09-09T00:01:00Z' }),
    node({ id: 2, nodeId: 'b', nodeName: 'Publish', status: 'CANCELLED', output: 'half of it',
      error: 'Cancelled by user.', dependsOn: ['a'], finishedAt: '2026-09-09T00:02:00Z' }),
  ],
})
assert.equal(finalStepOf(partial).nodeName, 'Publish')

// Mid-run the sink has nothing yet, so the button opens the furthest step that has
// spoken — the card is the thing that knows not to call that "the final step".
const midRun = run({
  status: 'RUNNING',
  nodes: [
    node({ id: 1, nodeId: 'a', finishedAt: '2026-09-09T00:01:00Z' }),
    node({ id: 2, nodeId: 'b', status: 'RUNNING', output: null, dependsOn: ['a'] }),
  ],
})
assert.equal(finalStepOf(midRun).nodeId, 'a')

// A sink that failed beats a branch that succeeded: the run ended on the failure.
const endedBadly = run({
  status: 'FAILED',
  nodes: [
    node({ id: 1, nodeId: 'a', finishedAt: '2026-09-09T00:01:00Z' }),
    node({ id: 2, nodeId: 'b', status: 'FAILED', output: null, error: 'nope', dependsOn: ['a'],
      finishedAt: '2026-09-09T00:02:00Z' }),
  ],
})
assert.equal(finalStepOf(endedBadly).nodeId, 'b')

// Stamps are parsed, not compared as strings — ISO fractional seconds are only as long
// as they need to be, so "…:30Z" sorts after "…:30.5Z" lexically and before it in fact.
const fractional = run({
  nodes: [
    node({ id: 1, nodeId: 'a', orderIndex: 1, finishedAt: '2026-09-09T00:00:30.500Z' }),
    node({ id: 2, nodeId: 'b', orderIndex: 2, finishedAt: '2026-09-09T00:00:31Z' }),
  ],
})
assert.equal(finalStepOf(fractional).nodeId, 'b')

// Older rows with no stamp still order — by their place in the run — so the answer
// never depends on which way round the array happened to be.
const unstamped = run({
  nodes: [
    node({ id: 1, nodeId: 'a', orderIndex: 2, finishedAt: null }),
    node({ id: 2, nodeId: 'b', orderIndex: 1, finishedAt: null }),
  ],
})
assert.equal(finalStepOf(unstamped).nodeId, 'a')

// Nothing to open: a queued run, and whatever the poll last returned.
assert.equal(finalStepOf(run({ status: 'PENDING', nodes: [node({ output: null })] })), null)
assert.equal(finalStepOf(run({ nodes: [] })), null)
assert.equal(finalStepOf(undefined), null)

console.log('ok')
