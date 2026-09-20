// node src/resultPage.test.mjs
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { RESULT_PAGE_CSS, buildResultPage, escapeHtml, rawText, styleTags } from './resultPage.js'

// --- escaping ---------------------------------------------------------------
assert.equal(escapeHtml('a & b'), 'a &amp; b')
assert.equal(escapeHtml('<img src=x onerror=1>'), '&lt;img src=x onerror=1&gt;')
assert.equal(escapeHtml(`"quoted" & 'single'`), '&quot;quoted&quot; &amp; &#39;single&#39;')
assert.equal(escapeHtml(null), '')

// --- copying the app's stylesheets ------------------------------------------
const link = (href, rel = 'stylesheet') => ({ tagName: 'LINK', rel, href })
const style = (textContent) => ({ tagName: 'STYLE', textContent })

// A built page links its CSS; dev injects it inline. Both have to come across,
// or the tab shows unstyled Markdown.
assert.deepEqual(
  styleTags([link('http://host/assets/app-1a2b.css'), style('.md-p{margin:0}')]),
  ['<link rel="stylesheet" href="http://host/assets/app-1a2b.css">', '<style>.md-p{margin:0}</style>'],
)

// Anything that isn't a stylesheet is not CSS: a favicon or preload link would
// arrive as a broken stylesheet reference.
assert.deepEqual(styleTags([link('/favicon.ico', 'icon'), link('/x.css', 'preload')]), [])
// ...but a multi-valued rel that includes stylesheet is one.
assert.deepEqual(styleTags([link('/x.css', 'alternate stylesheet')]), ['<link rel="stylesheet" href="/x.css">'])

// A closing tag inside the CSS would end the <style> early and spill the rest of
// the rules into the page as visible text.
assert.match(styleTags([style('a::after{content:"</style>"}')])[0], /^<style>.*<\/style>$/)
assert.equal(styleTags([style('a::after{content:"</style>"}')])[0].match(/<\/style>/g).length, 1)

// Empty or absent sources are skipped rather than emitting a bare tag.
assert.deepEqual(styleTags([style('  '), link(''), {}]), [])
assert.deepEqual(styleTags(null), [])

// An element that only answers getAttribute (no properties) still works.
assert.deepEqual(
  styleTags([{ tagName: 'link', getAttribute: (n) => ({ rel: 'stylesheet', href: '/a.css' }[n]) }]),
  ['<link rel="stylesheet" href="/a.css">'],
)

// --- the document ------------------------------------------------------------
const page = buildResultPage({
  title: 'Step <one> & two',
  subtitle: '公众号文章 · Run #4',
  meta: ['Done', '12.3s · $0.0421', ''],
  bodyHtml: '<p class="md-p">rendered</p>',
  styles: ['<style>.md-p{margin:0}</style>'],
  baseHref: 'http://board.example/',
})

assert.ok(page.startsWith('<!doctype html>'))
// The renderer's output is inserted as it stands — it is HTML, not text.
assert.ok(page.includes('<article class="md rp-body"><p class="md-p">rendered</p></article>'))
// Titles are text, wherever they land.
assert.ok(page.includes('<title>Step &lt;one&gt; &amp; two</title>'))
assert.ok(page.includes('<h1 class="rp-title">Step &lt;one&gt; &amp; two</h1>'))
assert.ok(page.includes('<p class="rp-sub">公众号文章 · Run #4</p>'))
// Meta reads as chips, and a missing half of the cost line leaves no empty one.
assert.ok(page.includes('<ul class="rp-meta"><li>Done</li><li>12.3s · $0.0421</li></ul>'))
// about:blank has no URL, so relative links in the result need a base to resolve.
assert.ok(page.includes('<base href="http://board.example/">'))
assert.ok(page.includes('<style>.md-p{margin:0}</style>'))
// The page is static: it carries no script of its own and nothing to run one.
assert.ok(!/<script/i.test(page))

// Optional parts are absent rather than empty.
const bare = buildResultPage({ title: '', bodyHtml: '' })
assert.ok(bare.includes('<title>Result</title>'))
// (the class names still appear in the page chrome's CSS; the elements must not)
assert.ok(!bare.includes('class="rp-sub"'))
assert.ok(!bare.includes('<ul class="rp-meta">'))
assert.ok(!bare.includes('<base'))
assert.ok(bare.includes('<article class="md rp-body"></article>'))

// A failure trace is text: it must not be able to close the article it sits in.
assert.equal(rawText('boom </article><script>x</script>'),
  '<pre class="rp-raw">boom &lt;/article&gt;&lt;script&gt;x&lt;/script&gt;</pre>')
assert.ok(!/<script/i.test(buildResultPage({ title: 'x', bodyHtml: rawText('<script>x</script>') })))

// --- the page's own layout and type scale ------------------------------------
// The page is served by document.write into about:blank, so these rules cannot
// be exercised without a browser. What IS checkable in bare node is the thing
// that actually broke: the .md-* rules are inherited from the board, where they
// are tuned for a 340px scroll box and written in absolute rem — so a heading
// arrived here SMALLER than the paragraph under it. Below pins the overrides,
// and, more usefully, notices when the board grows a new .md-* size that this
// page has not been told about.

const decls = (body) => Object.fromEntries(
  body.split(';').map((d) => d.split(/:(.*)/s)).filter((kv) => kv.length > 1)
    .map(([k, v]) => [k.trim(), v.trim().replace(/\s+/g, ' ')]),
)

/** Flat list of {selector, decls} — at-rule bodies are walked, at-rules themselves skipped. */
function cssRules(css) {
  const out = []
  const stack = []
  let buf = ''
  for (const c of css.replace(/\/\*[\s\S]*?\*\//g, '')) {
    if (c === '{') { const head = buf.trim(); buf = ''; stack.push(head.startsWith('@') ? null : head) }
    else if (c === '}') { const head = stack.pop(); if (head) out.push({ selector: head.replace(/\s+/g, ' '), decls: decls(buf) }); buf = '' }
    else buf += c
  }
  return out
}

const boardCss = readFileSync(new URL('./styles.css', import.meta.url), 'utf8')
const pageRules = cssRules(RESULT_PAGE_CSS)
const selectors = (r) => r.selector.split(',').map((s) => s.trim())
const emValue = (v) => /^[\d.]+em$/.test(v)

// Every font-size this page restates is in em, so the whole result scales with
// --rp-font. One rem in here and that element stops moving with the rest.
for (const r of pageRules) {
  if (!r.selector.startsWith('.rp-body ') || !r.decls['font-size']) continue
  assert.ok(emValue(r.decls['font-size']),
    `${r.selector} sets font-size ${r.decls['font-size']} — must be em so it tracks --rp-font`)
}

// Headings outrank the prose they head, and each other. h3 used to be 0.92rem
// against a 0.95rem paragraph, which is the whole complaint in one number.
const ruleFor = (sel, prop) => {
  const r = pageRules.find((x) => selectors(x).includes(sel) && (!prop || x.decls[prop]))
  assert.ok(r, `the maximize page has no rule setting ${prop || 'anything'} for ${sel}`)
  return r.decls
}
const sizeOf = (sel) => parseFloat(ruleFor(sel, 'font-size')['font-size'])
const [h2, h3, h4] = ['.rp-body h2.md-h', '.rp-body h3.md-h', '.rp-body h4.md-h'].map(sizeOf)
assert.ok(h2 > h3 && h3 > h4, `heading scale not descending: ${h2} ${h3} ${h4}`)
assert.ok(h4 > 1, `h4 (${h4}em) must be larger than the paragraph it heads`)
// Tables and code were 12.5px against 15.2px prose — near, not far below it.
for (const sel of ['.rp-body .md-table', '.rp-body .md-pre code']) {
  assert.ok(sizeOf(sel) >= 0.85, `${sel} at ${sizeOf(sel)}em is back at board density`)
}

// The board may add a .md-* size at any time; anything absolute has to be
// restated here or it arrives on the reading page at scroll-box size.
const overridden = new Set(
  pageRules.flatMap((r) => (r.decls['font-size'] ? selectors(r) : []))
    .map((s) => s.replace(/^\.rp-body /, '')),
)
for (const r of cssRules(boardCss)) {
  const size = r.decls['font-size']
  // .md.logbox.result needs all three classes and so never matches this page.
  if (!size || emValue(size) || !/(^|[\s.])md[-.\s]/.test(r.selector) || r.selector.includes('.logbox')) continue
  for (const sel of selectors(r)) {
    assert.ok(overridden.has(sel),
      `styles.css sets ${sel} { font-size: ${size} } — absolute, so the maximize page must restate it in em`)
  }
}

// Two widths, not one: prose is clamped to a measure, and the blocks whose
// width is part of their content are let out past it to the sheet's edge.
const root = ruleFor('.rp', '--rp-measure')
assert.ok(parseFloat(root['--rp-measure']) < parseFloat(root['--rp-wide']))
assert.equal(ruleFor('.rp-body > *', 'max-width')['max-width'], 'var(--rp-measure)')
for (const sel of ['.rp-body > .md-table-wrap', '.rp-body > .md-pre', '.rp-body > .rp-raw']) {
  assert.equal(ruleFor(sel, 'max-width')['max-width'], 'none', `${sel} is still held to the text measure`)
}
// ...and the sheet is only ever as wide as the widest of them.
assert.equal(ruleFor('.rp-page', 'width').width, 'fit-content')

// The diff stripe is drawn full-bleed by cancelling .md-pre's own padding. The
// page changes that padding, so the two numbers have to move together.
const prePad = ruleFor('.rp-body .md-pre', 'padding').padding.split(' ')[1]
const stripe = ruleFor('.rp-body .md-diff-line', 'padding')
assert.equal(stripe.margin, `0 -${prePad}`)
assert.ok(stripe.padding.startsWith(`0 ${prePad} 0 calc(${prePad} +`))

console.log('ok')
