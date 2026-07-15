#!/usr/bin/env node
// Generate a polished, self-contained webpage from the Singapore travel guide.
//
//   docs/singapore-travel-guide.md  ->  frontend/public/singapore.html
//
// The markdown is the single source of truth; this script renders it to a
// standalone HTML page (embedded CSS, no runtime deps) with a sticky sidebar
// table of contents, styled "来源与时效" source blocks, tables and nested
// lists. It runs as the frontend `prebuild` step so the page can never drift
// from the guide — every deploy regenerates it. Vite serves frontend/public/
// at the web root, so the result is reachable at /singapore.html.
//
// Deliberately dependency-free: a small Markdown subset covering exactly what
// the guide uses (headings, tables, nested/ordered lists, blockquotes, hr,
// bold/italic/code/links). Heading ids use GitHub's slug algorithm so the
// guide's own in-text anchors (e.g. [1.6 当地交通](#16-当地交通)) keep working.

import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..')
const SRC = path.join(ROOT, 'docs', 'singapore-travel-guide.md')
const OUT = path.join(ROOT, 'frontend', 'public', 'singapore.html')

// --- inline formatting -------------------------------------------------------
function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function inline(text) {
  let t = esc(text)
  t = t.replace(/`([^`]+)`/g, (_, c) => `<code>${c}</code>`)
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, txt, url) => {
    const internal = url.startsWith('#')
    const attrs = internal ? '' : ' target="_blank" rel="noopener noreferrer"'
    return `<a href="${url.replace(/"/g, '%22')}"${attrs}>${txt}</a>`
  })
  t = t.replace(/\*\*([^*]+)\*\*/g, (_, c) => `<strong>${c}</strong>`)
  t = t.replace(/\*([^*\n]+)\*/g, (_, c) => `<em>${c}</em>`)
  return t
}

// GitHub-flavoured heading slug: lowercase, drop punctuation (keep letters,
// numbers, combining marks — CJK included), spaces -> hyphens.
function slugify(text) {
  return text
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\p{M}\s-]/gu, '')
    .replace(/\s/g, '-')
}

// --- block parsing -----------------------------------------------------------
const isBlank = (l) => !l.trim()
const isTableRow = (l) => /^\s*\|/.test(l)
const isTableSep = (l) => /^\s*\|?[\s:|-]*-[\s:|-]*$/.test(l) && l.includes('|')
const listRe = /^(\s*)([-*+]|\d+[.)])(\s+)(.*)$/

const toc = [] // { level, id, text } for h2/h3, drives the sidebar

function renderTable(rows) {
  const cells = (row) =>
    row
      .trim()
      .replace(/^\|/, '')
      .replace(/\|$/, '')
      .split('|')
      .map((c) => c.trim())
  const head = cells(rows[0])
  const body = rows.slice(2).map(cells)
  const th = head.map((c) => `<th>${inline(c)}</th>`).join('')
  const trs = body
    .map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`)
    .join('\n')
  return `<div class="table-wrap"><table>\n<thead><tr>${th}</tr></thead>\n<tbody>\n${trs}\n</tbody>\n</table></div>`
}

// Parse a list beginning at lines[i]; returns [html, nextIndex].
function parseList(lines, start) {
  const baseIndent = lines[start].match(/^(\s*)/)[1].length
  const ordered = /^\s*\d+[.)]\s+/.test(lines[start])
  const items = [] // each item is an array of its (raw) lines
  let i = start
  while (i < lines.length) {
    const line = lines[i]
    if (isBlank(line)) {
      // Blank belongs to the list only if indented content follows.
      let j = i + 1
      while (j < lines.length && isBlank(lines[j])) j++
      if (j < lines.length && lines[j].match(/^(\s*)/)[1].length > baseIndent) {
        if (items.length) items[items.length - 1].push(line)
        i++
        continue
      }
      break
    }
    const indent = line.match(/^(\s*)/)[1].length
    const m = listRe.exec(line)
    if (m && indent === baseIndent) {
      items.push([line])
      i++
    } else if (indent > baseIndent) {
      if (!items.length) break
      items[items.length - 1].push(line)
      i++
    } else {
      break
    }
  }

  const lis = items
    .map((itemLines) => {
      const m = listRe.exec(itemLines[0])
      const contentCol = m[1].length + m[2].length + m[3].length
      const dedented = [
        m[4],
        ...itemLines.slice(1).map((l) => {
          const n = l.match(/^(\s*)/)[1].length
          return l.slice(Math.min(contentCol, n))
        }),
      ]
      return `<li>${parseBlocks(dedented, true)}</li>`
    })
    .join('\n')

  const tag = ordered ? 'ol' : 'ul'
  return [`<${tag}>\n${lis}\n</${tag}>`, i]
}

// Render a list of lines to HTML. `tight` suppresses the <p> wrapper around a
// lone leading text run (used for list items so bullets don't gain vertical gaps).
function parseBlocks(lines, tight = false) {
  const out = []
  let i = 0
  let firstText = true
  while (i < lines.length) {
    const line = lines[i]
    if (isBlank(line)) {
      i++
      continue
    }

    // horizontal rule
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
      out.push('<hr />')
      i++
      continue
    }

    // heading
    const h = /^(#{1,6})\s+(.*)$/.exec(line)
    if (h) {
      const level = h[1].length
      const text = h[2].trim()
      const id = slugify(text)
      if (level === 2 || level === 3) {
        if (text !== '目录') toc.push({ level, id, text })
      }
      out.push(`<h${level} id="${id}">${inline(text)}<a class="anchor" href="#${id}" aria-label="链接到本节">#</a></h${level}>`)
      i++
      continue
    }

    // table (header row + separator row)
    if (isTableRow(line) && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      const rows = []
      while (i < lines.length && isTableRow(lines[i])) rows.push(lines[i++])
      out.push(renderTable(rows))
      continue
    }

    // blockquote (possibly multi-line). "来源与时效" quotes get a marker class.
    if (/^\s*>\s?/.test(line)) {
      const buf = []
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        buf.push(lines[i++].replace(/^\s*>\s?/, ''))
      }
      const joined = buf.join('\n')
      const isSource = /^\s*\*\*来源与时效\*\*/.test(joined)
      const cls = isSource ? 'quote source' : 'quote'
      // The source card gets a styled "🔎 来源与时效" header via CSS ::before,
      // so drop the inline "**来源与时效**：" prefix to avoid showing it twice.
      const inner = isSource
        ? buf.join('\n').replace(/^\s*\*\*来源与时效\*\*[:：]\s*/, '').split('\n')
        : buf
      out.push(`<blockquote class="${cls}">${parseBlocks(inner)}</blockquote>`)
      continue
    }

    // list
    if (listRe.test(line)) {
      const [html, next] = parseList(lines, i)
      out.push(html)
      i = next
      continue
    }

    // paragraph — gather consecutive plain lines
    const buf = []
    while (
      i < lines.length &&
      !isBlank(lines[i]) &&
      !/^(#{1,6})\s+/.test(lines[i]) &&
      !/^\s*>\s?/.test(lines[i]) &&
      !listRe.test(lines[i]) &&
      !(isTableRow(lines[i]) && i + 1 < lines.length && isTableSep(lines[i + 1])) &&
      !/^\s*([-*_])(\s*\1){2,}\s*$/.test(lines[i])
    ) {
      buf.push(lines[i++])
    }
    const inner = inline(buf.join('\n')).replace(/\n/g, '<br />')
    if (tight && firstText) {
      out.push(inner) // bare text so list markers stay tight
    } else {
      out.push(`<p>${inner}</p>`)
    }
    firstText = false
  }
  return out.join('\n')
}

// --- page shell --------------------------------------------------------------
function renderTocSidebar() {
  const items = toc
    .map((h) => `<li class="lv${h.level}"><a href="#${h.id}">${esc(h.text)}</a></li>`)
    .join('\n')
  return `<nav class="toc" aria-label="目录">
<div class="toc-title">目录</div>
<ul>
${items}
</ul>
</nav>`
}

function build() {
  const md = fs.readFileSync(SRC, 'utf8')
  const lines = md.replace(/\r\n/g, '\n').split('\n')

  // Pull the H1 title off the top so it becomes the page hero, not body content.
  let title = '新加坡旅游攻略'
  const firstH1 = lines.findIndex((l) => /^#\s+/.test(l))
  if (firstH1 !== -1) {
    title = lines[firstH1].replace(/^#\s+/, '').trim()
    lines[firstH1] = ''
  }

  const body = parseBlocks(lines)
  const sidebar = renderTocSidebar()

  const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${esc(title)}</title>
<meta name="description" content="面向中国大陆公民的新加坡自由行完整攻略：签证、机票、景点、行程、美食、住宿与实用贴士。" />
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&family=Space+Grotesk:wght@600;700&display=swap" rel="stylesheet" />
<style>
:root {
  --bg: #f6f7f9;
  --card: #ffffff;
  --ink: #1f2430;
  --muted: #5b6472;
  --line: #e5e8ee;
  --accent: #d1495b;
  --accent-soft: #fdecef;
  --link: #1f6feb;
  --source-bg: #f0f7f4;
  --source-line: #3f9e78;
  --maxw: 1180px;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: "Noto Sans SC", system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 16px;
  line-height: 1.75;
}
a { color: var(--link); text-decoration: none; }
a:hover { text-decoration: underline; }

.hero {
  background: linear-gradient(135deg, #d1495b 0%, #e07a5f 55%, #f2a65a 100%);
  color: #fff;
  padding: 56px 24px 48px;
  text-align: center;
}
.hero h1 {
  font-family: "Space Grotesk", "Noto Sans SC", sans-serif;
  font-size: clamp(28px, 5vw, 44px);
  margin: 0 0 10px;
  letter-spacing: 0.5px;
}
.hero p { margin: 0 auto; max-width: 640px; opacity: 0.95; font-size: 15px; }

.layout {
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 32px 24px 80px;
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
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
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 1px;
  text-transform: uppercase;
  color: var(--muted);
  margin-bottom: 10px;
}
.toc ul { list-style: none; margin: 0; padding: 0; }
.toc li { margin: 1px 0; }
.toc li a {
  display: block;
  padding: 4px 8px;
  border-radius: 7px;
  color: var(--ink);
  border-left: 2px solid transparent;
}
.toc li a:hover { background: var(--accent-soft); text-decoration: none; }
.toc li.lv2 a { font-weight: 600; margin-top: 6px; }
.toc li.lv3 a { padding-left: 20px; color: var(--muted); font-size: 13px; }

.content {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 8px 40px 40px;
  min-width: 0;
}
.content h2, .content h3, .content h4, .content h5, .content h6 {
  font-family: "Space Grotesk", "Noto Sans SC", sans-serif;
  line-height: 1.35;
  scroll-margin-top: 16px;
  position: relative;
}
.content h2 {
  font-size: 26px;
  margin: 40px 0 14px;
  padding-bottom: 8px;
  border-bottom: 2px solid var(--accent-soft);
}
.content h3 { font-size: 20px; margin: 30px 0 10px; }
.content h4 { font-size: 17px; margin: 22px 0 8px; color: var(--accent); }
.content p { margin: 12px 0; }
.content ul, .content ol { margin: 12px 0; padding-left: 24px; }
.content li { margin: 5px 0; }
.content li > ul, .content li > ol { margin: 5px 0; }

.anchor {
  opacity: 0;
  margin-left: 8px;
  color: var(--accent);
  font-weight: 400;
  text-decoration: none;
}
h2:hover .anchor, h3:hover .anchor, h4:hover .anchor { opacity: 0.55; }

code {
  background: #eef0f4;
  border-radius: 5px;
  padding: 1px 6px;
  font-family: ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace;
  font-size: 0.9em;
}

blockquote.quote {
  margin: 16px 0;
  padding: 12px 18px;
  border-left: 4px solid var(--line);
  background: #f8f9fb;
  border-radius: 0 10px 10px 0;
  color: var(--muted);
}
blockquote.source {
  background: var(--source-bg);
  border-left-color: var(--source-line);
}
blockquote.source::before {
  content: "🔎 来源与时效";
  display: block;
  font-weight: 700;
  font-size: 13px;
  color: var(--source-line);
  margin-bottom: 4px;
}
blockquote p { margin: 4px 0; font-size: 14px; }

.table-wrap { overflow-x: auto; margin: 16px 0; }
table {
  border-collapse: collapse;
  width: 100%;
  font-size: 14.5px;
  min-width: 420px;
}
th, td { border: 1px solid var(--line); padding: 8px 12px; text-align: left; vertical-align: top; }
thead th { background: var(--accent-soft); color: var(--ink); font-weight: 700; }
tbody tr:nth-child(even) { background: #fafbfc; }

hr { border: none; border-top: 1px solid var(--line); margin: 28px 0; }

.backtop {
  position: fixed;
  right: 22px;
  bottom: 22px;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  border: none;
  font-size: 20px;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(209,73,91,0.4);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s;
}
.backtop.show { opacity: 1; pointer-events: auto; }

.mobile-toc-toggle { display: none; }

@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; gap: 16px; }
  .toc {
    position: static;
    max-height: none;
  }
  .mobile-toc-toggle {
    display: block;
    width: 100%;
    background: var(--card);
    border: 1px solid var(--line);
    border-radius: 10px;
    padding: 12px 16px;
    font: inherit;
    font-weight: 600;
    text-align: left;
    cursor: pointer;
  }
  .toc.collapsed { display: none; }
  .content { padding: 8px 20px 28px; }
}
</style>
</head>
<body>
<header class="hero">
<h1>${esc(title)}</h1>
<p>面向中国大陆公民的一份完整自由行攻略 · 行前基础 · 景点玩乐 · 行程路线 · 美食购物 · 住宿分区 · 实用贴士</p>
</header>

<div class="layout">
<button class="mobile-toc-toggle" onclick="document.querySelector('.toc').classList.toggle('collapsed')">☰ 目录（点击展开/收起）</button>
${sidebar}
<main class="content">
${body}
</main>
</div>

<button class="backtop" aria-label="回到顶部" onclick="window.scrollTo({top:0,behavior:'smooth'})">↑</button>
<script>
  var bt = document.querySelector('.backtop');
  window.addEventListener('scroll', function () {
    if (window.scrollY > 400) bt.classList.add('show');
    else bt.classList.remove('show');
  });
  // On small screens the TOC starts collapsed.
  if (window.matchMedia('(max-width: 900px)').matches) {
    document.querySelector('.toc').classList.add('collapsed');
  }
</script>
</body>
</html>
`

  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, html, 'utf8')
  console.log(`✔ singapore.html generated (${(html.length / 1024).toFixed(1)} KB, ${toc.length} TOC entries) -> ${path.relative(ROOT, OUT)}`)
}

build()
