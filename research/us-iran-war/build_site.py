#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build a self-contained HTML report from the six US-Iran research markdown files.
No external dependencies, no network — a hand-rolled converter for the markdown
subset used by these files (headings, blockquotes, ordered/unordered/nested lists,
GFM tables, hr, **bold**, *italic*, `code`, [links](url))."""
import html
import re
import os

HERE = os.path.dirname(os.path.abspath(__file__))

FILES = [
    ("01-background-and-roots.md",        "背景与根源",   "01", "冲突的四条结构性断层与带时间线的历史脉络（1953–2026）"),
    ("02-current-situation-snapshot.md",  "当前局势",     "02", "截至 2026-07-14 的现状快照：停火瓦解、霍尔木兹海峡成唯一引爆点"),
    ("03-military-balance-and-scenarios.md","军力对比",    "03", "极不对称的力量结构与趋于「技术性僵局」的战场态势"),
    ("04-geopolitical-impact.md",         "地缘影响",     "04", "悖论式扩散：战术惩罚伊朗，战略重创美国主导秩序"),
    ("05-market-and-energy-impact.md",    "市场与能源",   "05", "分裂式冲击：油市剧烈、股汇冷静，供给过剩当减震器"),
    ("06-scenarios-and-outlook.md",       "情景推演",     "06", "四情景概率、tripwire 仪表盘与前景研判（最终综述）"),
]

# ---------------------------------------------------------------- inline
def inline(text):
    """Escape HTML then re-apply inline markdown. Order matters."""
    text = html.escape(text, quote=False)
    # inline code first (protect its contents)
    codes = []
    def _stash(m):
        codes.append(m.group(1))
        return f"\x00{len(codes)-1}\x00"
    text = re.sub(r"`([^`]+)`", _stash, text)
    # links [text](url)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)",
                  r'<a href="\2" target="_blank" rel="noopener">\1</a>', text)
    # bold then italic
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\*\w])\*([^*\n]+)\*(?!\*)", r"<em>\1</em>", text)
    # restore code
    def _pop(m):
        return f"<code>{codes[int(m.group(1))]}</code>"
    text = re.sub(r"\x00(\d+)\x00", _pop, text)
    return text

def slugify(text, seen):
    base = re.sub(r"[^\w一-鿿]+", "-", text.strip()).strip("-").lower() or "s"
    s, i = base, 1
    while s in seen:
        i += 1; s = f"{base}-{i}"
    seen.add(s)
    return s

# ---------------------------------------------------------------- block
def convert(md, seen_slugs, headings):
    lines = md.split("\n")
    out = []
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]

        # blank
        if not line.strip():
            i += 1; continue

        # heading
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            lvl = len(m.group(1)); txt = m.group(2).strip()
            slug = slugify(txt, seen_slugs)
            if lvl in (2, 3):
                headings.append((lvl, txt, slug))
            out.append(f'<h{lvl} id="{slug}">{inline(txt)}</h{lvl}>')
            i += 1; continue

        # horizontal rule
        if re.match(r"^\s*-{3,}\s*$", line):
            out.append("<hr>"); i += 1; continue

        # table: current line has pipes and next line is a separator row
        if "|" in line and i + 1 < n and re.match(r"^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$", lines[i+1]) and "|" in lines[i+1]:
            header = [c.strip() for c in line.strip().strip("|").split("|")]
            i += 2
            rows = []
            while i < n and "|" in lines[i] and lines[i].strip():
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            t = ['<div class="table-wrap"><table><thead><tr>']
            t += [f"<th>{inline(c)}</th>" for c in header]
            t.append("</tr></thead><tbody>")
            for r in rows:
                # pad/truncate to header width
                r = (r + [""] * len(header))[:len(header)]
                t.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
            t.append("</tbody></table></div>")
            out.append("".join(t)); continue

        # blockquote (consecutive > lines)
        if re.match(r"^\s*>", line):
            buf = []
            while i < n and re.match(r"^\s*>", lines[i]):
                buf.append(re.sub(r"^\s*>\s?", "", lines[i]))
                i += 1
            inner = "<br>".join(inline(b) if b.strip() else "" for b in buf)
            out.append(f"<blockquote>{inner}</blockquote>"); continue

        # lists (unordered - / ordered N.), with 2-space nesting
        if re.match(r"^\s*([-*]|\d+\.)\s+", line):
            i = parse_list(lines, i, out, 0)
            continue

        # paragraph (gather until blank / block start)
        buf = []
        while i < n and lines[i].strip() and not re.match(
                r"^(#{1,6}\s|\s*>|\s*([-*]|\d+\.)\s|\s*-{3,}\s*$)", lines[i]) \
                and not ("|" in lines[i] and i+1 < n and re.match(r"^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$", lines[i+1])):
            buf.append(lines[i].strip()); i += 1
        out.append(f"<p>{inline(' '.join(buf))}</p>")
    return "\n".join(out)

def parse_list(lines, i, out, indent):
    n = len(lines)
    first = lines[i]
    ordered = bool(re.match(r"^\s*\d+\.\s+", first))
    tag = "ol" if ordered else "ul"
    out.append(f"<{tag}>")
    while i < n:
        line = lines[i]
        if not line.strip():
            i += 1; continue
        m = re.match(r"^(\s*)([-*]|\d+\.)\s+(.*)$", line)
        if not m:
            break
        cur_indent = len(m.group(1))
        if cur_indent < indent:
            break
        if cur_indent > indent:
            # nested list belongs to previous <li>; splice before its closing tag
            closing = out.pop()  # "</li>"
            i = parse_list(lines, i, out, cur_indent)
            out.append(closing)
            continue
        out.append(f"<li>{inline(m.group(3).strip())}</li>")
        i += 1
    out.append(f"</{tag}>")
    return i

# ---------------------------------------------------------------- assemble
sections_html = []
nav_items = []
for idx, (fname, short, num, subtitle) in enumerate(FILES):
    with open(os.path.join(HERE, fname), encoding="utf-8") as f:
        md = f.read()
    seen = set()
    headings = []
    body = convert(md, seen, headings)
    sec_id = f"sec-{num}"
    # sub-nav (h2 headings within the section)
    subnav = "".join(
        f'<a class="subnav-link" href="#{slug}">{html.escape(txt)}</a>'
        for lvl, txt, slug in headings if lvl == 2
    )
    sections_html.append(
        f'<section id="{sec_id}" class="doc" data-idx="{idx}">{body}</section>'
    )
    nav_items.append(
        f'<a class="nav-link" data-target="{sec_id}" href="#{sec_id}">'
        f'<span class="nav-num">{num}</span>'
        f'<span class="nav-text"><span class="nav-title">{short}</span>'
        f'<span class="nav-sub">{html.escape(subtitle)}</span></span></a>'
    )

NAV = "\n".join(nav_items)
DOCS = "\n".join(sections_html)

PAGE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>美伊战争 · 分析总览 (2026)</title>
<style>
:root{
  --bg:#0e1116; --bg2:#151a22; --panel:#171d27; --panel2:#1d2532;
  --ink:#e7ecf3; --muted:#9aa7b8; --faint:#6b7688;
  --line:#242d3a; --line2:#2e3a4c;
  --accent:#e5533d; --accent2:#f0a020; --iran:#3aa76d; --us:#4a83d4;
  --maxw:900px;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--ink);
  font-family:-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",Roboto,Helvetica,Arial,sans-serif;
  font-size:16px;line-height:1.75;-webkit-font-smoothing:antialiased}
a{color:var(--accent2);text-decoration:none}
a:hover{text-decoration:underline}

/* layout */
.wrap{display:flex;min-height:100vh}
.sidebar{width:300px;flex:0 0 300px;background:var(--bg2);border-right:1px solid var(--line);
  position:sticky;top:0;height:100vh;overflow-y:auto;padding:0}
.main{flex:1;min-width:0}

/* sidebar */
.brand{padding:22px 22px 16px;border-bottom:1px solid var(--line)}
.brand .kicker{font-size:11px;letter-spacing:.22em;color:var(--accent);text-transform:uppercase;font-weight:700}
.brand h1{font-size:21px;margin:8px 0 4px;line-height:1.3;font-weight:800;letter-spacing:.02em}
.brand .date{font-size:12px;color:var(--faint)}
.nav{padding:12px 12px 30px}
.nav-link{display:flex;gap:12px;align-items:flex-start;padding:11px 12px;border-radius:10px;
  color:var(--muted);margin-bottom:3px;border:1px solid transparent;transition:.15s}
.nav-link:hover{background:var(--panel);color:var(--ink);text-decoration:none}
.nav-link.active{background:var(--panel2);border-color:var(--line2);color:var(--ink)}
.nav-num{font-size:12px;font-weight:800;color:var(--faint);background:var(--bg);border:1px solid var(--line);
  border-radius:7px;width:30px;height:30px;display:grid;place-items:center;flex:0 0 30px;margin-top:1px}
.nav-link.active .nav-num{color:var(--accent);border-color:var(--accent)}
.nav-text{display:flex;flex-direction:column;min-width:0}
.nav-title{font-size:14.5px;font-weight:700;color:inherit}
.nav-sub{font-size:11.5px;color:var(--faint);line-height:1.45;margin-top:2px}

/* hero */
.hero{padding:52px 40px 30px;background:
   radial-gradient(1200px 300px at 80% -10%, rgba(229,83,61,.16), transparent 60%),
   radial-gradient(900px 260px at 10% 0%, rgba(74,131,212,.13), transparent 55%),
   var(--bg);border-bottom:1px solid var(--line)}
.hero .eyebrow{font-size:12px;letter-spacing:.28em;text-transform:uppercase;color:var(--accent);font-weight:700}
.hero h2{font-size:38px;line-height:1.15;margin:14px 0 10px;font-weight:800;letter-spacing:.01em;max-width:var(--maxw)}
.hero p.lede{font-size:16.5px;color:var(--muted);max-width:760px;margin:0}
.status{display:inline-flex;align-items:center;gap:9px;margin-top:20px;padding:8px 15px;border-radius:999px;
  background:rgba(229,83,61,.12);border:1px solid rgba(229,83,61,.4);color:#ffb3a5;font-size:13.5px;font-weight:600}
.status .dot{width:9px;height:9px;border-radius:50%;background:var(--accent);
  box-shadow:0 0 0 0 rgba(229,83,61,.6);animation:pulse 2s infinite}
@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(229,83,61,.55)}70%{box-shadow:0 0 0 9px rgba(229,83,61,0)}100%{box-shadow:0 0 0 0 rgba(229,83,61,0)}}

/* stat cards */
.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;padding:26px 40px 8px;max-width:1100px}
.stat{background:var(--panel);border:1px solid var(--line);border-radius:13px;padding:16px 17px}
.stat .k{font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--faint);font-weight:700}
.stat .v{font-size:26px;font-weight:800;margin:6px 0 2px;letter-spacing:.01em}
.stat .d{font-size:12px;color:var(--muted);line-height:1.5}
.stat.iran .v{color:var(--iran)} .stat.us .v{color:var(--us)}
.stat.oil .v{color:var(--accent2)} .stat.war .v{color:var(--accent)}

.scenarios{padding:22px 40px 6px;max-width:1100px}
.scenarios h3, .content-head{font-size:13px;letter-spacing:.14em;text-transform:uppercase;color:var(--faint);
  font-weight:700;margin:0 0 14px}
.sbars{display:flex;flex-direction:column;gap:11px}
.sbar{display:grid;grid-template-columns:150px 1fr 92px;gap:14px;align-items:center;font-size:13.5px}
.sbar .lbl{color:var(--muted);font-weight:600}
.sbar .track{background:var(--bg2);border:1px solid var(--line);border-radius:7px;height:22px;overflow:hidden}
.sbar .fill{height:100%;border-radius:6px 0 0 6px}
.sbar .pct{text-align:right;color:var(--muted);font-variant-numeric:tabular-nums}
.sbar.base .lbl{color:var(--ink)}

/* documents */
.content{padding:14px 40px 90px;max-width:var(--maxw)}
.doc{display:none}
.doc.visible{display:block;animation:fade .35s ease}
@keyframes fade{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
.doc h1{font-size:29px;font-weight:800;line-height:1.28;margin:20px 0 6px;letter-spacing:.01em}
.doc h2{font-size:22px;font-weight:800;margin:40px 0 12px;padding-top:14px;border-top:1px solid var(--line);letter-spacing:.01em}
.doc h2:first-of-type{border-top:none}
.doc h3{font-size:17.5px;font-weight:700;margin:26px 0 8px;color:#cfd8e6}
.doc h4{font-size:15px;font-weight:700;margin:18px 0 6px;color:var(--muted)}
.doc p{margin:12px 0}
.doc strong{color:#fff;font-weight:700}
.doc em{color:var(--accent2);font-style:normal;font-weight:600}
.doc code{background:var(--bg2);border:1px solid var(--line);border-radius:5px;padding:2px 6px;font-size:13.5px;
  font-family:"SF Mono",ui-monospace,"Cascadia Code",Menlo,Consolas,monospace;color:#f0b8ad;white-space:pre-wrap;word-break:break-word}
.doc ul,.doc ol{margin:12px 0;padding-left:24px}
.doc li{margin:6px 0}
.doc li>ul,.doc li>ol{margin:6px 0}
.doc hr{border:none;border-top:1px solid var(--line);margin:30px 0}
.doc blockquote{margin:16px 0;padding:12px 18px;background:var(--bg2);border-left:3px solid var(--accent2);
  border-radius:0 9px 9px 0;color:var(--muted);font-size:14.5px}
.doc blockquote a{word-break:break-all}
.table-wrap{overflow-x:auto;margin:18px 0;border:1px solid var(--line);border-radius:11px}
.doc table{border-collapse:collapse;width:100%;font-size:13.8px;min-width:520px}
.doc th{background:var(--panel2);text-align:left;padding:11px 14px;font-weight:700;color:#dbe4f0;
  border-bottom:1px solid var(--line2);white-space:nowrap}
.doc td{padding:10px 14px;border-bottom:1px solid var(--line);vertical-align:top;color:var(--ink)}
.doc tbody tr:last-child td{border-bottom:none}
.doc tbody tr:nth-child(even){background:rgba(255,255,255,.017)}

/* doc header bar with sub-nav */
.dochead{padding:26px 40px 4px;max-width:var(--maxw)}
.dochead .crumb{font-size:12px;letter-spacing:.16em;text-transform:uppercase;color:var(--accent);font-weight:700}

/* footer nav (prev/next) */
.pager{display:flex;justify-content:space-between;gap:14px;margin:50px 0 0;max-width:var(--maxw)}
.pager a{flex:1;background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:15px 18px;
  color:var(--muted);transition:.15s}
.pager a:hover{border-color:var(--line2);color:var(--ink);text-decoration:none;background:var(--panel2)}
.pager .dir{font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--faint)}
.pager .ttl{font-size:15px;font-weight:700;margin-top:3px;display:block}
.pager .next{text-align:right}
.pager a.disabled{opacity:.35;pointer-events:none}

.tofoot{padding:30px 40px 60px;max-width:var(--maxw);color:var(--faint);font-size:12.5px;border-top:1px solid var(--line);margin-top:20px}

.tabbar{display:none}
#toTop{position:fixed;right:26px;bottom:26px;width:44px;height:44px;border-radius:50%;background:var(--panel2);
  border:1px solid var(--line2);color:var(--ink);font-size:20px;cursor:pointer;display:none;place-items:center;z-index:50}
#toTop.show{display:grid}

@media(max-width:980px){
  .stats{grid-template-columns:repeat(2,1fr)}
  .sbar{grid-template-columns:110px 1fr 70px}
}
@media(max-width:760px){
  .wrap{flex-direction:column}
  .sidebar{position:static;width:100%;height:auto;border-right:none;border-bottom:1px solid var(--line)}
  .nav{display:flex;overflow-x:auto;gap:8px;padding:12px}
  .nav-link{flex:0 0 auto;margin:0}
  .nav-sub{display:none}
  .hero,.stats,.scenarios,.content,.dochead,.tofoot{padding-left:18px;padding-right:18px}
  .hero h2{font-size:28px}
}
</style>
</head>
<body>
<div class="wrap">
  <aside class="sidebar">
    <div class="brand">
      <div class="kicker">Situation Report</div>
      <h1>美伊战争<br>分析总览</h1>
      <div class="date">六步研究综合 · 截至 2026-07-14</div>
    </div>
    <nav class="nav" id="nav">
      <a class="nav-link" data-target="home" href="#home">
        <span class="nav-num">◎</span>
        <span class="nav-text"><span class="nav-title">总览仪表盘</span>
        <span class="nav-sub">执行摘要、关键数字与情景概率</span></span></a>
      __NAV__
    </nav>
  </aside>

  <main class="main">
    <!-- HOME / dashboard -->
    <section id="home" class="doc visible" data-idx="-1">
      <div class="hero">
        <div class="eyebrow">US–Iran War · Analytical Overview</div>
        <h2>一场已经进行时的战争，如何被解剖为六层分析</h2>
        <p class="lede">本页整合「美伊战争」研究故事的全部六步产出——从历史根源、当前战况、军力对比，到地缘、市场与情景推演。所有事实锚点均来自 chrome-devtools 实读的真实来源（维基/AP/半岛/CSIS/IISS/OilPrice/Kitco/CNBC 等），日期与数字不凭记忆编造。</p>
        <div class="status"><span class="dot"></span>战争进行中 · 2026-02-28 爆发 · 6 月停火已于 7 月初瓦解，连日互袭</div>
      </div>

      <div class="stats">
        <div class="stat war"><div class="k">冲突状态</div><div class="v">Day 137</div><div class="d">自 2026-02-28 开战；6/17 临时协议已事实瓦解</div></div>
        <div class="stat oil"><div class="k">布伦特油价</div><div class="v">$84.78</div><div class="d">周一 +8%；战时区间 $70→$139→$84，均值回归</div></div>
        <div class="stat iran"><div class="k">核心引爆点</div><div class="v">霍尔木兹</div><div class="d">海峡控制/通行权已收敛为唯一核心争点</div></div>
        <div class="stat us"><div class="k">战略研判</div><div class="v">伊胜美败</div><div class="d">主流智库具名判定：战术惩罚伊朗，战略挫美</div></div>
      </div>

      <div class="scenarios">
        <h3>未来 3–6 个月 · 四情景概率（分析判断，第 6 步）</h3>
        <div class="sbars">
          <div class="sbar"><div class="lbl">外交降级</div><div class="track"><div class="fill" style="width:27%;background:linear-gradient(90deg,#3aa76d,#2c8557)"></div></div><div class="pct">24–30%</div></div>
          <div class="sbar"><div class="lbl">有限打击</div><div class="track"><div class="fill" style="width:18%;background:linear-gradient(90deg,#4a83d4,#3a68ab)"></div></div><div class="pct">15–20%</div></div>
          <div class="sbar base"><div class="lbl">长期消耗 · 基准</div><div class="track"><div class="fill" style="width:44%;background:linear-gradient(90deg,#f0a020,#d4851a)"></div></div><div class="pct">40–48%</div></div>
          <div class="sbar"><div class="lbl">全面战争 · 尾部</div><div class="track"><div class="fill" style="width:11%;background:linear-gradient(90deg,#e5533d,#c23a26)"></div></div><div class="pct">8–14%</div></div>
        </div>
      </div>

      <div class="scenarios" style="padding-top:30px">
        <h3>六步研究 · 逐层导览</h3>
        <div class="sbars" id="home-cards"></div>
      </div>

      <div class="tofoot">
        方法学：本页为研究综述的可视化呈现，非新闻实时源。事实锚点可回溯至各步骤记录的实读来源；概率、时间窗、情景路径均为分析判断而非既定事实。争议信息（伤亡、领导人生死、海峡开闭）在原文中均标注单方归属。<br>
        构建：由第 7/7 步任务离线生成的自包含 HTML（无外部依赖、无网络请求）。数据截止 2026-07-14。
      </div>
    </section>

    __DOCS__

    <button id="toTop" title="回到顶部">↑</button>
  </main>
</div>

<script>
var order = ["home","sec-01","sec-02","sec-03","sec-04","sec-05","sec-06"];
var meta = __META__;
var links = document.querySelectorAll('.nav-link');
var docs  = document.querySelectorAll('.doc');

function show(id){
  docs.forEach(function(d){ d.classList.toggle('visible', d.id===id); });
  links.forEach(function(l){ l.classList.toggle('active', l.getAttribute('data-target')===id); });
  // build pager for doc sections
  var cur = docs.namedItem ? null : null;
  window.scrollTo({top:0,behavior:'auto'});
  history.replaceState(null,'','#'+id);
  buildPager(id);
}
function buildPager(id){
  var sec = document.getElementById(id);
  if(!sec || id==='home') return;
  if(sec.querySelector('.pager')) return;
  var i = order.indexOf(id);
  var prev = order[i-1], next = order[i+1];
  var p = document.createElement('div'); p.className='pager';
  function cell(tid,dir,cls){
    if(!tid) return '<a class="disabled '+cls+'"></a>';
    var m = meta[tid];
    return '<a class="'+cls+'" data-go="'+tid+'"><span class="dir">'+dir+'</span>'
      +'<span class="ttl">'+m.title+'</span></a>';
  }
  p.innerHTML = cell(prev,'← 上一部分','prev') + cell(next,'下一部分 →','next');
  sec.appendChild(p);
  p.querySelectorAll('a[data-go]').forEach(function(a){
    a.addEventListener('click',function(e){e.preventDefault();show(a.getAttribute('data-go'));});
  });
}

links.forEach(function(l){
  l.addEventListener('click',function(e){
    var t=l.getAttribute('data-target');
    if(t){e.preventDefault();show(t);}
  });
});

// home cards
var cardWrap = document.getElementById('home-cards');
order.slice(1).forEach(function(id){
  var m = meta[id];
  var a = document.createElement('a');
  a.href='#'+id; a.setAttribute('data-go',id);
  a.style.cssText='display:grid;grid-template-columns:44px 1fr;gap:14px;align-items:center;'+
    'background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px 16px;color:var(--muted)';
  a.innerHTML='<span style="font-size:15px;font-weight:800;color:var(--faint);background:var(--bg);'+
    'border:1px solid var(--line);border-radius:8px;width:44px;height:44px;display:grid;place-items:center">'+m.num+'</span>'+
    '<span><span style="display:block;font-size:15.5px;font-weight:700;color:var(--ink)">'+m.title+'</span>'+
    '<span style="display:block;font-size:12.5px;color:var(--faint);margin-top:2px">'+m.sub+'</span></span>';
  a.addEventListener('mouseenter',function(){a.style.borderColor='var(--line2)';});
  a.addEventListener('mouseleave',function(){a.style.borderColor='var(--line)';});
  a.addEventListener('click',function(e){e.preventDefault();show(id);});
  cardWrap.appendChild(a);
});

// back to top
var toTop=document.getElementById('toTop');
window.addEventListener('scroll',function(){
  toTop.classList.toggle('show', window.scrollY>500);
});
toTop.addEventListener('click',function(){window.scrollTo({top:0,behavior:'smooth'});});

// deep-link on load
var h=(location.hash||'').replace('#','');
if(order.indexOf(h)>=0) show(h); else show('home');
</script>
</body>
</html>
"""

# meta map for JS
meta_entries = ['"home":{"title":"总览仪表盘","num":"◎","sub":"执行摘要"}']
for fname, short, num, subtitle in FILES:
    t = short.replace('"', '\\"')
    s = subtitle.replace('"', '\\"')
    meta_entries.append(f'"sec-{num}":{{"title":"{t}","num":"{num}","sub":"{s}"}}')
META = "{" + ",".join(meta_entries) + "}"

PAGE = PAGE.replace("__NAV__", NAV).replace("__DOCS__", DOCS).replace("__META__", META)

out_path = os.path.join(HERE, "index.html")
with open(out_path, "w", encoding="utf-8") as f:
    f.write(PAGE)
print("wrote", out_path, os.path.getsize(out_path), "bytes")
