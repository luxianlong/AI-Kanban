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
const PAD = 14
/**
 * Gap between one depth and the next when the picture flows downwards. Smaller than
 * COL_GAP because it separates boxes across their short side: 62px of air between
 * 46px boxes would read as a list of unrelated cards rather than a chain.
 */
const TB_DEPTH_GAP = 38
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
 * The two directions the picture can flow, and the sizes that go with each.
 *
 * The layout below is written once, in the two directions that actually matter to
 * it: `along` is the way depth advances, `across` is the way the steps at one depth
 * are stacked. `at()` turns that pair back into a screen point, and is the only
 * place either arrangement knows about x and y.
 *
 * `lr` is the desktop picture — depth runs rightwards, which is how a chain of
 * prompts reads. `tb` is for a column too narrow for that: depth runs downwards, so
 * the picture is as wide as its widest fan-out rather than as wide as it is long,
 * and the thing you have to do to see the far end is scroll the page — which is the
 * one gesture a phone is good at.
 */
const FLOWS = {
  lr: {
    alongSize: NODE_W,
    acrossSize: NODE_H,
    alongGap: COL_GAP,
    at: (along, across) => ({ x: along, y: across }),
  },
  tb: {
    alongSize: NODE_H,
    acrossSize: NODE_W,
    alongGap: TB_DEPTH_GAP,
    at: (along, across) => ({ x: across, y: along }),
  },
}

/** The flows a caller may ask for, for anything that wants to offer the choice. */
export const FLOW_NAMES = Object.keys(FLOWS)

/**
 * Width below which a left-to-right picture has stopped being worth drawing. A
 * phone gives the graph about 330–356px; the widest a box plus its neighbour's
 * arrow needs is a little over 200, so anything under this can hold two steps at
 * most before it is all scrolling.
 */
export const NARROW_ROOM = 560

/**
 * Which way to draw, given the width the picture has to work with (null while that
 * is still unmeasured).
 *
 * Deliberately a function of the room alone and not of how big the graph is: a rule
 * like "flow downwards once it no longer fits" would put the common desktop pane
 * widths right on its own threshold, so adding a step — or nudging the window a few
 * pixels — would swing the whole picture through ninety degrees. Keyed on the column
 * it lives in, the arrangement is a property of where you are reading it.
 */
export function flowFor(room) {
  return room != null && room < NARROW_ROOM ? 'tb' : 'lr'
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
 * `flow` picks the direction depth advances in: 'lr' (the default) or 'tb'. It only
 * changes where the boxes are put — the box itself is NODE_W by NODE_H either way,
 * and every id, edge and ordering decision is the same, so a picture drawn one way
 * is the same graph as the picture drawn the other.
 *
 * Returns { positions: Map<id, {x, y}>, edges: [{from, to, points}], width, height }
 * — `positions` is the top-left corner of each box, `points` the polyline an edge
 * is drawn along (see edgePath).
 */
export function layoutGraph(nodes, flow = 'lr') {
  const f = FLOWS[flow] || FLOWS.lr
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
      h: f.acrossSize,
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

  const stackWidth = (column) =>
    column.reduce((sum, item) => sum + item.h, 0) + ROW_GAP * (column.length - 1)
  const widest = Math.max(f.acrossSize, ...columns.filter(Boolean).map(stackWidth))

  const positions = new Map() // box id -> top-left corner
  const spot = new Map() // box id -> the same corner, still in along/across
  const lanes = new Map() // lane id -> where the edge runs while crossing that column
  const middle = new Map() // every item id -> its centre line, for ordering the next column

  columns.forEach((column, d) => {
    if (!column) return
    const rowKey = (item) => {
      const placed = item.after.map((id) => middle.get(id)).filter((v) => v != null)
      // Nothing placed before it: fall back to the order the steps were written.
      return placed.length
        ? placed.reduce((sum, v) => sum + v, 0) / placed.length
        : PAD + item.index * (f.acrossSize + ROW_GAP)
    }
    const along = PAD + d * (f.alongSize + f.alongGap)
    let across = PAD + (widest - stackWidth(column)) / 2
    column
      .map((item) => ({ item, key: rowKey(item) }))
      .sort((a, b) => a.key - b.key || a.item.index - b.item.index)
      .forEach(({ item }) => {
        if (item.box) {
          positions.set(item.id, f.at(along, across))
          spot.set(item.id, { along, across })
        } else lanes.set(item.id, { along, across: across + item.h / 2 })
        middle.set(item.id, across + item.h / 2)
        across += item.h + ROW_GAP
      })
  })

  // Which port an edge gets: the ones on a side are handed out in the order of
  // where the edges come from (or go to), so fanning them out never introduces a
  // crossing that wasn't already there.
  const nextAcross = (edge) =>
    (edge.lanes.length ? middle.get(edge.lanes[0].id) : middle.get(edge.to))
  const prevAcross = (edge) =>
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
  const out = rank('from', nextAcross)
  const into = rank('to', prevAcross)
  const port = (start, i, count) => {
    if (count <= 1) return start + f.acrossSize / 2
    const spread = Math.min(f.acrossSize - 2 * PORT_INSET, (count - 1) * PORT_STEP)
    return start + f.acrossSize / 2 - spread / 2 + (spread * i) / (count - 1)
  }

  const drawn = []
  edges.forEach((edge) => {
    const from = spot.get(edge.from)
    const to = spot.get(edge.to)
    if (!from || !to) return
    const leaves = port(from.across, out.slot.get(edge), out.count.get(edge.from))
    const arrives = port(to.across, into.slot.get(edge), into.count.get(edge.to))
    const points = [f.at(from.along + f.alongSize, leaves)]
    edge.lanes.forEach((lane) => {
      const at = lanes.get(lane.id)
      if (at) points.push(f.at(at.along, at.across), f.at(at.along + f.alongSize, at.across))
    })
    points.push(f.at(to.along, arrives))
    drawn.push({ from: edge.from, to: edge.to, points })
  })

  const cols = Math.max(1, columns.length)
  const span = f.at(
    PAD * 2 + cols * f.alongSize + (cols - 1) * f.alongGap,
    PAD * 2 + widest,
  )
  return { positions, edges: drawn, width: span.x, height: span.y }
}

/**
 * The `d` of an edge: straight along its lanes, curved where it changes row. The
 * curve's handles sit at the midpoint of the segment, so a segment never strays
 * outside its own two points' bounding box — which is what lets the lanes above
 * promise that no edge is drawn across a box.
 *
 * `flow` must match the one the points were laid out with. It says which way a
 * segment travels, and so which coordinate the handles are spread along: get it
 * wrong and every curve bends the wrong way — the drawing still joins the right
 * boxes, but the S-bends leave their own bounding box and can cross a step.
 */
export function edgePath(points, flow = 'lr') {
  if (!points || points.length < 2) return ''
  const down = flow === 'tb'
  const r = (n) => Math.round(n * 100) / 100
  let d = `M${r(points[0].x)} ${r(points[0].y)}`
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1]
    const b = points[i]
    // Straight while the across-coordinate holds still: y flowing rightwards, x
    // flowing downwards.
    if (Math.abs(down ? a.x - b.x : a.y - b.y) < 0.5) {
      d += ` L${r(b.x)} ${r(b.y)}`
    } else if (down) {
      const mid = r((a.y + b.y) / 2)
      d += ` C${r(a.x)} ${mid}, ${r(b.x)} ${mid}, ${r(b.x)} ${r(b.y)}`
    } else {
      const mid = r((a.x + b.x) / 2)
      d += ` C${mid} ${r(a.y)}, ${mid} ${r(b.y)}, ${r(b.x)} ${r(b.y)}`
    }
  }
  return d
}
