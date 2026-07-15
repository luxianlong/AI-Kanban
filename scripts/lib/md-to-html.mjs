// Shared, dependency-free Markdown -> HTML converter.
//
// A deliberately small Markdown subset covering exactly what the repo's docs
// use: headings, tables, nested/ordered lists, blockquotes, horizontal rules,
// and inline bold/italic/code/links. Extracted so both the bespoke Singapore
// page (scripts/build-singapore-page.mjs) and the generic docs-site generator
// (scripts/build-docs-site.mjs) render Markdown identically instead of each
// carrying its own copy of the parser.
//
// Heading ids use GitHub's slug algorithm so a document's own in-text anchors
// (e.g. [1.6 当地交通](#16-当地交通)) keep working, and CJK is preserved.

// --- inline formatting -------------------------------------------------------
export function esc(s) {
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
export function slugify(text) {
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
function parseList(lines, start, state) {
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
      return `<li>${parseBlocks(dedented, state, true)}</li>`
    })
    .join('\n')

  const tag = ordered ? 'ol' : 'ul'
  return [`<${tag}>\n${lis}\n</${tag}>`, i]
}

// Render a list of lines to HTML. `tight` suppresses the <p> wrapper around a
// lone leading text run (used for list items so bullets don't gain vertical gaps).
// `state` carries the running toc (h2/h3 headings) and a hasMermaid flag.
function parseBlocks(lines, state, tight = false) {
  const toc = state.toc
  const out = []
  let i = 0
  let firstText = true
  while (i < lines.length) {
    const line = lines[i]
    if (isBlank(line)) {
      i++
      continue
    }

    // fenced code block (``` or ~~~). A ```mermaid block becomes a Mermaid
    // diagram (rendered client-side, like GitHub); any other language becomes
    // a plain <pre><code>. Content is never inline-formatted.
    const fence = /^\s*(```+|~~~+)\s*([\w-]*)\s*$/.exec(line)
    if (fence) {
      const marker = fence[1][0]
      const lang = fence[2].toLowerCase()
      const buf = []
      i++
      while (i < lines.length && !new RegExp(`^\\s*${marker === '`' ? '```+' : '~~~+'}\\s*$`).test(lines[i])) {
        buf.push(lines[i++])
      }
      i++ // consume closing fence
      const code = esc(buf.join('\n'))
      if (lang === 'mermaid') {
        state.hasMermaid = true
        out.push(`<pre class="mermaid">${code}</pre>`)
      } else {
        const cls = lang ? ` class="language-${lang}"` : ''
        out.push(`<pre class="code"><code${cls}>${code}</code></pre>`)
      }
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
      out.push(`<blockquote class="${cls}">${parseBlocks(inner, state)}</blockquote>`)
      continue
    }

    // list
    if (listRe.test(line)) {
      const [html, next] = parseList(lines, i, state)
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
      !/^\s*(```+|~~~+)/.test(lines[i]) &&
      !(isTableRow(lines[i]) && i + 1 < lines.length && isTableSep(lines[i + 1])) &&
      !/^\s*([-*_])(\s*\1){2,}\s*$/.test(lines[i])
    ) {
      buf.push(lines[i++])
    }
    const innerHtml = inline(buf.join('\n')).replace(/\n/g, '<br />')
    if (tight && firstText) {
      out.push(innerHtml) // bare text so list markers stay tight
    } else {
      out.push(`<p>${innerHtml}</p>`)
    }
    firstText = false
  }
  return out.join('\n')
}

// Render a Markdown string. Returns { title, body, toc, hasMermaid } where:
//   - title: the document's first H1 text (pulled out of the body so callers
//     can use it as a page hero), or null if the doc has no H1. Only removed
//     from the body when `stripH1` is true (the default).
//   - body:  the rendered HTML.
//   - toc:   [{ level, id, text }] for every h2/h3 (minus a "目录" heading).
//   - hasMermaid: true if the doc contains at least one ```mermaid block, so
//     the caller knows to load the Mermaid runtime.
export function renderMarkdown(md, { stripH1 = true } = {}) {
  const lines = md.replace(/\r\n/g, '\n').split('\n')
  let title = null
  const firstH1 = lines.findIndex((l) => /^#\s+/.test(l))
  if (firstH1 !== -1) {
    title = lines[firstH1].replace(/^#\s+/, '').trim()
    if (stripH1) lines[firstH1] = ''
  }
  const state = { toc: [], hasMermaid: false }
  const body = parseBlocks(lines, state)
  return { title, body, toc: state.toc, hasMermaid: state.hasMermaid }
}
