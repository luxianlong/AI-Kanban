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
// Per-doc visibility (task #268): documents are PUBLIC by default, but the owner
// can mark one PRIVATE so only they can open it. The set of private doc ids lives
// in this gitignored sidecar — written by the backend's visibility endpoint, read
// here (to stamp the manifest/index) and by the Vite auth gate (to enforce it).
// Absent/unreadable => nothing is private.
const VISIBILITY_FILE = path.join(ROOT, 'frontend', '.doc-visibility.json')
// Static pages published through the unauthenticated upload API (task #470):
// POST /api/pages writes frontend/public/p/<slug>.html plus a <slug>.meta.json
// sidecar (title/summary/createdAt). Vite serves them straight from the web root
// at /p/<slug>.html — public, no password (see docsGate.mjs) — and they get a
// card here so an uploaded page is listed on /docs.html like any other document.
const UPLOADS_DIR = path.join(ROOT, 'frontend', 'public', 'p')
// Manifest ids for uploaded pages are namespaced so they can never collide with
// a docs/*.md slug. The backend derives the same id (PageUploadService).
const UPLOAD_ID_PREFIX = 'upload-'

function readPrivateIds() {
  try {
    const raw = JSON.parse(fs.readFileSync(VISIBILITY_FILE, 'utf8'))
    const ids = Array.isArray(raw) ? raw : raw && raw.private
    return new Set(Array.isArray(ids) ? ids.filter((x) => typeof x === 'string') : [])
  } catch {
    return new Set()
  }
}

// A doc counts as "recently created/updated" if its source Markdown was last
// modified within this many days of the build. Such docs get a red "NEW" badge
// on the top-right of their card and sort to the front of the index (task #319).
// We key off the SOURCE .md mtime, never the generated HTML — the HTML is
// regenerated on every deploy, so its mtime would flag everything as new.
const NEW_WINDOW_DAYS = 7
const NEW_CUTOFF = Date.now() - NEW_WINDOW_DAYS * 24 * 60 * 60 * 1000

// Most-recent modification time (ms) across the given source files, ignoring any
// that can't be stat'd. Returns 0 when none resolve.
function newestMtime(absPaths) {
  let newest = 0
  for (const p of absPaths) {
    try {
      const m = fs.statSync(p).mtimeMs
      if (m > newest) newest = m
    } catch {
      /* missing/unreadable — skip */
    }
  }
  return newest
}

// Docs that already have a hand-tuned bespoke page — link to that instead of
// generating a generic one. Keyed by markdown filename ->
//   href:       the served URL (index links here instead of a generic page)
//   extraPaths: artifacts beyond the source .md that back this page and must be
//               removed when the doc is deleted from the web UI (the generated
//               HTML lives at the web root, plus any asset dir it owns).
const BESPOKE = {
  'singapore-travel-guide.md': {
    href: '/singapore.html',
    extraPaths: ['frontend/public/singapore.html', 'frontend/public/img/singapore'],
  },
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

// The date an entry wears in the archive list: its source mtime as YYYY.MM.DD,
// stable regardless of the reader's locale (the list is set in mono type).
function formatDate(iso) {
  return String(iso).slice(0, 10).replace(/-/g, '.')
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
// Visual language: a personal blog (task #519), replacing the Xiaohongshu / RED
// note look that came before it (task #462). The reference is someone's own
// writing site — a masthead with a voice, an archive of dated entries in one
// column, and an article page set for long reading. Warm paper, ink-blue links,
// Fraunces for display, Newsreader + Noto Serif SC for prose, IBM Plex Mono for
// the small utility type (dates, labels, tags). Nothing is a "card" any more,
// and the one loud element is the red rubber-stamp NEW marker on a fresh entry.
// Only CSS, copy and the new entry-date line change — the generated DOM
// (classes, data attributes, TOC, delete/privacy controls, PRIVATE-CARD
// markers) is untouched so every existing behaviour still works.
const THEME = `
:root {
  --bg: #f7f5f0;             /* warm laid paper */
  --sheet: #fffdf9;
  --card: #fffdf9;           /* legacy alias — panels that still need a surface */
  --ink: #211d1a;
  --muted: #6f6860;
  --line: #e4ddd0;
  --accent: #2c4a7c;         /* fountain-pen ink blue: links and marks */
  --accent-soft: #e8eaf1;
  --stamp: #b3402f;          /* rubber-stamp red: NEW, destructive actions */
  --link: #2c4a7c;
  --source-bg: #f1eee6;
  --source-line: #c2b193;
  --shadow: 0 2px 10px rgba(33,29,26,0.06);
  --display: "Fraunces", "Noto Serif SC", Georgia, serif;
  --body: "Newsreader", "Noto Serif SC", Georgia, "Songti SC", serif;
  --mono: "IBM Plex Mono", ui-monospace, "SFMono-Regular", Menlo, monospace;
  --maxw: 940px;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--body);
  font-size: 17px;
  line-height: 1.8;
  -webkit-font-smoothing: antialiased;
}
a { color: var(--link); text-decoration: none; }
a:hover { text-decoration: underline; text-underline-offset: 3px; }
::selection { background: #dde3ee; }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
/* Masthead — the quiet top of a personal site: a label, the name, one line of
   voice, a way back. No banner, no gradient; a single hairline closes it. */
.hero {
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 56px 24px 24px;
  border-bottom: 1px solid var(--line);
}
.hero .eyebrow {
  font-family: var(--mono);
  font-size: 11px; font-weight: 500; letter-spacing: 0.18em;
  text-transform: uppercase; color: var(--muted);
  margin: 0 0 14px;
}
.hero h1 {
  font-family: var(--display);
  font-weight: 700;
  font-size: clamp(30px, 5vw, 46px);
  line-height: 1.16;
  letter-spacing: -0.01em;
  margin: 0 0 12px;
}
.hero p.sub { margin: 0; max-width: 62ch; color: var(--muted); font-size: 16px; text-wrap: pretty; }
.hero .back {
  display: inline-block; margin-top: 20px;
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.05em;
  color: var(--muted);
}
.hero .back:hover { color: var(--accent); }
/* Visit tally (task #523). Filled in once /api/docs/views answers, and left
   hidden until then — an article nobody has opened yet says nothing rather than
   announcing a zero. Quiet mono figures, the same register as the dates. */
.views {
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.06em; color: var(--muted);
}
.hero .views { display: inline-block; margin-top: 20px; margin-left: 14px; }
@media (max-width: 700px) {
  .hero { padding: 34px 20px 20px; }
}
`

const CONTENT_CSS = `
.layout {
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 34px 24px 96px;
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 46px;
  align-items: start;
}
/* Marginalia, not a widget: the contents list sits unboxed in the left margin,
   the way a printed book sets its running heads. */
.toc {
  position: sticky;
  top: 28px;
  max-height: calc(100vh - 56px);
  overflow-y: auto;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.55;
  padding-right: 6px;
}
.toc-title {
  font-size: 11px; letter-spacing: 0.18em; text-transform: uppercase;
  color: var(--muted); margin-bottom: 12px;
  padding-bottom: 9px; border-bottom: 1px solid var(--line);
}
.toc ul { list-style: none; margin: 0; padding: 0; }
.toc li { margin: 0; }
.toc li a { display: block; padding: 4px 0; color: var(--muted); overflow-wrap: anywhere; }
.toc li a:hover { color: var(--accent); text-decoration: none; }
.toc li.lv2 a { color: var(--ink); margin-top: 9px; }
.toc li.lv3 a { padding-left: 14px; font-size: 12px; }
.content { min-width: 0; max-width: 70ch; padding: 0; }
.content h1 { font-family: var(--display); font-weight: 700; font-size: 32px; margin: 6px 0 14px; }
.content h2, .content h3, .content h4, .content h5, .content h6 {
  font-family: var(--display); font-weight: 600;
  line-height: 1.3; scroll-margin-top: 20px; position: relative;
}
.content h2 { font-size: 27px; margin: 54px 0 14px; padding-top: 22px; border-top: 1px solid var(--line); }
/* A section rule the author wrote (---) already separates the sections; don't
   double it with the heading's own rule. */
.content hr + h2 { border-top: none; padding-top: 0; margin-top: 18px; }
.content h3 { font-size: 21px; margin: 34px 0 10px; }
.content h4 {
  font-family: var(--mono); font-weight: 500; font-size: 13px; letter-spacing: 0.12em;
  text-transform: uppercase; color: var(--muted); margin: 26px 0 8px;
}
.content p { margin: 16px 0; }
.content ul, .content ol { margin: 16px 0; padding-left: 22px; }
.content li { margin: 7px 0; }
.content ul li::marker { color: var(--source-line); }
.content li > ul, .content li > ol { margin: 6px 0; }
.content strong { font-weight: 600; color: var(--ink); }
.content a { text-decoration: underline; text-decoration-color: #b7c2d6; text-underline-offset: 3px; }
.content a:hover { text-decoration-color: var(--accent); }
.anchor { opacity: 0; margin-left: 8px; color: var(--muted); font-weight: 400; text-decoration: none; }
h2:hover .anchor, h3:hover .anchor, h4:hover .anchor { opacity: 0.5; }
code { background: #efece4; color: #4a423a; border-radius: 3px; padding: 1px 5px; font-family: var(--mono); font-size: 0.85em; }
blockquote.quote { margin: 24px 0; padding: 2px 0 2px 22px; border-left: 2px solid var(--source-line); color: var(--muted); font-style: italic; }
blockquote.source { font-style: normal; border-left-color: var(--accent); }
blockquote.source::before {
  content: "来源与时效"; display: block; font-family: var(--mono); font-weight: 500;
  font-size: 11px; letter-spacing: 0.14em; color: var(--accent); margin-bottom: 4px;
}
blockquote p { margin: 4px 0; font-size: 15.5px; }
.figure { margin: 30px 0; }
.figure img { max-width: 100%; height: auto; border: 1px solid var(--line); }
.figure figcaption { margin-top: 9px; font-family: var(--mono); font-size: 12px; color: var(--muted); }
.content p img { max-width: 100%; height: auto; }
.table-wrap { overflow-x: auto; margin: 26px 0; }
table { border-collapse: collapse; width: 100%; font-size: 15px; min-width: 420px; }
th, td { border-bottom: 1px solid var(--line); padding: 9px 16px 9px 0; text-align: left; vertical-align: top; }
thead th {
  font-family: var(--mono); font-size: 11px; font-weight: 500; letter-spacing: 0.12em;
  text-transform: uppercase; color: var(--muted); border-bottom: 1px solid var(--ink);
}
tbody tr:last-child td { border-bottom: none; }
hr { border: none; border-top: 1px solid var(--line); margin: 40px 0; }
pre.code { background: #241f1c; color: #ede5d7; border-radius: 4px; padding: 16px 18px; overflow-x: auto; font-family: var(--mono); font-size: 13px; line-height: 1.6; }
pre.code code { background: none; padding: 0; color: inherit; font-size: inherit; }
pre.mermaid { background: var(--sheet); border: 1px solid var(--line); border-radius: 4px; padding: 18px; text-align: center; overflow-x: auto; }
.backtop {
  position: fixed; right: 24px; bottom: 24px; width: 40px; height: 40px;
  border-radius: 50%; background: var(--sheet); color: var(--ink);
  border: 1px solid var(--line); font-size: 16px; cursor: pointer;
  box-shadow: var(--shadow);
  opacity: 0; pointer-events: none; transition: opacity 0.2s;
}
.backtop.show { opacity: 1; pointer-events: auto; }
.backtop:hover { color: var(--accent); border-color: var(--accent); }
.mobile-toc-toggle { display: none; }
@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; gap: 12px; padding: 22px 20px 70px; }
  .toc { position: static; max-height: none; }
  .mobile-toc-toggle {
    display: block; width: 100%; background: none; border: none;
    border-bottom: 1px solid var(--line); padding: 10px 0; cursor: pointer;
    font-family: var(--mono); font-size: 12px; letter-spacing: 0.12em;
    text-transform: uppercase; color: var(--muted); text-align: left;
  }
  .toc.collapsed { display: none; }
  .content { max-width: none; }
}
`

const FONTS = `<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,600;9..144,700&family=IBM+Plex+Mono:wght@400;500&family=Newsreader:opsz,wght@6..72,400;6..72,600&family=Noto+Serif+SC:wght@400;600;700&display=swap" rel="stylesheet" />`

function renderDocPage({ id, title, body, toc, hasMermaid }) {
  const sidebar = toc.length
    ? `<nav class="toc" aria-label="目录">
<div class="toc-title">目录</div>
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
<title>${esc(title)} · 文章存档</title>
${FONTS}
<style>${THEME}${CONTENT_CSS}</style>
</head>
<body>
<header class="hero">
<p class="eyebrow">文章</p>
<h1>${esc(title)}</h1>
<a class="back" href="/docs.html">← 回到文章列表</a>${id ? '\n<span class="views" id="view-count" hidden></span>' : ''}
</header>
<div class="layout">
<button class="mobile-toc-toggle" onclick="document.querySelector('.toc').classList.toggle('collapsed')">☰ 目录</button>
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
</script>` : ''}${id ? `
<script>
  // Opening this document marks it "read" (task #357): the index's red "NEW" dot
  // and the board's Docs badge mean "recently updated AND not yet opened by you",
  // so once you've actually opened a doc it stops being flagged as new. Fire-and-
  // forget against the board's /api proxy; failures are harmless (badge just stays).
  try {
    fetch('/api/docs/reads', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ids: [${JSON.stringify(id)}] }),
      keepalive: true,
    }).catch(function () {});
  } catch (e) {}

  // Show this article's visit tally (task #523). The visit itself was already
  // counted server-side by the gate when it served this page, so there is
  // nothing to POST here — we only read the number back.
  fetch('/api/docs/views')
    .then(function (r) { return r.ok ? r.json() : {}; })
    .then(function (counts) {
      var n = counts && counts[${JSON.stringify(id)}];
      var el = document.getElementById('view-count');
      if (!el || !n) return;
      el.textContent = n + ' 次阅读';
      el.hidden = false;
    })
    .catch(function () {});
</script>` : ''}
</body>
</html>
`
}

const INDEX_CSS = `
.wrap { max-width: var(--maxw); margin: 0 auto; padding: 6px 24px 96px; }
/* The archive: one column of dated entries, not a wall of cards. Each entry is
   a date, a title, a paragraph and a link — the shape of a blog index. */
.grid { display: block; }
.card-wrap { position: relative; display: block; padding: 30px 0 28px; border-bottom: 1px solid var(--line); }
/* Hovering an entry inks a rule in the left margin instead of lifting a card. */
.card-wrap::before {
  content: ""; position: absolute; left: -20px; top: 30px; bottom: 28px; width: 2px;
  background: var(--accent); transform: scaleY(0); transform-origin: top;
  transition: transform 0.22s ease;
}
.card-wrap:hover::before { transform: scaleY(1); }
.doc-card { display: block; color: var(--ink); }
.doc-card:hover { text-decoration: none; }
.entry-date {
  font-family: var(--mono); font-size: 11.5px; letter-spacing: 0.14em;
  color: var(--muted); margin-bottom: 9px;
}
/* The visit tally sits with the date; a middot separates them when both show. */
.entry-date .views { font-size: 11.5px; letter-spacing: 0.14em; }
.entry-date .views::before { content: "·"; margin: 0 8px; }
.entry-date .views:first-child::before { content: none; }
.doc-card h2 {
  font-family: var(--display); font-weight: 600; font-size: 25px; line-height: 1.32;
  letter-spacing: -0.005em; margin: 0 0 9px;
}
.doc-card:hover h2 { color: var(--accent); }
.doc-card p { margin: 0; max-width: 68ch; color: var(--muted); font-size: 16px; line-height: 1.72; text-wrap: pretty; }
.doc-card .meta {
  display: inline-block; margin-top: 13px;
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.08em; color: var(--accent);
}
.doc-card:hover .meta { text-decoration: underline; text-underline-offset: 3px; }
.doc-card .badge {
  display: inline-block; margin-left: 10px; padding: 2px 8px; vertical-align: 4px;
  font-family: var(--mono); font-size: 10px; font-weight: 500; letter-spacing: 0.12em;
  text-transform: uppercase; color: var(--muted);
  border: 1px solid var(--line); border-radius: 2px;
}
.doc-card.featured .badge { color: var(--accent); border-color: #c2cdde; }
.empty { color: var(--muted); text-align: center; padding: 70px 0; font-style: italic; }
/* "Recently created/updated" marker (task #319): a red rubber stamp in the
   entry's right margin. Fresh docs already sort to the front, so the stamp is
   the only thing that has to shout — everything else on the page stays quiet. */
.new-dot {
  position: absolute; top: 22px; right: 0; z-index: 3;
  font-family: var(--mono); font-size: 10px; font-weight: 500; letter-spacing: 0.2em;
  line-height: 1; padding: 5px 7px 5px 9px;
  color: var(--stamp); background: transparent;
  border: 1.5px solid var(--stamp); border-radius: 2px;
  transform: rotate(-6deg); opacity: 0.9;
  pointer-events: none;
}
/* Owner-only controls sit in the entry's bottom-right corner, out of the way of
   the stamp, and only surface on hover/focus. */
.doc-del, .doc-priv {
  position: absolute; bottom: 24px; width: 28px; height: 28px;
  border-radius: 3px; border: 1px solid var(--line); background: var(--sheet);
  color: var(--muted); font-size: 13px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease, border-color 0.12s ease;
}
.doc-del { right: 0; }
.doc-priv { right: 34px; }
.card-wrap:hover .doc-del, .card-wrap:hover .doc-priv { opacity: 1; }
.card-wrap:focus-within .doc-del, .card-wrap:focus-within .doc-priv { opacity: 1; }
.doc-del:hover { background: #f6e9e6; color: var(--stamp); border-color: #ddbdb6; }
.doc-priv:hover { background: var(--accent-soft); color: var(--accent); border-color: #c2cdde; }
.doc-del:focus-visible, .doc-priv:focus-visible { opacity: 1; }
.doc-del:disabled, .doc-priv:disabled { cursor: default; opacity: 0.5; }
@media (hover: none) { .doc-del, .doc-priv { opacity: 1; } }
/* Privacy state (task #268): a lock tag on the title, in stamp red. */
.doc-card .badge.lock { color: var(--stamp); border-color: #ddbdb6; }
/* Owner-only controls stay hidden until the /__auth/whoami probe confirms the
   viewer is the authenticated owner. Anonymous public visitors never see the
   delete or privacy switch — and their private cards were stripped by the gate. */
body:not(.is-owner) .doc-del,
body:not(.is-owner) .doc-priv { display: none !important; }
/* Entry removal animation: collapse smoothly instead of vanishing abruptly. */
.card-wrap { transition: opacity 0.25s ease, transform 0.25s ease; }
.card-wrap.removing { opacity: 0; transform: scale(0.98); pointer-events: none; }
@media (max-width: 700px) {
  .wrap { padding: 6px 20px 70px; }
  .card-wrap::before { display: none; }
  .doc-card h2 { font-size: 22px; }
}

/* Confirmation modal — replaces the native window.confirm() with a styled,
   accessible dialog (backdrop, focus trap, Esc to cancel). */
.modal-backdrop {
  position: fixed; inset: 0; background: rgba(33,29,26,0.42);
  display: flex; align-items: center; justify-content: center; padding: 20px;
  opacity: 0; pointer-events: none; transition: opacity 0.18s ease; z-index: 50;
}
.modal-backdrop.open { opacity: 1; pointer-events: auto; }
.modal {
  background: var(--sheet); border: 1px solid var(--line); border-radius: 6px;
  max-width: 420px; width: 100%; padding: 26px 26px 22px;
  box-shadow: 0 26px 60px rgba(33,29,26,0.24);
  transform: translateY(12px) scale(0.98); transition: transform 0.18s ease;
}
.modal-backdrop.open .modal { transform: translateY(0) scale(1); }
.modal .modal-icon {
  width: 40px; height: 40px; border-radius: 50%; background: #f6e9e6; color: var(--stamp);
  display: flex; align-items: center; justify-content: center; font-size: 20px; margin-bottom: 14px;
}
.modal h3 { font-family: var(--display); font-weight: 600; font-size: 21px; margin: 0 0 8px; }
.modal p { margin: 0 0 6px; color: var(--muted); font-size: 15px; }
.modal .doc-name { color: var(--ink); font-weight: 600; word-break: break-word; }
.modal .note { font-size: 13.5px; color: var(--muted); margin-top: 10px; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 22px; }
.btn {
  font-family: var(--mono); font-size: 12.5px; letter-spacing: 0.06em;
  padding: 9px 16px; border-radius: 3px; cursor: pointer;
  border: 1px solid var(--line); background: var(--sheet); color: var(--ink);
  transition: background 0.12s ease, border-color 0.12s ease, opacity 0.12s ease;
}
.btn:hover { background: #f0ece3; }
.btn-danger { background: var(--stamp); border-color: var(--stamp); color: #fffdf9; }
.btn-danger:hover { background: #9a3527; border-color: #9a3527; }
.btn-danger:disabled { opacity: 0.6; cursor: default; }
.btn .spinner {
  display: inline-block; width: 12px; height: 12px; margin-right: 7px; vertical-align: -1px;
  border: 2px solid rgba(255,255,255,0.5); border-top-color: #fff; border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Toast notifications — replace native alert() for success/error feedback. */
.toast-host {
  position: fixed; left: 50%; bottom: 26px; transform: translateX(-50%);
  display: flex; flex-direction: column; gap: 10px; z-index: 60; width: max-content; max-width: 92vw;
}
.toast {
  display: flex; align-items: center; gap: 10px; padding: 11px 16px; border-radius: 4px;
  background: var(--ink); color: #f7f5f0; font-family: var(--mono); font-size: 12.5px;
  box-shadow: 0 12px 30px rgba(33,29,26,0.28);
  opacity: 0; transform: translateY(10px); transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast.show { opacity: 1; transform: translateY(0); }
.toast.error { background: var(--stamp); }
.toast .toast-icon { font-size: 15px; line-height: 1; }
@media (prefers-reduced-motion: reduce) {
  .card-wrap, .card-wrap::before, .modal-backdrop, .modal, .toast { transition: none; }
  .btn .spinner { animation: none; }
}
`

function renderIndex(cards) {
  const cardHtml = cards
    .map((c) => {
      const card = `<div class="card-wrap${c.isNew ? ' is-new' : ''}${c.private ? ' is-private' : ''}" data-id="${esc(c.id)}" data-private="${c.private ? 'true' : 'false'}">
  <a class="doc-card${c.featured ? ' featured' : ''}" href="${c.href}">
  <div class="entry-date">${c.updatedAt ? formatDate(c.updatedAt) : ''}<span class="views" hidden></span></div>
  <h2>${esc(c.title)}${c.featured ? '<span class="badge">Featured</span>' : ''}${c.private ? '<span class="badge lock">🔒 Private</span>' : ''}</h2>
  <p>${esc(c.excerpt || '点开读全文。')}</p>
  <div class="meta">阅读全文 →</div>
</a>${
        c.isNew
          ? `\n  <span class="new-dot"${c.updatedAt ? ` title="Updated ${esc(c.updatedAt.slice(0, 10))}"` : ''} aria-label="Recently updated">NEW</span>`
          : ''
      }
  <button class="doc-priv" type="button" title="${c.private ? 'Make public' : 'Make private'}" aria-label="${c.private ? 'Make public' : 'Make private'}: ${esc(c.title)}" data-id="${esc(c.id)}" data-private="${c.private ? 'true' : 'false'}">${c.private ? '🔒' : '🌐'}</button>${
        c.deletable
          ? `\n  <button class="doc-del" type="button" title="Delete this document" aria-label="Delete ${esc(c.title)}" data-id="${esc(c.id)}" data-title="${esc(c.title)}">✕</button>`
          : ''
      }
</div>`
      // Wrap private cards in markers the auth gate strips for anonymous visitors
      // (task #268) so a private doc is never even listed to others.
      return c.private ? `<!--PRIVATE-CARD:${esc(c.id)}-->\n${card}\n<!--/PRIVATE-CARD-->` : card
    })
    .join('\n')
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>文章存档</title>
<meta name="description" content="随手写下的报告、攻略和笔记，按更新时间排列。" />
${FONTS}
<style>${THEME}
${INDEX_CSS}</style>
</head>
<body>
<header class="hero">
<h1>文章存档</h1>
</header>
<div class="wrap">
${cards.length ? `<div class="grid">\n${cardHtml}\n</div>` : '<p class="empty">还没有文章。</p>'}
</div>

<div class="modal-backdrop" id="del-modal" role="dialog" aria-modal="true" aria-labelledby="del-modal-title" aria-describedby="del-modal-desc" hidden>
  <div class="modal">
    <div class="modal-icon" aria-hidden="true"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6"/><path d="M10 11v6M14 11v6"/></svg></div>
    <h3 id="del-modal-title">Delete this document?</h3>
    <p id="del-modal-desc">You're about to remove <span class="doc-name"></span>.</p>
    <p class="note">This deletes the document and its published page. It can't be undone from here.</p>
    <div class="modal-actions">
      <button type="button" class="btn" data-act="cancel">Cancel</button>
      <button type="button" class="btn btn-danger" data-act="confirm">Delete</button>
    </div>
  </div>
</div>
<div class="toast-host" id="toast-host" aria-live="polite" aria-atomic="true"></div>

<script>
  // Delete a document: ask the backend to remove its source Markdown + generated
  // page, then drop the card. The board proxies /api to the Spring backend, so a
  // same-origin fetch works whether we're on :80 (dev/tunnel) or a build preview.
  //
  // UX (task #278): the raw window.confirm()/alert() are replaced with an
  // accessible styled modal (focus trap, Esc to cancel, restores focus), a
  // smooth card-collapse animation, and toast feedback for success and errors.
  var grid = document.querySelector('.grid');
  var wrapEl = document.querySelector('.wrap');
  var modal = document.getElementById('del-modal');
  var modalName = modal.querySelector('.doc-name');
  var confirmBtn = modal.querySelector('[data-act="confirm"]');
  var cancelBtn = modal.querySelector('[data-act="cancel"]');
  var toastHost = document.getElementById('toast-host');
  var prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var pending = null; // the .doc-del button whose card we're about to delete
  var lastFocus = null;

  function toast(message, isError) {
    var el = document.createElement('div');
    el.className = 'toast' + (isError ? ' error' : '');
    el.setAttribute('role', isError ? 'alert' : 'status');
    var icon = document.createElement('span');
    icon.className = 'toast-icon';
    icon.textContent = isError ? '!' : '✓';
    el.appendChild(icon);
    el.appendChild(document.createTextNode(message));
    toastHost.appendChild(el);
    requestAnimationFrame(function () { el.classList.add('show'); });
    setTimeout(function () {
      el.classList.remove('show');
      setTimeout(function () { el.remove(); }, 250);
    }, isError ? 5000 : 3200);
  }

  function openModal(btn) {
    pending = btn;
    lastFocus = btn;
    modalName.textContent = '“' + (btn.getAttribute('data-title') || 'this document') + '”';
    modal.hidden = false;
    requestAnimationFrame(function () { modal.classList.add('open'); });
    confirmBtn.disabled = false;
    confirmBtn.textContent = 'Delete';
    setTimeout(function () { cancelBtn.focus(); }, 0);
  }

  function closeModal() {
    modal.classList.remove('open');
    var t = prefersReduced ? 0 : 180;
    setTimeout(function () { modal.hidden = true; }, t);
    pending = null;
    if (lastFocus && document.body.contains(lastFocus)) lastFocus.focus();
  }

  function removeCard(wrap) {
    wrap.classList.add('removing');
    var finish = function () {
      wrap.remove();
      if (grid && !grid.querySelector('.card-wrap') && wrapEl) {
        wrapEl.innerHTML = '<p class="empty">还没有文章。</p>';
      }
    };
    if (prefersReduced) finish();
    else setTimeout(finish, 260);
  }

  function doDelete() {
    if (!pending) return;
    var btn = pending;
    var id = btn.getAttribute('data-id');
    var title = btn.getAttribute('data-title') || 'this document';
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner" aria-hidden="true"></span>Deleting…';
    fetch('/api/docs/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function (res) {
        if (!res.ok && res.status !== 404) throw new Error('HTTP ' + res.status);
        var wrap = btn.closest('.card-wrap');
        // Don't restore focus to a button we're about to remove.
        lastFocus = null;
        closeModal();
        if (wrap) removeCard(wrap);
        toast('Deleted “' + title + '”', false);
      })
      .catch(function (err) {
        confirmBtn.disabled = false;
        confirmBtn.textContent = 'Delete';
        closeModal();
        toast('Couldn’t delete “' + title + '”: ' + err.message, true);
      });
  }

  // Open the modal from any delete button (delegated so it works after re-render).
  document.addEventListener('click', function (e) {
    var btn = e.target.closest && e.target.closest('.doc-del');
    if (!btn) return;
    e.preventDefault();
    openModal(btn);
  });

  confirmBtn.addEventListener('click', doDelete);
  cancelBtn.addEventListener('click', closeModal);
  modal.addEventListener('click', function (e) {
    if (e.target === modal) closeModal(); // click on backdrop
  });
  document.addEventListener('keydown', function (e) {
    if (modal.hidden) return;
    if (e.key === 'Escape') { closeModal(); return; }
    if (e.key === 'Tab') {
      // Simple focus trap between the two modal buttons.
      var focusables = [cancelBtn, confirmBtn].filter(function (b) { return !b.disabled; });
      if (!focusables.length) return;
      var first = focusables[0], last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  });
</script>
<script>
  // Make the red "NEW" badge mean "recently updated AND not yet opened by you"
  // (task #357). Two halves: (1) on load, strip NEW from any card whose doc the
  // user has already opened — read state lives server-side at /api/docs/reads so
  // it's the same across devices; (2) when the user clicks into a doc, record it
  // as read (keepalive so the POST survives the navigation) and clear its badge
  // immediately. Together: opening a doc stops it being flagged new here and on
  // the board's Docs badge. Bespoke pages (e.g. /singapore.html) that lack their
  // own mark-on-open script are covered by the click handler below.
  (function () {
    function markRead(id) {
      if (!id) return;
      try {
        fetch('/api/docs/reads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ ids: [id] }),
          keepalive: true,
        }).catch(function () {});
      } catch (e) {}
    }
    function clearNew(wrap) {
      if (!wrap) return;
      wrap.classList.remove('is-new');
      var dot = wrap.querySelector('.new-dot');
      if (dot) dot.remove();
    }
    fetch('/api/docs/reads')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (ids) {
        if (!Array.isArray(ids) || !ids.length) return;
        var seen = Object.create(null);
        ids.forEach(function (id) { seen[id] = true; });
        var wraps = document.querySelectorAll('.card-wrap[data-id]');
        for (var i = 0; i < wraps.length; i++) {
          if (seen[wraps[i].getAttribute('data-id')]) clearNew(wraps[i]);
        }
      })
      .catch(function () {});
    // Per-article visit tallies (task #523). Counting happens server-side in the
    // gate as each article is served, so the index only reads the numbers back
    // and paints them next to the date; an article nobody has opened stays bare.
    fetch('/api/docs/views')
      .then(function (r) { return r.ok ? r.json() : {}; })
      .then(function (counts) {
        if (!counts) return;
        var wraps = document.querySelectorAll('.card-wrap[data-id]');
        for (var i = 0; i < wraps.length; i++) {
          var n = counts[wraps[i].getAttribute('data-id')];
          var el = wraps[i].querySelector('.views');
          if (!el || !n) continue;
          el.textContent = n + ' 次阅读';
          el.hidden = false;
        }
      })
      .catch(function () {});
    document.addEventListener('click', function (e) {
      var link = e.target.closest && e.target.closest('.doc-card');
      if (!link) return; // delete buttons and empty space are handled elsewhere
      var wrap = link.closest('.card-wrap');
      if (!wrap) return;
      markRead(wrap.getAttribute('data-id'));
      clearNew(wrap);
    });
  })();
</script>
<script>
  // Per-doc privacy (task #268). Documents are public by default; the owner can
  // flip one PRIVATE (only they can open it) or back to PUBLIC. The delete and
  // privacy controls only make sense for the authenticated owner, so we probe the
  // gate at /__auth/whoami and reveal them via body.is-owner — anonymous visitors
  // never see them, and their private cards were already stripped by the gate.
  (function () {
    fetch('/__auth/whoami', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : { authed: false }; })
      .then(function (s) { if (s && s.authed) document.body.classList.add('is-owner'); })
      .catch(function () {});

    document.addEventListener('click', function (e) {
      var btn = e.target.closest && e.target.closest('.doc-priv');
      if (!btn) return;
      e.preventDefault();
      var id = btn.getAttribute('data-id');
      var makePrivate = btn.getAttribute('data-private') !== 'true';
      btn.disabled = true;
      fetch('/api/docs/' + encodeURIComponent(id) + '/visibility', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ private: makePrivate }),
      })
        .then(function (res) {
          if (!res.ok) throw new Error('HTTP ' + res.status);
          // The backend regenerated the index; reload to reflect the new state.
          window.location.reload();
        })
        .catch(function (err) {
          btn.disabled = false;
          if (typeof toast === 'function') {
            toast('Couldn’t update privacy: ' + err.message, true);
          }
        });
    });
  })();
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
    docUrlByFile[f] = BESPOKE[f]?.href || `/docs/${slugify(f.replace(/\.md$/, ''))}.html`
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
    const srcPath = path.join(DOCS_DIR, file)
    const md = fs.readFileSync(srcPath, 'utf8')
    const slug = slugify(file.replace(/\.md$/, ''))
    const { title: parsed, body, toc, hasMermaid } = renderMarkdown(md, { resolveLink })
    const title = parsed || humanize(file.replace(/\.md$/, ''))
    const ex = excerpt(md)
    const mtime = newestMtime([srcPath])

    if (BESPOKE[file]) {
      // Don't regenerate — link the index to the hand-tuned page. Deletable from
      // the web UI: `paths` lists the source Markdown plus every artifact the
      // bespoke page owns (its generated HTML at the web root and any asset dir),
      // so the backend can remove them all in one go.
      cards.push({
        id: slug, title, href: BESPOKE[file].href, excerpt: ex, featured: true,
        file: `docs/${file}`, deletable: true, mtime,
        paths: [`docs/${file}`, ...(BESPOKE[file].extraPaths || [])],
      })
      continue
    }

    const outPath = path.join(OUT_DIR, `${slug}.html`)
    fs.writeFileSync(outPath, renderDocPage({ id: slug, title, body, toc, hasMermaid }), 'utf8')
    // Plain single-file docs are the ones the web user may delete: removing
    // docs/<file> and the generated page is a clean, reversible-by-git operation.
    cards.push({
      id: slug, title, href: `/docs/${slug}.html`, excerpt: ex, featured: false,
      file: `docs/${file}`, deletable: true, mtime,
      paths: [`docs/${file}`, `frontend/public/docs/${slug}.html`],
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

    const mtime = newestMtime(parts.map((p) => path.join(dir, p)))
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
      renderDocPage({ id: slug, title: report.title, body, toc, hasMermaid }),
      'utf8'
    )
    cards.push({
      id: slug,
      title: report.title,
      href: `/docs/${slug}.html`,
      excerpt: report.summary || excerpt(md),
      featured: !!report.featured,
      // Stitched from several files under research/<dir>/. Deletable from the web
      // UI: `paths` removes the whole source directory plus the generated page.
      file: null,
      deletable: true,
      mtime,
      paths: [`research/${report.dir}`, `frontend/public/docs/${slug}.html`],
    })
    generated++
  }

  // Uploaded static pages -> one card each (the HTML is already the page).
  if (fs.existsSync(UPLOADS_DIR)) {
    const uploaded = fs
      .readdirSync(UPLOADS_DIR)
      .filter((f) => f.endsWith('.html'))
      .sort()
    for (const file of uploaded) {
      const slug = file.replace(/\.html$/, '')
      let meta = {}
      try {
        meta = JSON.parse(fs.readFileSync(path.join(UPLOADS_DIR, `${slug}.meta.json`), 'utf8')) || {}
      } catch {
        /* no/unreadable sidecar — fall back to the slug */
      }
      cards.push({
        id: `${UPLOAD_ID_PREFIX}${slug}`,
        title: meta.title || humanize(slug),
        href: `/p/${file}`,
        excerpt: meta.summary || '通过 POST /api/pages 上传的静态网页。',
        featured: false,
        file: null,
        // Removable from the web UI (owner only): drop the page and its sidecar.
        deletable: true,
        mtime: newestMtime([path.join(UPLOADS_DIR, file)]),
        paths: [`frontend/public/p/${file}`, `frontend/public/p/${slug}.meta.json`],
      })
    }
  }

  // Flag docs whose source Markdown changed within the freshness window, and
  // record when (for a hover tooltip + the manifest). Also stamp per-doc privacy
  // (task #268) so the index can badge/hide private cards and the auth gate knows
  // which pages to keep behind the password.
  const privateIds = readPrivateIds()
  for (const c of cards) {
    c.isNew = c.mtime > 0 && c.mtime >= NEW_CUTOFF
    c.updatedAt = c.mtime > 0 ? new Date(c.mtime).toISOString() : null
    c.private = privateIds.has(c.id)
  }

  // Recently created/updated docs go to the very front (newest first); the rest
  // keep the established order — featured (bespoke) cards, then alphabetical by
  // title (task #319).
  cards.sort((a, b) => {
    if (a.isNew !== b.isNew) return a.isNew ? -1 : 1
    if (a.isNew && b.isNew) return b.mtime - a.mtime
    return (b.featured - a.featured) || a.title.localeCompare(b.title)
  })

  fs.writeFileSync(INDEX_OUT, renderIndex(cards), 'utf8')
  // The manifest mirrors the index (same order). The board reads it to flag
  // unseen docs (id/title/href); the backend's DELETE /api/docs/{id} reads the
  // extra `deletable`/`paths` fields to know which docs the web user may remove
  // and exactly which files/dirs back each one.
  const manifest = cards.map((c) => ({
    id: c.id,
    title: c.title,
    href: c.href,
    deletable: !!c.deletable,
    file: c.file || null,
    // When the source Markdown was last modified, and whether that's recent
    // enough to flag as newly created/updated (task #319). The index already
    // sorts new docs first and paints their red badge; exposing it here keeps the
    // manifest a faithful mirror for any other consumer.
    updatedAt: c.updatedAt || null,
    isNew: !!c.isNew,
    // Whether the owner has marked this doc private (task #268). The Vite auth
    // gate reads this to keep private pages behind the password and to hide them
    // from anonymous listings; the board reads it to badge/skip private docs.
    private: !!c.private,
    // Every repo-relative file/dir backing this doc (sources + generated output).
    // DELETE /api/docs/{id} removes each of these, then regenerates the site.
    paths: c.paths || [],
  }))
  fs.writeFileSync(MANIFEST_OUT, JSON.stringify(manifest, null, 2) + '\n', 'utf8')
  console.log(
    `✔ docs site generated: ${generated} page(s) -> ${path.relative(ROOT, OUT_DIR)}/, index (${cards.length} card(s)) -> ${path.relative(ROOT, INDEX_OUT)}, manifest -> ${path.relative(ROOT, MANIFEST_OUT)}`
  )
}

build()
