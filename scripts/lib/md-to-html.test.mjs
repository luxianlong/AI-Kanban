// Self-contained regression test for the Markdown converter's diagram support —
// run with `node scripts/lib/md-to-html.test.mjs` (or via `npm test` from
// frontend/). Like docsGate.test.mjs it is its own harness: it asserts and exits
// non-zero on the first failure.
//
// What it guards (task #82): a ```mermaid block in a doc must reach the page as
// a <pre class="mermaid"> AND the page must actually load a renderer, otherwise
// the reader gets raw `graph TD` source instead of a drawing.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { renderMarkdown } from './md-to-html.mjs'

const ROOT = path.resolve(import.meta.dirname, '..', '..')

// --- ```mermaid fences become diagrams ---------------------------------------
{
  const { body, hasMermaid } = renderMarkdown('# T\n\n```mermaid\ngraph TD\n  A --> B\n```\n')
  assert.equal(hasMermaid, true, 'a ```mermaid block must flag the page as needing the runtime')
  assert.match(body, /<pre class="mermaid">graph TD\n {2}A --&gt; B<\/pre>/)
  // Escaped, never inline-formatted: the arrow survives as text (the renderer
  // reads textContent, which the parser decodes back to `-->`), and markup in a
  // diagram can't break out of the block.
  assert.doesNotMatch(body, /<em>|<strong>/)
}

// ~~~ fences are the same thing.
{
  const { body, hasMermaid } = renderMarkdown('~~~mermaid\nflowchart LR\n  A-->B\n~~~\n')
  assert.equal(hasMermaid, true)
  assert.match(body, /<pre class="mermaid">/)
}

// A doc with a mermaid diagram is not confused by a *code* block in the same doc,
// and a doc with no diagram must not pull the runtime in.
{
  const { body, hasMermaid } = renderMarkdown('```js\nconst a = 1 < 2\n```\n')
  assert.equal(hasMermaid, false)
  assert.match(body, /<pre class="code"><code class="language-js">const a = 1 &lt; 2<\/code><\/pre>/)
}

// --- the doc page wires up a renderer that actually exists --------------------
// The generator loads mermaid from same-origin /vendor/ files (task #82) rather
// than a CDN, so a diagram still renders when jsdelivr is blocked or the box is
// offline. That only holds while the vendored files are really there.
{
  const generator = fs.readFileSync(path.join(ROOT, 'scripts', 'build-docs-site.mjs'), 'utf8')
  for (const src of ['/vendor/mermaid.min.js', '/vendor/mermaid-init.js']) {
    assert.ok(generator.includes(`<script src="${src}"`), `doc pages must load ${src}`)
    assert.ok(
      fs.existsSync(path.join(ROOT, 'frontend', 'public', src.replace(/^\//, ''))),
      `${src} must exist in frontend/public`,
    )
  }
  assert.doesNotMatch(generator, /cdn\.jsdelivr\.net[^\n]*mermaid/, 'no third-party mermaid request')
}

console.log('✔ md-to-html.test.mjs — all assertions passed')
