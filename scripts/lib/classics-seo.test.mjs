#!/usr/bin/env node
// Self-contained regression test for the 典籍 pages' search metadata —
// run with `node scripts/lib/classics-seo.test.mjs` (or via `npm test` from
// frontend/). Like its neighbours it is its own harness: it asserts and exits
// non-zero on the first failure.
//
// What it guards. Forty-four reading pages are four readings of eleven books,
// and to a crawler they look like forty-four near-duplicates of each other.
// Three declarations are what keep them apart, and each was missing once:
//
//   1. a self-referencing canonical, so no reading is folded into another
//      (task #417);
//   2. a complete, mutually agreeing hreflang set — 简体 / 繁體 / English /
//      日本語 plus x-default — which only works if all four readings publish
//      the SAME set. One page listing three of the four is the failure mode
//      that silently drops a reading (tasks #417, #418);
//   3. a `sameAs` naming the work's Wikidata item, so the page is about the
//      孫子兵法 Google already knows rather than an unidentified text with a
//      similar title (task #419).
//
// The set of pages is taken from the manifest's `classic` zone, so a book
// added later is covered without touching this file.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const PUBLIC = path.join(ROOT, 'frontend', 'public')
const manifestPath = path.join(PUBLIC, 'docs-manifest.json')
if (!fs.existsSync(manifestPath)) {
  console.log('✔ classics-seo.test.mjs — no manifest yet, nothing to check')
  process.exit(0)
}

const generator = fs.readFileSync(path.join(ROOT, 'scripts', 'build-docs-site.mjs'), 'utf8')
const SHELF = (generator.match(/const CLASSICS_SHELF_HREF = '([^']+)'/) || [])[1]
const ORIGIN = 'https://youraikanban.com'

const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
const readers = manifest
  .filter((e) => e.zone === 'classic' && e.href !== SHELF)
  .map((e) => e.href)
  .filter((href) => fs.existsSync(path.join(PUBLIC, href.replace(/^\//, ''))))
  .sort()

if (!readers.length) {
  console.log('✔ classics-seo.test.mjs — no shelved readings yet, nothing to check')
  process.exit(0)
}

const read = (href) => fs.readFileSync(path.join(PUBLIC, href.replace(/^\//, '')), 'utf8')
const jsonld = (html) => {
  const m = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)
  assert.ok(m, 'the page must carry JSON-LD')
  return JSON.parse(m[1])['@graph']
}
const alternates = (html) =>
  Object.fromEntries(
    [...html.matchAll(/<link rel="alternate" hreflang="([\w-]+)" href="([^"]+)">/g)].map((m) => [
      m[1],
      m[2],
    ]),
  )

// --- every reading declares itself, and all four agree on the family ---------
const families = new Map()
for (const href of readers) {
  const html = read(href)
  const canonical = (html.match(/<link rel="canonical" href="([^"]+)">/) || [])[1]
  assert.equal(canonical, ORIGIN + href, `${href}: canonical must point at itself`)

  const alts = alternates(html)
  for (const lang of ['zh-Hans', 'zh-Hant', 'en', 'ja', 'x-default']) {
    assert.ok(alts[lang], `${href}: no ${lang} alternate — a reading would be invisible`)
  }
  assert.ok(
    Object.values(alts).includes(canonical),
    `${href}: the hreflang set must include the page itself`,
  )
  // Four readings of one book form one family; they must publish one identical
  // set, or Google picks the one that names the most siblings and drops the rest.
  const key = [...new Set(Object.values(alts))].sort().join('|')
  if (!families.has(key)) families.set(key, [])
  families.get(key).push(href)
}
for (const [key, group] of families) {
  const listed = key.split('|').map((u) => u.replace(ORIGIN, ''))
  assert.deepEqual(
    group.sort(),
    [...new Set(listed)].sort(),
    'every reading in a family must list, and be listed by, exactly its siblings',
  )
}

// --- every reading says which work it is ------------------------------------
for (const href of readers) {
  const html = read(href)
  assert.ok(
    !/data:image\/[a-z]+;base64/.test(html),
    `${href}: the cover lives under /img/, it is not inlined here`,
  )
  const graph = jsonld(html)
  const book = graph.find((n) => n['@type'] === 'Book')
  assert.ok(book, `${href}: a reading page is about a Book`)
  const sameAs = book.sameAs || []
  assert.ok(
    sameAs.some((u) => /^https:\/\/www\.wikidata\.org\/entity\/Q\d+$/.test(u)),
    `${href}: the Book must name its Wikidata item, or it is an unidentified text`,
  )
  for (const u of sameAs) {
    assert.match(u, /^https:\/\/(www\.wikidata\.org|[a-z]{2}\.wikipedia\.org)\//, `${href}: ${u}`)
  }
  if (book.author) {
    // Only the pages that name an author at all; the ones whose attribution is
    // a modern fiction deliberately name none, and this must not push one on them.
    assert.ok(
      (book.author.sameAs || []).some((u) => u.startsWith('https://www.wikidata.org/entity/Q')),
      `${href}: the author is named, so say which person that is`,
    )
  }
  // The cover is a file on disk; the markup must state its real size.
  const image = graph.find((n) => n['@type'] === 'ImageObject')
  assert.ok(image, `${href}: the cover must be described`)
  const file = path.join(PUBLIC, image.contentUrl.replace(ORIGIN, '').replace(/^\//, ''))
  assert.ok(fs.existsSync(file), `${href}: og:image points at ${image.contentUrl}, which is missing`)
  assert.ok(Number.isInteger(image.width) && image.width > 0, `${href}: cover width`)
  assert.ok(Number.isInteger(image.height) && image.height > 0, `${href}: cover height`)
}

// --- the shelf names the same works the readings do --------------------------
{
  const html = read(SHELF)
  const list = jsonld(html).find((n) => n['@type'] === 'ItemList')
  assert.ok(list, 'the shelf publishes its volumes as an ItemList')
  for (const li of list.itemListElement) {
    assert.ok(
      (li.item.sameAs || []).some((u) => u.startsWith('https://www.wikidata.org/entity/Q')),
      `the shelf entry ${li.name} must name the same work its reading page does`,
    )
  }
  assert.equal(list.itemListElement.length, list.numberOfItems, 'the shelf counts what it lists')
}

console.log(
  `✔ classics-seo.test.mjs — ${readers.length} reading(s) across ${families.size} book(s) checked`,
)
