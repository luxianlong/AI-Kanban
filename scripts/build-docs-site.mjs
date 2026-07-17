#!/usr/bin/env node
// Turn every Markdown document in docs/ into a browsable webpage.
//
//   docs/<name>.md          ->  frontend/public/docs/<name>.html
//   (index of all of them)  ->  frontend/public/docs.html
//
// Rationale (task #255 "Result format"): when a task produces a complicated
// result, we'd rather hand the user a real web page than a wall of Markdown.
// Anything committed as Markdown under docs/ should therefore be reachable by
// the end web user. Vite serves frontend/public/ at the web root, so a doc
// lands at /docs/<name>.html and the index at /docs.html (linked from the
// board header). This runs as a frontend `prebuild` step, so the pages can
// never drift from the Markdown — every deploy regenerates them.
//
// The flagship Singapore guide keeps its own bespoke page
// (scripts/build-singapore-page.mjs, served at /singapore.html); the index
// links to that richer page instead of generating a plainer duplicate.
//
// Dependency-free: shares the Markdown->HTML converter in ./lib/md-to-html.mjs.

import fs from 'node:fs'
import path from 'node:path'
import { renderMarkdown, esc, slugify } from './lib/md-to-html.mjs'

const ROOT = path.resolve(import.meta.dirname, '..')
const DOCS_DIR = path.join(ROOT, 'docs')
const RESEARCH_DIR = path.join(ROOT, 'research')
const OUT_DIR = path.join(ROOT, 'frontend', 'public', 'docs')
const INDEX_OUT = path.join(ROOT, 'frontend', 'public', 'docs.html')
// A flat manifest of every doc card, read by the board's frontend so it can show
// a "new docs" badge on the Docs link (a doc the user hasn't opened yet).
const MANIFEST_OUT = path.join(ROOT, 'frontend', 'public', 'docs-manifest.json')

// Docs that already have a hand-tuned bespoke page — link to that instead of
// generating a generic one. Keyed by markdown filename -> the served URL.
const BESPOKE = {
  'singapore-travel-guide.md': '/singapore.html',
}

// Multi-part research reports: a story's deliverable often lands as several
// numbered Markdown files in research/<dir>/ (one per step) rather than a single
// docs/*.md. Each listed here is stitched into ONE browsable page at
// /docs/<dir>.html — its parts concatenated in filename order, every heading
// demoted a level so each part title sits as an H2 section under the report
// title — and gets a card in the docs index. This is how e.g. the six-part
// 美伊战争 analysis becomes reachable from the board (task #267).
const REPORTS = [
  {
    dir: 'us-iran-war',
    title: '美伊战争 · 分析总览 (2026)',
    summary:
      '六部分深度研究：背景与结构性根源、当前局势快照、军力对比与升级情景、地缘影响、市场与能源冲击、情景推演与前景研判。',
    featured: true,
  },
]

// Demote every ATX heading by one level (# -> ##, ## -> ### …, capped at ######)
// so a report part's H1 becomes an H2 section under the page's report title (and
// so lands in the h2/h3 TOC). Safe here because these report files contain no
// fenced code blocks whose "#" lines could be mistaken for headings.
function demoteHeadings(md) {
  return md.replace(/^(#{1,5})(\s+)/gm, '#$1$2')
}

// Turn a filename like "ai-supply-chain" into "Ai supply chain" for a fallback
// title when a doc has no H1.
function humanize(slug) {
  return slug.replace(/[-_]+/g, ' ').replace(/^\w/, (c) => c.toUpperCase())
}

// A short plain-text summary for the index cards: the first real paragraph of
// the doc (skipping headings, blockquotes, lists, tables, rules and blanks).
function excerpt(md) {
  const lines = md.replace(/\r\n/g, '\n').split('\n')
  const buf = []
  for (const raw of lines) {
    const l = raw.trim()
    if (!l) {
      if (buf.length) break
      continue
    }
    if (/^(#|>|\||-|\*|\+|\d+[.)]|=)/.test(l) || /^-{3,}$/.test(l)) {
      if (buf.length) break
      continue
    }
    buf.push(l)
  }
  let text = buf.join(' ')
  // strip inline markdown so the card reads cleanly
  text = text
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
  if (text.length > 180) text = text.slice(0, 177).replace(/\s+\S*$/, '') + '…'
  return text
}

// --- shared page shell -------------------------------------------------------
const THEME = `
:root {
  --bg: #f6f7f9;
  --card: #ffffff;
  --ink: #1f2430;
  --muted: #5b6472;
  --line: #e5e8ee;
  --accent: #4f46e5;
  --accent-soft: #eef0fe;
  --link: #1f6feb;
  --source-bg: #f0f7f4;
  --source-line: #3f9e78;
  --maxw: 1120px;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: "Inter", system-ui, -apple-system, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 16px;
  line-height: 1.75;
}
a { color: var(--link); text-decoration: none; }
a:hover { text-decoration: underline; }
.hero {
  background: linear-gradient(135deg, #4f46e5 0%, #6366f1 55%, #7c9bf5 100%);
  color: #fff;
  padding: 48px 24px 40px;
  text-align: center;
}
.hero .eyebrow {
  text-transform: uppercase;
  letter-spacing: 2px;
  font-size: 12px;
  opacity: 0.9;
  margin: 0 0 8px;
}
.hero h1 {
  font-family: "Space Grotesk", "Inter", "Noto Sans SC", sans-serif;
  font-size: clamp(26px, 4.5vw, 40px);
  margin: 0 0 10px;
  letter-spacing: 0.3px;
}
.hero p.sub { margin: 0 auto; max-width: 680px; opacity: 0.95; font-size: 15px; }
.hero .back { display: inline-block; margin-top: 16px; color: #fff; font-size: 14px; opacity: 0.9; }
.hero .back:hover { opacity: 1; }
`

const CONTENT_CSS = `
.layout {
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 32px 24px 80px;
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 36px;
  align-items: start;
}
.toc {
  position: sticky;
  top: 20px;
  max-height: calc(100vh - 40px);
  overflow-y: auto;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 16px;
  font-size: 14px;
}
.toc-title {
  font-weight: 700; font-size: 13px; letter-spacing: 1px;
  text-transform: uppercase; color: var(--muted); margin-bottom: 10px;
}
.toc ul { list-style: none; margin: 0; padding: 0; }
.toc li { margin: 1px 0; }
.toc li a {
  display: block; padding: 4px 8px; border-radius: 7px; color: var(--ink);
}
.toc li a:hover { background: var(--accent-soft); text-decoration: none; }
.toc li.lv2 a { font-weight: 600; margin-top: 6px; }
.toc li.lv3 a { padding-left: 20px; color: var(--muted); font-size: 13px; }
.content {
  background: var(--card); border: 1px solid var(--line);
  border-radius: 14px; padding: 8px 40px 40px; min-width: 0;
}
.content h1 { font-family: "Space Grotesk","Inter",sans-serif; font-size: 30px; margin: 24px 0 12px; }
.content h2, .content h3, .content h4, .content h5, .content h6 {
  font-family: "Space Grotesk", "Inter", "Noto Sans SC", sans-serif;
  line-height: 1.35; scroll-margin-top: 16px; position: relative;
}
.content h2 { font-size: 25px; margin: 38px 0 14px; padding-bottom: 8px; border-bottom: 2px solid var(--accent-soft); }
.content h3 { font-size: 20px; margin: 28px 0 10px; }
.content h4 { font-size: 17px; margin: 22px 0 8px; color: var(--accent); }
.content p { margin: 12px 0; }
.content ul, .content ol { margin: 12px 0; padding-left: 24px; }
.content li { margin: 5px 0; }
.content li > ul, .content li > ol { margin: 5px 0; }
.anchor { opacity: 0; margin-left: 8px; color: var(--accent); font-weight: 400; text-decoration: none; }
h2:hover .anchor, h3:hover .anchor, h4:hover .anchor { opacity: 0.55; }
code { background: #eef0f4; border-radius: 5px; padding: 1px 6px; font-family: ui-monospace,"SFMono-Regular",Menlo,Consolas,monospace; font-size: 0.9em; }
blockquote.quote { margin: 16px 0; padding: 12px 18px; border-left: 4px solid var(--line); background: #f8f9fb; border-radius: 0 10px 10px 0; color: var(--muted); }
blockquote.source { background: var(--source-bg); border-left-color: var(--source-line); }
blockquote.source::before { content: "🔎 来源与时效"; display: block; font-weight: 700; font-size: 13px; color: var(--source-line); margin-bottom: 4px; }
blockquote p { margin: 4px 0; font-size: 14px; }
.figure { margin: 22px 0; text-align: center; }
.figure img { max-width: 100%; height: auto; border-radius: 12px; border: 1px solid var(--line); box-shadow: 0 6px 20px rgba(31,36,48,0.10); }
.figure figcaption { margin-top: 8px; font-size: 13px; color: var(--muted); }
.content p img { max-width: 100%; height: auto; border-radius: 10px; }
.table-wrap { overflow-x: auto; margin: 16px 0; }
table { border-collapse: collapse; width: 100%; font-size: 14.5px; min-width: 420px; }
th, td { border: 1px solid var(--line); padding: 8px 12px; text-align: left; vertical-align: top; }
thead th { background: var(--accent-soft); color: var(--ink); font-weight: 700; }
tbody tr:nth-child(even) { background: #fafbfc; }
hr { border: none; border-top: 1px solid var(--line); margin: 28px 0; }
pre.code { background: #1f2430; color: #e6e8ef; border-radius: 10px; padding: 14px 16px; overflow-x: auto; font-size: 13.5px; line-height: 1.55; }
pre.code code { background: none; padding: 0; color: inherit; font-size: inherit; }
pre.mermaid { background: var(--card); border: 1px solid var(--line); border-radius: 10px; padding: 16px; text-align: center; overflow-x: auto; }
.backtop {
  position: fixed; right: 22px; bottom: 22px; width: 46px; height: 46px;
  border-radius: 50%; background: var(--accent); color: #fff; border: none;
  font-size: 20px; cursor: pointer; box-shadow: 0 6px 18px rgba(79,70,229,0.4);
  opacity: 0; pointer-events: none; transition: opacity 0.2s;
}
.backtop.show { opacity: 1; pointer-events: auto; }
.mobile-toc-toggle { display: none; }
@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; gap: 16px; }
  .toc { position: static; max-height: none; }
  .mobile-toc-toggle {
    display: block; width: 100%; background: var(--card); border: 1px solid var(--line);
    border-radius: 10px; padding: 12px 16px; font: inherit; font-weight: 600; text-align: left; cursor: pointer;
  }
  .toc.collapsed { display: none; }
  .content { padding: 8px 20px 28px; }
}
`

const FONTS = `<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Noto+Sans+SC:wght@400;500;700&family=Space+Grotesk:wght@500;600;700&display=swap" rel="stylesheet" />`

function renderDocPage({ title, body, toc, hasMermaid }) {
  const sidebar = toc.length
    ? `<nav class="toc" aria-label="On this page">
<div class="toc-title">On this page</div>
<ul>
${toc.map((h) => `<li class="lv${h.level}"><a href="#${h.id}">${esc(h.text)}</a></li>`).join('\n')}
</ul>
</nav>`
    : '<div></div>'
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${esc(title)} · Kanban docs</title>
${FONTS}
<style>${THEME}${CONTENT_CSS}</style>
</head>
<body>
<header class="hero">
<p class="eyebrow">Kanban · Result</p>
<h1>${esc(title)}</h1>
<a class="back" href="/docs.html">← All documents</a>
</header>
<div class="layout">
<button class="mobile-toc-toggle" onclick="document.querySelector('.toc').classList.toggle('collapsed')">☰ On this page</button>
${sidebar}
<main class="content">
${body}
</main>
</div>
<button class="backtop" aria-label="Back to top" onclick="window.scrollTo({top:0,behavior:'smooth'})">↑</button>
<script>
  var bt = document.querySelector('.backtop');
  window.addEventListener('scroll', function () {
    if (window.scrollY > 400) bt.classList.add('show'); else bt.classList.remove('show');
  });
  if (window.matchMedia('(max-width: 900px)').matches) {
    var toc = document.querySelector('.toc');
    if (toc) toc.classList.add('collapsed');
  }
</script>
${hasMermaid ? `<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';
  mermaid.initialize({ startOnLoad: true, theme: 'neutral', securityLevel: 'strict' });
</script>` : ''}
</body>
</html>
`
}

const INDEX_CSS = `
.wrap { max-width: 960px; margin: 0 auto; padding: 40px 24px 80px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 20px; }
.doc-card {
  display: block; background: var(--card); border: 1px solid var(--line);
  border-radius: 14px; padding: 20px 22px; color: var(--ink);
  transition: transform 0.12s ease, box-shadow 0.12s ease, border-color 0.12s ease;
}
.doc-card:hover { text-decoration: none; transform: translateY(-2px); box-shadow: 0 10px 26px rgba(31,36,48,0.08); border-color: #c9cff5; }
.doc-card.featured { border-color: #c9cff5; background: linear-gradient(180deg, #fbfbff 0%, #ffffff 100%); }
.doc-card h2 { font-family: "Space Grotesk","Inter",sans-serif; font-size: 19px; margin: 0 0 8px; }
.doc-card p { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.6; }
.doc-card .meta { margin-top: 12px; font-size: 12px; color: var(--accent); font-weight: 600; letter-spacing: 0.3px; }
.doc-card .badge { display: inline-block; margin-left: 8px; padding: 1px 8px; border-radius: 999px; background: var(--accent-soft); color: var(--accent); font-size: 11px; vertical-align: middle; }
.empty { color: var(--muted); text-align: center; padding: 40px; }
.card-wrap { position: relative; display: flex; }
.card-wrap .doc-card { flex: 1; }
.doc-del {
  position: absolute; top: 10px; right: 10px; width: 28px; height: 28px;
  border-radius: 8px; border: 1px solid var(--line); background: var(--card);
  color: var(--muted); font-size: 15px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease;
}
.card-wrap:hover .doc-del { opacity: 1; }
.doc-del:hover { background: #fdecec; color: #d63939; border-color: #f3c0c0; }
.doc-del:disabled { cursor: default; opacity: 0.5; }
@media (hover: none) { .doc-del { opacity: 1; } }
`

function renderIndex(cards) {
  const cardHtml = cards
    .map(
      (c) => `<div class="card-wrap" data-id="${esc(c.id)}">
  <a class="doc-card${c.featured ? ' featured' : ''}" href="${c.href}">
  <h2>${esc(c.title)}${c.featured ? '<span class="badge">Featured</span>' : ''}</h2>
  <p>${esc(c.excerpt || 'Open to read the full document.')}</p>
  <div class="meta">Read →</div>
</a>${
        c.deletable
          ? `\n  <button class="doc-del" type="button" title="Delete this document" aria-label="Delete ${esc(c.title)}" data-id="${esc(c.id)}" data-title="${esc(c.title)}">✕</button>`
          : ''
      }
</div>`
    )
    .join('\n')
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Documents & Results · Kanban</title>
<meta name="description" content="Reports and documents produced by the Kanban board's agent, rendered as web pages." />
${FONTS}
<style>${THEME}
.hero p.sub { margin-top: 4px; }
${INDEX_CSS}</style>
</head>
<body>
<header class="hero">
<p class="eyebrow">Kanban</p>
<h1>Documents &amp; Results</h1>
<p class="sub">Reports and guides the board's agent produced, rendered from the repo's Markdown as browsable web pages.</p>
<a class="back" href="/">← Back to the board</a>
</header>
<div class="wrap">
${cards.length ? `<div class="grid">\n${cardHtml}\n</div>` : '<p class="empty">No documents yet.</p>'}
</div>
<script>
  // Delete a document: ask the backend to remove its source Markdown + generated
  // page, then drop the card. The board proxies /api to the Spring backend, so a
  // same-origin fetch works whether we're on :80 (dev/tunnel) or a build preview.
  var grid = document.querySelector('.grid');
  document.addEventListener('click', function (e) {
    var btn = e.target.closest && e.target.closest('.doc-del');
    if (!btn) return;
    e.preventDefault();
    var id = btn.getAttribute('data-id');
    var title = btn.getAttribute('data-title') || 'this document';
    if (!window.confirm('Delete "' + title + '"? This removes the document for good.')) return;
    btn.disabled = true;
    fetch('/api/docs/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function (res) {
        if (!res.ok && res.status !== 404) throw new Error('HTTP ' + res.status);
        var wrap = btn.closest('.card-wrap');
        if (wrap) wrap.remove();
        if (grid && !grid.querySelector('.card-wrap')) {
          var wrapEl = document.querySelector('.wrap');
          if (wrapEl) wrapEl.innerHTML = '<p class="empty">No documents yet.</p>';
        }
      })
      .catch(function (err) {
        btn.disabled = false;
        window.alert('Could not delete "' + title + '": ' + err.message);
      });
  });
</script>
</body>
</html>
`
}

// --- build -------------------------------------------------------------------
function build() {
  const files = fs
    .readdirSync(DOCS_DIR)
    .filter((f) => f.endsWith('.md'))
    .sort()

  fs.mkdirSync(OUT_DIR, { recursive: true })

  // Map every publishable doc to its served URL so cross-document links written
  // as sibling ".md" paths (e.g. [x](./architecture.md)) get repointed at the
  // real page (/docs/architecture.html) instead of dead-ending on markdown the
  // web user can't open — the exact failure this docs site exists to prevent.
  const docUrlByFile = {}
  for (const f of files) {
    docUrlByFile[f] = BESPOKE[f] || `/docs/${slugify(f.replace(/\.md$/, ''))}.html`
  }
  const resolveLink = (url) => {
    const m = /^(?:\.\/)?([^/#?]+\.md)(#.*)?$/.exec(url)
    if (!m) return url
    const target = docUrlByFile[m[1]]
    return target ? target + (m[2] || '') : url
  }

  const cards = []
  let generated = 0

  for (const file of files) {
    const md = fs.readFileSync(path.join(DOCS_DIR, file), 'utf8')
    const slug = slugify(file.replace(/\.md$/, ''))
    const { title: parsed, body, toc, hasMermaid } = renderMarkdown(md, { resolveLink })
    const title = parsed || humanize(file.replace(/\.md$/, ''))
    const ex = excerpt(md)

    if (BESPOKE[file]) {
      // Don't regenerate — link the index to the hand-tuned page. Not deletable
      // from the web UI: it owns a bespoke page that this generator doesn't
      // manage, so removing it cleanly isn't a simple file delete.
      cards.push({
        id: slug, title, href: BESPOKE[file], excerpt: ex, featured: true,
        file: `docs/${file}`, deletable: false,
      })
      continue
    }

    const outPath = path.join(OUT_DIR, `${slug}.html`)
    fs.writeFileSync(outPath, renderDocPage({ title, body, toc, hasMermaid }), 'utf8')
    // Plain single-file docs are the ones the web user may delete: removing
    // docs/<file> and the generated page is a clean, reversible-by-git operation.
    cards.push({
      id: slug, title, href: `/docs/${slug}.html`, excerpt: ex, featured: false,
      file: `docs/${file}`, deletable: true,
    })
    generated++
  }

  // Multi-part research reports -> one stitched page + card each.
  for (const report of REPORTS) {
    const dir = path.join(RESEARCH_DIR, report.dir)
    if (!fs.existsSync(dir)) continue
    const parts = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith('.md'))
      .sort()
    if (!parts.length) continue

    const md = parts
      .map((p) => demoteHeadings(fs.readFileSync(path.join(dir, p), 'utf8')))
      .join('\n\n---\n\n')
    // stripH1: false — after demotion there is no H1 anyway; the page title comes
    // from the report config, not from the Markdown.
    const { body, toc, hasMermaid } = renderMarkdown(md, { stripH1: false, resolveLink })
    const slug = slugify(report.dir)

    const outPath = path.join(OUT_DIR, `${slug}.html`)
    fs.writeFileSync(
      outPath,
      renderDocPage({ title: report.title, body, toc, hasMermaid }),
      'utf8'
    )
    cards.push({
      id: slug,
      title: report.title,
      href: `/docs/${slug}.html`,
      excerpt: report.summary || excerpt(md),
      featured: !!report.featured,
      // Stitched from several files under research/<dir>/ — not a single-file
      // delete, so keep it off the web-deletable path.
      file: null,
      deletable: false,
    })
    generated++
  }

  // Featured (bespoke) cards first, then the rest alphabetically by title.
  cards.sort((a, b) => (b.featured - a.featured) || a.title.localeCompare(b.title))

  fs.writeFileSync(INDEX_OUT, renderIndex(cards), 'utf8')
  // The manifest mirrors the index (same order). The board reads it to flag
  // unseen docs (id/title/href); the backend's DELETE /api/docs/{id} reads the
  // extra `deletable`/`file` fields to know which docs the web user may remove
  // and which source Markdown to delete.
  const manifest = cards.map((c) => ({
    id: c.id,
    title: c.title,
    href: c.href,
    deletable: !!c.deletable,
    file: c.file || null,
  }))
  fs.writeFileSync(MANIFEST_OUT, JSON.stringify(manifest, null, 2) + '\n', 'utf8')
  console.log(
    `✔ docs site generated: ${generated} page(s) -> ${path.relative(ROOT, OUT_DIR)}/, index (${cards.length} card(s)) -> ${path.relative(ROOT, INDEX_OUT)}, manifest -> ${path.relative(ROOT, MANIFEST_OUT)}`
  )
}

build()
