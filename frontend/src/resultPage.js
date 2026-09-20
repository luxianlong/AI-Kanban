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

// The page's own look. Two things it is not allowed to just inherit:
//
// 1. Width. 880px was one column for everything, and the content is not one
//    kind of thing. Prose wants ~75 characters a line; a four-column table of
//    sentences and a 120-column code block want the whole sheet. At 880px the
//    prose was already running to 92 characters (52 CJK) AND the code was
//    scrolling sideways, so widening the one column would have fixed the code
//    by making the prose worse. Instead: the text is clamped to a measure,
//    tables, code and logs are let out past it, and the sheet hugs whichever is
//    widest — so a result that is nothing but prose is a page, not a 1180px
//    white rectangle with a ribbon of text down the middle of it. Everything on
//    the page shares one left edge.
//
// 2. Type size. The .md-* rules come from the board's stylesheet, where a
//    result lives in a 340px scroll box and the scale (--f-*) is tuned for
//    density — and stated in rem, so it does not move when this page asks for
//    bigger text. Inherited as-is it put h3 at 14.7px UNDER a 15.2px paragraph,
//    and tables and code at 12.5px. Everything below is restated in em off
//    --rp-font, so the whole result scales as one thing: at 18px prose that is
//    h2 25.6 / h3 21.2 / table 16.9 / code 15.5.
export const RESULT_PAGE_CSS = `
.rp {
  --rp-font: 1.125rem;    /* 18px — this page is read, not skimmed */
  --rp-measure: 48rem;    /* the text column: ~76 Latin / ~42 CJK a line */
  --rp-wide: 74rem;       /* the widest the sheet may grow for a table or a log */
  --rp-gutter: 26px;      /* sheet to window */
  --rp-pad: 34px;         /* sheet edge to content */
}
/* The sheet. fit-content works here precisely because every child below is
   clamped, so the widest thing the sheet can be asked for is the widest thing
   actually in it. */
.rp-page {
  width: fit-content;
  min-width: min(100% - 2 * var(--rp-gutter), var(--rp-measure) + 2 * var(--rp-pad));
  max-width: min(100% - 2 * var(--rp-gutter), var(--rp-wide));
  margin: var(--rp-gutter) auto 72px;
  padding: 38px var(--rp-pad) 44px;
  background: #fff;
  border: 1px solid var(--hair, #e3e7ee);
  border-radius: 14px;
}

.rp-head { margin-bottom: 30px; padding-bottom: 20px; border-bottom: 1px solid var(--hair, #e3e7ee); }
.rp-head > * { max-width: var(--rp-measure); }
/* Sized off --rp-font like the content headings are, because it has to stay
   bigger than the largest of them. A fixed rem here did not: at the narrow
   breakpoint the title shrank to 24px while an h2 in the result stayed 25.6,
   and the page was announced by a smaller word than the section under it. */
.rp-title {
  margin-block: 0; font-size: calc(var(--rp-font) * 1.7);
  line-height: 1.25; font-weight: 700; letter-spacing: -0.01em;
}
.rp-sub { margin-block: 9px 0; font-size: 1rem; line-height: 1.45; color: var(--muted, #6b7280); }
.rp-meta { display: flex; flex-wrap: wrap; gap: 7px; margin-block: 16px 0; padding: 0; list-style: none; }
.rp-meta li {
  font-size: 0.8rem; font-weight: 600; letter-spacing: 0.02em;
  padding: 4px 10px; border-radius: 999px;
  color: var(--muted, #6b7280); background: var(--col, #f5f7fb);
  border: 1px solid var(--hair, #e3e7ee);
}

.rp-body { font-size: var(--rp-font); line-height: 1.7; }
/* Clamp the text; let out the blocks whose width IS part of their content. */
.rp-body > * { max-width: var(--rp-measure); }
.rp-body > .md-table-wrap,
.rp-body > .md-pre,
.rp-body > .rp-raw { max-width: none; }
.rp-body > :first-child { margin-top: 0; }
.rp-body > :last-child { margin-bottom: 0; }

/* --- the Markdown internals, re-scaled in em (see note 2 above) ------------ */
.rp-body .md-h { margin-block: 1.7em 0.45em; line-height: 1.3; }
.rp-body h2.md-h { font-size: 1.42em; }
.rp-body h3.md-h { font-size: 1.18em; }
.rp-body h4.md-h,
.rp-body h5.md-h,
.rp-body h6.md-h { font-size: 1.04em; }
.rp-body .md-p { margin-block: 0.85em; }
.rp-body .md-list { margin-block: 0.85em; padding-left: 1.5em; }
.rp-body .md-list li { margin-block: 0.3em; }
.rp-body .md-quote { margin-block: 1.1em; padding: 0.25em 1em; }
.rp-body .md-hr { margin-block: 2em; }
.rp-body .md-table-wrap { margin-block: 1.5em; }
/* min-width: a two-column table would otherwise end a third of the way across
   a sheet the prose beside it already fills. Held to at least the measure it
   lines up with the paragraph above, and still grows past it when it needs to. */
.rp-body .md-table { font-size: 0.94em; min-width: min(100%, var(--rp-measure)); }
.rp-body .md-table th,
.rp-body .md-table td { padding: 8px 12px; }
.rp-body .md-pre { margin-block: 1.5em; padding: 16px 18px; border-radius: 10px; }
.rp-body .md-pre code { font-size: 0.86em; line-height: 1.6; }
/* The full-bleed +/- stripe is measured off .md-pre's padding, which moved. */
.rp-body .md-diff-line { margin: 0 -18px; padding: 0 18px 0 calc(18px + 1.4em); }

.rp-raw {
  margin: 0; white-space: pre-wrap; word-break: break-word;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.88em; line-height: 1.6;
}

@media (max-width: 720px) {
  .rp { --rp-gutter: 10px; --rp-pad: 17px; }
  .rp-page { margin-bottom: 48px; padding-top: 26px; padding-bottom: 30px; border-radius: 10px; }
  .rp-title { font-size: calc(var(--rp-font) * 1.5); }
  .rp-head { margin-bottom: 22px; padding-bottom: 16px; }
}
/* Saving or printing the page is half of why you would want it on its own, and
   the card border prints as an empty box around page two. Paper is narrower
   than the measure anyway, so the clamp comes off with it. */
@media print {
  body { background: #fff; }
  .rp { --rp-measure: none; }
  .rp-page { width: auto; min-width: 0; max-width: none; margin: 0; padding: 0; border: 0; }
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
