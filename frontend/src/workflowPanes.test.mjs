// node src/workflowPanes.test.mjs
import assert from 'node:assert/strict'
import {
  NONE_COLLAPSED, STRIP_W, normalizeCollapsed, paneColumns, readCollapsed, writeCollapsed,
} from './workflowPanes.js'

const at = (c) => paneColumns(c)
const open = (over) => ({ ...NONE_COLLAPSED, ...over })

// Nothing folded: the layout the page has always had.
assert.deepEqual(at(open()), ['232px', 'minmax(0, 1fr)', 'minmax(320px, 420px)'])

// A folded pane is a spine, and the editor — the pane you folded the others for —
// keeps the 1fr, so its width grows by exactly what the others gave up.
assert.deepEqual(at(open({ list: true })), [STRIP_W, 'minmax(0, 1fr)', 'minmax(320px, 420px)'])
assert.deepEqual(at(open({ list: true, runs: true })), [STRIP_W, 'minmax(0, 1fr)', STRIP_W])

// Fold the editor and someone else has to take the slack, or the row ends in a gap.
assert.deepEqual(at(open({ editor: true })), ['232px', STRIP_W, 'minmax(320px, 1fr)'])
assert.deepEqual(at(open({ editor: true, runs: true })), ['minmax(232px, 520px)', STRIP_W, STRIP_W])

// All three folded is allowed; nothing stretches, and no track is left undefined.
assert.deepEqual(at(open({ list: true, editor: true, runs: true })), [STRIP_W, STRIP_W, STRIP_W])
for (const c of [open(), open({ list: true }), open({ editor: true, runs: true })]) {
  assert.equal(at(c).length, 3)
  assert.ok(at(c).every((t) => typeof t === 'string' && t.length))
}

// A hand-edited (or older) stored value can't wedge the page.
assert.deepEqual(normalizeCollapsed(null), NONE_COLLAPSED)
assert.deepEqual(normalizeCollapsed('nope'), NONE_COLLAPSED)
assert.deepEqual(normalizeCollapsed({ list: 'yes', bogus: true }), NONE_COLLAPSED)
assert.deepEqual(normalizeCollapsed({ runs: true }), open({ runs: true }))

// Round-trips through storage, and survives storage that throws (private mode).
const mem = new Map()
const store = { getItem: (k) => mem.get(k) ?? null, setItem: (k, v) => mem.set(k, v) }
writeCollapsed(open({ list: true, runs: true }), store)
assert.deepEqual(readCollapsed(store), open({ list: true, runs: true }))
const dead = { getItem() { throw new Error('denied') }, setItem() { throw new Error('denied') } }
assert.deepEqual(readCollapsed(dead), NONE_COLLAPSED)
writeCollapsed(open({ list: true }), dead)

console.log('ok')
