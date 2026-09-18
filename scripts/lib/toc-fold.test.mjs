#!/usr/bin/env node
// Self-contained regression test for the published pages' foldable contents
// list — run with `node scripts/lib/toc-fold.test.mjs` (or via `npm test` from
// frontend/). Like its neighbours it is its own harness: it asserts and exits
// non-zero on the first failure.
//
// What it guards (task #426). The /docs/ articles got a fold in task #224; the
// pages under /p/ never had one, so their sidebar held a ~250px column open for
// the whole read. /vendor/toc-fold.js retrofits it at runtime, which means it
// has to cope with markup it did not write. The things that are easy to break:
//
//   1. TWO page families, one script. The 典籍 readers are bare <a> children
//      with no heading; the uploaded long-form articles lead with
//      <h2>本页目录</h2><ol>. The control must land in the page's own heading
//      when there is one (or the page's `.toc h2` styling is orphaned) and be
//      synthesised when there isn't.
//   2. localStorage THROWS on these pages. /p/ is served with a CSP sandbox and
//      no allow-same-origin, so the opaque origin turns a plain getItem() into
//      a SecurityError. Unguarded, that kills the script at load and leaves a
//      dead button.
//   3. The column is reclaimed from the PARENT grid, whose width and breakpoint
//      differ per page — so it is measured, never assumed, and the override is
//      cleared before measuring or the second toggle reads back its own value.
//   4. A page that already folds (a /docs/ article's `.toc-title`) must not get
//      a second control for the same state.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const PUBLIC = path.join(ROOT, 'frontend', 'public')
const source = fs.readFileSync(path.join(PUBLIC, 'vendor', 'toc-fold.js'), 'utf8')

// --- a stub DOM, just wide enough to run the script for real -----------------
// Only the handful of selectors toc-fold.js actually uses are supported; a
// selector outside that set throws rather than quietly matching nothing.
function matches(el, sel) {
  return sel.split(',').some((part) => {
    const one = part.trim()
    const m = /^([a-z]+)?((?:\.[\w-]+)*)(\[href\^="#"\])?$/.exec(one)
    if (!m) throw new Error(`stub DOM: unsupported selector ${one}`)
    const [, tag, classes, hrefPrefix] = m
    if (tag && el.tagName !== tag.toUpperCase()) return false
    for (const c of (classes || '').split('.').filter(Boolean)) {
      if (!el.classList.contains(c)) return false
    }
    if (hrefPrefix && !String(el.attrs.href || '').startsWith('#')) return false
    return true
  })
}

function makeEl(tag) {
  const classes = new Set()
  const inline = new Map()
  const el = {
    tagName: tag.toUpperCase(),
    attrs: {},
    childNodes: [],
    parentElement: null,
    listeners: {},
    classList: {
      contains: (c) => classes.has(c),
      add: (c) => classes.add(c),
      toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)),
    },
    style: {
      removeProperty: (p) => inline.delete(p),
      get gridTemplateColumns() {
        return inline.get('grid-template-columns') || ''
      },
      set gridTemplateColumns(v) {
        inline.set('grid-template-columns', v)
      },
      get marginInline() {
        return inline.get('margin-inline') || ''
      },
      set marginInline(v) {
        inline.set('margin-inline', v)
      },
    },
    setAttribute: (k, v) => {
      el.attrs[k] = String(v)
    },
    getAttribute: (k) => (k in el.attrs ? el.attrs[k] : null),
    addEventListener: (type, fn) => (el.listeners[type] ||= []).push(fn),
    appendChild(node) {
      if (node.parentElement) node.parentElement.remove(node)
      node.parentElement = el
      el.childNodes.push(node)
      return node
    },
    insertBefore(node, ref) {
      if (node.parentElement) node.parentElement.remove(node)
      node.parentElement = el
      const at = ref ? el.childNodes.indexOf(ref) : el.childNodes.length
      el.childNodes.splice(at < 0 ? el.childNodes.length : at, 0, node)
      return node
    },
    remove(node) {
      const at = el.childNodes.indexOf(node)
      if (at >= 0) el.childNodes.splice(at, 1)
      node.parentElement = null
    },
    querySelector(sel) {
      for (const d of el.descendants()) if (matches(d, sel)) return d
      return null
    },
    *descendants() {
      for (const c of el.childNodes) {
        if (c.tagName) {
          yield c
          yield* c.descendants()
        }
      }
    },
  }
  Object.defineProperty(el, 'firstChild', { get: () => el.childNodes[0] || null })
  Object.defineProperty(el, 'nextElementSibling', {
    get: () => {
      const sibs = el.parentElement ? el.parentElement.childNodes : []
      return sibs.slice(sibs.indexOf(el) + 1).find((c) => c.tagName) || null
    },
  })
  Object.defineProperty(el, 'firstElementChild', {
    get: () => el.childNodes.find((c) => c.tagName) || null,
  })
  Object.defineProperty(el, 'className', {
    get: () => [...classes].join(' '),
    set: (v) => {
      classes.clear()
      for (const c of String(v).split(/\s+/).filter(Boolean)) classes.add(c)
    },
  })
  Object.defineProperty(el, 'textContent', {
    get: () => el.childNodes.map((c) => (c.tagName ? c.textContent : c.text)).join(''),
    set: (v) => {
      el.childNodes = []
      el.appendChild({ text: String(v), parentElement: null })
    },
  })
  Object.defineProperty(el, 'inlineProps', { get: () => inline })
  return el
}

function el(tag, attrs = {}, kids = []) {
  const node = makeEl(tag)
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v
    else node.setAttribute(k, v)
  }
  for (const kid of kids) node.appendChild(typeof kid === 'string' ? { text: kid, parentElement: null } : kid)
  return node
}

// Build a page, run toc-fold.js inside it, hand back the handles a test needs.
// `storage` may be a thrower, standing in for the opaque origin.
function run({ toc, wrapper = 'layout', computed, storage } = {}) {
  // The nav and the prose it indexes are the two grid items, in that order —
  // the shape both page families share.
  const prose = el('main', {}, ['正文'])
  const parent = el('div', { class: wrapper }, [toc, prose])
  const body = el('body', {}, [parent])
  const head = el('head')
  const root = el('html', {}, [head, body])

  const document = {
    readyState: 'complete',
    head,
    body,
    createElement: makeEl,
    createTextNode: (text) => ({ text, parentElement: null }),
    addEventListener: () => {},
    querySelector: (sel) => {
      for (const d of root.descendants()) if (matches(d, sel)) return d
      return null
    },
  }
  const window = {
    localStorage: storage || memoryStorage(),
    getComputedStyle: (node) => ({
      display: node === parent ? computed.display : 'block',
      gridTemplateColumns:
        node.inlineProps.get('grid-template-columns') ||
        (node === parent ? computed.gridTemplateColumns : 'none'),
    }),
    addEventListener: (type, fn) => (root.listeners[type] ||= []).push(fn),
  }
  new Function('window', 'document', source)(window, document)

  const btn = toc.querySelector('.toc-fold')
  return {
    toc,
    parent,
    prose,
    btn,
    head,
    click: () => btn.listeners.click.forEach((fn) => fn()),
    resize: () => (root.listeners.resize || []).forEach((fn) => fn()),
    styles: head.childNodes.map((s) => s.textContent).join('\n'),
  }
}

function memoryStorage(seed = {}) {
  const map = new Map(Object.entries(seed))
  return { getItem: (k) => (map.has(k) ? map.get(k) : null), setItem: (k, v) => map.set(k, v) }
}

// The 典籍 readers: bare anchors in the nav, no heading of their own.
const classicToc = () =>
  el('nav', { class: 'toc', id: 'toc' }, [
    el('a', { class: 'l2', href: '#ch1' }, ['一、计篇']),
    el('a', { class: 'l3', href: '#ch1p1' }, ['兵者，国之大事']),
  ])

// The uploaded long-form articles: the page writes its own "本页目录" heading.
const articleToc = () =>
  el('nav', { class: 'toc' }, [
    el('h2', {}, ['本页目录']),
    el('ol', {}, [el('li', {}, [el('a', { href: '#sec-1' }, ['1. 环境'])])]),
  ])

const WIDE = { display: 'grid', gridTemplateColumns: '236px 796px' }
const NARROW = { display: 'grid', gridTemplateColumns: '1120px' }

// --- 1. the control lands in the right place for each family -----------------
{
  const page = run({ toc: classicToc(), wrapper: 'wrap', computed: WIDE })
  assert.ok(page.btn, '典籍 reader must get a fold control')
  const host = page.toc.firstElementChild
  assert.equal(host.tagName, 'DIV', 'with no heading of its own the nav gets a synthesised host')
  assert.ok(host.classList.contains('toc-fold-head'), 'the host must be marked so the fold can spare it')
  assert.match(host.textContent, /目录/, 'the synthesised host needs a label')
  // The page's own links are untouched and still first-class children.
  assert.equal(page.toc.childNodes.filter((c) => c.tagName === 'A').length, 2)
}
{
  const page = run({ toc: articleToc(), computed: WIDE })
  const host = page.toc.firstElementChild
  assert.equal(host.tagName, 'H2', "the page's own heading must stay the heading")
  assert.ok(host.classList.contains('toc-fold-head'))
  // The button moved *inside* the h2, carrying its text, so `.toc h2 {…}` in the
  // page's stylesheet still applies. A replaced heading would drop that styling.
  assert.equal(page.btn.parentElement, host, 'the control belongs inside the existing heading')
  assert.match(page.btn.textContent, /本页目录/, "the heading's own words must survive")
  assert.equal(page.toc.childNodes.filter((c) => c.tagName === 'H2').length, 1, 'no duplicate heading')
}

// --- 2. folding hides the list and gives the column back ---------------------
{
  const page = run({ toc: articleToc(), computed: WIDE })
  assert.equal(page.toc.classList.contains('collapsed'), false, 'starts open')
  assert.equal(page.btn.getAttribute('aria-expanded'), 'true')
  assert.equal(page.parent.inlineProps.get('grid-template-columns'), undefined, 'open: page keeps its own grid')

  page.click()
  assert.ok(page.toc.classList.contains('collapsed'), 'clicking folds the catalog')
  assert.equal(page.btn.getAttribute('aria-expanded'), 'false')
  assert.equal(
    page.parent.inlineProps.get('grid-template-columns'),
    'max-content minmax(0, 1fr)',
    'folded, the margin column shrinks to the control and the prose takes the rest',
  )

  // Toggling back must restore the page's own grid, not leave the override on.
  page.click()
  assert.equal(page.toc.classList.contains('collapsed'), false)
  assert.equal(page.parent.inlineProps.get('grid-template-columns'), undefined, 'the override is cleared again')

  // And a second fold must still work: the width is re-measured from the page's
  // grid, not read back from the override the previous fold wrote.
  page.click()
  assert.equal(page.parent.inlineProps.get('grid-template-columns'), 'max-content minmax(0, 1fr)')
}

// The prose has to be re-centred in the widened track. The 典籍 readers cap
// `main` at 756px, so without this the reclaimed space becomes an empty
// right-hand gutter and the text lurches ~190px to the left instead of ~48px.
// On a page whose prose has no max-width the auto margins resolve to zero and
// the text really does get wider, which is why one rule covers both families.
{
  const page = run({ toc: classicToc(), wrapper: 'wrap', computed: WIDE })
  assert.equal(page.prose.inlineProps.get('margin-inline'), undefined, 'open: the page owns its margins')
  page.click()
  assert.equal(page.prose.inlineProps.get('margin-inline'), 'auto', 'folded: the prose re-centres')
  page.click()
  assert.equal(page.prose.inlineProps.get('margin-inline'), undefined, 'reopened: the margins are handed back')
}
// A layout with nothing to reclaim must not touch the prose either.
{
  const page = run({ toc: classicToc(), wrapper: 'wrap', computed: NARROW })
  page.click()
  assert.equal(page.prose.inlineProps.get('margin-inline'), undefined, 'stacked: no centring without a reclaim')
}

// The CSS has to win against the page's own rules, which set explicit displays.
{
  const page = run({ toc: classicToc(), wrapper: 'wrap', computed: WIDE })
  assert.match(
    page.styles,
    /\.toc\.collapsed > \*:not\(\.toc-fold-head\)\{display:none!important\}/,
    'the fold must hide every child but the control host, and beat the page stylesheet',
  )
}

// --- 2b. folded, the control is a caret and nothing else (task #457) --------
// The first cut of the fold kept the heading visible, so a "collapsed" catalog
// still held 41px (典籍) / 68px (uploaded) of margin open — several character
// widths of chrome for a list nobody is reading. The words now live in their
// own span so they can be hidden with the list.
{
  for (const [family, toc, words] of [
    ['典籍 reader', classicToc(), '目录'],
    ['uploaded article', articleToc(), '本页目录'],
  ]) {
    const page = run({ toc, wrapper: 'wrap', computed: WIDE })
    const label = page.btn.querySelector('.toc-fold-label')
    assert.ok(label, `${family}: the words must be wrapped so the fold can hide them`)
    assert.equal(label.textContent, words, `${family}: and the wrapper must carry the page's own words`)
    // Everything in the button is either the label or the caret: a stray text
    // node outside the span survives the fold and re-widens the column.
    for (const child of page.btn.childNodes) {
      assert.ok(
        child.tagName && (child === label || child.classList.contains('toc-fold-caret')),
        `${family}: only the label span and the caret may sit in the control`,
      )
    }
    // The caret is aria-hidden, so once the label is display:none the button
    // would have no accessible name at all.
    assert.equal(page.btn.getAttribute('aria-label'), words, `${family}: the bare caret still needs a name`)
    assert.equal(page.btn.getAttribute('title'), '收起目录', `${family}: open, the control says what it does`)
    page.click()
    assert.equal(page.btn.getAttribute('title'), '展开目录', `${family}: folded, an arrow alone must explain itself`)
    page.click()
    assert.equal(page.btn.getAttribute('title'), '收起目录')
  }
}
{
  const page = run({ toc: articleToc(), computed: WIDE })
  assert.match(
    page.styles,
    /\.toc\.collapsed \.toc-fold-label\{display:none\}/,
    'the label must be hidden by the fold, or the column stays several characters wide',
  )
  // The page's own heading rules (`.toc h2 {margin:0 0 10px}`, and on other
  // pages a border-bottom) are separating nothing once the list is gone, and
  // they are more specific than an unqualified override would be.
  assert.match(
    page.styles,
    /\.toc\.collapsed \.toc-fold-head\{margin:0!important;padding:0!important;border:0!important\}/,
    "folded, the host must shed the page's own spacing and rule",
  )
  // A caret at the label's 0.8em is an ~8px hit target; folded, the button
  // grows padding of its own. The padding must NOT be pulled back out with a
  // negative margin — both page families make the nav a scroll box, which
  // clips anything hanging outside it out of the hit region too.
  assert.match(page.styles, /\.toc\.collapsed \.toc-fold\{width:auto;gap:0;padding:5px\}/)
  assert.doesNotMatch(page.styles, /\.toc\.collapsed \.toc-fold-caret\{[^}]*margin:-/)
}

// --- 3. a single-column layout has no column to reclaim ----------------------
// Below each page's own breakpoint the wrapper stacks; forcing two tracks there
// would break the layout outright. The fold still hides the list.
{
  const page = run({ toc: articleToc(), computed: NARROW })
  page.click()
  assert.ok(page.toc.classList.contains('collapsed'), 'the list still folds on a narrow screen')
  assert.equal(page.parent.inlineProps.get('grid-template-columns'), undefined, 'no two-column override when stacked')
}

// --- 4. the opaque origin: localStorage throws, everything else keeps working -
{
  const throwing = {
    getItem() {
      throw new Error('SecurityError: opaque origin')
    },
    setItem() {
      throw new Error('SecurityError: opaque origin')
    },
  }
  const page = run({ toc: articleToc(), computed: WIDE, storage: throwing })
  assert.ok(page.btn, 'the control must still be built when storage is unreachable')
  page.click()
  assert.ok(page.toc.classList.contains('collapsed'), 'and the fold must still toggle')
}

// The preference is honoured when storage *is* readable, so the choice carries
// from one document to the next — same key the /docs/ articles use.
{
  const page = run({
    toc: articleToc(),
    computed: WIDE,
    storage: memoryStorage({ 'docs:toc-collapsed': '1' }),
  })
  assert.ok(page.toc.classList.contains('collapsed'), 'a remembered fold applies on load')
  assert.equal(page.parent.inlineProps.get('grid-template-columns'), 'max-content minmax(0, 1fr)')
}
assert.match(source, /'docs:toc-collapsed'/, 'must share the key with the /docs/ articles (task #224)')

// --- 5. pages that already fold, or have no catalog, are left alone ----------
{
  // A /docs/ article drives its fold from `.toc-title`; a second control would
  // be two widgets over one piece of state.
  const generated = el('nav', { class: 'toc' }, [
    el('button', { class: 'toc-title' }, ['目录']),
    el('ul', { id: 'toc-list' }, [el('li', {}, [el('a', { href: '#a' }, ['A'])])]),
  ])
  const page = run({ toc: generated, computed: WIDE })
  assert.equal(page.btn, null, 'a page that already folds must not get a second control')
}
{
  // Something else wearing the class is not a contents list.
  const notAToc = el('nav', { class: 'toc' }, [el('span', {}, ['no links here'])])
  const page = run({ toc: notAToc, computed: WIDE })
  assert.equal(page.btn, null, 'a nav with no anchors gets no control')
}

// --- 6. the backend really injects it, and the shipped pages really carry it -
const service = fs.readFileSync(
  path.join(ROOT, 'server', 'src', 'main', 'java', 'com', 'kanban', 'pages', 'PageUploadService.java'),
  'utf8',
)
assert.match(service, /\/vendor\/toc-fold\.js/, 'newly uploaded pages must be given the fold')
assert.match(service, /html = withTocFold\(html\);/, 'and the injector must actually be wired into store()')

// Guards against a half-applied retrofit: /p/*.html are tracked artifacts, so a
// page with a contents list and no fold tag would ship exactly the bug #426 is
// about. The /docs/ articles are excluded — they fold from `.toc-title`.
{
  const dir = path.join(PUBLIC, 'p')
  if (fs.existsSync(dir)) {
    const missing = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith('.html'))
      .filter((f) => {
        const html = fs.readFileSync(path.join(dir, f), 'utf8')
        return /class="toc"/.test(html) && !/toc-title/.test(html) && !/toc-fold\.js/.test(html)
      })
    assert.deepEqual(missing, [], 'these published pages have a contents list that cannot fold')
  }
}

console.log('✔ toc-fold.test.mjs — all assertions passed')
