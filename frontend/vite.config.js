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
// Instead of the browser's native Basic-Auth dialog (which password managers
// rarely offer to save), an unauthenticated HTML navigation is answered with a
// real login FORM. Browsers reliably remember the username + password typed
// into a proper <form> with autocomplete hints, and a "Remember me" checkbox
// decides whether the session cookie persists for 30 days or only until the
// browser closes. Submitting the form sets a signed session cookie so the
// browser stops re-prompting on every new tab / restart. The cookie value is a
// hash of the current credentials, so rotating .public-auth.json silently
// invalidates every previously-issued cookie (old cookie stops matching -> the
// login form returns and the new password is required). A `Basic` Authorization
// header is still honoured too, so curl/scripts keep working unchanged.
//
// To avoid asking a regular visitor for the password over and over, a
// "Remember me" session uses a ROLLING window: every authenticated full-page
// load re-issues the cookie with a fresh 30-day expiry, so the credentials are
// only requested again after 30 days of NOT visiting — not 30 days after the
// first sign-in. A companion marker cookie records that the session was a
// remembered one, so session-only logins (Remember-me unticked) are never
// silently upgraded into persistent ones.
const CREDS_FILE = path.resolve(import.meta.dirname, '.public-auth.json')

const AUTH_COOKIE = 'dash_auth'
// Marks a "Remember me" session so rolling refresh only extends persistent
// logins, never a deliberately session-only one. Not security-sensitive: it
// only gates whether the (already-validated) auth cookie's expiry slides.
const PERSIST_COOKIE = 'dash_auth_p'
// Endpoints the gate owns for the login form (kept under a /__auth/ prefix so
// they can never collide with an app route or static asset).
const LOGIN_PATH = '/__auth/login'
const LOGOUT_PATH = '/__auth/logout'
// How long a remembered session survives a period of inactivity before the
// browser re-prompts. Refreshed on every authenticated page load (rolling), so
// this is a window of inactivity, not a hard cap from first sign-in. Without
// "Remember me" the cookie is a session cookie (cleared on browser close).
const COOKIE_MAX_AGE = 60 * 60 * 24 * 30 // 30 days, in seconds

// A cookie value bound to the current password: changing the password changes
// the hash, so stale cookies no longer match and the gate re-challenges.
function authToken(creds) {
  return crypto
    .createHash('sha256')
    .update(`${creds.user}:${creds.pass}`)
    .digest('hex')
}

// Escape a string for safe interpolation into an HTML attribute value.
function htmlAttr(s = '') {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

// Only allow same-origin relative redirects (leading single "/", not "//"),
// so the ?next= round-trip can never become an open redirect.
function safeNext(next) {
  return typeof next === 'string' && /^\/(?!\/)/.test(next) ? next : '/'
}

// Build a single Set-Cookie header value. maxAge null -> session cookie.
function cookieStr(req, name, value, maxAge) {
  const https = (req.headers['x-forwarded-proto'] || '').includes('https')
  const attrs = [`${name}=${value}`, 'Path=/', 'HttpOnly', 'SameSite=Lax']
  if (maxAge != null) attrs.push(`Max-Age=${maxAge}`)
  if (https) attrs.push('Secure')
  return attrs.join('; ')
}

// Issue the auth session. When `persistent` (the user ticked "Remember me", or
// authenticated via a Basic header), the auth cookie and its marker both get a
// fresh 30-day expiry; calling this on each page load slides that window
// forward. When not persistent, the auth cookie is a session cookie and the
// marker is cleared so the session is never rolled into a persistent one.
function setSession(req, res, token, persistent) {
  res.setHeader('Set-Cookie', persistent
    ? [
        cookieStr(req, AUTH_COOKIE, token, COOKIE_MAX_AGE),
        cookieStr(req, PERSIST_COOKIE, '1', COOKIE_MAX_AGE),
      ]
    : [
        cookieStr(req, AUTH_COOKIE, token, null),
        cookieStr(req, PERSIST_COOKIE, '', 0),
      ])
}

// Clear both cookies (logout).
function clearSession(req, res) {
  res.setHeader('Set-Cookie', [
    cookieStr(req, AUTH_COOKIE, '', 0),
    cookieStr(req, PERSIST_COOKIE, '', 0),
  ])
}

// Read a small x-www-form-urlencoded body, capped so a bad client can't OOM us.
function readBody(req, cb) {
  let data = ''
  req.on('data', (chunk) => {
    data += chunk
    if (data.length > 4096) { data = ''; req.destroy() }
  })
  req.on('end', () => cb(data))
}

function loginPage({ next = '/', error = false } = {}) {
  const safe = htmlAttr(safeNext(next))
  const err = error
    ? '<p class="err" role="alert">Wrong username or password. Try again.</p>'
    : ''
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sign in · Dashboard</title>
<style>
  :root { color-scheme: light dark }
  * { box-sizing: border-box }
  body {
    margin: 0; min-height: 100vh; display: grid; place-items: center;
    font: 16px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    background: #0f172a; color: #e2e8f0; padding: 24px;
  }
  .card {
    width: 100%; max-width: 360px; background: #1e293b; border: 1px solid #334155;
    border-radius: 14px; padding: 28px 26px; box-shadow: 0 10px 40px rgba(0,0,0,.35);
  }
  h1 { margin: 0 0 4px; font-size: 1.25rem }
  .sub { margin: 0 0 20px; color: #94a3b8; font-size: .9rem }
  label { display: block; margin: 14px 0 6px; font-size: .85rem; color: #cbd5e1 }
  input[type=text], input[type=password] {
    width: 100%; padding: 10px 12px; border-radius: 9px; border: 1px solid #475569;
    background: #0f172a; color: #e2e8f0; font-size: 1rem;
  }
  input:focus { outline: 2px solid #6366f1; outline-offset: 1px; border-color: #6366f1 }
  .remember { display: flex; align-items: center; gap: 8px; margin: 18px 0 4px; font-size: .9rem; color: #cbd5e1 }
  .remember input { width: auto; margin: 0 }
  button {
    width: 100%; margin-top: 18px; padding: 11px; border: 0; border-radius: 9px;
    background: #6366f1; color: #fff; font-size: 1rem; font-weight: 600; cursor: pointer;
  }
  button:hover { background: #4f46e5 }
  .err { background: #7f1d1d; color: #fecaca; padding: 8px 12px; border-radius: 8px; font-size: .85rem; margin: 0 0 8px }
</style>
</head>
<body>
  <form class="card" method="POST" action="${htmlAttr(LOGIN_PATH)}" autocomplete="on">
    <h1>Dashboard</h1>
    <p class="sub">Sign in to continue</p>
    ${err}
    <input type="hidden" name="next" value="${safe}">
    <label for="username">Username</label>
    <input id="username" name="username" type="text" autocomplete="username"
           autocapitalize="none" autocorrect="off" spellcheck="false" autofocus required>
    <label for="password">Password</label>
    <input id="password" name="password" type="password" autocomplete="current-password" required>
    <label class="remember"><input type="checkbox" name="remember" checked> Remember me for 30 days</label>
    <button type="submit">Sign in</button>
  </form>
</body>
</html>`
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
        const urlPath = (req.url || '').split('?')[0]

        // Logout: drop the cookies and bounce back to the login form.
        if (urlPath === LOGOUT_PATH) {
          clearSession(req, res)
          res.statusCode = 302
          res.setHeader('Location', '/')
          return res.end()
        }

        // Already carrying a valid session cookie -> let it through, no prompt.
        const cookies = parseCookies(req.headers['cookie'])
        if (cookies[AUTH_COOKIE] === token) {
          // Rolling session: on a full-page navigation from a remembered login,
          // slide the 30-day window forward so an actively-used session never
          // lapses. The user is re-prompted only after 30 days of NOT visiting,
          // not 30 days after first sign-in — so no repeated password entry.
          const accept = req.headers['accept'] || ''
          if (req.method === 'GET' && cookies[PERSIST_COOKIE] === '1' && accept.includes('text/html')) {
            setSession(req, res, token, true)
          }
          return next()
        }

        // A `Basic` header still works (curl/scripts) — mint a 30-day cookie.
        const header = req.headers['authorization'] || ''
        const [scheme, encoded] = header.split(' ')
        if (scheme === 'Basic' && encoded) {
          const [u, p] = Buffer.from(encoded, 'base64').toString().split(':')
          if (u === creds.user && p === creds.pass) {
            setSession(req, res, token, true)
            return next()
          }
        }

        // Login form submission: validate creds, set the cookie (persistent
        // when "Remember me" is ticked, else a session cookie), then redirect.
        if (req.method === 'POST' && urlPath === LOGIN_PATH) {
          return readBody(req, (body) => {
            const form = new URLSearchParams(body)
            const u = form.get('username')
            const p = form.get('password')
            const next = form.get('next')
            if (u === creds.user && p === creds.pass) {
              setSession(req, res, token, !!form.get('remember'))
              res.statusCode = 302
              res.setHeader('Location', safeNext(next))
              return res.end()
            }
            res.statusCode = 401
            res.setHeader('Content-Type', 'text/html; charset=utf-8')
            res.end(loginPage({ next, error: true }))
          })
        }

        // Unauthenticated browser navigation -> serve the login FORM (so the
        // password manager can remember creds) instead of a native prompt.
        const accept = req.headers['accept'] || ''
        if (req.method === 'GET' && accept.includes('text/html')) {
          res.statusCode = 401
          res.setHeader('Content-Type', 'text/html; charset=utf-8')
          return res.end(loginPage({ next: req.url }))
        }

        // Non-HTML (API/XHR/assets): keep the Basic challenge for scripts.
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
    // Port 80 needs cap_net_bind_service on the node binary — see docs/public-tunnel.md.
    port: 80,
    // Bind all interfaces so the Azure public IP can reach the dev server;
    // the Basic-Auth gate above is the only thing in front of it.
    host: '0.0.0.0',
    // Allow the tunnel's random *.trycloudflare.com Host header (Vite 5.4+
    // blocks unknown hosts). localhost/127.0.0.1 stay allowed automatically.
    allowedHosts: ['.trycloudflare.com', '4.194.19.91', 'www.youraikanban.com'],
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
