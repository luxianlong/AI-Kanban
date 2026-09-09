// The graph maths behind the workflows page: run order, reachability, and the
// left-to-right layout the DAG picture is drawn from.
//
// Kept out of Workflows.jsx because it is plain data work with no React in it —
// which also means workflowGraph.test.mjs can import it in bare node.
//
// Every function here takes the editor's node shape: { id, name, inputs: [id] }.
// An execution's nodes are mapped onto the same shape before being laid out, so
// the picture of a run and the picture of the graph it came from are drawn by the
// same code and can't drift apart.

/** Box size of a node in the picture, and the gaps between boxes. */
export const NODE_W = 152
export const NODE_H = 46
const COL_GAP = 62
const ROW_GAP = 20
const PAD_X = 14
const PAD_Y = 14

/**
 * Column (depth) of every node: one past the deepest thing feeding it. This is
 * also the earliest tick the runner could reach the node, so the picture matches
 * the order the graph actually works through.
 */
export function depths(nodes) {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const memo = new Map()
  const visit = (id, seen) => {
    if (memo.has(id)) return memo.get(id)
    if (seen.has(id)) return 0 // a cycle can only exist mid-edit; don't hang on it
    seen.add(id)
    const node = byId.get(id)
    const depth = !node || node.inputs.length === 0
      ? 0
      : 1 + Math.max(...node.inputs.map((from) => visit(from, seen)))
    seen.delete(id)
    memo.set(id, depth)
    return depth
  }
  const result = new Map()
  for (const node of nodes) result.set(node.id, visit(node.id, new Set()))
  return result
}

/** Nodes sorted so each comes after everything feeding it; ties keep their order. */
export function inRunOrder(nodes) {
  const depth = depths(nodes)
  return nodes
    .map((node, index) => ({ node, index }))
    .sort((a, b) =>
      (depth.get(a.node.id) ?? 0) - (depth.get(b.node.id) ?? 0) || a.index - b.index)
    .map(({ node }) => node)
}

/**
 * Everything downstream of `startId`, following the inputs the other way. Used to
 * hide the connections that would make a cycle: a node can never be fed by one of
 * its own descendants, and offering the choice only to reject it server-side would
 * be a worse way to learn that.
 */
export function descendantsOf(nodes, startId) {
  const out = new Set()
  const queue = [startId]
  while (queue.length) {
    const current = queue.pop()
    for (const node of nodes) {
      if (node.inputs.includes(current) && !out.has(node.id)) {
        out.add(node.id)
        queue.push(node.id)
      }
    }
  }
  return out
}

/** Node ids a run can actually get to by following edges out of the entry node. */
export function reachableFrom(nodes, entryNodeId) {
  const seen = new Set(entryNodeId ? [entryNodeId] : [])
  let grew = true
  while (grew) {
    grew = false
    for (const node of nodes) {
      if (!seen.has(node.id) && node.inputs.some((from) => seen.has(from))) {
        seen.add(node.id)
        grew = true
      }
    }
  }
  return seen
}

/**
 * Where each box goes: one column per depth, and inside a column the rows follow
 * the average height of whatever feeds the node. That last part is what stops a
 * fan-out from crossing its own edges — without it, rows are insertion order and
 * two branches that were added alternately weave through each other.
 *
 * Columns are centred against the tallest one so a join reads as a join.
 *
 * Returns { positions: Map<id, {x, y}>, width, height }.
 */
export function layoutGraph(nodes) {
  const depth = depths(nodes)
  const columns = []
  nodes.forEach((node, index) => {
    const d = depth.get(node.id) ?? 0
    if (!columns[d]) columns[d] = []
    columns[d].push({ node, index })
  })

  const tallest = Math.max(1, ...columns.map((c) => (c ? c.length : 0)))
  const step = NODE_H + ROW_GAP
  const positions = new Map()

  columns.forEach((column, d) => {
    if (!column) return
    const meanParentY = ({ node, index }) => {
      const known = node.inputs.map((from) => positions.get(from)).filter(Boolean)
      // Nothing placed to the left: fall back to the order the steps were written.
      return known.length
        ? known.reduce((sum, p) => sum + p.y, 0) / known.length
        : PAD_Y + index * step
    }
    const ordered = column
      .map((entry) => ({ ...entry, key: meanParentY(entry) }))
      .sort((a, b) => a.key - b.key || a.index - b.index)
    const offset = ((tallest - ordered.length) * step) / 2
    ordered.forEach(({ node }, row) => {
      positions.set(node.id, {
        x: PAD_X + d * (NODE_W + COL_GAP),
        y: PAD_Y + offset + row * step,
      })
    })
  })

  return {
    positions,
    width: PAD_X * 2 + Math.max(1, columns.length) * NODE_W + Math.max(0, columns.length - 1) * COL_GAP,
    height: PAD_Y * 2 + tallest * NODE_H + (tallest - 1) * ROW_GAP,
  }
}
