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
// so a public doc's figures load for anonymous visitors.
export function isDocAssetPath(urlPath) {
  return urlPath.startsWith('/img/')
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
