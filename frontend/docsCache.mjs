// In-memory cache for the static docs site (task #566).
//
// The docs pages, the docs index and the assets they load (/img/, /vendor/) are
// served by the Vite dev server straight off disk, uncompressed, with
// `Cache-Control: no-cache` — so every visit re-read and re-sent the full bytes
// (a 1 MB uploaded page, a 3.5 MB mermaid bundle). This module keeps each file
// in memory together with its gzip/brotli encodings and a strong ETag, keyed on
// the file's mtime+size so a regenerated or re-uploaded doc is picked up on the
// very next request with no restart. Responses carry cache headers that let the
// browser show a doc instantly from its own cache and revalidate in the
// background (stale-while-revalidate).
//
// Pure helpers only (no Vite): unit-tested by docsCache.test.mjs and wired into
// the gate in vite.config.js.

import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import crypto from 'node:crypto'

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.pdf': 'application/pdf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
}

// Text formats worth compressing; images/fonts/PDFs are already compressed.
const COMPRESSIBLE = new Set(['.html', '.json', '.js', '.mjs', '.css', '.svg', '.txt', '.xml'])

export function contentTypeFor(filePath) {
  return TYPES[path.extname(filePath).toLowerCase()] || null
}

// Map a request path onto a file inside publicDir, or null when it would escape
// the directory or can't be decoded. Nothing outside public/ is ever served.
export function resolvePublicFile(publicDir, urlPath) {
  let decoded
  try {
    decoded = decodeURIComponent(urlPath)
  } catch {
    return null
  }
  if (decoded.includes('\0')) return null
  const root = path.resolve(publicDir)
  const abs = path.resolve(root, '.' + path.posix.normalize('/' + decoded))
  if (abs !== root && !abs.startsWith(root + path.sep)) return null
  return abs
}

// Cache-Control per doc kind:
//   public page  -> browser may reuse it for a minute, then shows the cached copy
//                   instantly while refetching in the background for a day. A doc
//                   edit shows up on the next visit after that minute.
//   private page -> owner-only: never stored by a shared cache, always revalidated.
//   asset        -> images / vendored libraries change rarely: an hour fresh.
//   index / manifest -> the listing differs for owner vs anonymous and must drop a
//                   doc the moment it goes private, so always revalidated (a 304
//                   when nothing changed, which is still just headers).
export function docCacheControl(kind, { isPrivate = false } = {}) {
  if (kind === 'page') {
    return isPrivate ? 'private, no-cache' : 'public, max-age=60, stale-while-revalidate=86400'
  }
  if (kind === 'asset') return 'public, max-age=3600, stale-while-revalidate=86400'
  return 'private, no-cache'
}

// Pick the best encoding the client accepts: br > gzip > identity.
export function pickEncoding(acceptEncoding = '') {
  const accepted = new Map()
  for (const part of String(acceptEncoding).toLowerCase().split(',')) {
    const [name, ...params] = part.trim().split(';')
    if (!name) continue
    const q = params.map((p) => p.trim()).find((p) => p.startsWith('q='))
    accepted.set(name, q ? Number(q.slice(2)) : 1)
  }
  const ok = (n) => (accepted.get(n) ?? accepted.get('*') ?? 0) > 0
  if (ok('br')) return 'br'
  if (ok('gzip')) return 'gzip'
  return null
}

// A bounded LRU of file bodies. `get(file, variant, transform)` returns the
// cached entry for the file's current mtime+size, rebuilding it (applying
// `transform` to the UTF-8 text when given) whenever the file changed.
export function createDocCache({ maxBytes = 96 * 1024 * 1024, maxFileBytes = 8 * 1024 * 1024 } = {}) {
  const entries = new Map() // key -> entry, in LRU order (oldest first)
  let total = 0

  const weigh = (e) => e.body.length + (e.gzip?.length || 0) + (e.br?.length || 0)
  const drop = (key) => {
    const e = entries.get(key)
    if (!e) return
    total -= weigh(e)
    entries.delete(key)
  }
  const evict = () => {
    for (const key of entries.keys()) {
      if (total <= maxBytes) break
      drop(key)
    }
  }

  function get(file, variant = 'raw', transform = null) {
    const key = `${variant}\0${file}`
    let stat
    try {
      stat = fs.statSync(file)
    } catch {
      drop(key)
      return null
    }
    if (!stat.isFile() || stat.size > maxFileBytes) return null
    const type = contentTypeFor(file)
    if (!type) return null

    const hit = entries.get(key)
    if (hit && hit.mtimeMs === stat.mtimeMs && hit.size === stat.size) {
      entries.delete(key) // refresh LRU position
      entries.set(key, hit)
      return hit
    }
    drop(key)

    let body = fs.readFileSync(file)
    if (transform) body = Buffer.from(transform(body.toString('utf8')), 'utf8')
    const entry = {
      body,
      type,
      compressible: COMPRESSIBLE.has(path.extname(file).toLowerCase()) && body.length > 1024,
      etag: '"' + crypto.createHash('sha1').update(body).digest('base64url') + '"',
      lastModified: stat.mtime.toUTCString(),
      mtimeMs: stat.mtimeMs,
      size: stat.size,
      gzip: null,
      br: null,
    }
    entries.set(key, entry)
    total += weigh(entry)
    evict()
    return entry
  }

  // Compress lazily, once per file version, and account for it in the budget.
  function encoded(entry, encoding) {
    if (!entry.compressible || !encoding) return null
    if (!entry[encoding]) {
      entry[encoding] =
        encoding === 'br'
          ? zlib.brotliCompressSync(entry.body, {
              params: {
                [zlib.constants.BROTLI_PARAM_QUALITY]: 7,
                [zlib.constants.BROTLI_PARAM_SIZE_HINT]: entry.body.length,
              },
            })
          : zlib.gzipSync(entry.body, { level: 6 })
      total += entry[encoding].length
      evict()
    }
    return entry[encoding]
  }

  return {
    get,
    encoded,
    stats: () => ({ entries: entries.size, bytes: total }),
  }
}

// True when the client's If-None-Match already names this entry's ETag.
export function isNotModified(req, etag) {
  const inm = req.headers['if-none-match']
  if (!inm) return false
  if (inm.trim() === '*') return true
  return inm.split(',').some((t) => t.trim().replace(/^W\//, '') === etag)
}

// Answer a GET/HEAD from a cache entry: 304 on a matching ETag, otherwise the
// best encoding the client accepts. `vary` adds extra Vary headers (Cookie for
// responses whose body depends on who is asking).
export function sendCached(req, res, cache, entry, cacheControl, { vary = [] } = {}) {
  res.setHeader('Cache-Control', cacheControl)
  res.setHeader('ETag', entry.etag)
  res.setHeader('Last-Modified', entry.lastModified)
  const varies = entry.compressible ? ['Accept-Encoding', ...vary] : vary
  if (varies.length) res.setHeader('Vary', varies.join(', '))

  if (isNotModified(req, entry.etag)) {
    res.statusCode = 304
    return res.end()
  }

  const encoding = pickEncoding(req.headers['accept-encoding'])
  const packed = cache.encoded(entry, encoding)
  const body = packed || entry.body
  res.statusCode = 200
  res.setHeader('Content-Type', entry.type)
  if (packed) res.setHeader('Content-Encoding', encoding)
  res.setHeader('Content-Length', body.length)
  return res.end(req.method === 'HEAD' ? undefined : body)
}
