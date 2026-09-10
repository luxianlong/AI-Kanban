// A tiny, dependency-free Markdown renderer.
//
// Agent results come back as Markdown, but we were previously dumping them into a
// monospace <pre>, so headings, lists, **bold**, `code`, and links showed as raw
// syntax. This renders the common subset to real React elements — no innerHTML, so
// there's no XSS surface. Anything it doesn't recognise falls back to plain text.
//
// Diff blocks, colour spans, inline HTML and tables get the same treatment for
// a specific reason: a workflow step asked to "show the changes in red and
// green" answers in exactly those shapes, and until they were handled the
// comparison arrived as a wall of grey monospace — or, for a step told to
// underline what it added, as visible <u> tags.

import { createElement } from 'react'

import {
  COLOR_SPAN_RE,
  INLINE_HTML_RE,
  classifyDiffLine,
  colorSpanParts,
  inlineHtmlParts,
  parseTableBlock,
} from './markdownParts.js'

// --- inline: **bold**, *italic* / _italic_, `code`, [text](url), <u>…</u> ----
// Returns an array of strings / React elements for a single line of text.
function renderInline(text, keyPrefix) {
  // Ordered by precedence; each pattern captures its inner content.
  const patterns = [
    {
      re: COLOR_SPAN_RE,
      node: (m, k) => {
        const { color, inner } = colorSpanParts(m)
        // A colour we don't have a class for still loses its tags — the text it
        // wrapped reads better unstyled than fenced in visible HTML.
        return (
          <span key={k} className={color ? `md-mark md-mark-${color}` : undefined}>
            {renderInline(inner, k)}
          </span>
        )
      },
    },
    {
      re: INLINE_HTML_RE,
      node: (m, k) => {
        const { tag, inner } = inlineHtmlParts(m)
        return createElement(tag, { key: k }, renderInline(inner, k))
      },
    },
    { re: /`([^`]+)`/, node: (m, k) => <code key={k} className="md-code">{m[1]}</code> },
    { re: /\*\*([^*]+)\*\*/, node: (m, k) => <strong key={k}>{renderInline(m[1], k)}</strong> },
    { re: /(?:\*([^*]+)\*|_([^_]+)_)/, node: (m, k) => <em key={k}>{renderInline(m[1] ?? m[2], k)}</em> },
    {
      re: /\[([^\]]+)\]\(([^)\s]+)\)/,
      node: (m, k) => (
        <a key={k} href={m[2]} target="_blank" rel="noopener noreferrer" className="md-link">
          {m[1]}
        </a>
      ),
    },
  ]

  let earliest = null
  for (const p of patterns) {
    const m = p.re.exec(text)
    if (m && (earliest === null || m.index < earliest.m.index)) earliest = { p, m }
  }
  if (!earliest) return [text]

  const { p, m } = earliest
  const before = text.slice(0, m.index)
  const after = text.slice(m.index + m[0].length)
  const key = `${keyPrefix}-${m.index}`
  return [
    ...(before ? renderInline(before, `${key}b`) : []),
    p.node(m, key),
    ...(after ? renderInline(after, `${key}a`) : []),
  ]
}

// --- block level -------------------------------------------------------------
export default function Markdown({ text, className }) {
  const lines = (text || '').replace(/\r\n/g, '\n').split('\n')
  const blocks = []
  let i = 0

  const flushList = (items, ordered) => {
    const Tag = ordered ? 'ol' : 'ul'
    blocks.push(
      <Tag key={`l${blocks.length}`} className="md-list">
        {items.map((it, n) => (
          <li key={n}>{renderInline(it, `li${blocks.length}-${n}`)}</li>
        ))}
      </Tag>,
    )
  }

  while (i < lines.length) {
    const line = lines[i]

    // fenced code block
    const fence = /^\s*```+\s*([\w+#-]*)/.exec(line)
    if (fence) {
      const lang = fence[1].toLowerCase()
      const buf = []
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i])) buf.push(lines[i++])
      i++ // closing fence
      if (lang === 'diff' || lang === 'patch') {
        // One block-level span per line so each can carry its own colour. No
        // trailing newline: the spans are display:block, and a newline inside a
        // <pre> on top of that would double-space the whole diff. Empty lines
        // get a non-breaking space so they keep their height.
        blocks.push(
          <pre key={`c${blocks.length}`} className="md-pre md-diff">
            <code>
              {buf.map((l, n) => (
                <span key={n} className={`md-diff-line md-diff-${classifyDiffLine(l)}`}>
                  {l === '' ? '\u00a0' : l}
                </span>
              ))}
            </code>
          </pre>,
        )
      } else {
        blocks.push(
          <pre key={`c${blocks.length}`} className="md-pre">
            <code>{buf.join('\n')}</code>
          </pre>,
        )
      }
      continue
    }

    // blank line
    if (!line.trim()) {
      i++
      continue
    }

    // heading
    const h = /^(#{1,6})\s+(.*)$/.exec(line)
    if (h) {
      const level = h[1].length
      const Tag = `h${Math.min(level + 1, 6)}` // h1 reserved for page/drawer title
      blocks.push(
        <Tag key={`h${blocks.length}`} className="md-h">
          {renderInline(h[2], `h${blocks.length}`)}
        </Tag>,
      )
      i++
      continue
    }

    // horizontal rule
    if (/^\s*([-*_])\s*(\1\s*){2,}$/.test(line)) {
      blocks.push(<hr key={`hr${blocks.length}`} className="md-hr" />)
      i++
      continue
    }

    // blockquote
    if (/^\s*>\s?/.test(line)) {
      const buf = []
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^\s*>\s?/, ''))
      blocks.push(
        <blockquote key={`q${blocks.length}`} className="md-quote">
          {renderInline(buf.join(' '), `q${blocks.length}`)}
        </blockquote>,
      )
      continue
    }

    // table
    const table = parseTableBlock(lines, i)
    if (table) {
      const key = `t${blocks.length}`
      const cell = (Tag, text, n, r) => (
        <Tag key={n} style={table.align[n] ? { textAlign: table.align[n] } : undefined}>
          {renderInline(text, `${key}-${r}-${n}`)}
        </Tag>
      )
      blocks.push(
        <div key={key} className="md-table-wrap">
          <table className="md-table">
            <thead>
              <tr>{table.header.map((c, n) => cell('th', c, n, 'h'))}</tr>
            </thead>
            <tbody>
              {table.rows.map((row, r) => (
                <tr key={r}>{row.map((c, n) => cell('td', c, n, r))}</tr>
              ))}
            </tbody>
          </table>
        </div>,
      )
      i = table.next
      continue
    }

    // unordered list
    if (/^\s*[-*+]\s+/.test(line)) {
      const items = []
      while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
        items.push(lines[i++].replace(/^\s*[-*+]\s+/, ''))
      }
      flushList(items, false)
      continue
    }

    // ordered list
    if (/^\s*\d+[.)]\s+/.test(line)) {
      const items = []
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) {
        items.push(lines[i++].replace(/^\s*\d+[.)]\s+/, ''))
      }
      flushList(items, true)
      continue
    }

    // paragraph — gather consecutive non-blank, non-special lines
    const buf = []
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^\s*```/.test(lines[i]) &&
      !/^(#{1,6})\s+/.test(lines[i]) &&
      !/^\s*[-*+]\s+/.test(lines[i]) &&
      !/^\s*\d+[.)]\s+/.test(lines[i]) &&
      !/^\s*>\s?/.test(lines[i]) &&
      !parseTableBlock(lines, i)
    ) {
      buf.push(lines[i++])
    }
    blocks.push(
      <p key={`p${blocks.length}`} className="md-p">
        {renderInline(buf.join('\n'), `p${blocks.length}`)}
      </p>,
    )
  }

  return <div className={className ? `md ${className}` : 'md'}>{blocks}</div>
}
