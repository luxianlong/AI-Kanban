// node src/resultPage.test.mjs
import assert from 'node:assert/strict'
import { buildResultPage, escapeHtml, rawText, styleTags } from './resultPage.js'

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

console.log('ok')
