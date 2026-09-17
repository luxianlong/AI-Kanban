// 典籍点击量统计 — collect the dataset (task #421).
//
// Joins three sources that nobody joins at runtime:
//   1. doc_views       — the tally the Vite gate writes (id -> count, lastViewedAt)
//   2. docs-manifest   — which ids are classics (zone === 'classic') and where they live
//   3. <slug>.meta.json — when each reading was published, so a count can be
//                         divided by the days it was actually on the web.
//
// The tally lives in H2, which the running server holds open, so read a COPY of
// the file in read-only mode (see README). Counts are cross-checked against the
// live /api/docs/views so a stale copy can never pass silently.
//
// Usage: node collect.mjs <views.tsv> > dataset.json
import fs from 'node:fs'
import path from 'node:path'

const REPO = path.resolve(import.meta.dirname, '..')
const PUB = path.join(REPO, 'frontend/public')

const manifest = JSON.parse(fs.readFileSync(path.join(PUB, 'docs-manifest.json'), 'utf8'))
const entries = Object.values(manifest)

const rows = fs.readFileSync(process.argv[2], 'utf8').trim().split('\n').slice(1)
const views = new Map()
for (const line of rows) {
  const [id, n, ts] = line.split('\t')
  if (!id) continue
  views.set(id.trim(), { count: Number(n), lastViewedAt: (ts || '').trim() })
}

// Books, in shelf order (the order dianji.html lays them out).
const BOOKS = [
  ['sunzi', '孙子兵法', '春秋', -500],
  ['guiguzi', '鬼谷子', '战国', -300],
  ['sanshiliuji', '三十六计', '明清', 1600],
  ['bingjian', '冰鉴', '清', 1850],
  ['daodejing', '道德经', '春秋', -500],
  ['liaofansixun', '了凡四训', '明', 1600],
  ['sushu', '素书', '秦汉', -200],
  ['shangjunshu', '商君书', '战国', -340],
  ['luozhijing', '罗织经', '唐', 700],
  ['xiaorenjing', '小人经', '五代', 950],
  ['caigentan', '菜根谭', '明', 1600],
  ['fanjing', '反经', '唐', 716],
]
const READINGS = [
  ['hans', '简体'],
  ['', '繁體'],
  ['en', 'English'],
  ['ja', '日本語'],
]

const slugOf = (book, r) => (r ? `${book}-${r}` : book)

function meta(slug) {
  const f = path.join(PUB, 'p', `${slug}.meta.json`)
  if (!fs.existsSync(f)) return null
  return JSON.parse(fs.readFileSync(f, 'utf8'))
}

const NOW = new Date(process.env.ASOF || Date.now())
const DAY = 86400000

const out = { asof: NOW.toISOString(), books: [], shelf: null, others: [] }

function record(slug) {
  const e = entries.find((x) => x.href === `/p/${slug}.html`)
  if (!e) return null
  const v = views.get(e.id) || { count: 0, lastViewedAt: null }
  const m = meta(slug)
  const born = m && m.createdAt ? new Date(m.createdAt) : null
  const days = born ? (NOW - born) / DAY : null
  return {
    slug,
    id: e.id,
    zone: e.zone,
    title: e.title,
    bytes: m ? m.bytes : null,
    publishedAt: born ? born.toISOString() : null,
    days: days == null ? null : Number(days.toFixed(2)),
    views: v.count,
    lastViewedAt: v.lastViewedAt || null,
    perDay: days ? Number((v.count / days).toFixed(3)) : null,
  }
}

for (const [book, cn, era, year] of BOOKS) {
  const readings = {}
  for (const [r, label] of READINGS) {
    const rec = record(slugOf(book, r))
    if (rec) readings[label] = rec
  }
  const total = Object.values(readings).reduce((a, b) => a + b.views, 0)
  const first = Object.values(readings)
    .map((r) => r.publishedAt)
    .filter(Boolean)
    .sort()[0]
  out.books.push({ book, cn, era, year, total, firstPublishedAt: first, readings })
}

out.shelf = record('dianji')

// Everything else in the manifest, for the comparison the report needs.
for (const e of entries) {
  if (e.zone === 'classic') continue
  const v = views.get(e.id) || { count: 0, lastViewedAt: null }
  out.others.push({ id: e.id, title: e.title, href: e.href, zone: e.zone, views: v.count, lastViewedAt: v.lastViewedAt || null })
}

// Rows in the tally whose doc is gone from the manifest — invisible to the API.
out.orphans = [...views.entries()]
  .filter(([id]) => !entries.some((e) => e.id === id))
  .map(([id, v]) => ({ id, ...v }))

console.log(JSON.stringify(out, null, 1))
