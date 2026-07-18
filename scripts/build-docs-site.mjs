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

function renderDocPage({ id, title, body, toc, hasMermaid }) {
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
/* "Recently created/updated" marker (task #319): a red badge pinned to the
   card's top-right corner. The whole card also gets a subtle red-tinted border
   so fresh docs — which sort to the front — read as new at a glance. */
.card-wrap.is-new .doc-card { border-color: #f3c0c0; }
.new-dot {
  position: absolute; top: -8px; right: -8px; z-index: 3;
  background: #e5484d; color: #fff; font-size: 10px; font-weight: 800;
  letter-spacing: 0.6px; line-height: 1; padding: 4px 7px; border-radius: 999px;
  border: 2px solid var(--bg); box-shadow: 0 3px 10px rgba(229,72,77,0.45);
  pointer-events: none;
}
.doc-del {
  position: absolute; top: 10px; right: 10px; width: 28px; height: 28px;
  border-radius: 8px; border: 1px solid var(--line); background: var(--card);
  color: var(--muted); font-size: 15px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease;
}
.card-wrap:hover .doc-del { opacity: 1; }
.card-wrap:focus-within .doc-del { opacity: 1; }
.doc-del:hover { background: #fdecec; color: #d63939; border-color: #f3c0c0; }
.doc-del:focus-visible { opacity: 1; outline: 2px solid var(--accent); outline-offset: 2px; }
.doc-del:disabled { cursor: default; opacity: 0.5; }
@media (hover: none) { .doc-del { opacity: 1; } }
/* Card removal animation: collapse smoothly instead of vanishing abruptly. */
.card-wrap { transition: opacity 0.25s ease, transform 0.25s ease; }
.card-wrap.removing { opacity: 0; transform: scale(0.94); pointer-events: none; }

/* Confirmation modal — replaces the native window.confirm() with a styled,
   accessible dialog (backdrop, focus trap, Esc to cancel). */
.modal-backdrop {
  position: fixed; inset: 0; background: rgba(31,36,48,0.45);
  display: flex; align-items: center; justify-content: center; padding: 20px;
  opacity: 0; pointer-events: none; transition: opacity 0.18s ease; z-index: 50;
}
.modal-backdrop.open { opacity: 1; pointer-events: auto; }
.modal {
  background: var(--card); border-radius: 16px; max-width: 420px; width: 100%;
  padding: 26px 26px 22px; box-shadow: 0 24px 60px rgba(31,36,48,0.28);
  transform: translateY(12px) scale(0.98); transition: transform 0.18s ease;
}
.modal-backdrop.open .modal { transform: translateY(0) scale(1); }
.modal .modal-icon {
  width: 44px; height: 44px; border-radius: 50%; background: #fdecec; color: #d63939;
  display: flex; align-items: center; justify-content: center; font-size: 22px; margin-bottom: 14px;
}
.modal h3 { font-family: "Space Grotesk","Inter",sans-serif; font-size: 19px; margin: 0 0 8px; }
.modal p { margin: 0 0 6px; color: var(--muted); font-size: 14.5px; }
.modal .doc-name { color: var(--ink); font-weight: 600; word-break: break-word; }
.modal .note { font-size: 13px; color: var(--muted); margin-top: 10px; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 22px; }
.btn {
  font: inherit; font-weight: 600; font-size: 14px; padding: 9px 16px; border-radius: 10px;
  cursor: pointer; border: 1px solid var(--line); background: var(--card); color: var(--ink);
  transition: background 0.12s ease, border-color 0.12s ease, opacity 0.12s ease;
}
.btn:hover { background: #f3f4f8; }
.btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.btn-danger { background: #d63939; border-color: #d63939; color: #fff; }
.btn-danger:hover { background: #c02c2c; border-color: #c02c2c; }
.btn-danger:disabled { opacity: 0.6; cursor: default; }
.btn .spinner {
  display: inline-block; width: 13px; height: 13px; margin-right: 7px; vertical-align: -1px;
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
  display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-radius: 12px;
  background: var(--ink); color: #fff; font-size: 14px; font-weight: 500;
  box-shadow: 0 10px 30px rgba(31,36,48,0.3);
  opacity: 0; transform: translateY(10px); transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast.show { opacity: 1; transform: translateY(0); }
.toast.error { background: #b02525; }
.toast .toast-icon { font-size: 16px; line-height: 1; }
@media (prefers-reduced-motion: reduce) {
  .card-wrap, .modal-backdrop, .modal, .toast { transition: none; }
  .btn .spinner { animation: none; }
}
`

function renderIndex(cards) {
  const cardHtml = cards
    .map(
      (c) => `<div class="card-wrap${c.isNew ? ' is-new' : ''}" data-id="${esc(c.id)}">
  <a class="doc-card${c.featured ? ' featured' : ''}" href="${c.href}">
  <h2>${esc(c.title)}${c.featured ? '<span class="badge">Featured</span>' : ''}</h2>
  <p>${esc(c.excerpt || 'Open to read the full document.')}</p>
  <div class="meta">Read →</div>
</a>${
        c.isNew
          ? `\n  <span class="new-dot"${c.updatedAt ? ` title="Updated ${esc(c.updatedAt.slice(0, 10))}"` : ''} aria-label="Recently updated">NEW</span>`
          : ''
      }${
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
        wrapEl.innerHTML = '<p class="empty">No documents yet.</p>';
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

  // Flag docs whose source Markdown changed within the freshness window, and
  // record when (for a hover tooltip + the manifest).
  for (const c of cards) {
    c.isNew = c.mtime > 0 && c.mtime >= NEW_CUTOFF
    c.updatedAt = c.mtime > 0 ? new Date(c.mtime).toISOString() : null
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
