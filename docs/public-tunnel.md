# Exposing the dashboard as a public website

How to put this locally-running app on a public URL, gated by a password.
Two options: a **quick tunnel** (zero setup, ephemeral URL — the current default)
and a **named tunnel** (stable URL, needs a Cloudflare account + domain).

The tool is [`cloudflared`](https://github.com/cloudflare/cloudflared), installed
at `~/bin/cloudflared` (user-local binary, no sudo). Neither option touches the
backend config; both tunnel the Vite dev server on **:5173**, and the public only
reaches the Spring backend through Vite's `/api` proxy.

---

## The auth gate (shared by both options)

`frontend/vite.config.js` registers a **Basic-Auth middleware that runs before the
`/api` proxy**, so one gate protects the UI *and* the backend. Credentials live in:

    frontend/.public-auth.json      # gitignored — never committed
    { "user": "...", "pass": "..." }

- **Editing this file takes effect live** (it's re-read on mtime change — no restart).
- **If the file is absent, the gate is OFF** — frictionless local dev. So *only expose
  a tunnel when this file exists.*
- `vite.config.js` also sets `allowedHosts: ['.trycloudflare.com']` because Vite 5.4+
  rejects unknown Host headers; a named tunnel on your own domain needs its hostname
  added there too.

**Change the password:** edit `frontend/.public-auth.json`. That's it.

---

## Option A — Quick tunnel (no account, ephemeral URL)

**Start it:**

    nohup ~/bin/cloudflared tunnel --url http://localhost:5173 --no-autoupdate \
      > /tmp/cloudflared.log 2>&1 &

**Get the public URL** (printed a few seconds after launch):

    grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' /tmp/cloudflared.log | head -1

**Verify it demands auth** (replace URL + creds):

    curl -s -o /dev/null -w '%{http_code}\n' "$URL/"                 # want 401
    curl -s -o /dev/null -w '%{http_code}\n' -u user:pass "$URL/"    # want 200

**Stop it:**

    pkill -f 'cloudflared tunnel --url'      # or: kill <pid>

### What survives a restart

- **Restart just the app** (Vite / backend) while `cloudflared` keeps running →
  **same URL keeps working.** The tunnel points at `localhost:5173`; it errors while
  the app is down, then auto-recovers.
- **Reboot the machine, or kill `cloudflared`** → tunnel is gone (nothing here is a
  service — Vite, backend, and tunnel are all plain processes). A fresh `cloudflared`
  gives a **brand-new random URL.** A stable URL requires Option B.

---

## Option B — Named tunnel (stable URL, needs Cloudflare account + domain)

Gives a permanent `https://dashboard.yourdomain.com`. Requires a domain already
added to a Cloudflare account (its nameservers on Cloudflare).

1. **One-time interactive login** (opens a browser, pick your zone):

       ~/bin/cloudflared tunnel login          # writes ~/.cloudflared/cert.pem

2. **Run the setup helper** (creates tunnel, writes `~/.cloudflared/config.yml`,
   routes DNS):

       ~/bin/setup-named-tunnel.sh dashboard.yourdomain.com

   Then add that hostname to `allowedHosts` in `frontend/vite.config.js`.

3. **Start it:**  `~/bin/cloudflared tunnel run dashboard`

4. **Auto-start on boot** (optional): `cloudflared service install` (system service,
   needs sudo) or a `systemd --user` unit with lingering enabled.

---

## Security notes

- The worker runs `claude` on **this machine**. With the default
  `worker.extra-args` (no `--dangerously-skip-permissions`), a headless session
  refuses tool actions needing approval — so a public visitor **can't** edit files or
  run arbitrary commands, but **can** queue prompts that burn your Claude tokens and
  do read-only recon in `worker.working-directory`. Share the password only with people
  you trust; rotate it by editing `.public-auth.json`.
- If you ever set `--dangerously-skip-permissions`, **do not expose a tunnel** without
  much stronger auth (a named tunnel behind Cloudflare Access SSO), since visitors could
  then make the worker act autonomously on this box.
