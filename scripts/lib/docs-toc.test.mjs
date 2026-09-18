// Self-contained regression test for the article page's foldable contents list —
// run with `node scripts/lib/docs-toc.test.mjs` (or via `npm test` from
// frontend/). Like docs-index.test.mjs it is its own harness: it asserts and
// exits non-zero on the first failure.
//
// What it guards (task #224): the catalog folds away, and both controls (the
// heading in the margin, the ☰ bar on a narrow screen) drive the SAME state.
// The earlier narrow-screen control toggled the class straight from an inline
// onclick, which is exactly the shape that desynchronises once a second control
// and an aria-expanded flag exist.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const generator = fs.readFileSync(path.join(ROOT, 'scripts', 'build-docs-site.mjs'), 'utf8')

// --- the generator emits a real control, not a bare heading ------------------
{
  assert.match(
    generator,
    /<button class="toc-title" type="button" aria-expanded="true" aria-controls="toc-list"/,
    'the contents heading must be a button so it can be clicked and reached by keyboard',
  )
  assert.match(generator, /<ul id="toc-list">/, 'the list needs the id that aria-controls points at')
  // Folding must actually hide the list and hand the freed column to the prose.
  assert.match(generator, /\.toc\.collapsed ul \{ display: none; \}/)
  assert.match(generator, /\.layout\.toc-collapsed \{ grid-template-columns: max-content/)

  // Task #457: folded, the control is the caret and nothing else. The word 目录
  // is in its own span precisely so it can be hidden; if it leaks back out of
  // the span the fold quietly goes back to holding ~45px of margin open.
  assert.match(generator, /<span class="toc-label">目录<\/span>/, 'the label must be wrappable/hideable')
  assert.match(generator, /\.toc\.collapsed \.toc-label \{ display: none; \}/, 'folded, the label goes')
  assert.match(
    generator,
    /\.toc\.collapsed \.toc-title \{ border-bottom: none; padding: 5px; margin: 0; width: auto; gap: 0; \}/,
    'the bare caret keeps a click-sized pad and drops the heading rule and its spacing',
  )
  // The pad has to live inside the nav: `.toc` is a scroll box, so a negative
  // margin would hang the hit area outside it and get it clipped away.
  assert.match(
    generator,
    /\.toc\.collapsed \{ padding-right: 0; overflow: visible; max-height: none; \}/,
    'the folded nav must stop being a scroll box',
  )
  // With the words gone the button has no accessible name left — the caret is
  // aria-hidden — so it has to carry one.
  assert.match(generator, /<button class="toc-title"[^>]*aria-label="目录"/, 'the caret needs a name')
  // One state, one code path: no control may flip the class on its own.
  assert.doesNotMatch(
    generator,
    /onclick="document\.querySelector\('\.toc'\)/,
    'the narrow-screen bar must go through the shared toggle, not an inline classList.toggle',
  )
  // The choice survives to the next document.
  assert.match(generator, /localStorage\.setItem\(TOC_KEY/)
}

// --- and the generated pages really carry it ---------------------------------
// Guards against a stale build being shipped: docs/*.html are tracked artifacts.
{
  const outDir = path.join(ROOT, 'frontend', 'public', 'docs')
  if (fs.existsSync(outDir)) {
    const pages = fs
      .readdirSync(outDir)
      .filter((f) => f.endsWith('.html'))
      .map((f) => [f, fs.readFileSync(path.join(outDir, f), 'utf8')])
      // Only pages this generator wrote: docs/ also holds one hand-authored
      // HTML page from before the generator existed, with its own markup.
      .filter(([, html]) => html.includes('<nav class="toc" aria-label="目录">'))
    assert.ok(pages.length > 0, 'at least one generated article must have a contents list')
    for (const [name, html] of pages) {
      assert.match(html, /<button class="toc-title"/, `${name} must render the fold control`)
      assert.match(html, /<ul id="toc-list">/, `${name} must render the list the control targets`)
      assert.match(html, /<span class="toc-label">/, `${name} predates the caret-only fold (#457)`)
      assert.doesNotMatch(html, /<div class="toc-title">/, `${name} still has the old static heading`)
    }
  }
}

console.log('✔ docs-toc.test.mjs — all assertions passed')
