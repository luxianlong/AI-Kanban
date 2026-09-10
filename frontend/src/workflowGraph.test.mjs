// Unit tests for the workflows DAG maths. Run with: node src/workflowGraph.test.mjs
import assert from 'node:assert/strict'
import {
  NARROW_ROOM, NODE_H, NODE_W, depths, descendantsOf, edgePath, flowFor, inRunOrder,
  layoutGraph, reachableFrom,
} from './workflowGraph.js'

// An edge is drawn along its points with curve handles at each segment's midpoint,
// so a segment never leaves the box its two ends span. Overlapping that box with a
// node's box is therefore enough to catch a line drawn across a step.
const crossesABox = ({ positions }, edge) => {
  for (const [id, at] of positions) {
    if (id === edge.from || id === edge.to) continue
    for (let i = 1; i < edge.points.length; i += 1) {
      const a = edge.points[i - 1]
      const b = edge.points[i]
      const overX = Math.min(Math.max(a.x, b.x), at.x + NODE_W) - Math.max(Math.min(a.x, b.x), at.x)
      const overY = Math.min(Math.max(a.y, b.y), at.y + NODE_H) - Math.max(Math.min(a.y, b.y), at.y)
      if (overX > 0.5 && overY > 0.5) return id
    }
  }
  return null
}

const n = (id, ...inputs) => ({ id, name: id.toUpperCase(), inputs })

// a → b, a → c, (b, c) → d : the diamond every other case is a variation of.
const diamond = [n('a'), n('b', 'a'), n('c', 'a'), n('d', 'b', 'c')]

{
  const d = depths(diamond)
  assert.deepEqual([...d.values()], [0, 1, 1, 2])
}

{
  // Stored out of order: run order has to put a step after whatever feeds it.
  const shuffled = [n('d', 'b', 'c'), n('c', 'a'), n('b', 'a'), n('a')]
  assert.deepEqual(inRunOrder(shuffled).map((x) => x.id), ['a', 'c', 'b', 'd'])
}

{
  assert.deepEqual([...descendantsOf(diamond, 'b')], ['d'])
  assert.deepEqual([...descendantsOf(diamond, 'a')].sort(), ['b', 'c', 'd'])
  assert.deepEqual([...descendantsOf(diamond, 'd')], [])
}

{
  assert.equal(reachableFrom(diamond, 'a').size, 4)
  // Entry moved onto a branch: the other branch and the entry's own feeder drop out.
  assert.deepEqual([...reachableFrom(diamond, 'b')].sort(), ['b', 'd'])
}

{
  const { positions, width, height } = layoutGraph(diamond)
  const x = (id) => positions.get(id).x
  const y = (id) => positions.get(id).y
  // One column per depth, in depth order.
  assert.ok(x('a') < x('b'))
  assert.equal(x('b'), x('c'))
  assert.ok(x('c') < x('d'))
  // The fan-out is stacked, and the join sits between the two branches feeding it.
  assert.notEqual(y('b'), y('c'))
  assert.ok(y('d') > Math.min(y('b'), y('c')) && y('d') < Math.max(y('b'), y('c')))
  // Everything drawn fits inside the reported box.
  for (const p of positions.values()) {
    assert.ok(p.x >= 0 && p.x + NODE_W <= width)
    assert.ok(p.y >= 0 && p.y + NODE_H <= height)
  }
}

{
  // Several sources into one step: each edge gets its own landing point on the left
  // edge of the box, in the order the sources are stacked, so three arrowheads read
  // as three and not as one thick one.
  const layout = layoutGraph([n('a'), n('b'), n('c'), n('d', 'a', 'b', 'c')])
  const into = layout.edges.filter((e) => e.to === 'd')
  const at = layout.positions.get('d')
  assert.equal(into.length, 3)
  const ends = into.map((e) => e.points[e.points.length - 1])
  assert.equal(new Set(ends.map((p) => p.y)).size, 3)
  for (const p of ends) {
    assert.equal(p.x, at.x) // they all meet the left edge
    assert.ok(p.y > at.y && p.y < at.y + NODE_H)
  }
  // No crossing at the port: sources higher up land higher up.
  const sourceY = (e) => layout.positions.get(e.from).y
  const byArrival = [...into].sort((x, y) => x.points.at(-1).y - y.points.at(-1).y)
  assert.deepEqual(byArrival.map(sourceY), [...into.map(sourceY)].sort((x, y) => x - y))
  // Edges leaving one step fan out the same way.
  const outOf = layoutGraph([n('a'), n('b', 'a'), n('c', 'a')]).edges
  assert.notEqual(outOf[0].points[0].y, outOf[1].points[0].y)
}

{
  // An edge that skips a column is routed between that column's boxes, not over
  // them: a → d jumps two columns past b and c.
  const skipping = [n('a'), n('b', 'a'), n('c', 'b'), n('d', 'a', 'c')]
  const layout = layoutGraph(skipping)
  const long = layout.edges.find((e) => e.from === 'a' && e.to === 'd')
  assert.ok(long.points.length > 2, 'the long edge should bend around the columns')
  for (const edge of layout.edges) {
    assert.equal(crossesABox(layout, edge), null, `${edge.from}→${edge.to} runs over a box`)
  }
  // Everything drawn still fits in the reported box.
  for (const edge of layout.edges) {
    for (const p of edge.points) {
      assert.ok(p.x >= 0 && p.x <= layout.width)
      assert.ok(p.y >= 0 && p.y <= layout.height)
    }
  }
}

{
  // The path: straight where the row doesn't change, curved where it does.
  assert.equal(edgePath([{ x: 0, y: 5 }, { x: 10, y: 5 }]), 'M0 5 L10 5')
  assert.equal(edgePath([{ x: 0, y: 0 }, { x: 10, y: 4 }]), 'M0 0 C5 0, 5 4, 10 4')
  assert.equal(edgePath([{ x: 0, y: 0 }]), '')
  assert.equal(edgePath(undefined), '')
}

{
  // Rows follow the average row of the parents, so branches don't weave: two
  // chains written interleaved still come out as two straight lines.
  const twoChains = [n('a'), n('x1', 'a'), n('y1', 'a'), n('y2', 'y1'), n('x2', 'x1')]
  const { positions } = layoutGraph(twoChains)
  assert.equal(positions.get('x1').y, positions.get('x2').y)
  assert.equal(positions.get('y1').y, positions.get('y2').y)
}

{
  // A cycle can only exist mid-edit; it must not hang or throw.
  const cyclic = [n('a', 'b'), n('b', 'a')]
  assert.equal(layoutGraph(cyclic).positions.size, 2)
  assert.equal(layoutGraph([]).positions.size, 0)
  // An input naming a step that has since been deleted is not an edge.
  assert.equal(layoutGraph([n('a', 'gone')]).edges.length, 0)
}

// ---------------------------------------------------------------------------
// Flowing downwards: the same graph drawn for a column too narrow to run it
// left to right.
// ---------------------------------------------------------------------------

{
  // The arrangement is a property of the column the picture is read in, not of the
  // graph — so it can't swing through ninety degrees just because a step was added.
  assert.equal(flowFor(null), 'lr') // not measured yet: assume there is room
  assert.equal(flowFor(NARROW_ROOM), 'lr')
  assert.equal(flowFor(NARROW_ROOM - 1), 'tb')
  assert.equal(flowFor(356), 'tb') // a phone
  assert.equal(flowFor(1200), 'lr')
}

{
  // Same graph, turned: depth runs downwards, and the steps at one depth are side
  // by side instead of stacked.
  const { positions, width, height } = layoutGraph(diamond, 'tb')
  const x = (id) => positions.get(id).x
  const y = (id) => positions.get(id).y
  assert.ok(y('a') < y('b'))
  assert.equal(y('b'), y('c'))
  assert.ok(y('c') < y('d'))
  // The fan-out spreads sideways, and the join sits between the two branches.
  assert.notEqual(x('b'), x('c'))
  assert.ok(x('d') > Math.min(x('b'), x('c')) && x('d') < Math.max(x('b'), x('c')))
  for (const p of positions.values()) {
    assert.ok(p.x >= 0 && p.x + NODE_W <= width)
    assert.ok(p.y >= 0 && p.y + NODE_H <= height)
  }
  // A box is the same box either way round; only where it is put changes.
  const across = layoutGraph(diamond)
  assert.deepEqual([...positions.keys()].sort(), [...across.positions.keys()].sort())
  assert.deepEqual(
    layoutGraph(diamond, 'tb').edges.map((e) => `${e.from}>${e.to}`).sort(),
    across.edges.map((e) => `${e.from}>${e.to}`).sort(),
  )
}

{
  // The whole point of the turn: a long chain stops being wider than any phone.
  const chain = Array.from({ length: 12 }, (_, i) => n(`s${i}`, ...(i ? [`s${i - 1}`] : [])))
  const across = layoutGraph(chain)
  const down = layoutGraph(chain, 'tb')
  assert.ok(across.width > 1900, `sideways it is ${across.width}px wide`)
  assert.ok(down.width <= NARROW_ROOM, `downwards it is ${down.width}px wide`)
  // A straight chain has nothing to fan out, so it is exactly one box wide.
  assert.equal(down.width, layoutGraph([n('only')], 'tb').width)
  assert.ok(down.height > across.height)
  // Adding a step makes it longer, never wider — which is what keeps it readable.
  const longer = layoutGraph([...chain, n('s12', 's11')], 'tb')
  assert.equal(longer.width, down.width)
  assert.ok(longer.height > down.height)
}

{
  // Several sources into one step land on its TOP edge, spread across it.
  const layout = layoutGraph([n('a'), n('b'), n('c'), n('d', 'a', 'b', 'c')], 'tb')
  const into = layout.edges.filter((e) => e.to === 'd')
  const at = layout.positions.get('d')
  assert.equal(into.length, 3)
  const ends = into.map((e) => e.points.at(-1))
  assert.equal(new Set(ends.map((p) => p.x)).size, 3)
  for (const p of ends) {
    assert.equal(p.y, at.y)
    assert.ok(p.x > at.x && p.x < at.x + NODE_W)
  }
  // Spread about the middle of that edge, across its full width — the ports have to
  // be measured against the side they are on, and turned, that side is NODE_W long.
  // Sharing the sideways picture's NODE_H would bunch all three into the left third.
  assert.equal(
    ends.map((p) => p.x).sort((p, q) => p - q)[1], at.x + NODE_W / 2,
    'the middle arrival should sit on the centre of the top edge',
  )
  // A step fed by one thing meets that edge dead centre.
  const lone = layoutGraph([n('a'), n('b', 'a')], 'tb')
  assert.equal(lone.edges[0].points.at(-1).x, lone.positions.get('b').x + NODE_W / 2)
  assert.equal(lone.edges[0].points[0].x, lone.positions.get('a').x + NODE_W / 2)
  // Sources further left land further left, so the arrowheads never cross.
  const sourceX = (e) => layout.positions.get(e.from).x
  const byArrival = [...into].sort((p, q) => p.points.at(-1).x - q.points.at(-1).x)
  assert.deepEqual(byArrival.map(sourceX), [...into.map(sourceX)].sort((p, q) => p - q))
}

{
  // An edge that skips a depth is still routed between that depth's boxes rather
  // than over them — the lanes work the same way turned.
  const skipping = [n('a'), n('b', 'a'), n('c', 'b'), n('d', 'a', 'c')]
  const layout = layoutGraph(skipping, 'tb')
  const long = layout.edges.find((e) => e.from === 'a' && e.to === 'd')
  assert.ok(long.points.length > 2, 'the long edge should bend around the boxes')
  for (const edge of layout.edges) {
    assert.equal(crossesABox(layout, edge), null, `${edge.from}→${edge.to} runs over a box`)
  }
  for (const edge of layout.edges) {
    for (const p of edge.points) {
      assert.ok(p.x >= 0 && p.x <= layout.width)
      assert.ok(p.y >= 0 && p.y <= layout.height)
    }
  }
}

{
  // The path, turned: straight where the column doesn't change, and the curve's
  // handles spread along y instead of x.
  assert.equal(edgePath([{ x: 5, y: 0 }, { x: 5, y: 10 }], 'tb'), 'M5 0 L5 10')
  assert.equal(edgePath([{ x: 0, y: 0 }, { x: 4, y: 10 }], 'tb'), 'M0 0 C0 5, 4 5, 4 10')
  // ...and the default is still the sideways one, for every caller that has none.
  assert.equal(edgePath([{ x: 0, y: 0 }, { x: 10, y: 4 }]), edgePath([{ x: 0, y: 0 }, { x: 10, y: 4 }], 'lr'))
  assert.equal(edgePath([], 'tb'), '')
}

{
  // A flow nobody defined draws the ordinary picture rather than nothing at all.
  assert.deepEqual(layoutGraph(diamond, 'sideways'), layoutGraph(diamond))
  // Cycles and deleted inputs are no more dangerous turned than they were.
  assert.equal(layoutGraph([n('a', 'b'), n('b', 'a')], 'tb').positions.size, 2)
  assert.equal(layoutGraph([], 'tb').positions.size, 0)
  assert.equal(layoutGraph([n('a', 'gone')], 'tb').edges.length, 0)
}
console.log('workflowGraph.test.mjs: ok')
