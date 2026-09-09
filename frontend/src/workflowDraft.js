// The editor's working copy of a workflow, and the pure functions that convert
// between it and what the API stores. Split out of Workflows.jsx for the same
// reason workflowGraph.js and workflowPanes.js were: it is transformation over
// plain data, so it can be tested in bare node.
//
// A draft's nodes carry their own `inputs` (the ids feeding them) instead of a
// separate edge list, because that is how they are edited — you open a step and say
// what it hears from. It is flattened back into { nodes, edges } only when saving.

import { inRunOrder } from './workflowGraph.js'

// The effort levels a step can be set to, lowest first — the CLI's own list, and the
// same one EffortLevel.java accepts. '' is not a sixth level: it is "leave this step
// at whatever the CLI's default is", which is deliberately not named here, because a
// release that moved the default would leave every step claiming the old one.
export const EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh', 'max']

export const newId = () =>
  (crypto.randomUUID ? crypto.randomUUID() : `n${Date.now()}${Math.random()}`).slice(0, 36)

export function blankDraft() {
  const entry = newId()
  return {
    id: null,
    name: '',
    description: '',
    entryNodeId: entry,
    nodes: [{ id: entry, name: 'Step 1', prompt: '', effort: '', inputs: [] }],
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
    })),
    edges: draft.nodes.flatMap((n) => n.inputs.map((from) => ({ from, to: n.id }))),
  }
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
