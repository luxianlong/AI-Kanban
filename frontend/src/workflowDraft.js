// The editor's working copy of a workflow, and the pure functions that convert
// between it and what the API stores. Split out of Workflows.jsx for the same
// reason workflowGraph.js and workflowPanes.js were: it is transformation over
// plain data, so it can be tested in bare node.
//
// A draft's nodes carry their own `inputs` (the ids feeding them) instead of a
// separate edge list, because that is how they are edited — you open a step and say
// what it hears from. It is flattened back into { nodes, edges } only when saving.

import { inRunOrder } from './workflowGraph.js'

// The "think harder" scale, lowest first. '' is not a sixth level: it is "leave this
// step at whatever the CLI's default is", which is deliberately not named here, because
// a release that moved the default would leave every step claiming the old one.
export const EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh', 'max']

// Kept out of the scale above on purpose. Ultracode is xhigh thinking plus standing
// dynamic-workflow orchestration, so it changes how many sessions a step is rather than
// only how long one reasons — offering it as the notch after 'max' would say the one
// thing about it that is untrue. The picker shows it, separately.
export const ULTRACODE = 'ultracode'

// What a step a person has just created starts on, before anybody has an opinion about
// it. Not '' — a step that is left at "Default" is thought about at whatever the CLI
// charges for by default, which is several notches up this scale, and the level nobody
// chose is the one nearly every step ends up running at. Starting at the bottom makes
// the expensive levels something a step is *given* because its work needs it.
//
// It is only a starting value, written into the step as a real level: the picker still
// offers "Default", the saved workflow still stores 'low' rather than nothing, and
// steps already saved are untouched (draftFrom keeps whatever they hold, blank
// included). That is the difference between this and moving the meaning of "absent",
// which stays "let the CLI decide" — see EffortLevel.java for why that must not be
// pinned to a level here.
export const DEFAULT_EFFORT = 'low'

export const newId = () =>
  (crypto.randomUUID ? crypto.randomUUID() : `n${Date.now()}${Math.random()}`).slice(0, 36)

export function blankDraft() {
  const entry = newId()
  return {
    id: null,
    name: '',
    description: '',
    entryNodeId: entry,
    nodes: [{
      id: entry, name: 'Step 1', prompt: '', effort: DEFAULT_EFFORT, manualStart: false, inputs: [],
    }],
  }
}

export function draftFrom(workflow) {
  const nodes = workflow.nodes.map((n) => ({
    id: n.id,
    name: n.name,
    prompt: n.prompt || '',
    // '' rather than null, so the <select> is a controlled component on the same
    // value the "Default" option carries.
    effort: n.effort || '',
    // Whether a run stops in front of this step and waits to be told to go. Coerced
    // rather than passed through: the checkbox must be controlled on a boolean, and an
    // older workflow's nodes come back without the field at all.
    manualStart: !!n.manualStart,
    inputs: workflow.edges.filter((e) => e.to === n.id).map((e) => e.from),
  }))
  return {
    id: workflow.id,
    name: workflow.name,
    description: workflow.description || '',
    entryNodeId: workflow.entryNodeId,
    // Listed in run order, so a card's number means "this step runs Nth" and the
    // entry is always first. The saved order is only insertion order, which after a
    // reload can put a step above the one that feeds it — and then the numbering
    // says the opposite of what the graph does.
    nodes: inRunOrder(nodes),
  }
}

export function payloadFrom(draft) {
  return {
    name: draft.name.trim(),
    description: draft.description.trim() || null,
    entryNodeId: draft.entryNodeId,
    nodes: draft.nodes.map((n) => ({
      id: n.id,
      name: n.name.trim() || 'Untitled step',
      prompt: n.prompt,
      // Back to null on the way out: the API reads blank as "the default", and it
      // rejects anything that isn't a level rather than quietly ignoring it.
      effort: n.effort || null,
      manualStart: !!n.manualStart,
    })),
    edges: draft.nodes.flatMap((n) => n.inputs.map((from) => ({ from, to: n.id }))),
  }
}

/**
 * Add a step to the end of a draft, wired to the step before it.
 *
 * A chain is what people mean most of the time, and unhooking a connection is easier
 * than hunting for one that was never made. `step` carries whatever the new step is —
 * a blank one from "Add step", or a favourite's own text — and may set its own
 * `inputs` to override the default wiring.
 */
export function appendStep(draft, step) {
  const previous = draft.nodes[draft.nodes.length - 1]
  return {
    ...draft,
    nodes: [...draft.nodes, { inputs: previous ? [previous.id] : [], ...step }],
    // A draft with no entry yet (it had no steps) gets one, and it is this step.
    entryNodeId: draft.entryNodeId || step.id,
  }
}

/**
 * A blank step, named for the place it takes in the list. No `inputs`: how it is
 * wired is appendStep's to decide.
 *
 * The number is only a starting name — steps are listed in run order, so inserting
 * one above this later leaves "Step 3" running fourth. That is the same trade the
 * name has always made: a name you can edit beats a number that renumbers under you.
 */
export function blankStep(id, count) {
  return { id, name: `Step ${count + 1}`, prompt: '', effort: DEFAULT_EFFORT, manualStart: false }
}

/**
 * A step written from a favourite. A COPY, deliberately: from here on the step and
 * the favourite are unrelated, so editing the step doesn't rewrite the shelf and
 * re-saving the favourite doesn't reach into graphs already built from it.
 *
 * Only name, prompt and effort come across. Where a step sits, what feeds it, and whether
 * the run waits for a person in front of it belong to the graph it is being inserted INTO,
 * not to the work the step does — the same prompt is worth pausing for in the graph that
 * publishes what it writes and not in the one that only drafts.
 */
export function stepFromFavorite(favorite, id) {
  return {
    id,
    name: favorite.name,
    prompt: favorite.prompt || '',
    // Same '' as draftFrom uses: null is what the API stores, '' is what the picker
    // edits, and the two must not be mixed or the <select> stops being controlled.
    effort: favorite.effort || '',
    manualStart: false,
  }
}

/** What POSTing a step to the shelf sends — the three fields a favourite is. */
export function favoriteFromStep(node) {
  return {
    name: node.name.trim() || 'Untitled step',
    prompt: node.prompt,
    effort: node.effort || null,
  }
}

/**
 * The favourite this step was saved as, if it is still on the shelf under this name —
 * how the star knows whether it is filled. Matched on the name (case-insensitively)
 * because that is what the shelf is keyed by; a favourite whose prompt has since been
 * edited is still this step's favourite, and re-saving is what updates it.
 */
export function favoriteNamed(favorites, name) {
  const wanted = (name || '').trim().toLowerCase()
  if (!wanted) return null
  return favorites.find((f) => f.name.trim().toLowerCase() === wanted) ?? null
}

/**
 * A string that changes exactly when the draft would save differently — used to ask
 * "are there unsaved edits?" before an action that reads the *stored* workflow
 * rather than the one on screen (duplicating).
 *
 * Both sides are normalised first: the order steps are listed in, and the order a
 * step's inputs were ticked in, are not part of what a workflow is. Without that,
 * merely toggling a connection off and on again would count as an edit and the user
 * would be warned about changes they had already undone.
 */
export function draftFingerprint(draft) {
  const saved = payloadFrom(draft)
  return JSON.stringify({
    name: saved.name,
    description: saved.description,
    entryNodeId: saved.entryNodeId,
    nodes: [...saved.nodes].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)),
    edges: saved.edges.map((e) => `${e.from}→${e.to}`).sort(),
  })
}
