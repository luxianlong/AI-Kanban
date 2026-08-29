// Self-contained regression test for docsGate.mjs — run with `node docsGate.test.mjs`
// (from frontend/) or via `npm test`. Like docLinks.test.mjs it is its own
// harness: it asserts and exits non-zero on the first failure. It guards the
// public-docs gate classification/filtering that decides which requests bypass
// the password (task #268) — get this wrong and you either leak the private
// board or hide public docs.
import assert from 'node:assert/strict'
import {
  classifyDoc,
  stripPrivateCards,
  stripPrivateManifest,
  isDocAssetPath,
  isUploadedPagePath,
  isPublicApiPath,
  isDocsReadStatePath,
  isDocsViewCountPath,
  docIdForPath,
  stripPrivateViewCounts,
  shouldCountView,
} from './docsGate.mjs'

const manifest = {
  entries: [
    { id: 'a', href: '/docs/a.html', private: false },
    { id: 'sg', href: '/singapore.html', private: true },
    { id: 'b', href: '/docs/b.html', private: true },
  ],
  privatePaths: new Set(['/singapore.html', '/docs/b.html']),
}

let failed = 0
function check(name, fn) {
  try {
    fn()
    console.log('  ✓ ' + name)
  } catch (e) {
    failed++
    console.error('  ✗ ' + name + '\n    ' + e.message)
  }
}

check('index and manifest paths are classified', () => {
  assert.equal(classifyDoc('/docs.html', manifest), 'index')
  assert.equal(classifyDoc('/docs-manifest.json', manifest), 'manifest')
})

check('generated doc pages classify as page', () => {
  assert.equal(classifyDoc('/docs/a.html', manifest), 'page')
  assert.equal(classifyDoc('/docs/anything-else.html', manifest), 'page')
})

check('bespoke doc href (from manifest) classifies as page', () => {
  assert.equal(classifyDoc('/singapore.html', manifest), 'page')
})

check('doc assets under /img/ classify as asset', () => {
  assert.equal(classifyDoc('/img/singapore/skyline.jpg', manifest), 'asset')
  assert.equal(isDocAssetPath('/img/x.png'), true)
})

check('non-doc paths (board/api/vite) are not doc requests', () => {
  assert.equal(classifyDoc('/', manifest), null)
  assert.equal(classifyDoc('/api/todos', manifest), null)
  assert.equal(classifyDoc('/src/App.jsx', manifest), null)
  assert.equal(classifyDoc('/@vite/client', manifest), null)
  assert.equal(classifyDoc('/some-random.html', manifest), null)
})

check('private page paths are recognised via the manifest', () => {
  assert.equal(manifest.privatePaths.has('/docs/b.html'), true)
  assert.equal(manifest.privatePaths.has('/docs/a.html'), false)
})

check('stripPrivateCards removes only marked blocks', () => {
  const html =
    '<div class="grid">\n' +
    '<div class="card-wrap" data-id="a">PUBLIC A</div>\n' +
    '<!--PRIVATE-CARD:b--><div class="card-wrap" data-id="b">SECRET B</div><!--/PRIVATE-CARD-->\n' +
    '<div class="card-wrap" data-id="c">PUBLIC C</div>\n' +
    '</div>'
  const out = stripPrivateCards(html)
  assert.ok(out.includes('PUBLIC A'), 'keeps public A')
  assert.ok(out.includes('PUBLIC C'), 'keeps public C')
  assert.ok(!out.includes('SECRET B'), 'drops private B')
  assert.ok(!out.includes('PRIVATE-CARD'), 'leaves no marker residue')
})

check('stripPrivateManifest drops private entries', () => {
  const json = JSON.stringify([
    { id: 'a', href: '/docs/a.html', private: false },
    { id: 'b', href: '/docs/b.html', private: true },
  ])
  const out = JSON.parse(stripPrivateManifest(json))
  assert.equal(out.length, 1)
  assert.equal(out[0].id, 'a')
})

check('stripPrivateManifest passes through non-array/invalid JSON', () => {
  assert.equal(stripPrivateManifest('not json'), 'not json')
})

check('uploaded pages under /p/ classify as public doc pages (task #470)', () => {
  assert.equal(isUploadedPagePath('/p/my-page.html'), true)
  assert.equal(classifyDoc('/p/my-page.html', manifest), 'page')
  // Only the sanitised shape the upload API can produce — no traversal, no
  // nested segments, no sidecar metadata.
  assert.equal(isUploadedPagePath('/p/../src/App.jsx'), false)
  assert.equal(isUploadedPagePath('/p/nested/deep.html'), false)
  assert.equal(isUploadedPagePath('/p/my-page.meta.json'), false)
  assert.equal(classifyDoc('/p/my-page.meta.json', manifest), null)
})

check('publishing an uploaded page needs no password; deleting one does', () => {
  assert.equal(isPublicApiPath('/api/pages', 'POST'), true)
  assert.equal(isPublicApiPath('/api/pages', 'GET'), true)
  assert.equal(isPublicApiPath('/api/pages/my-page', 'GET'), true)
  // Everything else on the API stays behind the gate.
  assert.equal(isPublicApiPath('/api/pages', 'DELETE'), false)
  assert.equal(isPublicApiPath('/api/pages/my-page', 'DELETE'), false)
  assert.equal(isPublicApiPath('/api/pages/my-page', 'PUT'), false)
  assert.equal(isPublicApiPath('/api/todos', 'POST'), false)
  assert.equal(isPublicApiPath('/api/docs/upload-my-page', 'DELETE'), false)
})

check('the delegated-task API is public to read and report on (task #471)', () => {
  assert.equal(isPublicApiPath('/api/public/tasks', 'GET'), true)
  assert.equal(isPublicApiPath('/api/public/tasks/42', 'GET'), true)
  assert.equal(isPublicApiPath('/api/public/tasks/42/claim', 'POST'), true)
  assert.equal(isPublicApiPath('/api/public/tasks/42/status', 'POST'), true)
  // Only read + report: a stranger can never delete or overwrite wholesale.
  assert.equal(isPublicApiPath('/api/public/tasks/42', 'DELETE'), false)
  assert.equal(isPublicApiPath('/api/public/tasks/42', 'PATCH'), false)
  // And the private board's own task API stays gated, however it's spelled.
  assert.equal(isPublicApiPath('/api/todos', 'GET'), false)
  assert.equal(isPublicApiPath('/api/publications', 'GET'), false)
})

check('the docs read-state probe is recognised so a public page never prompts (task #472)', () => {
  assert.equal(isDocsReadStatePath('/api/docs/reads'), true)
  // Nothing else on /api/docs is exempt — delete and visibility stay owner-only.
  assert.equal(isDocsReadStatePath('/api/docs'), false)
  assert.equal(isDocsReadStatePath('/api/docs/reads/all'), false)
  assert.equal(isDocsReadStatePath('/api/docs/some-id'), false)
  assert.equal(isDocsReadStatePath('/api/docs/some-id/visibility'), false)
  assert.equal(isDocsReadStatePath('/api/todos'), false)
  // It is NOT a blanket public API path: the exemption is a no-op answer in the
  // gate, not a pass-through to the backend.
  assert.equal(isPublicApiPath('/api/docs/reads', 'GET'), false)
})

check('the visit-count endpoint is readable without the password (task #523)', () => {
  assert.equal(isDocsViewCountPath('/api/docs/views'), true)
  // Reading tallies is the only exemption; the rest of /api/docs stays gated.
  assert.equal(isDocsViewCountPath('/api/docs'), false)
  assert.equal(isDocsViewCountPath('/api/docs/views/alpha'), false)
  assert.equal(isDocsViewCountPath('/api/docs/some-id'), false)
  assert.equal(isDocsViewCountPath('/api/docs/some-id/visibility'), false)
  // Not a blanket pass-through either: the gate answers GET itself and lets
  // every other verb (the increment included) fall through to the challenge, so
  // a visitor can be counted but can never inflate a tally by hand.
  assert.equal(isPublicApiPath('/api/docs/views', 'GET'), false)
  assert.equal(isPublicApiPath('/api/docs/views', 'POST'), false)
})

check('a served doc page resolves to the manifest id it should be counted under', () => {
  assert.equal(docIdForPath('/docs/a.html', manifest), 'a')
  assert.equal(docIdForPath('/docs/b.html', manifest), 'b')
  assert.equal(docIdForPath('/singapore.html', manifest), 'sg')
  // Not a document -> nothing to count.
  assert.equal(docIdForPath('/docs.html', manifest), null)
  assert.equal(docIdForPath('/img/a/pic.png', manifest), null)
  assert.equal(docIdForPath('/', manifest), null)
  assert.equal(docIdForPath('/docs/a.html', { entries: [] }), null)
})

check('private docs\' tallies are withheld from anonymous visitors (task #523)', () => {
  const counts = { a: 12, b: 7, sg: 3 }
  assert.deepEqual(stripPrivateViewCounts(counts, manifest), { a: 12 })
  // Degrades safely on an empty/absent answer.
  assert.deepEqual(stripPrivateViewCounts({}, manifest), {})
  assert.deepEqual(stripPrivateViewCounts(null, manifest), {})
})

check('repeat hits from the same visitor fold into one visit (task #523)', () => {
  const seen = new Map()
  const t = 1_000_000
  assert.equal(shouldCountView(seen, '1.2.3.4', 'a', t), true)
  // Same visitor, same doc, inside the window -> not counted again.
  assert.equal(shouldCountView(seen, '1.2.3.4', 'a', t + 1000), false)
  // A different doc, or a different visitor, still counts.
  assert.equal(shouldCountView(seen, '1.2.3.4', 'b', t + 1000), true)
  assert.equal(shouldCountView(seen, '5.6.7.8', 'a', t + 1000), true)
  // Past the window the same visitor counts as a fresh visit, and the stale
  // entries are pruned so the map can't grow without bound.
  assert.equal(shouldCountView(seen, '1.2.3.4', 'a', t + 31 * 60 * 1000), true)
  assert.equal(seen.size, 1)
  // Nothing to count for a path with no document behind it.
  assert.equal(shouldCountView(seen, '1.2.3.4', null, t), false)
})

if (failed) {
  console.error(`\n${failed} docsGate test(s) failed`)
  process.exit(1)
}
console.log('\nAll docsGate tests passed')
