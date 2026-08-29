// Render Mermaid diagrams inside published pages (task #81).
//
// The backend injects this script (together with the vendored
// /vendor/mermaid.min.js) into an uploaded page whenever the HTML contains
// mermaid markup — see PageUploadService.withMermaidSupport. A page author can
// also include the two tags by hand; nothing here assumes the injector ran.
//
// Uploaded pages are served in a CSP sandbox (opaque origin), so everything has
// to work from same-origin static files with no /api access: mermaid itself is
// vendored under /vendor/ rather than pulled from a CDN, and that directory is
// public in the docs gate so anonymous readers get the diagrams too.
;(function () {
  // Every shape a diagram arrives in:
  //   <pre class="mermaid">…</pre> / <div class="mermaid">…</div>  (mermaid's own)
  //   <pre><code class="language-mermaid">…</code></pre>           (markdown → HTML)
  var CODE_BLOCKS = 'code.language-mermaid, code.lang-mermaid, pre > code.mermaid'

  // Unwrap markdown-style fenced blocks into the <pre class="mermaid"> element
  // mermaid looks for. textContent is already entity-decoded by the parser, so
  // `A --> B` survives an escaped `&gt;` intact.
  function normalise() {
    var codes = document.querySelectorAll(CODE_BLOCKS)
    for (var i = 0; i < codes.length; i++) {
      var code = codes[i]
      var target = code.parentElement && code.parentElement.tagName === 'PRE' ? code.parentElement : code
      var pre = document.createElement('pre')
      pre.className = 'mermaid'
      pre.textContent = code.textContent
      target.parentNode.replaceChild(pre, target)
    }
  }

  // A <pre> keeps its monospace framing until mermaid swaps in the SVG; drop the
  // decoration and let the drawing scale down on a phone.
  function addStyles() {
    var css =
      'pre.mermaid{background:none;border:0;padding:0;overflow:visible;white-space:normal;text-align:center}' +
      '.mermaid svg{max-width:100%;height:auto}' +
      '.mermaid[data-mermaid-error]{white-space:pre-wrap;text-align:left;color:#b91c1c;font:12px/1.5 ui-monospace,monospace}'
    var style = document.createElement('style')
    style.textContent = css
    document.head.appendChild(style)
  }

  function render() {
    normalise()
    var nodes = document.querySelectorAll('.mermaid')
    if (!nodes.length || !window.mermaid) return
    addStyles()
    var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
    window.mermaid.initialize({
      startOnLoad: false,
      // The page is untrusted HTML from an anonymous uploader; keep mermaid's
      // own sanitiser on and its click handlers off.
      securityLevel: 'strict',
      theme: dark ? 'dark' : 'default',
    })
    // suppressErrors: one malformed diagram must not stop the rest of the page
    // from rendering. The failed block keeps showing its source text.
    var run = window.mermaid.run({ querySelector: '.mermaid', suppressErrors: true })
    if (run && run.catch) run.catch(function (e) { console.error('mermaid:', e) })
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', render)
  } else {
    render()
  }
})()
