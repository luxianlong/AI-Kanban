// Self-contained regression test for the archive index's "全部标为已读" button —
// run with `node scripts/lib/docs-mark-all-read.test.mjs` (or via `npm test`
// from frontend/). Like docs-index.test.mjs it is its own harness: it asserts
// and exits non-zero on the first failure.
//
// What it guards (task #412): the masthead carries one button that clears every
// unread document at once. The rules that are easy to break later:
//
//   1. "unread" is the MANIFEST minus /api/docs/reads — the same arithmetic the
//      board's Docs badge does. Counting only the cards on the page would leave
//      the 典籍 (which stand on the shelf, not in a card) permanently unread and
//      the board badge stuck above zero;
//   2. it sends ONE POST carrying every id, not one request per document;
//   3. the button is hidden while there is nothing unread, and hides itself
//      again once it has run — and `[hidden]` has to be spelled out in CSS,
//      because the owner rule sets an explicit display that would beat the
//      browser's own [hidden] { display: none }.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const generator = fs.readFileSync(path.join(ROOT, 'scripts', 'build-docs-site.mjs'), 'utf8')

// --- the generator emits the control ----------------------------------------
{
  assert.match(
    generator,
    /<button class="write mark-read" type="button" id="mark-all-read" hidden>/,
    'the masthead must carry the mark-all-read button, hidden until there is work',
  )
  // `.hero .write { display: none }` + `body.is-owner .hero .write { display:
  // inline-block }` means the UA's [hidden] rule loses; without this the button
  // would show for the owner even with nothing unread.
  assert.match(
    generator,
    /\.hero \.write\[hidden\] \{ display: none !important; \}/,
    'the hidden state must be spelled out or the owner display rule beats it',
  )
  assert.match(
    generator,
    /fetch\('\/docs-manifest\.json'/,
    'the unread set must come from the manifest, not just the cards on the page',
  )
}

// --- and it really works, run against the generated page ---------------------
// The index is a tracked artifact, so this also catches a stale build.
const indexHtml = path.join(ROOT, 'frontend', 'public', 'docs.html')
const html = fs.readFileSync(indexHtml, 'utf8')
assert.match(html, /id="mark-all-read"/, 'the built index must carry the button')

// Pull the read-state <script> out of the page and run it against a stub DOM.
const block = (html.match(/<script>([\s\S]*?)<\/script>/g) || []).find((s) =>
  s.includes("getElementById('mark-all-read')"),
)
assert.ok(block, 'the built index must carry the read-state script')
const source = block.replace(/^<script>/, '').replace(/<\/script>$/, '')

function makeCard(id, isNew) {
  const card = {
    id,
    isNew,
    hasDot: isNew,
    classList: {
      remove(c) {
        if (c === 'is-new') card.isNew = false
      },
    },
    getAttribute: (name) => (name === 'data-id' ? id : null),
    querySelector: (sel) =>
      sel === '.new-dot' && card.hasDot ? { remove: () => (card.hasDot = false) } : null,
  }
  return card
}

const cards = [makeCard('a', true), makeCard('b', true)]
const button = { hidden: true, disabled: false, title: '', onClick: null }
button.addEventListener = (evt, fn) => {
  if (evt === 'click') button.onClick = fn
}
const posted = []
const document_ = {
  getElementById: (id) => (id === 'mark-all-read' ? button : null),
  querySelectorAll: (sel) => (sel.indexOf('.card-wrap') === 0 ? cards : []),
  addEventListener() {},
}
function fetch_(url, opts) {
  const method = (opts && opts.method) || 'GET'
  if (url === '/api/docs/reads' && method === 'GET') return reply(['a'])
  if (url === '/api/docs/reads' && method === 'POST') {
    posted.push(JSON.parse(opts.body))
    return reply(null)
  }
  // The manifest holds a shelved classic ('c') that has no card on the page.
  if (url === '/docs-manifest.json') return reply([{ id: 'a' }, { id: 'b' }, { id: 'c' }])
  if (url === '/api/docs/views') return reply({})
  throw new Error('unexpected fetch: ' + method + ' ' + url)
}
function reply(body) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) })
}
const settle = () => new Promise((r) => setTimeout(r, 0))

new Function('document', 'fetch', source)(document_, fetch_)
await settle()

// 'a' was already read: its stamp is gone, and it is not in the pending set.
assert.equal(cards[0].isNew, false, 'an already-opened doc must lose its NEW stamp on load')
assert.equal(cards[1].isNew, true, 'an unread doc keeps its stamp')
assert.equal(button.hidden, false, 'the button appears when something is unread')
assert.match(button.title, /2 篇未读/, 'the button says how many it would clear')

await button.onClick()
await settle()

assert.equal(posted.length, 1, 'one POST for the whole batch, not one per document')
assert.deepEqual(
  posted[0].ids.slice().sort(),
  ['b', 'c'],
  'it marks every unread manifest id — including the shelved classic with no card',
)
assert.equal(cards[1].isNew, false, 'every NEW stamp is cleared')
assert.equal(cards[1].hasDot, false, 'the stamp element is removed, not just the class')
assert.equal(button.hidden, true, 'nothing left unread — the button stands down')
assert.equal(button.disabled, false, 'the button is re-enabled after the request settles')

console.log('✔ docs-mark-all-read.test.mjs — all assertions passed')
