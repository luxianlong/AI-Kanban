// 典籍点击量统计 — build the standing dashboard (task #421).
//
// Reads dataset.json (see collect.mjs) and writes frontend/public/stats/dianji.html.
// The page is NOT a document: it lives outside public/p/ and public/docs/, so the
// docs generator never turns it into an article card, the sitemap never lists it,
// and — because only manifest hrefs are counted — opening it does not add a click
// to anything. It sits behind the site password like the board itself.
//
// What is baked in: the book list, each reading's publish date, and the baseline
// snapshot taken when this file was generated. What is fetched live: the current
// tallies from /api/docs/views. So the page keeps telling the truth as the numbers
// move, and shows the delta against the baseline — which is the only way to see
// growth from a counter that only ever stores a cumulative total.
import fs from 'node:fs'
import path from 'node:path'

const HERE = path.resolve(import.meta.dirname)
const OUT = path.join(HERE, '..', 'frontend', 'public', 'stats', 'dianji.html')
const d = JSON.parse(fs.readFileSync(path.join(HERE, 'dataset.json'), 'utf8'))

const READINGS = ['简体', '繁體', 'English', '日本語']

// The baseline: id -> count at generation time, plus the per-reading facts the
// live endpoint does not carry (publish date, last-view, size).
const baseline = { asof: d.asof, counts: {}, books: [], shelf: null }
for (const b of d.books) {
  const row = { cn: b.cn, era: b.era, readings: {} }
  for (const r of READINGS) {
    const x = b.readings[r]
    if (!x) continue
    baseline.counts[x.id] = x.views
    row.readings[r] = {
      id: x.id,
      slug: x.slug,
      href: `/p/${x.slug}.html`,
      publishedAt: x.publishedAt,
      lastViewedAt: x.lastViewedAt,
      kb: Math.round(x.bytes / 1024),
    }
  }
  baseline.books.push(row)
}
baseline.counts[d.shelf.id] = d.shelf.views
baseline.shelf = {
  id: d.shelf.id,
  href: '/p/dianji.html',
  publishedAt: d.shelf.publishedAt,
  lastViewedAt: d.shelf.lastViewedAt,
}

const html = fs.readFileSync(path.join(HERE, 'page.template.html'), 'utf8').replace(
  '/*__BASELINE__*/null',
  JSON.stringify(baseline, null, 1)
)
fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, html)
console.log(`wrote ${OUT} (${(html.length / 1024).toFixed(1)} KB, baseline ${d.asof})`)
