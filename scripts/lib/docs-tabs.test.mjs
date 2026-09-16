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
//   4. no shelved page is orphaned — since task #393 the shelf's covers carry
//      one reading each, so the other two are reachable through the corner
//      switch's data and through the volume's own toolbar, and the test holds
//      the two entrances (the 典籍 tab and /p/dianji.html) to the same offer;
//   5. since task #397 the switch also renames the books — every reading a bay
//      offers brings its own title and imprint, so pressing 繁體 can't leave the
//      shelf labelling nine volumes in 简体.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const generator = fs.readFileSync(path.join(ROOT, 'scripts', 'build-docs-site.mjs'), 'utf8')
const CLASSICS_SHELF_HREF = (generator.match(/const CLASSICS_SHELF_HREF = '([^']+)'/) || [])[1]

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

  // One switch for the whole shelf (task #393), not three pills under every
  // book. The readings are declared once, so the switch's buttons and each
  // volume's edition list can't drift apart, and a label that isn't one of them
  // fails the build rather than quietly dropping a volume off the switch.
  assert.match(generator, /const LANGS = \[/, 'the readings are declared in one table')
  assert.match(
    generator,
    /const LANG_KEY_BY_LABEL = new Map\(LANGS\.map/,
    'the switch and the edition lists must speak one vocabulary',
  )
  assert.match(
    generator,
    /if \(!LANG_KEY_BY_LABEL\.has\(ed\.label\)\) \{[\s\S]{0,200}?throw new Error/,
    'an unknown edition label must fail the build, not vanish from the switch',
  )
  assert.match(generator, /function renderLangbar\(\)/, 'the shelf has one language control')
  assert.doesNotMatch(
    generator,
    /class="ed( first)?"/,
    'the per-book edition pills are gone (task #393)',
  )
  // The control re-points the covers; it does not navigate. And the choice is
  // remembered, under a key both entrances to the shelf share.
  assert.match(generator, /class="lang" data-lang="\$\{l\.key\}"/, 'the switch is buttons, not links')
  assert.match(generator, /var KEY = 'classics:lang'/, 'the chosen reading is remembered')
  assert.match(
    generator,
    / data-href-\$\{l\.key\}="/,
    'every bay must carry the editions the switch can point it at',
  )

  // Task #397: a reading names the book. The name lives on the edition, so the
  // registry can't hold a volume that follows the switch but won't be renamed by
  // it, and the build fails rather than shipping a 繁體 shelf written in 简体.
  for (const a of [/ data-title-\$\{l\.key\}="/, / data-imprint-\$\{l\.key\}="/]) {
    assert.match(generator, a, 'every bay must carry each reading\'s own name')
  }
  assert.match(
    generator,
    /if \(!ed\.title \|\| !ed\.imprint[\s\S]{0,200}?throw new Error/,
    'an edition with no name of its own must fail the build',
  )
  assert.match(
    generator,
    /head\.textContent = name/,
    'the switch must rewrite the plate, not just re-point the link',
  )
  assert.match(
    generator,
    /data-label-\$\{l\.key\}="/,
    'the link label is baked per reading — a script in the page can only write Chinese',
  )
  assert.doesNotMatch(
    generator,
    /'打开《' \+ title/,
    'the switch must not compose its labels in one language at runtime (task #397)',
  )
  assert.match(
    generator,
    /h3\.setAttribute\('lang', tags\[use\]\)/,
    'the plate must be tagged with the language it is written in',
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

      // A classic is never a card. Not in the timeline, and since task #382 not
      // in the classics zone either: that zone is a shelf of covers, with no
      // delete button and no privacy switch on it for anyone. The reader still
      // has to be able to get to the page, so the shelf must link it.
      for (const c of classics) {
        assert.ok(
          !beforeClassics.includes(`data-id="${c.id}"`),
          `${c.id} is a classic but still listed among the articles`,
        )
        assert.ok(
          !html.includes(`data-id="${c.id}"`),
          `${c.id} is a classic but still has a management card`,
        )
      }
      assert.ok(
        !afterClassics.includes('class="manage"'),
        'the classics zone carries no owner controls (task #382)',
      )
      assert.equal(
        (afterClassics.match(/class="doc-priv"|class="doc-del"/g) || []).length,
        0,
        'no privacy switch or delete button belongs on the shelf',
      )
      // Dropping the cards must not orphan a page. A cover carries ONE reading
      // now (task #393), so a shelved page counts as reachable if the panel
      // either links it outright or hangs it on a bay for the corner switch —
      // and, for the readings the switch points at, only if the volume's own
      // page links back to it, which is the no-JS way through.
      const reachable = (href) =>
        afterClassics.includes(`href="${href}"`) || afterClassics.includes(`="${href}"`)
      for (const c of classics) {
        assert.ok(reachable(c.href), `${c.id} has no card any more, so the shelf must reach it`)
      }
      for (const c of classics) {
        if (afterClassics.includes(`<a class="vol" href="${c.href}"`)) continue // a cover opens it
        if (c.href === CLASSICS_SHELF_HREF) continue
        const page = path.join(ROOT, 'frontend', 'public', c.href.replace(/^\//, ''))
        if (!fs.existsSync(page)) continue
        const defaults = [...afterClassics.matchAll(/<a class="vol" href="([^"]+)"/g)].map((m) => m[1])
        const ok = defaults.some((d) => {
          const from = path.join(ROOT, 'frontend', 'public', d.replace(/^\//, ''))
          return fs.existsSync(from) && fs.readFileSync(from, 'utf8').includes(`href="${c.href}"`)
        })
        assert.ok(ok, `${c.id} is only reachable through the switch — no shelved page links it`)
      }

      // One control, in the corner, for the whole shelf — and every bay carries
      // the readings it can be pointed at.
      assert.equal(
        (afterClassics.match(/class="langbar"/g) || []).length,
        1,
        'the shelf gets one language switch, not one per book',
      )
      assert.equal(
        (afterClassics.match(/class="ed( first)?"/g) || []).length,
        0,
        'the per-book edition pills are gone (task #393)',
      )
      const bayCount = (afterClassics.match(/class="bay"/g) || []).length
      assert.equal(
        (afterClassics.match(/ data-href-hans="/g) || []).length,
        bayCount,
        'every bay must name the reading the switch defaults to',
      )
      // Task #397: the switch renames the books as well as re-pointing them, so
      // every reading a bay offers has to arrive with its own title and imprint —
      // one bay left out would keep its 简体 name while the rest of the shelf
      // turned 繁體, which is the exact half-translated shelf this guards against.
      for (const [attr, what] of [
        [' data-title-', 'a title'],
        [' data-imprint-', 'an imprint'],
        [' data-label-', 'a link label'],
      ]) {
        for (const lang of ['hans', 'hant', 'en']) {
          const hrefs = (afterClassics.match(new RegExp(` data-href-${lang}="`, 'g')) || []).length
          const names = (afterClassics.match(new RegExp(`${attr}${lang}="`, 'g')) || []).length
          assert.ok(
            names >= hrefs,
            `every ${lang} reading on the shelf must bring ${what} of its own`,
          )
        }
      }
      // A label is there even for a reading a volume hasn't got, so the switch
      // never leaves a cover unexplained.
      for (const lang of ['hans', 'hant', 'en']) {
        assert.equal(
          (afterClassics.match(new RegExp(` data-label-${lang}="`, 'g')) || []).length,
          bayCount,
          `every bay must say what pressing ${lang} does to it`,
        )
      }
      // The names really are different from one reading to the next — a shelf
      // that "switches" to 繁體 and shows the same 简体 titles would pass every
      // structural check above.
      const titlesFor = (lang) =>
        [...afterClassics.matchAll(new RegExp(` data-title-${lang}="([^"]+)"`, 'g'))].map((m) => m[1])
      assert.notDeepEqual(
        titlesFor('hans'),
        titlesFor('en'),
        'the English reading must carry English titles',
      )
      assert.ok(
        titlesFor('hans').some((t, i) => t !== titlesFor('hant')[i]),
        'the 繁體 reading must carry 繁體 titles',
      )

      // At least one volume is actually on the shelf, drawn as a book.
      assert.ok(
        (afterClassics.match(/class="bay"/g) || []).length > 0,
        'the classics panel must shelve at least one volume',
      )
      assert.ok(afterClassics.includes('<ul class="shelf">'), 'the volumes are shelved in a grid')

      // The same volumes also stand on the standalone shelf at /p/dianji.html.
      // The two entrances must make the SAME offer, or which door a reader came
      // in by decides which script they get. This caught a real mismatch: the
      // tab opened 繁體 while the shelf page had been moved to 简体 (task #378).
      // Since task #393 that means the whole bay — the default AND each reading
      // the corner switch can point it at — not just the inked pill.
      const shelfPage = path.join(ROOT, 'frontend', 'public', 'p', 'dianji.html')
      if (fs.existsSync(shelfPage)) {
        const dianji = fs.readFileSync(shelfPage, 'utf8')
        const bays = (src) => {
          const out = new Map()
          for (const m of src.matchAll(/<li class="bay"([^>]*)>/g)) {
            const at = Object.fromEntries(
              [
                ...m[1].matchAll(
                  /data-(href[\w-]*|title[\w-]*|imprint[\w-]*|label[\w-]*|note[\w-]*)="([^"]+)"/g,
                ),
              ].map((a) => [a[1], a[2]]),
            )
            if (at.title) out.set(at.title, at)
          }
          return out
        }
        const onTab = bays(afterClassics)
        const onPage = bays(dianji)
        if (onTab.size && onPage.size) {
          assert.deepEqual(
            [...onTab.keys()].sort(),
            [...onPage.keys()].sort(),
            'the tab and /p/dianji.html must shelve the same volumes',
          )
          for (const [title, tab] of onTab) {
            assert.deepEqual(
              tab,
              onPage.get(title),
              `《${title}》 is offered differently on the tab and on /p/dianji.html`,
            )
          }
        }
        // And the standalone shelf carries the same single control, remembering
        // the choice under the same key, so picking 繁體 on one holds on both.
        assert.equal(
          (dianji.match(/class="langbar"/g) || []).length,
          1,
          '/p/dianji.html gets one language switch, not one per book',
        )
        assert.equal(
          (dianji.match(/class="ed( first)?"/g) || []).length,
          0,
          'the per-book edition pills are gone from /p/dianji.html too (task #393)',
        )
        assert.ok(
          dianji.includes("var KEY = 'classics:lang'"),
          'both entrances must remember the reading under the same key',
        )
        // Every reading is LINKED, on both entrances (task #419). The corner
        // switch moves an href with a script; a search engine follows the href
        // that is written down. Before the editions row under each plate, only
        // the eleven 简体 pages were ever written down — the 繁體, English and
        // 日本語 readings, thirty-three pages, had no internal link pointing at
        // them from either door into the shelf. A reading that is on the shelf
        // but reachable only by pressing a button is a reading Google will not
        // find here, so assert the anchors, not the data attributes.
        for (const [src, where] of [
          [afterClassics, 'the 典籍 tab'],
          [dianji, '/p/dianji.html'],
        ]) {
          const shelved = new Set()
          for (const m of src.matchAll(/<li class="bay"([^>]*)>/g)) {
            for (const h of m[1].matchAll(/data-href-[\w-]+="([^"]+)"/g)) shelved.add(h[1])
          }
          assert.ok(shelved.size > 0, `${where} shelves nothing at all`)
          for (const href of shelved) {
            assert.ok(
              src.includes(`<a href="${href}"`),
              `${where} offers ${href} but never links it — only the switch can reach it`,
            )
          }
        }

        // And both actually rewrite the plate (task #397) — the offer is the
        // same only if pressing 繁體 renames the books on either entrance.
        for (const [src, where] of [
          [afterClassics, 'the 典籍 tab'],
          [dianji, '/p/dianji.html'],
        ]) {
          assert.ok(
            src.includes('head.textContent = name'),
            `${where} must rename the books when the reading changes`,
          )
          assert.ok(
            /data-bcp47="zh-Hant"/.test(src),
            `${where} must tag the plate with the language it is written in`,
          )
        }
      }
    }
  }
}

console.log('✔ docs-tabs.test.mjs — all assertions passed')
