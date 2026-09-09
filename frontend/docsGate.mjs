// Public-docs gate helpers (task #268).
//
// Documents produced by the board are PUBLIC by default: anyone can read a doc
// page without the site password. A document can be marked PRIVATE, in which
// case only the authenticated owner may open it, and it isn't even listed to
// anonymous visitors. The board, the /api backend and every non-doc asset stay
// behind the password gate exactly as before.
//
// These are the pure classification/filtering helpers used by the Basic-Auth
// middleware in vite.config.js. They live in their own module so they can be
// unit-tested (docsGate.test.mjs) without spinning up a Vite server. The auth
// primitives (cookies, credential hashing) stay in vite.config.js.

import fs from 'node:fs'

// Images and other binary assets backing a doc live under /img/ — always public
// so a public doc's figures load for anonymous visitors. /vendor/ holds the
// libraries a doc page loads for itself (mermaid, task #81); a diagram must
// render for an anonymous reader, so that directory is public on the same
// grounds. Both are read-only static files — nothing of the board lives there.
export function isDocAssetPath(urlPath) {
  return urlPath.startsWith('/img/') || urlPath.startsWith('/vendor/')
}

// Static pages published through POST /api/pages (task #470) are written to
// frontend/public/p/<slug>.html and served from the web root at /p/<slug>.html.
// The slug is sanitised server-side to [a-z0-9-], so a legitimate uploaded page
// never contains a further path segment.
export function isUploadedPagePath(urlPath) {
  return /^\/p\/[a-z0-9][a-z0-9-]*\.html$/.test(urlPath)
}

// The API surfaces that are deliberately reachable WITHOUT the site password.
// Everything else under /api stays behind the gate — in particular DELETE, so a
// stranger can publish a page but never unpublish someone else's.
//
//   /api/pages        (task #470) publishing a static page + reading it back
//   /api/public/...   (task #471) the delegated-task API: an outside worker
//                     lists 代理 tasks, claims one, and reports the outcome.
//                     The backend only ever exposes tasks explicitly flagged
//                     `delegated` there, so the private board can't leak through.
export function isPublicApiPath(urlPath, method) {
  const m = (method || 'GET').toUpperCase()
  if (urlPath === '/api/pages') return m === 'POST' || m === 'GET'
  if (/^\/api\/pages\/[^/]+$/.test(urlPath)) return m === 'GET'
  if (urlPath === '/api/public' || urlPath.startsWith('/api/public/')) {
    return m === 'GET' || m === 'POST'
  }
  return false
}

// Per-doc read state (task #357) lives server-side at /api/docs/reads: the docs
// index and each doc page probe it on load to dim the "NEW" badges. Those pages
// are public (task #268), so an anonymous visitor hits a gated endpoint on a
// page that never asked for a password — and the 401's `WWW-Authenticate: Basic`
// header makes the browser pop its native login dialog (task #472). The gate
// answers this one path anonymously with a benign no-op instead. Only the read
// tracker: every other /api/docs verb (delete, visibility) stays owner-only.
export function isDocsReadStatePath(urlPath) {
  return urlPath === '/api/docs/reads'
}

// Classify a request path for the public-docs gate:
//   'index'    -> the docs listing (/docs.html); filtered for anonymous
//   'manifest' -> the docs manifest (/docs-manifest.json); filtered for anonymous
//   'page'     -> a specific document page (/docs/<slug>.html or a bespoke href)
//   'asset'    -> an image/asset backing a doc
//   null       -> not a public-doc request (stays behind the password gate)
export function classifyDoc(urlPath, manifest) {
  if (urlPath === '/docs.html') return 'index'
  if (urlPath === '/docs-manifest.json') return 'manifest'
  if (isDocAssetPath(urlPath)) return 'asset'
  if (/^\/docs\/[^/]+\.html$/.test(urlPath)) return 'page'
  // Static pages published through the public upload API (task #470) live under
  // /p/. They are documents like any other: public unless marked private, and
  // listed on the docs index.
  if (isUploadedPagePath(urlPath)) return 'page'
  // Bespoke doc pages (e.g. /singapore.html) live at the web root — recognise
  // them only if the manifest lists that exact .html href as a document.
  const entries = manifest && manifest.entries
  if (
    entries &&
    entries.some(
      (e) =>
        e &&
        typeof e.href === 'string' &&
        e.href.split('?')[0] === urlPath &&
        urlPath.endsWith('.html')
    )
  ) {
    return 'page'
  }
  return null
}

// Remove every PRIVATE-CARD block from the index HTML so anonymous visitors
// don't even see a private doc listed. The generator wraps each private card in
// <!--PRIVATE-CARD:id-->…<!--/PRIVATE-CARD--> markers we control on both ends,
// so this stays robust regardless of the card's inner markup.
export function stripPrivateCards(html) {
  return html.replace(/<!--PRIVATE-CARD:[^>]*?-->[\s\S]*?<!--\/PRIVATE-CARD-->\s*/g, '')
}

// Drop private entries from the manifest JSON for anonymous visitors, so the
// board/badge and any other consumer never learn a private doc exists.
export function stripPrivateManifest(text) {
  try {
    const arr = JSON.parse(text)
    if (!Array.isArray(arr)) return text
    return JSON.stringify(arr.filter((e) => !(e && e.private)), null, 2) + '\n'
  } catch {
    return text
  }
}

// Read the generated manifest, returning { privatePaths: Set<urlPath>, entries }.
// Cached by mtime so we re-read only when the file changes (the backend rewrites
// it whenever a doc's visibility is toggled), giving the gate live updates with
// no Vite restart.
export function readDocsManifest(manifestFile, cache) {
  let stat
  try {
    stat = fs.statSync(manifestFile)
  } catch {
    // No manifest yet -> nothing is known private.
    return cache.data || (cache.data = { privatePaths: new Set(), entries: [] })
  }
  if (cache.mtimeMs === stat.mtimeMs && cache.data) return cache.data
  cache.mtimeMs = stat.mtimeMs
  try {
    const parsed = JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
    const entries = Array.isArray(parsed) ? parsed : []
    const privatePaths = new Set()
    for (const e of entries) {
      if (e && e.private && typeof e.href === 'string') {
        privatePaths.add(e.href.split('?')[0])
      }
    }
    cache.data = { privatePaths, entries }
  } catch {
    cache.data = { privatePaths: new Set(), entries: [] }
  }
  return cache.data
}

// --- Per-document visit counts (task #523) ------------------------------
// Every article carries a visit tally. Counting happens in the gate rather than
// in a script on the page: the gate sees every doc page it serves, so uploaded
// pages under /p/ (sandboxed, no scripts of ours) and bespoke pages at the web
// root are counted exactly like generated ones, and a reader with JavaScript off
// still counts. The gate POSTs the increment straight to the backend, so the
// write endpoint itself never has to be opened to the public — only the read.

// The counts endpoint the docs index and each article page read on load. It has
// to answer anonymous visitors (the docs are public), so the gate serves it
// without a password — filtered, see stripPrivateViewCounts.
export function isDocsViewCountPath(urlPath) {
  return urlPath === '/api/docs/views'
}

// The manifest id of the document served at `urlPath`, or null if that path is
// not a document. Matches the manifest's own hrefs, so every doc kind resolves:
// generated pages (/docs/x.html), uploaded pages (/p/x.html) and bespoke pages.
export function docIdForPath(urlPath, manifest) {
  const entries = (manifest && manifest.entries) || []
  for (const e of entries) {
    if (e && typeof e.href === 'string' && e.href.split('?')[0] === urlPath) {
      return e.id || null
    }
  }
  return null
}

// Drop private docs' tallies before handing counts to an anonymous visitor: the
// gate hides even the existence of a private doc, and a count keyed by its id
// would give it away.
export function stripPrivateViewCounts(counts, manifest) {
  const entries = (manifest && manifest.entries) || []
  const privateIds = new Set(
    entries.filter((e) => e && e.private && e.id).map((e) => e.id)
  )
  const out = {}
  for (const [id, n] of Object.entries(counts || {})) {
    if (!privateIds.has(id)) out[id] = n
  }
  return out
}

// How long the same visitor's repeat hits on the same document fold into the
// first one. Without this a reload or a back-button trip would inflate the
// tally; half an hour is the usual "one visit" window for a simple counter.
export const VIEW_DEDUPE_MS = 30 * 60 * 1000

// Decide whether this hit counts, remembering it if so. `seen` is a plain Map of
// "<visitor>|<docId>" -> timestamp, pruned as we go so it can't grow unbounded.
export function shouldCountView(seen, visitor, docId, now, windowMs = VIEW_DEDUPE_MS) {
  if (!docId) return false
  const key = `${visitor || '-'}|${docId}`
  const last = seen.get(key)
  if (last !== undefined && now - last < windowMs) return false
  seen.set(key, now)
  for (const [k, t] of seen) {
    if (now - t >= windowMs) seen.delete(k)
  }
  return true
}

// --- Search-engine crawler files (task #79) ------------------------------
// Google could not index the site: /robots.txt and /sitemap.xml both sat behind
// the password gate and answered 401 with the login form, so a crawler saw an
// unreadable site and the "sitemap" submitted to Search Console (/docs.html)
// was an HTML page, not a sitemap. Both files are therefore served by the gate
// itself, without a password, and built from the live manifest so they can
// never drift from what is actually public: a doc marked private disappears
// from the sitemap (and loses its robots Allow) the moment it is toggled.

// The two crawler files the gate owns. Generated on the fly rather than kept in
// public/ so privacy toggles take effect with no rebuild.
export function isCrawlerFilePath(urlPath) {
  return urlPath === '/robots.txt' || urlPath === '/sitemap.xml'
}

// The absolute origin this request arrived on ("https://www.example.com"), so a
// sitemap's URLs always match the host Google actually crawled — apex or www,
// tunnel hostname or bare IP. Behind cloudflared the real scheme only survives
// in X-Forwarded-Proto, so prefer it over the (always plain-HTTP) hop we see.
// True when the visitor's connection is encrypted. Two ways it can be: the
// request arrived over a TLS socket this server terminated itself (req.socket
// .encrypted — the case now that Vite serves HTTPS directly), or a proxy in
// front terminated it and said so. Checking only the header, as this used to,
// silently reported "http" for every direct-TLS request — which would have left
// the session cookies without their Secure flag.
export function isSecureRequest(req) {
  const headers = (req && req.headers) || {}
  const forwarded = String(headers['x-forwarded-proto'] || '').split(',')[0].trim()
  if (forwarded === 'https') return true
  return Boolean(req && req.socket && req.socket.encrypted)
}

export function requestOrigin(req) {
  const headers = (req && req.headers) || {}
  const first = (v) => String(v || '').split(',')[0].trim()
  const proto = isSecureRequest(req) ? 'https' : 'http'
  const host = first(headers['x-forwarded-host']) || first(headers['host'])
  // Reject anything that isn't a plain host[:port] so a spoofed Host header
  // can't inject markup or a foreign origin into the generated files.
  if (!/^[A-Za-z0-9.\-]+(:\d+)?$/.test(host)) return `${proto}://localhost`
  return `${proto}://${host}`
}

function xmlEsc(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

// YYYY-MM-DD for <lastmod>, or null when the timestamp is missing/unparseable.
function lastmodDay(updatedAt) {
  if (!updatedAt) return null
  const t = Date.parse(updatedAt)
  return Number.isNaN(t) ? null : new Date(t).toISOString().slice(0, 10)
}

// Every publicly readable document path, in manifest order, de-duplicated.
// Private docs are omitted: the gate hides even their existence, so listing one
// would both 401 the crawler and leak the title through the URL.
export function publicDocPaths(manifest) {
  const out = []
  const seen = new Set()
  for (const e of (manifest && manifest.entries) || []) {
    if (!e || e.private || typeof e.href !== 'string') continue
    const loc = e.href.split('?')[0]
    // Same-origin absolute paths only ("//host/x" would point off-site).
    if (!/^\/(?!\/)/.test(loc) || seen.has(loc)) continue
    seen.add(loc)
    out.push({ loc, lastmod: lastmodDay(e.updatedAt) })
  }
  return out
}

// The sitemap: the docs index plus every public document. The index carries the
// newest document's date, since that is when the listing last changed.
export function buildSitemap(manifest, origin) {
  const docs = publicDocPaths(manifest)
  const newest = docs.reduce((a, d) => (d.lastmod && d.lastmod > a ? d.lastmod : a), '')
  const urls = [{ loc: '/docs.html', lastmod: newest || null }, ...docs]
  const body = urls
    .map(({ loc, lastmod }) =>
      '  <url>\n' +
      `    <loc>${xmlEsc(origin + loc)}</loc>\n` +
      (lastmod ? `    <lastmod>${lastmod}</lastmod>\n` : '') +
      '  </url>'
    )
    .join('\n')
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
    body +
    '\n</urlset>\n'
  )
}

// robots.txt: the board, its API and the auth endpoints are password-gated, so
// crawling them only ever yields a login page — deny the site by default and
// allow exactly the public document surfaces back in. Longest-match wins for
// Google/Bing, so each `Allow` below overrides the blanket `Disallow: /`.
// Bespoke docs live at the web root (/singapore.html), which no prefix rule
// covers, so they get an explicit Allow line each, straight from the manifest.
export function buildRobotsTxt(manifest, origin) {
  const rootDocs = publicDocPaths(manifest)
    .map((d) => d.loc)
    .filter((loc) => loc !== '/docs.html' && /^\/[^/]+\.html$/.test(loc))
  return [
    '# The kanban board itself is private (password-gated); the published',
    '# documents under /docs/ and /p/ are public and meant to be indexed.',
    'User-agent: *',
    'Allow: /docs.html',
    'Allow: /docs/',
    'Allow: /p/',
    'Allow: /img/',
    // Mermaid & friends: a crawler that cannot fetch them renders a doc's
    // diagrams as raw text (task #81).
    'Allow: /vendor/',
    ...rootDocs.map((loc) => `Allow: ${loc}`),
    'Disallow: /api/',
    'Disallow: /__auth/',
    'Disallow: /',
    '',
    `Sitemap: ${origin}/sitemap.xml`,
    '',
  ].join('\n')
}
