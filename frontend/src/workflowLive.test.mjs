// node src/workflowLive.test.mjs
import assert from 'node:assert/strict'
import {
  followedNode, mergeExecutions, nodesWithStaleInput, outputPredatesPrompt, sameValue,
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

// --- outputPredatesPrompt -------------------------------------------------

// An untouched prompt: whatever is on the row is what this prompt produced.
assert.equal(outputPredatesPrompt(node({ finishedAt: '2026-09-09T10:00:00Z' })), false)

// Edited after the step finished — the answer on screen is the old prompt's.
assert.equal(outputPredatesPrompt(node({
  finishedAt: '2026-09-09T10:00:00Z',
  promptEditedAt: '2026-09-09T10:05:00Z',
})), true)

// Edited, then re-run: the new answer is the new prompt's, so the label goes away.
assert.equal(outputPredatesPrompt(node({
  finishedAt: '2026-09-09T10:10:00Z',
  promptEditedAt: '2026-09-09T10:05:00Z',
})), false)

// Queued again and not finished yet: nothing on the row to be stale about.
assert.equal(outputPredatesPrompt(node({
  status: 'PENDING', finishedAt: null, promptEditedAt: '2026-09-09T10:05:00Z',
})), false)

// Fractional seconds — the reason these are parsed and not string-compared. An edit
// half a second later sorts BEFORE the finish as text ('.' < 'Z') and after it in fact.
assert.equal(outputPredatesPrompt(node({
  finishedAt: '2026-09-09T10:00:00Z',
  promptEditedAt: '2026-09-09T10:00:00.5Z',
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

// Same fractional-second trap as outputPredatesPrompt: '.5Z' sorts before 'Z' as text
// and after it in fact, so these are parsed rather than compared as strings.
assert.deepEqual([...nodesWithStaleInput([
  node({ nodeId: 'a', dependsOn: [], finishedAt: '2026-09-09T10:00:00.5Z' }),
  node({ nodeId: 'b', dependsOn: ['a'], finishedAt: '2026-09-09T10:00:00Z' }),
])], ['b'])

// Junk in, empty set out — the panel calls this on whatever the poll last returned.
assert.deepEqual([...nodesWithStaleInput(undefined)], [])
assert.deepEqual([...nodesWithStaleInput([])], [])

console.log('ok')
