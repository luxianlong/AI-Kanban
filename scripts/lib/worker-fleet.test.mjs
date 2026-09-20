// How many workers this box runs, and what each of them is allowed to take.
//
// The server now hands out the independent branches of one workflow run at the same time
// (a → b, c, d), and a worker runs one session at a time, so the fan-out is only as wide as
// the fleet. WORKER_COUNT in scripts/env.sh is that number. The two rules worth pinning are
// both invisible until they bite:
//
//   * worker 1 is byte-for-byte what a single-worker box always launched — same name, same
//     log, same capabilities — so raising nothing changes nothing;
//   * every worker after the first has `repo:<name>` struck out, because a project-improvement
//     task edits THIS git checkout and runs the guarded deploy in it. Two at once would be two
//     Claude sessions branching over each other in one working tree.
//
// Driven by really sourcing env.sh in bash: these are shell functions, and a JS re-implementation
// of them would pass while the file they describe was broken. REPO is pointed at an empty temp
// directory so the machine's own gitignored scripts/local.env cannot reach in and change the
// answers — this file is run on every box that clones the repo.
import assert from 'node:assert'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const envSh = join(here, '..', 'env.sh')

/** Run a snippet with env.sh sourced, in a sandbox REPO with no local.env of its own. */
function sh(snippet, vars = {}) {
  const sandbox = mkdtempSync(join(tmpdir(), 'kanban-fleet-'))
  try {
    return execFileSync('bash', ['-euo', 'pipefail', '-c',
      `source ${JSON.stringify(envSh)} >/dev/null 2>&1\n${snippet}`], {
      encoding: 'utf8',
      env: {
        ...process.env,
        REPO: sandbox,
        WORKER_NAME: 'testbox',
        WORKER_CAPABILITIES: 'general,workflow,repo:Dashboard',
        WORKER_LOG: join(sandbox, 'worker.log'),
        KANBAN_WORKER_TOKEN: 'test-token',
        WORKER_ENABLED: '1',
        ...vars,
      },
    }).trim()
  } finally {
    rmSync(sandbox, { recursive: true, force: true })
  }
}

// --- the default is one worker, launched exactly as before -------------------

assert.equal(sh('echo "$WORKER_COUNT"'), '1')
// A typo must not silently leave the box with no worker: `seq 1 three` produces nothing, the
// start loop runs zero times, and every script afterwards still reports success.
assert.equal(sh('echo "$WORKER_COUNT"', { WORKER_COUNT: 'three' }), '1')
assert.equal(sh('echo "$WORKER_COUNT"', { WORKER_COUNT: '0' }), '1')
assert.equal(sh('echo "$WORKER_COUNT"', { WORKER_COUNT: '4' }), '4')
assert.equal(sh('worker_name_for 1'), 'testbox')
assert.equal(sh('worker_capabilities_for 1'), 'general,workflow,repo:Dashboard')
assert.ok(sh('worker_log_for 1').endsWith('/worker.log'))

// --- the extras are the same worker minus the repo ---------------------------

assert.equal(sh('worker_name_for 2'), 'testbox-2')
assert.equal(sh('worker_name_for 3'), 'testbox-3')
assert.ok(sh('worker_log_for 2').endsWith('/worker-2.log'),
  'each worker writes its own log, or two JVMs interleave into one file')

// The point of the whole rule: a second worker may take workflow nodes (that is what makes a
// fan-out go wide) and general tasks, but never a project-improvement task.
assert.equal(sh('worker_capabilities_for 2'), 'general,workflow')
assert.equal(sh('worker_capabilities_for 9'), 'general,workflow')
assert.equal(sh('worker_capabilities_for 2', { WORKER_CAPABILITIES: 'repo:Dashboard' }), '',
  'a box that only does repo work has nothing to give a second worker')
assert.equal(sh('worker_capabilities_for 2', { WORKER_CAPABILITIES: 'workflow' }), 'workflow')

// --- and the launch actually uses them ---------------------------------------

// `setsid` and `kill_worker` are shadowed: the first so nothing is really launched, the second
// because it kills by pattern and this test runs on a machine with a live worker on it.
const launcher = `
setsid() { echo "$*"; }
kill_worker() { :; }
start_worker
wait 2>/dev/null || true
`
const fleetOf = (count) => {
  const sandbox = mkdtempSync(join(tmpdir(), 'kanban-fleet-'))
  const out = execFileSync('bash', ['-euo', 'pipefail', '-c',
    `source ${JSON.stringify(envSh)} >/dev/null 2>&1\n${launcher}`], {
    encoding: 'utf8',
    env: {
      ...process.env,
      REPO: sandbox,
      WORKER_NAME: 'testbox',
      WORKER_CAPABILITIES: 'general,workflow,repo:Dashboard',
      WORKER_LOG: join(sandbox, 'worker.log'),
      KANBAN_WORKER_TOKEN: 'test-token',
      WORKER_ENABLED: '1',
      WORKER_COUNT: String(count),
    },
  })
  // Each launch line lands in that worker's own log, which is how we know the routing is right.
  const logs = []
  for (let n = 1; n <= 6; n += 1) {
    const path = n === 1 ? join(sandbox, 'worker.log') : join(sandbox, `worker-${n}.log`)
    if (existsSync(path)) logs.push(readFileSync(path, 'utf8'))
  }
  rmSync(sandbox, { recursive: true, force: true })
  return { stdout: out, logs }
}

const one = fleetOf(1)
assert.equal(one.logs.length, 1, 'the default box still launches exactly one worker')
assert.match(one.logs[0], /--worker\.name=testbox\b/)
assert.match(one.logs[0], /--worker\.capabilities=general,workflow,repo:Dashboard/)

const three = fleetOf(3)
assert.equal(three.logs.length, 3, 'WORKER_COUNT=3 launches three JVMs, each with its own log')
assert.match(three.logs[0], /--worker\.name=testbox /)
assert.match(three.logs[1], /--worker\.name=testbox-2 /)
assert.match(three.logs[2], /--worker\.name=testbox-3 /)
assert.equal(three.logs.filter((l) => l.includes('repo:Dashboard')).length, 1,
  'exactly one worker on a box may touch the working tree')

// Off is off, however many were asked for.
assert.ok(!sh(launcher, { WORKER_ENABLED: '0', WORKER_COUNT: '4' }).includes('--worker.name'),
  'WORKER_ENABLED=0 launches nothing at all')

// --- stopping them ------------------------------------------------------------

// Not executed: kill_worker finds workers by command-line pattern, so running it here would
// stop the real one on this machine. Read instead — the failure it guards against is a deploy
// that replaces the jar, stops worker 1, and leaves workers 2..N running the old code.
const envSource = readFileSync(envSh, 'utf8')
const killBody = envSource.slice(envSource.indexOf('kill_worker() {'),
  envSource.indexOf('start_worker() {'))
assert.ok(killBody.includes('worker_pids'), 'kill_worker must stop every worker, not the first')
assert.ok(!killBody.replace(/worker_pids/g, '').includes('worker_pid'),
  'kill_worker must not fall back to the single-pid helper')

console.log('✔ worker-fleet.test.mjs — all assertions passed')
