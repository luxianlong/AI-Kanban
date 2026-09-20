// node src/workflowDraft.test.mjs
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  DEFAULT_EFFORT, EFFORT_LEVELS, ULTRACODE, appendStep, blankDraft, blankStep, draftFingerprint,
  draftFrom, favoriteFromStep, favoriteNamed, payloadFrom, stepFromFavorite,
} from './workflowDraft.js'

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

// Ultracode is offered, but not as the sixth notch of the scale above — the picker
// renders those two constants as two groups, so folding it into EFFORT_LEVELS is
// exactly the change that would silently say "one harder than max".
assert.ok(!EFFORT_LEVELS.includes(ULTRACODE))
assert.deepEqual([...EFFORT_LEVELS, ULTRACODE],
  ['low', 'medium', 'high', 'xhigh', 'max', 'ultracode'])

// The spelling is the flag's. The CLI ignores an --effort value it does not know with
// only a warning on stderr, so a slip here would not fail anything: the step would run
// at the default while the page kept showing the level that was saved.
assert.equal(ULTRACODE, 'ultracode')

// A step can be saved on it, and it survives the round trip to the API shape.
const ultra = draftFrom({
  id: 7, name: 'w', description: '', entryNodeId: 'a',
  nodes: [{ id: 'a', name: 'Dig', prompt: 'go', effort: 'ultracode' }], edges: [],
})
assert.equal(ultra.nodes[0].effort, 'ultracode')
assert.equal(payloadFrom(ultra).nodes[0].effort, 'ultracode')

// A brand new step starts at the bottom of the scale, not on "Default": the level
// nobody picks is the one nearly every step runs at, so it has to be the cheap one.
assert.equal(DEFAULT_EFFORT, 'low')
assert.ok(EFFORT_LEVELS.includes(DEFAULT_EFFORT))
assert.equal(DEFAULT_EFFORT, EFFORT_LEVELS[0]) // the bottom of the scale, wherever it moves
assert.notEqual(DEFAULT_EFFORT, ULTRACODE)     // and never the gated, fan-out one
assert.equal(blankDraft().nodes[0].effort, DEFAULT_EFFORT)
// It is a real level and saves as one, rather than a blank the API reads as "no flag".
assert.equal(payloadFrom(blankDraft()).nodes[0].effort, 'low')

// Steps that already exist are NOT rewritten by that default. A workflow saved before
// it — or one deliberately left on "Default" — comes back blank, because draftFrom
// reads what is stored and the picker's "Default" option must stay reachable.
assert.equal(draftFrom(workflow).nodes.find((n) => n.id === 'a').effort, '')
assert.equal(payloadFrom(draftFrom(workflow)).nodes.find((n) => n.id === 'a').effort, null)

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

// --- adding a step, blank or from the shelf ---------------------------------

// A new step goes on the end, wired to the step that was last: a chain is what
// people mean, and the numbering in the editor then reads straight down.
const grown = appendStep(draft, blankStep('d', draft.nodes.length))
assert.deepEqual(grown.nodes.map((n) => n.id), ['a', 'b', 'c', 'd'])
assert.deepEqual(grown.nodes[3].inputs, ['c'])
assert.equal(grown.nodes[3].name, 'Step 4')
assert.equal(grown.nodes[3].prompt, '')
assert.equal(grown.nodes[3].effort, DEFAULT_EFFORT)
// It leaves the draft it was given alone — the editor holds the working copy in
// state, and a helper that mutated it would edit the version React is diffing against.
assert.equal(draft.nodes.length, 3)
assert.deepEqual(draft.nodes[2].inputs, ['b'])

// The entry is untouched when there already is one, and taken by the first step when
// there isn't — a draft with no steps has nothing else that could be the entry.
assert.equal(grown.entryNodeId, 'a')
const fromNothing = appendStep({ nodes: [], entryNodeId: null }, blankStep('x', 0))
assert.equal(fromNothing.entryNodeId, 'x')
assert.deepEqual(fromNothing.nodes[0].inputs, [])

// A favourite becomes an ordinary step carrying its name, prompt and level.
const favorite = { id: 'f1', name: 'Summarise', prompt: 'Five bullets', effort: 'low' }
const inserted = appendStep(draft, stepFromFavorite(favorite, 'd'))
assert.equal(inserted.nodes[3].name, 'Summarise')
assert.equal(inserted.nodes[3].prompt, 'Five bullets')
assert.equal(inserted.nodes[3].effort, 'low')
// Wired like any other new step, and carrying nothing of where it sat before: what
// feeds a step belongs to the graph it is being put into, not to the favourite.
assert.deepEqual(inserted.nodes[3].inputs, ['c'])
assert.ok(!('inputs' in favorite))

// The third place a step is born is the "add a step into a finished run" form, which
// lives in Workflows.jsx and has no pure function to call. Checked at the source, so
// that form cannot drift back to starting on "Default" while the editor starts on low —
// the two are the same act and a user who found one cheap and the other not would have
// no way to tell why.
const editorSource = readFileSync(new URL('./Workflows.jsx', import.meta.url), 'utf8')
assert.match(editorSource, /const \[effort, setEffort\] = useState\(DEFAULT_EFFORT\)/)
assert.ok(!/const \[effort, setEffort\] = useState\(''\)/.test(editorSource))

// A favourite saved with no level inserts at '' — the value the picker's "Default"
// option carries — not at null, or the <select> would go uncontrolled on first change.
// Not at DEFAULT_EFFORT either: a favourite is a level somebody already chose and put
// on the shelf, so inserting it is a read, not the birth of a step.
assert.equal(stepFromFavorite({ name: 'Tidy', prompt: 'x', effort: null }, 'e').effort, '')
assert.equal(stepFromFavorite({ name: 'Tidy', prompt: null, effort: null }, 'e').prompt, '')

// Inserting is a COPY: editing the step afterwards leaves the favourite as it was.
inserted.nodes[3] = { ...inserted.nodes[3], prompt: 'Ten bullets' }
assert.equal(favorite.prompt, 'Five bullets')

// --- saving a step to the shelf ---------------------------------------------

// The three fields a favourite is, and no more. '' goes back out as null, the same
// asymmetry payloadFrom has: the API stores null for "the CLI's default" and rejects
// anything that is not a level.
assert.deepEqual(
  favoriteFromStep({ id: 'a', name: ' Collect ', prompt: 'Find sources', effort: '', inputs: ['b'] }),
  { name: 'Collect', prompt: 'Find sources', effort: null },
)
assert.equal(favoriteFromStep({ name: 'x', prompt: 'p', effort: 'ultracode' }).effort, 'ultracode')
// An unnamed step still saves under something findable rather than under ''.
assert.equal(favoriteFromStep({ name: '   ', prompt: 'p', effort: '' }).name, 'Untitled step')

// --- which favourite a step's star is showing --------------------------------

const shelf = [{ id: 'f1', name: 'Summarise', prompt: 'Five bullets', effort: 'low' }]
assert.equal(favoriteNamed(shelf, 'Summarise').id, 'f1')
// Case and surrounding space don't make it a different shelf label — the server
// matches the same way, so a star that ignored them would offer to save a second
// favourite and then silently overwrite the first.
assert.equal(favoriteNamed(shelf, ' summarise ').id, 'f1')
assert.equal(favoriteNamed(shelf, 'Publish'), null)
// A step still called nothing matches nothing, rather than every unnamed favourite.
assert.equal(favoriteNamed(shelf, ''), null)
assert.equal(favoriteNamed(shelf, undefined), null)
// It is matched on the NAME, not the text: a step whose prompt has since been edited
// is still this favourite's step, and pressing the star again is what updates it.
assert.equal(favoriteNamed(shelf, 'Summarise').prompt, 'Five bullets')

// --- manual start: the flag that stops a run in front of a step ---------------

// It round-trips, and it is a boolean on both sides: the checkbox has to be controlled
// on one, and the API stores one.
const gated = draftFrom({
  ...workflow,
  nodes: [
    { id: 'a', name: 'Collect', prompt: 'Find sources' },
    { id: 'b', name: 'Summarise', prompt: null, manualStart: true },
    { id: 'c', name: 'Publish', prompt: 'Write it up', manualStart: false },
  ],
})
assert.deepEqual(gated.nodes.map((n) => n.manualStart), [false, true, false])
assert.deepEqual(payloadFrom(gated).nodes.map((n) => n.manualStart), [false, true, false])

// A workflow saved before the flag existed comes back without the field at all, and must
// read as off rather than as undefined — an uncontrolled checkbox warns and then ignores
// the first click.
assert.equal(draftFrom(workflow).nodes.every((n) => n.manualStart === false), true)

// Off on a new step. A graph that walks itself is what a workflow is for; pausing is the
// exception somebody asks for. (Unlike effort, whose new-step value is a real level —
// the two defaults differ because "don't pause" and "think cheaply" are opposite kinds
// of unset: one is the absence of a request, the other is a choice worth storing.)
assert.equal(blankStep('n1', 0).manualStart, false)
assert.equal(blankDraft().nodes[0].manualStart, false)

// And off on a step written from a favourite, even where the step it was saved from
// paused: whether a person is wanted in front of this step belongs to the graph it is
// being inserted into, not to the prompt.
assert.equal(stepFromFavorite({ name: 'Publish', prompt: 'Ship it' }, 'n2').manualStart, false)
assert.equal(favoriteFromStep({ name: 'Publish', prompt: 'Ship it', manualStart: true }).manualStart,
  undefined)

// Turning it on is an unsaved edit — otherwise duplicating a workflow would quietly
// duplicate the version that still runs straight through.
const toggled = {
  ...gated,
  nodes: gated.nodes.map((n) => (n.id === 'c' ? { ...n, manualStart: true } : n)),
}
assert.notEqual(draftFingerprint(toggled), draftFingerprint(gated))

console.log('ok')
