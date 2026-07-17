// Turn internal doc-file references inside an agent result into clickable links
// to the published webpage the user can actually open.
//
// Agent results are freeform Markdown and habitually mention the *internal* file
// they produced — `docs/foo.md`, `frontend/public/docs/foo.html` — paths that a
// user browsing the board can't reach. Every such file, though, is published to a
// servable page listed in docs-manifest.json (its `href`). This module maps the
// internal paths back to that href and rewrites the mentions in place, so a result
// links to the real page ("好的呈现,用户能在网站上打开") instead of talking about
// an inaccessible `xxx.md`.
//
// It only rewrites paths the manifest knows map to a published page — source files
// like `App.jsx` or `scripts/deploy.sh` are genuinely internal and left untouched.

// Build a lookup { internalPath -> { href, title } } from the docs manifest array.
// Each manifest entry contributes its source `file` (the .md), every `.md`/`.html`
// path it lists, and its own public `href` (so a bare `/docs/foo.html` mention is
// linked to its nice title too).
export function buildDocIndex(manifest) {
  const index = new Map()
  if (!Array.isArray(manifest)) return index
  for (const entry of manifest) {
    if (!entry || !entry.href) continue
    const title = entry.title || entry.href
    const add = (p) => {
      if (typeof p === 'string' && p && !index.has(p)) index.set(p, { href: entry.href, title })
    }
    add(entry.file)
    if (Array.isArray(entry.paths)) {
      for (const p of entry.paths) if (/\.(md|html)$/.test(p)) add(p)
    }
    add(entry.href)
  }
  return index
}

const RX_SPECIAL = /[.*+?^${}()|[\]\\]/g
const escapeRe = (s) => s.replace(RX_SPECIAL, '\\$&')
// A char that can legally sit inside a file path — used to reject partial matches
// (so `docs/foo.md` inside `docs/foo.md.bak` isn't linked).
const PATH_CHAR = /[\w/.\-]/

// True when the char after a matched path doesn't continue it, i.e. the match sits
// on a real right-hand path boundary. A trailing '.' is the tricky case: it's a
// path char (extensions use it) but is *also* how agents end a sentence
// ("...produced docs/foo.md."). We treat that '.' as sentence punctuation unless
// something path-like follows it — so `docs/foo.md.` at a sentence end links, while
// `docs/foo.md.bak` (a different, longer file) is still left alone.
function rightBoundary(text, end) {
  const after = text[end]
  if (!after || !PATH_CHAR.test(after)) return true
  if (after === '.') {
    const next = text[end + 1]
    return !next || !PATH_CHAR.test(next)
  }
  return false
}

// Rewrite every known internal doc path in `text` into a Markdown link to its
// published page. Returns `text` unchanged when there's nothing to map. Handles
// three surface forms without double-wrapping or breaking existing links:
//   `docs/foo.md`      (code span)  -> [Title](/docs/foo.html)
//   [label](docs/foo.md) (link)     -> [label](/docs/foo.html)   (target only)
//   docs/foo.md        (bare)       -> [Title](/docs/foo.html)
export function linkifyDocRefs(text, index) {
  if (!text || !(index instanceof Map) || index.size === 0) return text
  // Longest paths first so the more specific path wins at a shared position
  // (e.g. the full .html path over a prefix).
  const paths = [...index.keys()].sort((a, b) => b.length - a.length)
  const union = new RegExp(paths.map(escapeRe).join('|'), 'g')

  const hits = []
  let m
  while ((m = union.exec(text)) !== null) {
    let start = m.index
    let end = start + m[0].length
    const info = index.get(m[0])
    if (!info) continue
    const before = text[start - 1]
    const after = text[end]

    if (before === '`' && after === '`') {
      // code span: consume the backticks and replace with a titled link
      hits.push({ start: start - 1, end: end + 1, text: `[${info.title}](${info.href})` })
    } else if (text.slice(start - 2, start) === '](') {
      // link target: swap just the URL, keep the author's label
      hits.push({ start, end, text: info.href })
    } else if ((!before || !PATH_CHAR.test(before)) && rightBoundary(text, end)) {
      // bare mention on its own path boundary
      hits.push({ start, end, text: `[${info.title}](${info.href})` })
    }
  }
  if (!hits.length) return text

  // Apply non-overlapping, left to right. `union` already yields non-overlapping
  // matches, and the boundary checks above keep each hit self-contained.
  let out = ''
  let cursor = 0
  for (const h of hits) {
    if (h.start < cursor) continue
    out += text.slice(cursor, h.start) + h.text
    cursor = h.end
  }
  out += text.slice(cursor)
  return out
}
