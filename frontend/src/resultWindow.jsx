// Opening the page that resultPage.js builds. Split from it because this half
// needs a DOM and React and so can't run in the bare-node test suite.

import { flushSync } from 'react-dom'
import { createRoot } from 'react-dom/client'
import Icon from './Icon.jsx'
import Markdown from './Markdown.jsx'
import { buildResultPage, rawText, styleTags } from './resultPage.js'

/**
 * Serialise the app's own <Markdown> to HTML, by rendering it into a detached
 * node and reading the result back. flushSync is what makes that synchronous —
 * without it the div is still empty when we read it, and the tab opens blank.
 *
 * Doing it this way (rather than a second Markdown-to-HTML function) is the
 * point: the tab shows exactly what the panel shows, including the diff, table
 * and colour handling added for workflow results, and cannot fall behind it.
 */
export function markdownToHtml(text) {
  const host = document.createElement('div')
  const root = createRoot(host)
  try {
    flushSync(() => root.render(<Markdown text={text} />))
    // <Markdown> renders its own .md wrapper, and the page puts the body in one
    // too. Nested, the ".md > :first-child" margin reset lands on the wrapper
    // instead of the first heading, so hand back the blocks themselves.
    const rendered = host.firstElementChild
    return rendered?.classList.contains('md') ? rendered.innerHTML : host.innerHTML
  } finally {
    // Unmounting synchronously from inside the same tick React just flushed
    // upsets it, so let the current task finish first.
    setTimeout(() => root.unmount(), 0)
  }
}

/**
 * Open one result in its own tab. Must be called straight from a click, or the
 * browser takes it for an unsolicited pop-up and blocks it.
 *
 * `mono` renders the text as-is instead of as Markdown — for a failure trace or
 * a log, where the line breaks are the meaning.
 *
 * Returns false if the tab could not be opened, having said so.
 */
export function openResultPage({ title, subtitle, meta, text, mono = false }) {
  const win = window.open('', '_blank')
  if (!win) {
    window.alert('Your browser blocked the pop-up. Allow pop-ups for this site to open a result in its own tab.')
    return false
  }
  const html = buildResultPage({
    title,
    subtitle,
    meta,
    bodyHtml: mono ? rawText(text) : markdownToHtml(text),
    styles: styleTags(document.querySelectorAll('style, link[rel~="stylesheet"]')),
    baseHref: `${window.location.origin}/`,
  })
  win.document.write(html)
  win.document.close()
  return true
}

/**
 * The control itself, shared by the board's task result and a workflow step's
 * output — the same affordance in both places because it means the same thing.
 * Sits inside a .section-label, which is already a flex row.
 */
export function MaximizeButton({ title, subtitle, meta, text, mono = false, what = 'result' }) {
  return (
    <button
      type="button"
      className="label-action"
      title={`Open this ${what} on a page of its own`}
      onClick={() => openResultPage({ title, subtitle, meta, text, mono })}
    >
      <Icon name="maximize" size={12} /> Maximize
    </button>
  )
}
