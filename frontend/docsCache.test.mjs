// Self-contained regression test for docsCache.mjs (task #566) — run with
// `node docsCache.test.mjs` or via `npm test`. It guards the in-memory docs
// cache: a changed file must never be served stale, nothing outside public/ may
// be read, and private docs must never get a shared-cache header.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import {
  contentTypeFor,
  createDocCache,
  docCacheControl,
  isNotModified,
  pickEncoding,
  resolvePublicFile,
  sendCached,
} from './docsCache.mjs'

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

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'docs-cache-'))
const page = path.join(dir, 'page.html')
const big = '<p>' + 'hello docs '.repeat(500) + '</p>'
fs.writeFileSync(page, big)

function fakeRes() {
  const headers = {}
  return {
    headers,
    statusCode: 0,
    body: undefined,
    setHeader(k, v) { headers[k.toLowerCase()] = v },
    end(b) { this.body = b; this.ended = true },
  }
}
const req = (h = {}, method = 'GET') => ({ method, headers: h })

check('resolvePublicFile keeps requests inside public/', () => {
  assert.equal(resolvePublicFile(dir, '/page.html'), page)
  assert.equal(resolvePublicFile(dir, '/docs/a%20b.html'), path.join(dir, 'docs', 'a b.html'))
  const esc = resolvePublicFile(dir, '/../etc/passwd')
  assert.ok(esc === null || esc.startsWith(dir + path.sep))
  const esc2 = resolvePublicFile(dir, '/%2e%2e/%2e%2e/etc/passwd')
  assert.ok(esc2 === null || esc2.startsWith(dir + path.sep))
  assert.equal(resolvePublicFile(dir, '/%E0%A4%A'), null)
  assert.equal(resolvePublicFile(dir, '/a%00.html'), null)
})

check('content types', () => {
  assert.match(contentTypeFor('/x/a.html'), /text\/html/)
  assert.match(contentTypeFor('/x/a.JS'), /javascript/)
  assert.equal(contentTypeFor('/x/a.png'), 'image/png')
  assert.equal(contentTypeFor('/x/a.unknown'), null)
})

check('cache-control: private pages never public, listings always revalidate', () => {
  assert.equal(docCacheControl('page', { isPrivate: true }), 'private, no-cache')
  assert.match(docCacheControl('page'), /^public, max-age=\d+, stale-while-revalidate=\d+$/)
  assert.match(docCacheControl('asset'), /^public, max-age=/)
  assert.equal(docCacheControl('index'), 'private, no-cache')
  assert.equal(docCacheControl('manifest'), 'private, no-cache')
})

check('pickEncoding prefers br, honours q=0', () => {
  assert.equal(pickEncoding('gzip, deflate, br'), 'br')
  assert.equal(pickEncoding('gzip'), 'gzip')
  assert.equal(pickEncoding('br;q=0, gzip'), 'gzip')
  assert.equal(pickEncoding(''), null)
  assert.equal(pickEncoding(undefined), null)
  assert.equal(pickEncoding('identity'), null)
})

check('cache returns the same entry until the file changes', () => {
  const cache = createDocCache()
  const a = cache.get(page)
  assert.equal(a.body.toString(), big)
  assert.equal(cache.get(page), a)
  fs.writeFileSync(page, big + '<p>edited</p>')
  const future = new Date(Date.now() + 5000)
  fs.utimesSync(page, future, future)
  const b = cache.get(page)
  assert.notEqual(b, a)
  assert.ok(b.body.toString().endsWith('<p>edited</p>'))
  assert.notEqual(b.etag, a.etag)
})

check('variants apply their transform and are cached separately', () => {
  const cache = createDocCache()
  const raw = cache.get(page)
  const anon = cache.get(page, 'anon', (s) => s.replace('edited', 'x'))
  assert.ok(raw.body.toString().includes('edited'))
  assert.ok(!anon.body.toString().includes('edited'))
  assert.notEqual(raw.etag, anon.etag)
})

check('missing, oversized and unknown-type files are not cached', () => {
  const cache = createDocCache({ maxFileBytes: 100 })
  assert.equal(cache.get(path.join(dir, 'nope.html')), null)
  assert.equal(cache.get(page), null) // bigger than 100 bytes
  fs.writeFileSync(path.join(dir, 'a.weird'), 'x')
  assert.equal(createDocCache().get(path.join(dir, 'a.weird')), null)
  assert.equal(createDocCache().get(dir), null) // a directory
})

check('a deleted file drops out of the cache', () => {
  const f = path.join(dir, 'gone.html')
  fs.writeFileSync(f, 'bye')
  const cache = createDocCache()
  assert.ok(cache.get(f))
  fs.unlinkSync(f)
  assert.equal(cache.get(f), null)
  assert.equal(cache.stats().entries, 0)
})

check('LRU evicts to stay within the byte budget', () => {
  const cache = createDocCache({ maxBytes: 20 })
  const f1 = path.join(dir, 'e1.txt')
  const f2 = path.join(dir, 'e2.txt')
  fs.writeFileSync(f1, '0123456789ab')
  fs.writeFileSync(f2, '0123456789cd')
  cache.get(f1)
  cache.get(f2)
  assert.equal(cache.stats().entries, 1)
  assert.ok(cache.stats().bytes <= 20)
})

check('sendCached serves br/gzip bodies that decode to the original', () => {
  const cache = createDocCache()
  const entry = cache.get(page)
  const r1 = fakeRes()
  sendCached(req({ 'accept-encoding': 'br' }), r1, cache, entry, 'public, max-age=60')
  assert.equal(r1.statusCode, 200)
  assert.equal(r1.headers['content-encoding'], 'br')
  assert.equal(r1.headers['cache-control'], 'public, max-age=60')
  assert.equal(r1.headers['vary'], 'Accept-Encoding')
  assert.deepEqual(zlib.brotliDecompressSync(r1.body), entry.body)
  assert.ok(r1.body.length < entry.body.length)

  const r2 = fakeRes()
  sendCached(req({ 'accept-encoding': 'gzip' }), r2, cache, entry, 'x', { vary: ['Cookie'] })
  assert.equal(r2.headers['content-encoding'], 'gzip')
  assert.equal(r2.headers['vary'], 'Accept-Encoding, Cookie')
  assert.deepEqual(zlib.gunzipSync(r2.body), entry.body)

  const r3 = fakeRes()
  sendCached(req({}), r3, cache, entry, 'x')
  assert.equal(r3.headers['content-encoding'], undefined)
  assert.deepEqual(r3.body, entry.body)
  assert.equal(r3.headers['content-length'], entry.body.length)
})

check('sendCached answers 304 for a matching ETag and HEAD with no body', () => {
  const cache = createDocCache()
  const entry = cache.get(page)
  assert.ok(isNotModified(req({ 'if-none-match': `W/${entry.etag}` }), entry.etag))
  assert.ok(!isNotModified(req({ 'if-none-match': '"other"' }), entry.etag))
  const r = fakeRes()
  sendCached(req({ 'if-none-match': entry.etag }), r, cache, entry, 'x')
  assert.equal(r.statusCode, 304)
  assert.equal(r.body, undefined)
  const h = fakeRes()
  sendCached(req({}, 'HEAD'), h, cache, entry, 'x')
  assert.equal(h.statusCode, 200)
  assert.equal(h.body, undefined)
  assert.equal(h.headers['content-length'], entry.body.length)
})

fs.rmSync(dir, { recursive: true, force: true })

if (failed) {
  console.error(`\n${failed} docsCache test(s) failed`)
  process.exit(1)
}
console.log('\nAll docsCache tests passed')
