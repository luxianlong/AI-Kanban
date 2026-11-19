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
//
// #497: the baseline is now *appended*, not replaced. Every snapshot ever taken
// is kept in baselines.json; the page compares live numbers against the newest
// one and shows the whole chain below it. Refreshing the baseline used to throw
// away the only long-range series we had — it no longer does.
import fs from 'node:fs'
import path from 'node:path'

const HERE = path.resolve(import.meta.dirname)
const OUT = path.join(HERE, '..', 'frontend', 'public', 'stats', 'dianji.html')
const HIST = path.join(HERE, 'baselines.json')
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

// ---- the chain of baselines -------------------------------------------------
// Append this snapshot (replacing any earlier one with the same asof, so a
// re-run is idempotent), then derive one row per snapshot for the page.
const snapshots = fs.existsSync(HIST) ? JSON.parse(fs.readFileSync(HIST, 'utf8')) : []
const mine = { asof: d.asof, note: process.env.NOTE || '', counts: baseline.counts }
const kept = snapshots.filter((s) => s.asof !== mine.asof)
kept.push(mine)
kept.sort((a, b) => a.asof.localeCompare(b.asof))
fs.writeFileSync(HIST, JSON.stringify(kept, null, 1) + '\n')

const sum = (c) => Object.values(c).reduce((a, b) => a + b, 0)
baseline.history = kept.map((s, i) => {
  const prev = i ? kept[i - 1] : null
  // Split the growth: pages that did not exist at the previous snapshot vs.
  // pages that did. The first number is what publishing a book costs; the
  // second is the only place a reader can show up.
  let fresh = 0, freshViews = 0, carried = 0
  if (prev) {
    for (const [id, n] of Object.entries(s.counts)) {
      if (prev.counts[id] == null) { fresh++; freshViews += n }
      else carried += n - prev.counts[id]
    }
  }
  return {
    asof: s.asof,
    note: s.note || '',
    pages: Object.keys(s.counts).length,
    total: sum(s.counts),
    delta: prev ? sum(s.counts) - sum(prev.counts) : null,
    freshPages: prev ? fresh : null,
    freshViews: prev ? freshViews : null,
    carried: prev ? carried : null,
  }
})

const html = fs.readFileSync(path.join(HERE, 'page.template.html'), 'utf8').replace(
  '/*__BASELINE__*/null',
  JSON.stringify(baseline, null, 1)
)
fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, html)
console.log(
  `wrote ${OUT} (${(html.length / 1024).toFixed(1)} KB, baseline ${d.asof}, ` +
    `${baseline.books.length} books, ${Object.keys(baseline.counts).length} pages, ` +
    `${kept.length} baselines in chain)`
)
