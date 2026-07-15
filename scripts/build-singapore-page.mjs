#!/usr/bin/env node
// Generate a polished, self-contained webpage from the Singapore travel guide.
//
//   docs/singapore-travel-guide.md  ->  frontend/public/singapore.html
//
// The markdown is the single source of truth; this script renders it to a
// standalone HTML page (embedded CSS, no runtime deps) with a sticky sidebar
// table of contents, styled "来源与时效" source blocks, tables and nested
// lists. It runs as the frontend `prebuild` step so the page can never drift
// from the guide — every deploy regenerates it. Vite serves frontend/public/
// at the web root, so the result is reachable at /singapore.html.
//
// This is the *bespoke* landing page for the flagship guide (custom hero copy
// and warm colour theme). The shared Markdown->HTML converter lives in
// scripts/lib/md-to-html.mjs; the generic per-doc renderer that turns every
// other docs/*.md into a page is scripts/build-docs-site.mjs.

import fs from 'node:fs'
import path from 'node:path'
import { renderMarkdown, esc } from './lib/md-to-html.mjs'

const ROOT = path.resolve(import.meta.dirname, '..')
const SRC = path.join(ROOT, 'docs', 'singapore-travel-guide.md')
const OUT = path.join(ROOT, 'frontend', 'public', 'singapore.html')

function renderTocSidebar(toc) {
  const items = toc
    .map((h) => `<li class="lv${h.level}"><a href="#${h.id}">${esc(h.text)}</a></li>`)
    .join('\n')
  return `<nav class="toc" aria-label="目录">
<div class="toc-title">目录</div>
<ul>
${items}
</ul>
</nav>`
}

function build() {
  const md = fs.readFileSync(SRC, 'utf8')
  const { title: parsedTitle, body, toc } = renderMarkdown(md)
  const title = parsedTitle || '新加坡旅游攻略'
  const sidebar = renderTocSidebar(toc)

  const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${esc(title)}</title>
<meta name="description" content="面向中国大陆公民的新加坡自由行完整攻略：签证、机票、景点、行程、美食、住宿与实用贴士。" />
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&family=Space+Grotesk:wght@600;700&display=swap" rel="stylesheet" />
<style>
:root {
  --bg: #f6f7f9;
  --card: #ffffff;
  --ink: #1f2430;
  --muted: #5b6472;
  --line: #e5e8ee;
  --accent: #d1495b;
  --accent-soft: #fdecef;
  --link: #1f6feb;
  --source-bg: #f0f7f4;
  --source-line: #3f9e78;
  --maxw: 1180px;
}
html[data-theme="dark"] {
  --bg: #12151c;
  --card: #1b1f29;
  --ink: #e6e9f0;
  --muted: #9aa4b4;
  --line: #2b3140;
  --accent: #ef6b7e;
  --accent-soft: #2a1e24;
  --link: #6ea8fe;
  --source-bg: #17241f;
  --source-line: #4bb98d;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
@media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
body, .toc, .content, .backtop, th, td, blockquote.quote { transition: background-color 0.25s ease, color 0.25s ease, border-color 0.25s ease; }
html[data-theme="dark"] .figure img,
html[data-theme="dark"] .content p img { filter: brightness(0.88); }
html[data-theme="dark"] thead th { color: var(--ink); }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: "Noto Sans SC", system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 16px;
  line-height: 1.75;
}
a { color: var(--link); text-decoration: none; }
a:hover { text-decoration: underline; }

.hero {
  position: relative;
  background: linear-gradient(135deg, #d1495b 0%, #e07a5f 55%, #f2a65a 100%);
  color: #fff;
  padding: 56px 24px 48px;
  text-align: center;
}
.hero h1 {
  font-family: "Space Grotesk", "Noto Sans SC", sans-serif;
  font-size: clamp(28px, 5vw, 44px);
  margin: 0 0 10px;
  letter-spacing: 0.5px;
}
.hero p { margin: 0 auto; max-width: 640px; opacity: 0.95; font-size: 15px; }

.layout {
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 32px 24px 80px;
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 36px;
  align-items: start;
}

.toc {
  position: sticky;
  top: 20px;
  max-height: calc(100vh - 40px);
  overflow-y: auto;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 16px;
  font-size: 14px;
}
.toc-title {
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 1px;
  text-transform: uppercase;
  color: var(--muted);
  margin-bottom: 10px;
}
.toc ul { list-style: none; margin: 0; padding: 0; }
.toc li { margin: 1px 0; }
.toc li a {
  display: block;
  padding: 4px 8px;
  border-radius: 7px;
  color: var(--ink);
  border-left: 2px solid transparent;
}
.toc li a:hover { background: var(--accent-soft); text-decoration: none; }
.toc li.lv2 a { font-weight: 600; margin-top: 6px; }
.toc li.lv3 a { padding-left: 20px; color: var(--muted); font-size: 13px; }
.toc li a.active {
  background: var(--accent-soft);
  border-left-color: var(--accent);
  color: var(--accent);
  font-weight: 700;
}

.content {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 8px 40px 40px;
  min-width: 0;
}
.content h2, .content h3, .content h4, .content h5, .content h6 {
  font-family: "Space Grotesk", "Noto Sans SC", sans-serif;
  line-height: 1.35;
  scroll-margin-top: 16px;
  position: relative;
}
.content h2 {
  font-size: 26px;
  margin: 40px 0 14px;
  padding-bottom: 8px;
  border-bottom: 2px solid var(--accent-soft);
}
.content h3 { font-size: 20px; margin: 30px 0 10px; }
.content h4 { font-size: 17px; margin: 22px 0 8px; color: var(--accent); }
.content p { margin: 12px 0; }
.figure {
  margin: 22px 0;
  text-align: center;
}
.figure img {
  max-width: 100%;
  height: auto;
  border-radius: 12px;
  border: 1px solid var(--line);
  box-shadow: 0 6px 20px rgba(31,36,48,0.10);
  cursor: zoom-in;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
}
.figure img:hover { transform: translateY(-2px); box-shadow: 0 12px 30px rgba(31,36,48,0.18); }
.figure figcaption {
  margin-top: 8px;
  font-size: 13px;
  color: var(--muted);
}
.content p img { max-width: 100%; height: auto; border-radius: 10px; }
.content ul, .content ol { margin: 12px 0; padding-left: 24px; }
.content li { margin: 5px 0; }
.content li > ul, .content li > ol { margin: 5px 0; }

.anchor {
  opacity: 0;
  margin-left: 8px;
  color: var(--accent);
  font-weight: 400;
  text-decoration: none;
}
h2:hover .anchor, h3:hover .anchor, h4:hover .anchor { opacity: 0.55; }

code {
  background: #eef0f4;
  border-radius: 5px;
  padding: 1px 6px;
  font-family: ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace;
  font-size: 0.9em;
}

blockquote.quote {
  margin: 16px 0;
  padding: 12px 18px;
  border-left: 4px solid var(--line);
  background: #f8f9fb;
  border-radius: 0 10px 10px 0;
  color: var(--muted);
}
blockquote.source {
  background: var(--source-bg);
  border-left-color: var(--source-line);
}
blockquote.source::before {
  content: "🔎 来源与时效";
  display: block;
  font-weight: 700;
  font-size: 13px;
  color: var(--source-line);
  margin-bottom: 4px;
}
blockquote p { margin: 4px 0; font-size: 14px; }

.table-wrap { overflow-x: auto; margin: 16px 0; }
table {
  border-collapse: collapse;
  width: 100%;
  font-size: 14.5px;
  min-width: 420px;
}
th, td { border: 1px solid var(--line); padding: 8px 12px; text-align: left; vertical-align: top; }
thead th { background: var(--accent-soft); color: var(--ink); font-weight: 700; }
tbody tr:nth-child(even) { background: #fafbfc; }

hr { border: none; border-top: 1px solid var(--line); margin: 28px 0; }

.backtop {
  position: fixed;
  right: 22px;
  bottom: 22px;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  border: none;
  font-size: 20px;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(209,73,91,0.4);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s;
}
.backtop.show { opacity: 1; pointer-events: auto; }

/* Reading progress bar (fixed at very top) */
.progress-bar {
  position: fixed;
  top: 0;
  left: 0;
  height: 3px;
  width: 0%;
  background: linear-gradient(90deg, #d1495b, #f2a65a);
  z-index: 60;
  transition: width 0.05s linear;
}

/* Dark-mode toggle button (top-right of hero) */
.theme-toggle {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid rgba(255,255,255,0.5);
  background: rgba(255,255,255,0.15);
  color: #fff;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  backdrop-filter: blur(4px);
  transition: background 0.2s;
}
.theme-toggle:hover { background: rgba(255,255,255,0.28); }

/* Image lightbox overlay */
.lightbox {
  position: fixed;
  inset: 0;
  background: rgba(10,12,16,0.86);
  display: none;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  z-index: 100;
  padding: 24px;
  cursor: zoom-out;
  opacity: 0;
  transition: opacity 0.2s ease;
}
.lightbox.open { display: flex; opacity: 1; }
.lightbox img {
  max-width: 92vw;
  max-height: 82vh;
  border-radius: 10px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.6);
}
.lightbox figcaption {
  color: #e6e9f0;
  font-size: 14px;
  margin-top: 14px;
  max-width: 760px;
  text-align: center;
  line-height: 1.6;
}
.lightbox .lb-close {
  position: absolute;
  top: 18px;
  right: 22px;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: none;
  background: rgba(255,255,255,0.15);
  color: #fff;
  font-size: 24px;
  cursor: pointer;
}
.lightbox .lb-close:hover { background: rgba(255,255,255,0.3); }

.mobile-toc-toggle { display: none; }

@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; gap: 16px; }
  .toc {
    position: static;
    max-height: none;
  }
  .mobile-toc-toggle {
    display: block;
    width: 100%;
    background: var(--card);
    border: 1px solid var(--line);
    border-radius: 10px;
    padding: 12px 16px;
    font: inherit;
    font-weight: 600;
    text-align: left;
    cursor: pointer;
  }
  .toc.collapsed { display: none; }
  .content { padding: 8px 20px 28px; }
}
</style>
</head>
<body>
<div class="progress-bar" id="progressBar"></div>
<header class="hero">
<button class="theme-toggle" id="themeToggle" aria-label="切换深色/浅色模式" title="切换深色/浅色模式">🌙</button>
<h1>${esc(title)}</h1>
<p>面向中国大陆公民的一份完整自由行攻略 · 行前基础 · 景点玩乐 · 行程路线 · 美食购物 · 住宿分区 · 实用贴士</p>
</header>

<div class="layout">
<button class="mobile-toc-toggle" onclick="document.querySelector('.toc').classList.toggle('collapsed')">☰ 目录（点击展开/收起）</button>
${sidebar}
<main class="content">
${body}
</main>
</div>

<button class="backtop" aria-label="回到顶部" onclick="window.scrollTo({top:0,behavior:'smooth'})">↑</button>

<div class="lightbox" id="lightbox" aria-hidden="true">
  <button class="lb-close" aria-label="关闭" id="lbClose">×</button>
  <img id="lbImg" alt="" />
  <figcaption id="lbCap"></figcaption>
</div>

<script>
  // --- Back-to-top button + reading progress bar ---
  var bt = document.querySelector('.backtop');
  var pb = document.getElementById('progressBar');
  function onScroll() {
    var y = window.scrollY;
    if (y > 400) bt.classList.add('show'); else bt.classList.remove('show');
    var h = document.documentElement.scrollHeight - window.innerHeight;
    pb.style.width = (h > 0 ? (y / h) * 100 : 0) + '%';
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  // On small screens the TOC starts collapsed.
  if (window.matchMedia('(max-width: 900px)').matches) {
    document.querySelector('.toc').classList.add('collapsed');
  }

  // --- Dark mode toggle (persisted, respects OS preference on first visit) ---
  var root = document.documentElement;
  var toggle = document.getElementById('themeToggle');
  function applyTheme(t) {
    root.setAttribute('data-theme', t);
    toggle.textContent = t === 'dark' ? '☀️' : '🌙';
  }
  var saved = null;
  try { saved = localStorage.getItem('sg-theme'); } catch (e) {}
  if (!saved) saved = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  applyTheme(saved);
  toggle.addEventListener('click', function () {
    var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    try { localStorage.setItem('sg-theme', next); } catch (e) {}
  });

  // --- TOC scroll-spy: highlight the section currently in view ---
  var tocLinks = Array.prototype.slice.call(document.querySelectorAll('.toc a'));
  var linkById = {};
  tocLinks.forEach(function (a) { linkById[decodeURIComponent(a.hash.slice(1))] = a; });
  var headings = tocLinks
    .map(function (a) { return document.getElementById(decodeURIComponent(a.hash.slice(1))); })
    .filter(Boolean);
  var current = null;
  function setActive(id) {
    if (id === current) return;
    if (current && linkById[current]) linkById[current].classList.remove('active');
    current = id;
    var link = linkById[id];
    if (link) {
      link.classList.add('active');
      // keep the active item visible inside a scrollable sidebar
      if (link.scrollIntoView) link.scrollIntoView({ block: 'nearest' });
    }
  }
  if ('IntersectionObserver' in window && headings.length) {
    var visible = new Set();
    var spy = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) visible.add(en.target); else visible.delete(en.target);
      });
      // pick the topmost heading still on screen
      var best = null, bestTop = Infinity;
      visible.forEach(function (el) {
        var top = el.getBoundingClientRect().top;
        if (top < bestTop) { bestTop = top; best = el; }
      });
      if (best) setActive(best.id);
    }, { rootMargin: '-10% 0px -70% 0px', threshold: 0 });
    headings.forEach(function (h) { spy.observe(h); });
  }

  // --- Image lightbox: click any figure image to view it enlarged ---
  var lb = document.getElementById('lightbox');
  var lbImg = document.getElementById('lbImg');
  var lbCap = document.getElementById('lbCap');
  function openLightbox(img) {
    lbImg.src = img.currentSrc || img.src;
    lbImg.alt = img.alt || '';
    var fig = img.closest('figure');
    var cap = fig ? fig.querySelector('figcaption') : null;
    lbCap.textContent = cap ? cap.textContent : (img.alt || '');
    lb.classList.add('open');
    lb.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
  }
  function closeLightbox() {
    lb.classList.remove('open');
    lb.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    lbImg.src = '';
  }
  document.querySelectorAll('.figure img, .content p img').forEach(function (img) {
    img.style.cursor = 'zoom-in';
    img.addEventListener('click', function () { openLightbox(img); });
  });
  lb.addEventListener('click', function (e) {
    if (e.target === lb || e.target.id === 'lbClose') closeLightbox();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && lb.classList.contains('open')) closeLightbox();
  });
</script>
</body>
</html>
`

  fs.mkdirSync(path.dirname(OUT), { recursive: true })
  fs.writeFileSync(OUT, html, 'utf8')
  console.log(`✔ singapore.html generated (${(html.length / 1024).toFixed(1)} KB, ${toc.length} TOC entries) -> ${path.relative(ROOT, OUT)}`)
}

build()
