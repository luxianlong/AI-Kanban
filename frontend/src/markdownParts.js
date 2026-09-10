// The parts of Markdown that Markdown.jsx needs but that are worth testing on
// their own: plain data in, plain data out, so they run in bare node while the
// JSX renderer does not (same split as workflowGraph.js).
//
// All three exist for the same reason: a workflow step asked to "show the
// changes in red and green" answers with a ```diff block, a colour span, or a
// summary table — and the renderer used to show all three as raw syntax.

// --- diff blocks -------------------------------------------------------------
// Which colour a line inside a ```diff fence gets. Order matters: `---` and
// `+++` are file headers, so they must be recognised before the bare `-` / `+`
// rules claim them as a removal / addition.
export function classifyDiffLine(line) {
  if (/^(---|\+\+\+)(\s|$)/.test(line)) return 'meta'
  if (/^@@/.test(line)) return 'meta'
  if (/^(diff|index) /.test(line)) return 'meta'
  if (line.startsWith('\\')) return 'meta' // "\ No newline at end of file"
  if (line.startsWith('+')) return 'add'
  if (line.startsWith('-')) return 'del'
  return 'ctx'
}

// --- colour spans ------------------------------------------------------------
// A model told to mark things red and green often reaches for HTML rather than
// Markdown. Only these two colours are recognised and they map to class names —
// nothing the model wrote ever reaches a style attribute, so this adds no more
// injection surface than **bold** does.
export const COLOR_SPAN_RE =
  /<span\s+style\s*=\s*["']?\s*color\s*:\s*(red|green)[^"'>]*["']?\s*>([\s\S]*?)<\/span>|<font\s+color\s*=\s*["']?(red|green)["']?\s*>([\s\S]*?)<\/font>/i

export function colorSpanParts(m) {
  return { color: (m[1] || m[3]).toLowerCase(), inner: m[2] ?? m[4] }
}

// --- inline HTML -------------------------------------------------------------
// Markdown has no underline, so a step told to "mark every line you added"
// answers with <u>…</u> — and those tags used to show up as literal text in the
// result panel. Same shapes for the same reason: strikethrough and highlight
// have no syntax here either, and a model that has already typed one HTML tag
// tends to type <b>/<i> as well.
//
// The rule is the colour spans' rule: an allow-list of formatting tags carrying
// no attributes, each mapped to an element we chose. Nothing the model wrote
// reaches an attribute, so this adds no more injection surface than **bold**.
const INLINE_HTML_TAGS = {
  u: 'u',
  mark: 'mark',
  b: 'strong',
  strong: 'strong',
  i: 'em',
  em: 'em',
  s: 'del',
  del: 'del',
  strike: 'del',
}

// The backreference is what keeps <b>x</i> from matching: an unclosed tag stays
// literal text rather than swallowing the rest of the paragraph.
export const INLINE_HTML_RE = /<(u|mark|b|strong|i|em|s|del|strike)\s*>([\s\S]*?)<\/\1\s*>/i

export function inlineHtmlParts(m) {
  return { tag: INLINE_HTML_TAGS[m[1].toLowerCase()], inner: m[2] }
}

// --- tables ------------------------------------------------------------------
// A leading pipe is required so that prose containing " | " isn't mistaken for
// a table; the block is only accepted once the delimiter row parses, which is
// what actually rules out false positives.
const DELIM_CELL = /^:?-+:?$/

function splitRow(line) {
  let s = line.trim()
  if (s.startsWith('|')) s = s.slice(1)
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1)
  const cells = []
  let cur = ''
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\' && s[i + 1] === '|') {
      cur += '|'
      i++
    } else if (s[i] === '|') {
      cells.push(cur.trim())
      cur = ''
    } else {
      cur += s[i]
    }
  }
  cells.push(cur.trim())
  return cells
}

function parseAlign(cells) {
  const align = []
  for (const c of cells) {
    if (!DELIM_CELL.test(c)) return null
    const left = c.startsWith(':')
    const right = c.endsWith(':')
    align.push(left && right ? 'center' : right ? 'right' : left ? 'left' : null)
  }
  return align
}

// Returns { header, align, rows, next } for the table starting at `start`, or
// null if there isn't one. `next` is the first line after the table.
export function parseTableBlock(lines, start) {
  const isRow = (l) => typeof l === 'string' && /^\s*\|/.test(l)
  if (!isRow(lines[start]) || !isRow(lines[start + 1])) return null
  const header = splitRow(lines[start])
  const align = parseAlign(splitRow(lines[start + 1]))
  if (!align || align.length !== header.length) return null

  const rows = []
  let i = start + 2
  while (isRow(lines[i])) {
    const cells = splitRow(lines[i])
    // Pad or trim to the header width: a ragged row should lose or gain a cell,
    // not shift every column after it.
    while (cells.length < header.length) cells.push('')
    rows.push(cells.slice(0, header.length))
    i++
  }
  return { header, align, rows, next: i }
}
