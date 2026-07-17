// Self-contained regression test for docLinks.js — run with `npm test` (from
// frontend/) or `node src/docLinks.test.mjs`. No test runner is wired into the
// project, so this file is its own harness: it asserts and exits non-zero on any
// failure. It guards the path-boundary logic, which is regex-heavy and easy to
// regress (a trailing '.' that ends a sentence must still link the path).
import { buildDocIndex, linkifyDocRefs } from './docLinks.js'

// A small fixed manifest so the test doesn't depend on the live docs set.
const manifest = [
  { id: 'arch', title: 'Architecture', href: '/docs/architecture.html',
    file: 'docs/architecture.md',
    paths: ['docs/architecture.md', 'frontend/public/docs/architecture.html'] },
  { id: 'sg', title: '新加坡攻略', href: '/singapore.html',
    file: 'docs/singapore-travel-guide.md',
    paths: ['docs/singapore-travel-guide.md'] },
]
const idx = buildDocIndex(manifest)
const H = '/docs/architecture.html', T = 'Architecture'

const cases = [
  // trailing sentence punctuation must not defeat the link
  ['I created docs/architecture.md.', `I created [${T}](${H}).`],
  ['Published to /docs/architecture.html.', `Published to [${T}](${H}).`],
  ['See docs/architecture.md, more below', `See [${T}](${H}), more below`],
  ['Done: docs/architecture.md!', `Done: [${T}](${H})!`],
  ['(docs/architecture.md)', `([${T}](${H}))`],
  ['ends the line docs/architecture.md', `ends the line [${T}](${H})`],
  // surface forms
  ['a `docs/architecture.md` span', `a [${T}](${H}) span`],
  ['a [label](docs/architecture.md) link', `a [label](${H}) link`],
  ['the /docs/architecture.html page', `the [${T}](${H}) page`],
  // must NOT link: longer/different file, non-published source, near-miss extension
  ['docs/architecture.md.bak stays', 'docs/architecture.md.bak stays'],
  ['docs/architecture.mdx stays', 'docs/architecture.mdx stays'],
  ['edited App.jsx and scripts/deploy.sh', 'edited App.jsx and scripts/deploy.sh'],
  // other manifest entry, href remap
  ['guide at docs/singapore-travel-guide.md.', 'guide at [新加坡攻略](/singapore.html).'],
  // no-op guards
  ['', ''],
]

let failed = 0
for (const [input, expected] of cases) {
  const got = linkifyDocRefs(input, idx)
  if (got !== expected) {
    failed++
    console.error('FAIL', JSON.stringify(input))
    console.error('  expected', JSON.stringify(expected))
    console.error('  got     ', JSON.stringify(got))
  }
}
// index guards
if (buildDocIndex(null).size !== 0) { failed++; console.error('FAIL buildDocIndex(null)') }
if (linkifyDocRefs('x', new Map()) !== 'x') { failed++; console.error('FAIL empty index no-op') }

if (failed) { console.error(`\n${failed} test(s) failed`); process.exit(1) }
console.log(`docLinks: ${cases.length + 2} checks passed`)
