// Self-contained regression test for the archive index's two zones —
// run with `node scripts/lib/docs-tabs.test.mjs` (or via `npm test` from
// frontend/). Like docs-index.test.mjs it is its own harness: it asserts and
// exits non-zero on the first failure.
//
// What it guards (task #377): /docs.html lists two different kinds of thing —
// 文章收藏 (what was written here) and 典籍 (books that were already old when
// this board was built) — in two tabs rather than one interleaved timeline, and
// the classics are shown AS books: a library grid of covers, several volumes to
// a row. The rules that are easy to break later:
//
//   1. membership is DECLARED by manifest id, never inferred from a title, so a
//      new upload can't drift onto the classics shelf;
//   2. the shelf's grid collapses the tracks nobody stands in, which is what
//      keeps two books from being stranded under a shelf board that runs off
//      into empty space;
//   3. deleting the last article blanks the ARTICLES PANEL, not `.wrap` — which
//      would take the tab bar and the shelf with it;
//   4. the owner keeps a delete/privacy control for every shelved page, since
//      a cover is a link and carries no buttons.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const generator = fs.readFileSync(path.join(ROOT, 'scripts', 'build-docs-site.mjs'), 'utf8')

// --- the rules live in the generator ----------------------------------------
{
  // Declared membership: the registry names ids, and nothing keys off a title.
  assert.match(generator, /const CLASSICS_SHELF_ID = '/, 'the shelf page is named by id')
  assert.match(generator, /const CLASSICS = \[/, 'the volumes are declared in a registry')
  assert.match(
    generator,
    /function classicsIds\(\)[\s\S]*?for \(const ed of book\.editions\) ids\.add\(ed\.id\)/,
    'the classics zone is the set of DECLARED ids — never a title match',
  )

  // The shelf is a grid that fits several volumes to a row, and collapses the
  // tracks nobody stands in (auto-fit + a fixed track, not auto-fill + 1fr).
  assert.match(
    generator,
    /grid-template-columns: repeat\(auto-fit, \d+px\);/,
    'the shelf must use auto-fit with a fixed track so empty tracks collapse',
  )
  assert.doesNotMatch(
    generator,
    /\.shelf \{[\s\S]*?repeat\(auto-fill/,
    'auto-fill would leave the shelf board running out past the last book',
  )

  // Deleting the last article must not be able to wipe the tab bar.
  assert.match(
    generator,
    /var wrapEl = document\.getElementById\('panel-articles'\) \|\| document\.querySelector\('\.wrap'\)/,
    'the empty-state wipe is scoped to the articles panel',
  )

  // A tab is linkable, and follows the fragment when it changes under us.
  assert.match(generator, /window\.addEventListener\('hashchange', fromHash\)/)

  // The article count is baked with every card present, but the gate serves an
  // anonymous visitor the page with the private cards stripped out. A baked
  // number would then both overpromise and disclose how many private docs are
  // being withheld, so the label counts the panel it names — on load, and again
  // after a delete takes a card out of it.
  assert.match(
    generator,
    /<span class="n" data-count-of="panel-articles">/,
    'the articles count must be marked as counting a panel, not baked alone',
  )
  assert.match(
    generator,
    /n\.textContent = panel\.querySelectorAll\('\.card-wrap'\)\.length/,
    'the tab label must be recounted from the cards actually in the panel',
  )
  assert.match(
    generator,
    /wrap\.remove\(\);[\s\S]{0,400}?if \(window\.recountTabs\) window\.recountTabs\(\)/,
    'deleting a card must refresh the tab count',
  )
}

// --- and the generated index really carries it --------------------------------
// Guards against a stale build being shipped: docs.html is a tracked artifact.
{
  const indexHtml = path.join(ROOT, 'frontend', 'public', 'docs.html')
  const manifestFile = path.join(ROOT, 'frontend', 'public', 'docs-manifest.json')
  if (fs.existsSync(indexHtml) && fs.existsSync(manifestFile)) {
    const html = fs.readFileSync(indexHtml, 'utf8')
    const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
    const classics = manifest.filter((c) => c.zone === 'classic')

    // A build with no classics is legitimate (the tabs only appear once there is
    // something on the shelf), so only assert the pairing when they're there.
    if (classics.length) {
      assert.ok(html.includes('role="tablist"'), 'two zones means a tablist')
      for (const id of ['tab-articles', 'tab-classics', 'panel-articles', 'panel-classics']) {
        assert.ok(html.includes(`id="${id}"`), `missing ${id}`)
      }

      const [beforeClassics, afterClassics] = html.split('<section id="panel-classics"')
      assert.ok(afterClassics, 'the classics panel must exist')

      // Every classic lives in the classics panel; none is left in the timeline.
      for (const c of classics) {
        assert.ok(
          !beforeClassics.includes(`data-id="${c.id}"`),
          `${c.id} is a classic but still listed among the articles`,
        )
        assert.ok(
          afterClassics.includes(`data-id="${c.id}"`),
          `${c.id} is a classic but has no card in the classics panel`,
        )
      }

      // The shelf shows covers, so the owner's controls ride the folded-away
      // cards instead — one delete and one privacy switch per shelved page.
      const manage = afterClassics.split('<details class="manage">')[1] || ''
      assert.equal(
        (manage.match(/class="doc-priv"/g) || []).length,
        classics.length,
        'every shelved page keeps a privacy switch',
      )

      // At least one volume is actually on the shelf, drawn as a book.
      assert.ok(
        (afterClassics.match(/class="bay"/g) || []).length > 0,
        'the classics panel must shelve at least one volume',
      )
      assert.ok(afterClassics.includes('<ul class="shelf">'), 'the volumes are shelved in a grid')

      // The same volumes also stand on the standalone shelf at /p/dianji.html.
      // Both shelves must open the SAME edition, or which door a reader came in
      // by decides which script they get. This caught a real mismatch: the tab
      // opened 繁體 while the shelf page had been moved to 简体 (task #378).
      const shelfPage = path.join(ROOT, 'frontend', 'public', 'p', 'dianji.html')
      if (fs.existsSync(shelfPage)) {
        const firstEds = (src) => (src.match(/<a class="ed first" href="([^"]+)"/g) || [])
        const onTab = firstEds(afterClassics)
        const onPage = firstEds(fs.readFileSync(shelfPage, 'utf8'))
        if (onTab.length && onPage.length) {
          assert.deepEqual(
            onTab,
            onPage,
            'the tab and /p/dianji.html must open the same edition of each volume',
          )
        }
      }
    }
  }
}

console.log('✔ docs-tabs.test.mjs — all assertions passed')
