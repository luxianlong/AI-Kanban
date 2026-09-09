// The standalone page behind the "Maximize" button on a result.
//
// A result is read in a 340px scroll box wedged between a graph and the next
// step, which is fine for glancing and hopeless for reading a 13k-character
// answer. Maximize lifts one result out of the app entirely: a written-out HTML
// document in its own tab, with no script and no connection back here. Temporary
// in the literal sense — it exists in that tab and nowhere else, so closing the
// tab is the whole of the cleanup, and nothing is added to the board or the
// server to be tidied up later.
//
// Everything here is string in / string out so it tests in bare node, the same
// reason workflowGraph.js and markdownParts.js are their own modules. The one
// thing it does NOT do is render Markdown — that is the app's own <Markdown>
// component, serialised by resultWindow.jsx, so the tab shows exactly what the
// panel showed rather than a second renderer that drifts from it.

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

export function escapeHtml(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => ESCAPES[c])
}

/**
 * Copy the app's own stylesheets into the new document, given the <style> and
 * <link> elements of this one.
 *
 * Copying rather than restating them is deliberate: in dev Vite injects the CSS
 * as inline <style> tags and a build emits a hashed <link>, so there is no one
 * URL to point at — and a hand-written copy of the Markdown rules would drift
 * from the panel it is supposed to look like. A <link>'s `href` property is
 * already absolute, which matters because the page is written into about:blank
 * and has no URL of its own to resolve against.
 */
export function styleTags(nodes) {
  const out = []
  for (const node of nodes || []) {
    const tag = (node.tagName || '').toLowerCase()
    if (tag === 'link') {
      const rel = (node.rel || node.getAttribute?.('rel') || '').toLowerCase()
      if (!rel.split(/\s+/).includes('stylesheet')) continue
      const href = node.href || node.getAttribute?.('href')
      if (href) out.push(`<link rel="stylesheet" href="${escapeHtml(href)}">`)
    } else if (tag === 'style') {
      const css = node.textContent || ''
      // A closing tag inside the CSS would end the <style> early and dump the
      // rest of the rules into the page as text.
      if (css.trim()) out.push(`<style>${css.replace(/<\/(style)/gi, '<\\/$1')}</style>`)
    }
  }
  return out
}

// Page chrome only — one readable column and a header. Everything that styles
// the result itself (.md-*) comes from the app's stylesheet, copied in above.
export const RESULT_PAGE_CSS = `
.rp-page { max-width: 880px; margin: 0 auto; padding: 34px 20px 72px; }
.rp-head { margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid var(--hair, #e3e7ee); }
.rp-title { margin: 0; font-size: 1.4rem; line-height: 1.3; font-weight: 700; }
.rp-sub { margin: 6px 0 0; font-size: 0.85rem; color: var(--muted, #6b7280); }
.rp-meta { display: flex; flex-wrap: wrap; gap: 6px; margin: 12px 0 0; padding: 0; list-style: none; }
.rp-meta li {
  font-size: 0.72rem; font-weight: 600; letter-spacing: 0.03em;
  padding: 3px 8px; border-radius: 999px;
  color: var(--muted, #6b7280); background: var(--col, #fff);
  border: 1px solid var(--hair, #e3e7ee);
}
.rp-body {
  background: #fff; border: 1px solid var(--hair, #e3e7ee);
  border-radius: 10px; padding: 24px 26px;
  font-size: 0.95rem; line-height: 1.65;
}
.rp-raw { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono, ui-monospace, monospace); font-size: 0.85rem; }
/* Saving or printing the page is half of why you would want it on its own, and
   the card border prints as an empty box around page two. */
@media print {
  body { background: #fff; }
  .rp-page { max-width: none; padding: 0; }
  .rp-body { border: 0; padding: 0; }
}
`

/**
 * The whole document, ready for document.write.
 *
 * `bodyHtml` is inserted verbatim — it is the app's own renderer's output, which
 * never emits raw input (see Markdown.jsx). Text that has NOT been through it
 * must arrive escaped; rawText() below is the way to do that. `styles` are the
 * tags from styleTags(); the page chrome goes last so it wins on a tie.
 */
export function buildResultPage({
  title, subtitle = '', meta = [], bodyHtml = '', styles = [], baseHref = '',
}) {
  const heading = title || 'Result'
  const chips = (meta || []).filter(Boolean).map((m) => `<li>${escapeHtml(m)}</li>`).join('')
  const head = [
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    baseHref ? `<base href="${escapeHtml(baseHref)}">` : '',
    `<title>${escapeHtml(heading)}</title>`,
    ...(styles || []),
    `<style>${RESULT_PAGE_CSS}</style>`,
  ].filter(Boolean)
  return [
    '<!doctype html>',
    '<html lang="en">',
    `<head>${head.join('')}</head>`,
    '<body class="rp">',
    '<div class="rp-page">',
    '<header class="rp-head">',
    `<h1 class="rp-title">${escapeHtml(heading)}</h1>`,
    subtitle ? `<p class="rp-sub">${escapeHtml(subtitle)}</p>` : '',
    chips ? `<ul class="rp-meta">${chips}</ul>` : '',
    '</header>',
    `<article class="md rp-body">${bodyHtml}</article>`,
    '</div>',
    '</body>',
    '</html>',
  ].filter(Boolean).join('')
}

/** Plain text (a failure trace, a log) as a body — monospaced and escaped. */
export function rawText(text) {
  return `<pre class="rp-raw">${escapeHtml(text)}</pre>`
}
