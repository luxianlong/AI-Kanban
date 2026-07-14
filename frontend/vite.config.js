import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

// During `npm run dev`, proxy API calls to the Spring Boot backend on :8080
// so the browser talks to a single origin (no CORS surprises in dev).

// --- Public-exposure Basic-Auth gate ------------------------------------
// When this dev server is exposed publicly (e.g. via a cloudflared tunnel),
// credentials in frontend/.public-auth.json (gitignored) lock it down.
// Shape: { "user": "...", "pass": "..." }. If the file is absent the gate is
// OFF, so local dev stays frictionless — but then DON'T expose a tunnel.
// The middleware is registered inside configureServer *before* the proxy, so
// it guards both the UI and the /api backend in one place. Creds are re-read
// from disk whenever the file's mtime changes, so editing .public-auth.json
// takes effect immediately — no server restart needed.
//
// Once you've typed the Basic-Auth password once, a signed session cookie is
// set so the browser stops re-prompting on every new tab / restart. The cookie
// value is a hash of the current credentials, so rotating .public-auth.json
// silently invalidates every previously-issued cookie (old cookie stops
// matching -> Basic-Auth prompt returns with the new password).
const CREDS_FILE = path.resolve(import.meta.dirname, '.public-auth.json')

const AUTH_COOKIE = 'dash_auth'
// How long the session cookie survives before the browser re-prompts.
const COOKIE_MAX_AGE = 60 * 60 * 24 * 30 // 30 days, in seconds

// A cookie value bound to the current password: changing the password changes
// the hash, so stale cookies no longer match and the gate re-challenges.
function authToken(creds) {
  return crypto
    .createHash('sha256')
    .update(`${creds.user}:${creds.pass}`)
    .digest('hex')
}

function parseCookies(header = '') {
  const out = {}
  for (const part of header.split(';')) {
    const eq = part.indexOf('=')
    if (eq === -1) continue
    out[part.slice(0, eq).trim()] = part.slice(eq + 1).trim()
  }
  return out
}

function readCreds(cache) {
  let stat
  try {
    stat = fs.statSync(CREDS_FILE)
  } catch {
    return null // file gone -> gate off
  }
  if (cache.mtimeMs === stat.mtimeMs) return cache.creds
  cache.mtimeMs = stat.mtimeMs
  try {
    const { user, pass } = JSON.parse(fs.readFileSync(CREDS_FILE, 'utf8'))
    cache.creds = user && pass ? { user, pass } : null
  } catch {
    cache.creds = null // bad JSON -> gate off
  }
  return cache.creds
}

function basicAuthGate() {
  const cache = { mtimeMs: -1, creds: null }
  return {
    name: 'public-basic-auth-gate',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const creds = readCreds(cache)
        if (!creds) return next() // no file -> frictionless local dev
        const token = authToken(creds)

        // Already carrying a valid session cookie -> let it through, no prompt.
        const cookies = parseCookies(req.headers['cookie'])
        if (cookies[AUTH_COOKIE] === token) return next()

        const header = req.headers['authorization'] || ''
        const [scheme, encoded] = header.split(' ')
        if (scheme === 'Basic' && encoded) {
          const [u, p] = Buffer.from(encoded, 'base64').toString().split(':')
          if (u === creds.user && p === creds.pass) {
            // First valid login: hand back a session cookie so subsequent
            // requests (new tabs, restarts) skip the Basic-Auth prompt.
            // Secure only when the request arrived over HTTPS (the tunnel),
            // so plain-http local testing still works.
            const https = (req.headers['x-forwarded-proto'] || '').includes('https')
            res.setHeader(
              'Set-Cookie',
              `${AUTH_COOKIE}=${token}; Path=/; Max-Age=${COOKIE_MAX_AGE}; ` +
                `HttpOnly; SameSite=Lax${https ? '; Secure' : ''}`,
            )
            return next()
          }
        }
        res.statusCode = 401
        res.setHeader('WWW-Authenticate', 'Basic realm="Dashboard"')
        res.end('Authentication required')
      })
    },
  }
}

export default defineConfig({
  plugins: [basicAuthGate(), react()],
  server: {
    port: 5173,
    // Allow the tunnel's random *.trycloudflare.com Host header (Vite 5.4+
    // blocks unknown hosts). localhost/127.0.0.1 stay allowed automatically.
    allowedHosts: ['.trycloudflare.com'],
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
