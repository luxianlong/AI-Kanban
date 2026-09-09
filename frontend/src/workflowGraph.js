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
 * Room an edge reserves in every column it flies over. An edge that skips a column
 * used to be drawn straight through it, across whatever box happened to sit in the
 * way; giving it a slot of its own in that column's stack is what stops that.
 */
const LANE_H = 10
/**
 * Where edges meet a box. Several edges into one step used to arrive at the same
 * point on its left edge, so the lines and their arrowheads landed on top of each
 * other and you couldn't tell two sources from three. Each one now gets its own
 * port, PORT_STEP apart, keeping PORT_INSET clear of the rounded corners.
 */
const PORT_STEP = 12
const PORT_INSET = 9

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
 * Where everything goes: one column per depth, and inside a column the rows follow
 * the average height of whatever feeds the node. That last part is what stops a
 * fan-out from crossing its own edges — without it, rows are insertion order and
 * two branches that were added alternately weave through each other.
 *
 * A column holds more than boxes: an edge that spans more than one column takes a
 * lane in each column it crosses, so it is routed *between* the boxes there rather
 * than over them. Vertical movement then only ever happens in the gaps between
 * columns, where nothing is drawn.
 *
 * Columns are centred against the tallest one so a join reads as a join.
 *
 * Returns { positions: Map<id, {x, y}>, edges: [{from, to, points}], width, height }
 * — `positions` is the top-left corner of each box, `points` the polyline an edge
 * is drawn along (see edgePath).
 */
export function layoutGraph(nodes) {
  const depth = depths(nodes)
  const known = new Set(nodes.map((n) => n.id))
  const depthOf = (id) => depth.get(id) ?? 0

  // Every edge, plus the columns it has to fly over on the way.
  const edges = []
  nodes.forEach((node) => {
    node.inputs.forEach((fromId) => {
      if (!known.has(fromId) || fromId === node.id) return
      const lanes = []
      for (let d = depthOf(fromId) + 1; d < depthOf(node.id); d += 1) {
        lanes.push({ d, id: `lane:${fromId}>${node.id}@${d}` })
      }
      edges.push({ from: fromId, to: node.id, lanes })
    })
  })

  // What sits in each column, boxes and lanes alike. `after` is what the item
  // follows in the column to the left — the feeder itself when it is next door,
  // otherwise the lane carrying its edge — which is what the row order is from.
  const columns = []
  const add = (d, item) => {
    if (!columns[d]) columns[d] = []
    columns[d].push(item)
  }
  nodes.forEach((node, index) => {
    add(depthOf(node.id), {
      id: node.id,
      box: true,
      h: NODE_H,
      index,
      after: edges
        .filter((edge) => edge.to === node.id)
        .map((edge) => (edge.lanes.length ? edge.lanes[edge.lanes.length - 1].id : edge.from)),
    })
  })
  let order = nodes.length
  edges.forEach((edge) => {
    edge.lanes.forEach((lane, i) => {
      add(lane.d, {
        id: lane.id,
        box: false,
        h: LANE_H,
        index: (order += 1),
        after: [i === 0 ? edge.from : edge.lanes[i - 1].id],
      })
    })
  })

  const stackHeight = (column) =>
    column.reduce((sum, item) => sum + item.h, 0) + ROW_GAP * (column.length - 1)
  const tallest = Math.max(NODE_H, ...columns.filter(Boolean).map(stackHeight))

  const positions = new Map() // box id -> top-left corner
  const lanes = new Map() // lane id -> where the edge runs while crossing that column
  const middle = new Map() // every item id -> its centre line, for ordering the next column

  columns.forEach((column, d) => {
    if (!column) return
    const rowKey = (item) => {
      const placed = item.after.map((id) => middle.get(id)).filter((y) => y != null)
      // Nothing placed to the left: fall back to the order the steps were written.
      return placed.length
        ? placed.reduce((sum, y) => sum + y, 0) / placed.length
        : PAD_Y + item.index * (NODE_H + ROW_GAP)
    }
    const x = PAD_X + d * (NODE_W + COL_GAP)
    let y = PAD_Y + (tallest - stackHeight(column)) / 2
    column
      .map((item) => ({ item, key: rowKey(item) }))
      .sort((a, b) => a.key - b.key || a.item.index - b.item.index)
      .forEach(({ item }) => {
        if (item.box) positions.set(item.id, { x, y })
        else lanes.set(item.id, { x, y: y + item.h / 2 })
        middle.set(item.id, y + item.h / 2)
        y += item.h + ROW_GAP
      })
  })

  // Which port an edge gets: the ones on a side are handed out in the order of
  // where the edges come from (or go to), so fanning them out never introduces a
  // crossing that wasn't already there.
  const nextY = (edge) => (edge.lanes.length ? middle.get(edge.lanes[0].id) : middle.get(edge.to))
  const prevY = (edge) =>
    (edge.lanes.length ? middle.get(edge.lanes[edge.lanes.length - 1].id) : middle.get(edge.from))
  const rank = (side, sortBy) => {
    const slot = new Map()
    const count = new Map()
    const byNode = new Map()
    edges.forEach((edge) => {
      const id = edge[side]
      if (!byNode.has(id)) byNode.set(id, [])
      byNode.get(id).push(edge)
    })
    byNode.forEach((group, id) => {
      group
        .slice()
        .sort((a, b) => (sortBy(a) ?? 0) - (sortBy(b) ?? 0))
        .forEach((edge, i) => slot.set(edge, i))
      count.set(id, group.length)
    })
    return { slot, count }
  }
  const out = rank('from', nextY)
  const into = rank('to', prevY)
  const portY = (top, i, count) => {
    if (count <= 1) return top + NODE_H / 2
    const spread = Math.min(NODE_H - 2 * PORT_INSET, (count - 1) * PORT_STEP)
    return top + NODE_H / 2 - spread / 2 + (spread * i) / (count - 1)
  }

  const drawn = []
  edges.forEach((edge) => {
    const from = positions.get(edge.from)
    const to = positions.get(edge.to)
    if (!from || !to) return
    const points = [
      { x: from.x + NODE_W, y: portY(from.y, out.slot.get(edge), out.count.get(edge.from)) },
    ]
    edge.lanes.forEach((lane) => {
      const at = lanes.get(lane.id)
      if (at) points.push({ x: at.x, y: at.y }, { x: at.x + NODE_W, y: at.y })
    })
    points.push({ x: to.x, y: portY(to.y, into.slot.get(edge), into.count.get(edge.to)) })
    drawn.push({ from: edge.from, to: edge.to, points })
  })

  const cols = Math.max(1, columns.length)
  return {
    positions,
    edges: drawn,
    width: PAD_X * 2 + cols * NODE_W + (cols - 1) * COL_GAP,
    height: PAD_Y * 2 + tallest,
  }
}

/**
 * The `d` of an edge: straight along its lanes, curved where it changes row. The
 * curve's handles sit at the midpoint of the segment, so a segment never strays
 * outside its own two points' bounding box — which is what lets the lanes above
 * promise that no edge is drawn across a box.
 */
export function edgePath(points) {
  if (!points || points.length < 2) return ''
  const r = (n) => Math.round(n * 100) / 100
  let d = `M${r(points[0].x)} ${r(points[0].y)}`
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1]
    const b = points[i]
    if (Math.abs(a.y - b.y) < 0.5) {
      d += ` L${r(b.x)} ${r(b.y)}`
    } else {
      const mid = r((a.x + b.x) / 2)
      d += ` C${mid} ${r(a.y)}, ${mid} ${r(b.y)}, ${r(b.x)} ${r(b.y)}`
    }
  }
  return d
}
