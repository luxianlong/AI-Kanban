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

if (failed) {
  console.error(`\n${failed} docsGate test(s) failed`)
  process.exit(1)
}
console.log('\nAll docsGate tests passed')
