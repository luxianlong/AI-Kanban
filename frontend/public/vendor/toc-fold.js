// Make a published page's contents list foldable (task #426).
//
// The generated articles under /docs/ got this in task #224, where the catalog
// heading itself is the control. Pages under /p/ never did: the 典籍 readers and
// the uploaded long-form articles each ship their own hand-written sidebar, and
// on a wide screen it stands open forever — a 236–256px column of links the
// reader has already used, permanently narrowing the prose.
//
// The backend injects this script into an uploaded page whose markup has a
// contents nav — see PageUploadService.withTocFold. A page author can also add
// the tag by hand; nothing here assumes the injector ran.
//
// Two constraints shape everything below:
//
//   1. The host page's CSS is not ours. Pages disagree about the wrapper class
//      (.layout vs .wrap), the column width (236px vs 256px) and the breakpoint
//      (940px vs 1000px), so nothing here may hard-code any of them. The fold
//      reads the *computed* grid instead and only reclaims the column when the
//      browser actually resolved one.
//   2. Uploaded pages are served in a CSP sandbox with no `allow-same-origin`
//      (see UPLOADED_PAGE_CSP in vite.config.js), which puts them in an opaque
//      origin where touching localStorage THROWS rather than returning null.
//      Every access goes through store() below; an unguarded one would take the
//      whole script down and leave the page with a dead button.
;(function () {
  var KEY = 'docs:toc-collapsed'
  var COLLAPSED = 'collapsed'

  // Opaque-origin safe storage: falls back to remembering nothing, which is the
  // right degradation — the fold still works, it just doesn't outlive the page.
  var store = {
    get: function () {
      try {
        return window.localStorage.getItem(KEY)
      } catch (e) {
        return null
      }
    },
    set: function (v) {
      try {
        window.localStorage.setItem(KEY, v)
      } catch (e) {
        /* sandboxed: preference is per-page only */
      }
    },
  }

  function addStyles() {
    var css =
      // The control host is all that survives a fold; everything else in the
      // nav (headings, lists, bare <a> children) goes. Written as "not the
      // host" rather than a list of tags because the two page families put
      // different elements in there.
      '.toc.collapsed > *:not(.toc-fold-head){display:none!important}' +
      // The nav is sticky and scrollable when open; folded it is one line, and
      // a leftover max-height/overflow would clip the caret.
      '.toc.collapsed{max-height:none!important;overflow:visible!important}' +
      // Inherit, don't impose: the button sits inside the page's own heading
      // when there is one, so it must not bring a font or colour of its own.
      '.toc-fold{display:inline-flex;align-items:center;gap:7px;width:100%;' +
      'background:none;border:0;padding:0;margin:0;cursor:pointer;' +
      'font:inherit;color:inherit;letter-spacing:inherit;text-transform:inherit;text-align:left}' +
      '.toc-fold:hover{opacity:.7}' +
      '.toc-fold-caret{font-size:.8em;line-height:1;transition:transform .18s}' +
      '.toc.collapsed .toc-fold-caret{transform:rotate(-90deg)}' +
      // Folded, the caret is the WHOLE control (task #457). Keeping the label
      // left "目录" / "本页目录" standing in the margin — 41px on the 典籍
      // readers, 68px on the uploaded articles — which is most of what a fold
      // was supposed to give back. Hiding it takes the column to the glyph.
      '.toc.collapsed .toc-fold-label{display:none}' +
      // An 8px arrow is not a click target, so the button keeps padding of its
      // own. It stays INSIDE the button: a negative margin would hang the hit
      // area outside the nav, which is a scroll box on both page families and
      // clips what leaves it.
      '.toc.collapsed .toc-fold{width:auto;gap:0;padding:5px}' +
      '.toc.collapsed .toc-fold-caret{font-size:13px}' +
      // The host is the only survivor, so its spacing and any rule the page
      // hung under it are now separating nothing. This has to out-shout the
      // page's own `.toc h2 {…}`, which is why it is !important and not tidy.
      '.toc.collapsed .toc-fold-head{margin:0!important;padding:0!important;border:0!important}' +
      // Only used for the no-heading family, where we supply the host element.
      '.toc-fold-head.toc-fold-synth{margin:0 0 10px;font-size:12px;letter-spacing:.14em;' +
      'text-transform:uppercase;opacity:.75}'
    var style = document.createElement('style')
    style.textContent = css
    document.head.appendChild(style)
  }

  // The contents nav, or null. A page that already has the /docs/ generator's
  // own fold (task #224) is left alone — two controls for one state is the bug
  // that test guards against.
  function findToc() {
    if (document.querySelector('.toc-title')) return null
    var toc = document.querySelector('nav.toc, .toc')
    if (!toc || toc.querySelector('.toc-fold')) return null
    // A contents list with no links is somebody else's element wearing the
    // class; don't put a control on it.
    return toc.querySelector('a[href^="#"]') ? toc : null
  }

  // Give the nav a clickable host for the caret. If the page wrote its own
  // heading ("本页目录"), the button moves *inside* it so the page's `.toc h2`
  // styling still lands; otherwise we supply a minimal one.
  //
  // The words always go in a .toc-fold-label wrapper, because folding hides
  // them: a collapsed control is the caret alone, which is also why the button
  // carries an aria-label — with the label gone and the caret aria-hidden it
  // would otherwise have no accessible name at all.
  function mountControl(toc) {
    var btn = document.createElement('button')
    btn.className = 'toc-fold'
    btn.type = 'button'
    btn.setAttribute('aria-expanded', 'true')

    var label = document.createElement('span')
    label.className = 'toc-fold-label'

    var head = toc.firstElementChild
    if (head && /^H[1-6]$/.test(head.tagName)) {
      while (head.firstChild) label.appendChild(head.firstChild)
      btn.appendChild(label)
      head.appendChild(btn)
    } else {
      head = document.createElement('div')
      head.className = 'toc-fold-synth'
      label.appendChild(document.createTextNode('目录'))
      btn.appendChild(label)
      head.appendChild(btn)
      toc.insertBefore(head, toc.firstChild)
    }
    head.classList.add('toc-fold-head')
    btn.setAttribute('aria-label', (label.textContent || '目录').trim() || '目录')

    var caret = document.createElement('span')
    caret.className = 'toc-fold-caret'
    caret.setAttribute('aria-hidden', 'true')
    caret.textContent = '▾'
    btn.appendChild(caret)

    if (!toc.id) toc.id = 'toc'
    btn.setAttribute('aria-controls', toc.id)
    return btn
  }

  // Hand the freed column back to the prose.
  //
  // The width lives on the *parent* grid, and each page sizes it differently,
  // so instead of guessing we clear any override, ask the browser what it
  // resolved, and only act when it really laid out two or more columns with the
  // nav in the first one. Below the page's own breakpoint the wrapper collapses
  // to a single column and this correctly does nothing — the fold still hides
  // the list, which is the whole win on a phone.
  function reclaim(toc, collapsed) {
    var parent = toc.parentElement
    if (!parent || parent.firstElementChild !== toc) return
    var prose = toc.nextElementSibling
    parent.style.removeProperty('grid-template-columns')
    if (prose) prose.style.removeProperty('margin-inline')
    if (!collapsed) return
    var cs = window.getComputedStyle(parent)
    if (cs.display.indexOf('grid') < 0) return
    // A computed template is resolved to used values ("236px 796px"), so a
    // plain split counts the tracks. minmax()/repeat() never survive to here.
    if (cs.gridTemplateColumns.split(/\s+/).filter(Boolean).length < 2) return
    parent.style.gridTemplateColumns = 'max-content minmax(0, 1fr)'
    // These pages cap their measure (the 典籍 readers pin `main` to 756px), so a
    // widened track would not widen the prose — it would strand it against the
    // left edge with the reclaimed space as an empty right-hand gutter. Auto
    // side margins re-centre it, and are a no-op on a page whose prose has no
    // max-width and really does fill the track.
    if (prose) prose.style.marginInline = 'auto'
  }

  function start() {
    var toc = findToc()
    if (!toc) return
    addStyles()
    var btn = mountControl(toc)

    function apply(collapsed) {
      toc.classList.toggle(COLLAPSED, collapsed)
      btn.setAttribute('aria-expanded', String(!collapsed))
      // Folded there is no label left to say what the arrow is for.
      btn.setAttribute('title', collapsed ? '展开目录' : '收起目录')
      reclaim(toc, collapsed)
    }

    btn.addEventListener('click', function () {
      var collapsed = !toc.classList.contains(COLLAPSED)
      apply(collapsed)
      store.set(collapsed ? '1' : '0')
    })
    // Re-measure on resize: crossing the page's breakpoint changes whether
    // there is a column to reclaim at all.
    window.addEventListener('resize', function () {
      reclaim(toc, toc.classList.contains(COLLAPSED))
    })

    apply(store.get() === '1')
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start)
  } else {
    start()
  }
})()
