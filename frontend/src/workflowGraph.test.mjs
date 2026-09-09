// Unit tests for the workflows DAG maths. Run with: node src/workflowGraph.test.mjs
import assert from 'node:assert/strict'
import {
  NODE_H, NODE_W, depths, descendantsOf, inRunOrder, layoutGraph, reachableFrom,
} from './workflowGraph.js'

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
}

console.log('workflowGraph.test.mjs: ok')
