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
// Markdown. When it wants a particular shade it writes a hex literal instead of
// a colour name — a step told to highlight the jokes it added answered with
// <span style="color:#d95f02">, which fell outside the old two-name whitelist
// and so arrived in the result panel (and on the Maximize page, where a long
// answer is actually read) as visible tags around every sentence it marked.
//
// So any colour is now recognised, and snapped to one of a few buckets that are
// class names. That keeps the original rule intact — nothing the model wrote
// ever reaches a style attribute, so this adds no more injection surface than
// **bold** does — and it keeps the result legible, because we pick the value:
// a step cannot land on white-on-white, and its shade of red is our red.
//
// The style attribute must actually carry a `color` declaration; a span styled
// some other way is left alone, as is any other attribute.
export const COLOR_SPAN_RE =
  /<span\s+style\s*=\s*["']?(?:[^"'<>]*;)?\s*color\s*:\s*([^;"'<>]+)[^"'<>]*["']?\s*>([\s\S]*?)<\/span\s*>|<font\s+color\s*=\s*["']?([^\s"'<>]+)["']?\s*>([\s\S]*?)<\/font\s*>/i

// The names a model actually types, each pointing at the bucket it belongs in.
// Anything not here is still understood if it is written as a literal; a name
// that is neither is dropped, leaving the text it wrapped as plain prose.
const NAMED_COLORS = {
  red: 'red', crimson: 'red', firebrick: 'red', darkred: 'red', maroon: 'red',
  tomato: 'red', salmon: 'red', pink: 'red', hotpink: 'red', deeppink: 'red',
  orange: 'orange', darkorange: 'orange', orangered: 'orange', coral: 'orange',
  chocolate: 'orange', peru: 'orange', sienna: 'orange',
  yellow: 'yellow', gold: 'yellow', goldenrod: 'yellow', olive: 'yellow', khaki: 'yellow',
  green: 'green', darkgreen: 'green', forestgreen: 'green', seagreen: 'green',
  lime: 'green', limegreen: 'green', olivedrab: 'green', teal: 'green',
  blue: 'blue', darkblue: 'blue', navy: 'blue', royalblue: 'blue', dodgerblue: 'blue',
  steelblue: 'blue', cornflowerblue: 'blue', skyblue: 'blue', cyan: 'blue', aqua: 'blue',
  purple: 'purple', violet: 'purple', darkviolet: 'purple', indigo: 'purple',
  magenta: 'purple', fuchsia: 'purple', orchid: 'purple', mediumpurple: 'purple',
  gray: 'grey', grey: 'grey', darkgray: 'grey', darkgrey: 'grey',
  lightgray: 'grey', lightgrey: 'grey', silver: 'grey', white: 'grey',
  black: null, // the default ink already; wrapping it in a colour would lighten it
}

// Upper bound of each hue range, in degrees. Red owns both ends of the wheel.
const HUE_BUCKETS = [
  [15, 'red'], [45, 'orange'], [70, 'yellow'], [170, 'green'],
  [255, 'blue'], [320, 'purple'], [360, 'red'],
]

function toRgb(value) {
  const hex = /^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/.exec(value)
  if (hex) {
    const h = hex[1]
    const short = h.length <= 4
    const at = (i) => (short ? parseInt(h[i] + h[i], 16) : parseInt(h.slice(i * 2, i * 2 + 2), 16))
    return [at(0), at(1), at(2)]
  }
  const fn = /^rgba?\(([^)]*)\)$/.exec(value)
  if (fn) {
    const nums = fn[1].split(/[\s,/]+/).filter(Boolean).slice(0, 3).map(Number)
    if (nums.length === 3 && nums.every(Number.isFinite)) {
      return nums.map((n) => Math.min(255, Math.max(0, n)))
    }
  }
  return null
}

/** Which of our colour classes a written colour belongs to, or null for none. */
export function colorBucket(value) {
  const v = String(value ?? '').trim().toLowerCase()
  if (!v) return null
  if (Object.prototype.hasOwnProperty.call(NAMED_COLORS, v)) return NAMED_COLORS[v]
  const rgb = toRgb(v)
  if (!rgb) return null
  const [r, g, b] = rgb
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const chroma = max - min
  // Near-black is the body colour already; anything else without enough colour
  // in it is grey, whichever grey it happens to be.
  if (max < 40) return null
  if (chroma / max < 0.15) return 'grey'
  let hue
  if (max === r) hue = ((g - b) / chroma) * 60
  else if (max === g) hue = ((b - r) / chroma + 2) * 60
  else hue = ((r - g) / chroma + 4) * 60
  if (hue < 0) hue += 360
  return HUE_BUCKETS.find(([upTo]) => hue < upTo)[1]
}

export function colorSpanParts(m) {
  return { color: colorBucket(m[1] ?? m[3]), inner: m[2] ?? m[4] }
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
