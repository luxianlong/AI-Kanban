import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'node:fs'
import path from 'node:path'

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
const CREDS_FILE = path.resolve(import.meta.dirname, '.public-auth.json')

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
        const header = req.headers['authorization'] || ''
        const [scheme, encoded] = header.split(' ')
        if (scheme === 'Basic' && encoded) {
          const [u, p] = Buffer.from(encoded, 'base64').toString().split(':')
          if (u === creds.user && p === creds.pass) return next()
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
