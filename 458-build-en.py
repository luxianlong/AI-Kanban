#!/usr/bin/env python3
"""把 stage-N.md 转成和 #422 同风格的自包含 HTML 页。"""
from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path

import markdown
from pygments.formatters import HtmlFormatter

BLOCK_RE = re.compile(r"^:::(\w+)(?:[ \t]+(.*))?$")
KIND_META = {
    "exercise": ("Exercise", "box box-ex"),
    "lab":      ("Lab", "box box-lab"),
    "note":     ("Note", "box box-note"),
    "warn":     ("Caution", "box box-warn"),
    "goal":     ("Goal", "box box-goal"),
}


def preprocess(text: str) -> str:
    out: list[str] = []
    stack: list[str] = []
    for line in text.split("\n"):
        m = BLOCK_RE.match(line.rstrip())
        if m:
            kind, title = m.group(1), (m.group(2) or "").strip()
            if kind == "solution":
                out.append('<details class="sol" markdown="1"><summary>'
                           f'{html.escape(title or "Answer")}</summary>')
                stack.append("</details>")
            elif kind == "out":
                out.append('<div class="out" markdown="1">'
                           f'<div class="out-h">{html.escape(title or "Output")}</div>')
                stack.append("</div>")
            elif kind in KIND_META:
                label, cls = KIND_META[kind]
                out.append(f'<div class="{cls}" markdown="1">'
                           f'<div class="box-h">{html.escape(title or label)}</div>')
                stack.append("</div>")
            else:
                out.append(line)
            continue
        if line.rstrip() == ":::" and stack:
            out.append(stack.pop())
            continue
        out.append(line)
    while stack:
        out.append(stack.pop())
    return "\n".join(out)


LIST_START = re.compile(r"^([ \t]*)([-*+]|\d+\.)[ \t]+\S")


def fix_tight_lists(text: str) -> str:
    """Python-Markdown 要求列表前有空行，补上 —— 否则列表会变成普通段落文字。"""
    out: list[str] = []
    in_fence = False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        if (not in_fence and LIST_START.match(line) and out
                and out[-1].strip() and not LIST_START.match(out[-1])
                and not out[-1].lstrip().startswith(("|", ">", "<", ":::"))):
            out.append("")
        out.append(line)
    return "\n".join(out)


MERMAID_RE = re.compile(r"^```mermaid\n(.*?)^```$", re.S | re.M)


def convert(md_text: str):
    blocks: list[str] = []

    def stash(m):
        blocks.append(m.group(1))
        return f"\n\x00MERMAID{len(blocks)-1}\x00\n"

    md_text = MERMAID_RE.sub(stash, md_text)
    md = markdown.Markdown(
        extensions=["extra", "md_in_html", "sane_lists", "toc", "codehilite", "attr_list"],
        extension_configs={
            "codehilite": {"guess_lang": False, "linenums": False, "css_class": "hl"},
            "toc": {"slugify": lambda v, s: "sec-" + re.sub(
                r"[^\w一-鿿]+", "-", v.lower()).strip("-")[:60]},
        },
    )
    body = md.convert(fix_tight_lists(preprocess(md_text)))
    for i, src in enumerate(blocks):
        tag = f'<pre class="mermaid">{html.escape(src)}</pre>'
        body = body.replace(f"<p>\x00MERMAID{i}\x00</p>", tag).replace(
            f"\x00MERMAID{i}\x00", tag)

    toc = [(t["id"], t["name"]) for t in md.toc_tokens]

    def anchorize(m):
        lvl, hid, inner = m.group(1), m.group(2), m.group(3)
        return (f'<h{lvl} id="{hid}">{inner}'
                f'<a class="anchor" href="#{hid}" aria-label="Link to this section">#</a></h{lvl}>')

    body = re.sub(r'<h([23]) id="([^"]+)">(.*?)</h\1>', anchorize, body, flags=re.S)
    body = re.sub(r"<li>\[ \]\s*", '<li class="task"><input type="checkbox"/> ', body)
    body = re.sub(r"<li>\[x\]\s*", '<li class="task"><input type="checkbox" checked/> ',
                  body, flags=re.I)
    return body, toc


CSS_EXTRA = """
.stagebar{display:flex;flex-wrap:wrap;gap:8px;margin:18px 0 0}
.stagebar a,.stagebar span{font-size:12.5px;padding:5px 11px;border-radius:99px;border:1px solid var(--line);text-decoration:none;color:var(--muted);background:var(--bg)}
.stagebar a:hover{border-color:var(--accent);color:var(--accent)}
.stagebar .cur{background:var(--accent);color:#fff;border-color:var(--accent);font-weight:600}
.meta{display:flex;flex-wrap:wrap;gap:16px;margin:16px 0 0;font-size:13px;color:var(--muted)}
.meta b{color:var(--fg);font-weight:600}
.box{margin:20px 0;padding:14px 18px;border-radius:10px;border:1px solid var(--line);background:var(--card)}
.box-h{font-weight:700;font-size:14px;margin:0 0 8px;letter-spacing:.02em}
.box>*:last-child{margin-bottom:0}
.box-ex{border-left:3px solid var(--accent)}
.box-ex .box-h{color:var(--accent)}
.box-lab{border-left:3px solid #3a8f6a}.box-lab .box-h{color:#3a8f6a}
.box-note{border-left:3px solid #4a7fb5}.box-note .box-h{color:#4a7fb5}
.box-warn{border-left:3px solid #c1682c}.box-warn .box-h{color:#c1682c}
.box-goal{border-left:3px solid var(--accent);background:var(--code-bg)}
.box-goal .box-h{color:var(--accent)}
details.sol{margin:12px 0;border:1px solid var(--line);border-radius:10px;background:var(--code-bg);overflow:hidden}
details.sol>summary{cursor:pointer;padding:10px 16px;font-weight:600;font-size:14px;color:var(--accent);user-select:none;list-style:none}
details.sol>summary::-webkit-details-marker{display:none}
details.sol>summary::before{content:"\\25B8\\00a0\\00a0";display:inline-block;transition:transform .15s}
details.sol[open]>summary::before{transform:rotate(90deg)}
details.sol[open]>summary{border-bottom:1px solid var(--line)}
details.sol>*:not(summary){margin-left:16px;margin-right:16px}
details.sol>.codewrap{margin-left:16px;margin-right:16px}
.out{margin:14px 0;border-radius:10px;overflow:hidden;border:1px solid var(--line)}
.out-h{font-size:11.5px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);background:var(--code-bg);padding:6px 14px;border-bottom:1px solid var(--line)}
.out .codewrap{margin:0}
.out pre{margin:0!important;border:0!important;border-radius:0!important;background:#15140f!important}
.out pre,.out pre *{color:#d5d0c3!important;background:transparent!important}
.out pre{background:#15140f!important}
.codewrap{position:relative;margin:16px 0}
.codewrap pre{margin:0}
.copybtn{position:absolute;top:8px;right:8px;font:500 11px/1 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC",sans-serif;padding:5px 9px;border-radius:6px;border:1px solid var(--line);background:var(--card);color:var(--muted);cursor:pointer;opacity:0;transition:opacity .15s}
.codewrap:hover .copybtn{opacity:1}
.copybtn:hover{color:var(--accent);border-color:var(--accent)}
.copybtn.done{color:#3a8f6a;border-color:#3a8f6a}
.content h2{margin-top:56px}
.pager{display:flex;justify-content:space-between;gap:16px;margin:56px 0 0;padding-top:24px;border-top:1px solid var(--line);font-size:14px}
.pager a{display:block;max-width:46%;color:var(--accent);text-decoration:none}
.pager a:hover{text-decoration:underline}
.pager .lbl{display:block;font-size:11.5px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);margin-bottom:3px}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(270px,1fr));gap:16px;margin:24px 0}
.card{display:block;padding:18px 20px;border:1px solid var(--line);border-radius:12px;background:var(--card);text-decoration:none;color:inherit;transition:border-color .15s,transform .15s}
.card:hover{border-color:var(--accent);transform:translateY(-2px)}
.card .k{font-size:11.5px;letter-spacing:.12em;text-transform:uppercase;color:var(--accent)}
.card .t{font-size:17px;font-weight:650;margin:6px 0 6px;line-height:1.35}
.card .d{font-size:13.5px;color:var(--muted);line-height:1.6}
"""

BASE_CSS = """
:root{--bg:#fbfaf7;--fg:#22201c;--muted:#6b6660;--line:#e6e1d8;--accent:#8a5a2b;--code-bg:#f3f0ea;--card:#fff}
@media(prefers-color-scheme:dark){:root{--bg:#16150f;--fg:#e9e5dc;--muted:#a49e94;--line:#2f2c25;--accent:#d7a468;--code-bg:#211f18;--card:#1c1a14}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.75 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif}
.hero{padding:52px 24px 28px;border-bottom:1px solid var(--line);background:var(--card)}
.hero-inner{max-width:1120px;margin:0 auto}
.eyebrow{margin:0 0 8px;color:var(--accent);letter-spacing:.18em;font-size:12px;text-transform:uppercase}
.hero h1{margin:0;font-size:clamp(25px,3.2vw,38px);line-height:1.25;letter-spacing:-.01em}
.hero .sub{margin:12px 0 0;color:var(--muted);font-size:15px;max-width:78ch}
.layout{max-width:1120px;margin:0 auto;padding:32px 24px 96px;display:grid;grid-template-columns:236px 1fr;gap:44px}
@media(max-width:940px){.layout{grid-template-columns:1fr;gap:16px}.toc{position:static!important;max-height:none}}
.toc{position:sticky;top:24px;align-self:start;max-height:84vh;overflow:auto;font-size:13.5px}
.toc h2{font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:var(--muted);margin:0 0 10px}
.toc ol{list-style:none;margin:0;padding:0}
.toc li{margin:0 0 6px}
.toc a{color:var(--muted);text-decoration:none;border-left:2px solid var(--line);padding-left:10px;display:block}
.toc a:hover{color:var(--accent);border-left-color:var(--accent)}
.content{min-width:0}
.content .anchor{opacity:0;margin-left:8px;text-decoration:none;color:var(--muted);font-weight:400}
.content h2:hover .anchor,.content h3:hover .anchor{opacity:.6}
.content h2{margin:48px 0 14px;font-size:23px;padding-bottom:8px;border-bottom:1px solid var(--line);scroll-margin-top:20px}
.content h3{margin:34px 0 10px;font-size:18px;color:var(--accent);scroll-margin-top:20px}
.content h4{margin:24px 0 8px;font-size:15.5px}
.content p{margin:0 0 14px}
.content ul,.content ol{padding-left:24px;margin:0 0 14px}
.content li{margin:0 0 6px}
.content a{color:var(--accent)}
.content blockquote{margin:18px 0;padding:12px 18px;border-left:3px solid var(--accent);background:var(--code-bg);border-radius:0 8px 8px 0;color:var(--muted)}
.content blockquote p:last-child{margin:0}
.content code{background:var(--code-bg);padding:2px 6px;border-radius:4px;font-size:.88em;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.content pre{background:var(--code-bg);padding:15px 18px;border-radius:10px;overflow:auto;border:1px solid var(--line);line-height:1.62}
.content pre code{background:none;padding:0;font-size:13px}
.content table{border-collapse:collapse;width:100%;margin:18px 0;font-size:14.5px;display:block;overflow-x:auto}
.content th,.content td{border:1px solid var(--line);padding:9px 12px;text-align:left;vertical-align:top}
.content th{background:var(--code-bg);font-weight:600;white-space:nowrap}\n.content li.task{list-style:none;margin-left:-20px}\n.content li.task input{margin-right:8px;accent-color:var(--accent);width:15px;height:15px;vertical-align:-2px;cursor:pointer}
.content tr:nth-child(even) td{background:color-mix(in srgb,var(--code-bg) 45%,transparent)}
.content hr{border:0;border-top:1px solid var(--line);margin:40px 0}
.backtop{position:fixed;right:22px;bottom:22px;width:42px;height:42px;border-radius:50%;border:1px solid var(--line);background:var(--card);color:var(--fg);cursor:pointer;opacity:0;transition:opacity .2s;font-size:17px;z-index:9}
.backtop.show{opacity:.9}
footer{max-width:1120px;margin:0 auto;padding:0 24px 60px;color:var(--muted);font-size:13px}
footer a{color:var(--accent)}
"""

JS = """
var bt=document.querySelector('.backtop');
window.addEventListener('scroll',function(){if(window.scrollY>400)bt.classList.add('show');else bt.classList.remove('show')});
function fallbackCopy(t,ok){var a=document.createElement('textarea');a.value=t;
  a.style.position='fixed';a.style.opacity='0';document.body.appendChild(a);a.select();
  try{document.execCommand('copy');ok()}catch(e){}document.body.removeChild(a)}
document.querySelectorAll('.content pre').forEach(function(pre){
  if(pre.classList.contains('mermaid'))return;
  var w=document.createElement('div');w.className='codewrap';
  pre.parentNode.insertBefore(w,pre);w.appendChild(pre);
  var b=document.createElement('button');b.className='copybtn';b.textContent='Copy';
  b.addEventListener('click',function(){
    var t=pre.innerText;
    var ok=function(){b.textContent='Copied';b.classList.add('done');
      setTimeout(function(){b.textContent='Copy';b.classList.remove('done')},1400)};
    if(navigator.clipboard&&navigator.clipboard.writeText){
      navigator.clipboard.writeText(t).then(ok,function(){fallbackCopy(t,ok)});
    }else{fallbackCopy(t,ok)}
  });
  w.appendChild(b);
});
"""

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>{title}</title>
<meta name="description" content="{desc}"/>
<style>{css}</style>
</head>
<body>
<header class="hero"><div class="hero-inner">
<p class="eyebrow">{eyebrow}</p>
<h1>{h1}</h1>
<p class="sub">{sub}</p>
<div class="meta">{meta}</div>
</div></header>
<div class="layout">
<nav class="toc"><h2>On this page</h2><ol>{toc}</ol></nav>
<main class="content">
{body}
</main>
</div>
<footer>{footer}</footer>
<button class="backtop" aria-label="Back to top" onclick="window.scrollTo({{top:0,behavior:'smooth'}})">&#8593;</button>
<script>{js}</script>
<script src="/vendor/mermaid.min.js" defer></script>
<script src="/vendor/mermaid-init.js" defer></script>
</body>
</html>"""

def build(md_path: Path, out_dir: Path) -> Path:
    raw = md_path.read_text(encoding="utf-8")
    fm, _, md_body = raw.partition("\n---FM---\n")
    meta = json.loads(fm)
    body, toc = convert(md_body)
    slug = meta["slug"]
    # nobackground=True：让 pygments 不要自己画底色，底色一律由本页 CSS 决定
    pyg_light = HtmlFormatter(style="friendly", cssclass="hl",
                              nobackground=True).get_style_defs(".hl")
    pyg_dark = HtmlFormatter(style="native", cssclass="hl",
                             nobackground=True).get_style_defs(".hl")
    css = (BASE_CSS
           + "@media(prefers-color-scheme:light){" + pyg_light + "}"
           + "@media(prefers-color-scheme:dark){" + pyg_dark + "}"
           + CSS_EXTRA)
    out_html = TEMPLATE.format(
        title=html.escape(meta["title"]),
        desc=html.escape(meta["summary"]),
        css=css,
        eyebrow=html.escape(meta["eyebrow"]),
        h1=html.escape(meta["h1"]),
        sub=meta["sub"],
        meta="".join(f"<span>{m}</span>" for m in meta["facts"]),
        toc="".join(f'<li><a href="#{i}">{html.escape(n)}</a></li>' for i, n in toc),
        body=body,
        footer=meta["footer"],
        js=JS,
    )
    out = out_dir / f"{slug}.html"
    out.write_text(out_html, encoding="utf-8")
    return out


if __name__ == "__main__":
    outdir = Path(sys.argv[2] if len(sys.argv) > 2 else ".")
    outdir.mkdir(parents=True, exist_ok=True)
    for arg in sys.argv[1].split(","):
        p = build(Path(arg), outdir)
        print(f"{p.name:34} {p.stat().st_size:>8,} bytes")
