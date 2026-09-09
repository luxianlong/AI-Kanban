// node src/workflowDraft.test.mjs
import assert from 'node:assert/strict'
import { EFFORT_LEVELS, blankDraft, draftFingerprint, draftFrom, payloadFrom } from './workflowDraft.js'

const workflow = {
  id: 'w1',
  name: 'Research',
  description: 'three steps',
  entryNodeId: 'a',
  nodes: [
    { id: 'c', name: 'Publish', prompt: 'Write it up' },
    { id: 'a', name: 'Collect', prompt: 'Find sources' },
    { id: 'b', name: 'Summarise', prompt: null, effort: 'low' },
  ],
  edges: [{ from: 'a', to: 'b' }, { from: 'b', to: 'c' }],
}

// A stored workflow becomes a draft in RUN order, whatever order its rows came in,
// and its edges become each step's own `inputs`.
const draft = draftFrom(workflow)
assert.deepEqual(draft.nodes.map((n) => n.id), ['a', 'b', 'c'])
assert.deepEqual(draft.nodes.map((n) => n.inputs), [[], ['a'], ['b']])
assert.equal(draft.nodes[1].prompt, '') // a null prompt edits as empty text, not "null"

// --- a step's own effort level ----------------------------------------------

// A stored level edits as itself; a step without one edits as '', which is the value
// the picker's "Default" option carries — not the string "null", and not undefined,
// or the <select> would flip between uncontrolled and controlled on first change.
assert.equal(draft.nodes[1].effort, 'low')
assert.equal(draft.nodes[0].effort, '')
assert.equal(draft.nodes[2].effort, '')

// And on the way back out '' becomes null, because the API takes null as "the CLI's
// default" and REJECTS anything that is not a level. That asymmetry is the point: the
// CLI itself ignores a bad --effort value with only a warning on stderr, so a level
// that never reached the server would show on the page over a session that ran at the
// default.
const levels = payloadFrom(draft).nodes
assert.deepEqual(levels.map((n) => n.effort), [null, 'low', null])

// The list is the CLI's, lowest first, and 'default' is not a member of it.
assert.deepEqual(EFFORT_LEVELS, ['low', 'medium', 'high', 'xhigh', 'max'])
assert.ok(!EFFORT_LEVELS.includes('default'))
assert.ok(!EFFORT_LEVELS.includes(''))

// A brand new step starts on the default rather than on a level somebody has to
// notice and undo.
assert.equal(blankDraft().nodes[0].effort, '')

// Changing a step's level is an unsaved edit — it changes what a run costs, so
// duplicating without saving would silently take the old one.
assert.ok(draftFingerprint(draft) !== draftFingerprint({
  ...draft,
  nodes: draft.nodes.map((n) => (n.id === 'a' ? { ...n, effort: 'max' } : n)),
}))
// But re-picking the level it already had is not.
assert.equal(draftFingerprint(draft), draftFingerprint({
  ...draft,
  nodes: draft.nodes.map((n) => (n.id === 'b' ? { ...n, effort: 'low' } : n)),
}))

// And converts back to the same graph.
const payload = payloadFrom(draft)
assert.equal(payload.name, 'Research')
assert.equal(payload.entryNodeId, 'a')
assert.deepEqual(payload.edges, [{ from: 'a', to: 'b' }, { from: 'b', to: 'c' }])
assert.deepEqual(payload.nodes.map((n) => n.name), ['Collect', 'Summarise', 'Publish'])

// A step left unnamed still saves — the server requires a name, and refusing the
// whole save over a blank one would lose the rest of the edit.
assert.equal(payloadFrom({ ...draft, nodes: [{ id: 'a', name: '  ', prompt: 'p', inputs: [] }] })
  .nodes[0].name, 'Untitled step')
// An empty description is null rather than "", so the field reads as absent.
assert.equal(payloadFrom({ ...draft, description: '   ' }).description, null)

// --- the unsaved-edits check behind Duplicate -------------------------------

const same = (a, b) => draftFingerprint(a) === draftFingerprint(b)

// Reopening a workflow is not an edit.
assert.ok(same(draftFrom(workflow), draftFrom(workflow)))

// Neither is re-listing the same steps, or re-ticking the same connections in
// another order: neither changes what the workflow does, and warning about edits
// the user has already undone teaches them to click through the warning.
assert.ok(same(draft, { ...draft, nodes: [draft.nodes[2], draft.nodes[0], draft.nodes[1]] }))
const twoInputs = { ...draft, nodes: [...draft.nodes, { id: 'd', name: 'D', prompt: '', inputs: ['a', 'b'] }] }
const flipped = { ...draft, nodes: [...draft.nodes, { id: 'd', name: 'D', prompt: '', inputs: ['b', 'a'] }] }
assert.ok(same(twoInputs, flipped))

// Everything that would save differently does count.
assert.ok(!same(draft, { ...draft, name: 'Research v2' }))
assert.ok(!same(draft, { ...draft, description: 'four steps' }))
assert.ok(!same(draft, { ...draft, entryNodeId: 'b' }))
assert.ok(!same(draft, {
  ...draft,
  nodes: draft.nodes.map((n) => (n.id === 'a' ? { ...n, prompt: 'Find better sources' } : n)),
}))
assert.ok(!same(draft, {
  ...draft,
  nodes: draft.nodes.map((n) => (n.id === 'b' ? { ...n, name: 'Condense' } : n)),
}))
assert.ok(!same(draft, { ...draft, nodes: draft.nodes.filter((n) => n.id !== 'c') }))
assert.ok(!same(draft, {
  ...draft,
  nodes: draft.nodes.map((n) => (n.id === 'c' ? { ...n, inputs: ['a'] } : n)),
}))

// A brand new draft is one empty entry step, and it is the entry.
const fresh = blankDraft()
assert.equal(fresh.id, null)
assert.equal(fresh.nodes.length, 1)
assert.equal(fresh.nodes[0].id, fresh.entryNodeId)
assert.deepEqual(fresh.nodes[0].inputs, [])
assert.notEqual(blankDraft().entryNodeId, fresh.entryNodeId) // ids are per-draft

console.log('ok')
