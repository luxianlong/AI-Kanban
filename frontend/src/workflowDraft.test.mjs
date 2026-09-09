// node src/workflowDraft.test.mjs
import assert from 'node:assert/strict'
import { blankDraft, draftFingerprint, draftFrom, payloadFrom } from './workflowDraft.js'

const workflow = {
  id: 'w1',
  name: 'Research',
  description: 'three steps',
  entryNodeId: 'a',
  nodes: [
    { id: 'c', name: 'Publish', prompt: 'Write it up' },
    { id: 'a', name: 'Collect', prompt: 'Find sources' },
    { id: 'b', name: 'Summarise', prompt: null },
  ],
  edges: [{ from: 'a', to: 'b' }, { from: 'b', to: 'c' }],
}

// A stored workflow becomes a draft in RUN order, whatever order its rows came in,
// and its edges become each step's own `inputs`.
const draft = draftFrom(workflow)
assert.deepEqual(draft.nodes.map((n) => n.id), ['a', 'b', 'c'])
assert.deepEqual(draft.nodes.map((n) => n.inputs), [[], ['a'], ['b']])
assert.equal(draft.nodes[1].prompt, '') // a null prompt edits as empty text, not "null"

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
