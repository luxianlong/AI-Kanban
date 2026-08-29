// Self-contained regression test for the archive index's entry meta line —
// run with `node scripts/lib/docs-index.test.mjs` (or via `npm test` from
// frontend/). Like md-to-html.test.mjs it is its own harness: it asserts and
// exits non-zero on the first failure.
//
// What it guards (task #83): the date and the visit tally share one mono grey
// line, so they must be separated by a visible mark. The separator used to hang
// off `.views:first-child`, which never matched a bare text-node date — the two
// numbers ran together as "2026.08.2912 次阅读".
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const generator = fs.readFileSync(path.join(ROOT, 'scripts', 'build-docs-site.mjs'), 'utf8')

// --- the generator emits the date inside its own element ---------------------
{
  assert.match(
    generator,
    /<div class="entry-date">\$\{c\.updatedAt \? `<span class="date">\$\{formatDate\(c\.updatedAt\)\}<\/span>` : ''\}<span class="views" hidden><\/span><\/div>/,
    'the entry date must be wrapped in <span class="date"> so the tally can be separated from it',
  )
  // The separator keys off that element, not off `:first-child` (which text
  // nodes do not affect and so could never tell "date present" from "no date").
  assert.match(generator, /\.entry-date \.date \+ \.views::before \{ content: "·"/)
  assert.doesNotMatch(generator, /\.entry-date \.views:first-child::before/)
}

// --- and the generated index really carries it -------------------------------
// Guards against a stale build being shipped: docs.html is a tracked artifact.
{
  const indexHtml = path.join(ROOT, 'frontend', 'public', 'docs.html')
  if (fs.existsSync(indexHtml)) {
    const html = fs.readFileSync(indexHtml, 'utf8')
    const metas = html.match(/<div class="entry-date">.*?<\/div>/g) || []
    assert.ok(metas.length > 0, 'the archive index must render at least one entry')
    for (const meta of metas) {
      // Every dated entry keeps its date in an element; none leaves the date as
      // a bare text node butted up against the tally.
      assert.doesNotMatch(
        meta,
        /<div class="entry-date">[^<]+</,
        `date must be wrapped, got: ${meta}`,
      )
    }
  }
}

console.log('✔ docs-index.test.mjs — all assertions passed')
