# -*- coding: utf-8 -*-
"""反經（長短經）— builds the four readings from text.json + the note/translation files."""
import json, os, re, subprocess, sys

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
T      = json.load(open('text.json'))
JUAN   = json.load(open('juan.json'))
PT     = {int(k): v for k, v in json.load(open('pian-titles.json')).items()}
NOTES  = json.load(open('notes.json'))      # 繁體 卷旨／篇旨
COLO   = json.load(open('colophon.json'))   # 繁體 底本與校例
EN     = json.load(open('en.json')) if os.path.exists('en.json') else None
JA     = json.load(open('ja.json')) if os.path.exists('ja.json') else None
PIAN   = {r['n']: r for r in T['pian']}
COLO_HANT = COLO

def esc(s):
    return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')

# ---------- Chinese reading (繁體 master; 简体 is converted from it) ----------
def runs_html(p):
    out = []
    for kind, txt in p:
        if kind == 0:   out.append(esc(txt))
        elif kind == 1: out.append('<span class="zhu">〔' + esc(txt) + '〕</span>')
        else:           out.append('<span class="sf">' + esc(txt) + '</span>')
    return ''.join(out)

def zh_body():
    h = []
    toc = []
    toc.append('<a class="l2" href="#xu">序</a>')
    h.append('<h2 id="xu">序</h2>')
    h.append('<div class="ai ai-head">' + esc(NOTES['xu']) + '</div>')
    for i, p in enumerate(T['pre']['paras'], 1):
        h.append('<p id="xu-%d">%s</p>' % (i, runs_html(p)))
    for j in JUAN:
        jid = 'j%d' % j['n']
        toc.append('<a class="l2" href="#%s">%s　%s</a>' % (jid, j['name'], j['sub']))
        h.append('<h2 id="%s">%s　%s</h2>' % (jid, j['name'], j['sub']))
        h.append('<div class="ai ai-head ai-juan">' + esc(NOTES[jid]) + '</div>')
        for n in range(j['from'], j['to'] + 1):
            cid = 'c%d' % n
            toc.append('<a class="l3" href="#%s">%s</a>' % (cid, PT[n]))
            h.append('<h3 id="%s">%s</h3>' % (cid, PT[n]))
            h.append('<div class="ai ai-head">' + esc(NOTES[cid]) + '</div>')
            for i, p in enumerate(PIAN[n]['paras'], 1):
                h.append('<p id="%s-%d">%s</p>' % (cid, i, runs_html(p)))
    toc.append('<a class="l2" href="#ben">%s</a>'
               % ('底本与校例' if COLO is not COLO_HANT else '底本與校例'))
    return '\n'.join(h), '\n'.join(toc)

def zh_colophon():
    hans = COLO is not COLO_HANT
    h = ['<div class="colo">',
         '  <h2 id="ben" class="colo-h">%s</h2>' % ('底本与校例' if hans else '底本與校例')]
    for p in COLO:
        h.append('  <p>' + p + '</p>')
    h.append('  <p class="colo-ai">%s</p>' %
             ('卷旨与篇旨为 AI 所撰，可用工具栏第一颗按钮开关。' if hans
              else '卷旨與篇旨為 AI 所撰，可用工具欄第一顆按鈕開關。'))
    h.append('</div>')
    return '\n'.join(h)

# ---------- EN / JA readings ----------
def west_body(D, lang):
    h, toc = [], []
    lbl = D['labels']
    toc.append('<a class="l2" href="#xu">%s</a>' % esc(lbl['preface']))
    h.append('<h2 id="xu">%s<span class="hz">序</span></h2>' % esc(lbl['preface']))
    h.append('<p class="lead">%s</p>' % D['chapters']['xu']['head'])
    for i, p in enumerate(D['chapters']['xu']['paras'], 1):
        h.append('<p id="xu-%d">%s</p>' % (i, p))
    for j in JUAN:
        jid = 'j%d' % j['n']
        jt = D['juan'][jid]
        toc.append('<a class="l2" href="#%s">%s</a>' % (jid, esc(jt['title'])))
        h.append('<h2 id="%s">%s<span class="hz">%s　%s</span></h2>' % (jid, esc(jt['title']), j['name'], j['sub']))
        h.append('<p class="lead">%s</p>' % jt['head'])
        for n in range(j['from'], j['to'] + 1):
            cid = 'c%d' % n
            c = D['chapters'][cid]
            toc.append('<a class="l3" href="#%s">%s</a>' % (cid, esc(c['title'])))
            h.append('<h3 id="%s">%s<span class="hz">%s</span></h3>' % (cid, esc(c['title']), PT[n]))
            if c.get('head'): h.append('<p class="lead">%s</p>' % c['head'])
            for i, p in enumerate(c['paras'], 1):
                h.append('<p id="%s-%d">%s</p>' % (cid, i, p))
    toc.append('<a class="l2" href="#ben">%s</a>' % esc(lbl['colophon']))
    return '\n'.join(h), '\n'.join(toc)

def west_colophon(D):
    h = ['<div class="colo">', '  <h2 id="ben" class="colo-h">%s</h2>' % esc(D['labels']['colophon'])]
    for p in D['colophon']:
        h.append('  <p>' + p + '</p>')
    h.append('</div>')
    return '\n'.join(h)

# ---------- page shell ----------
LANGS = [('简体','fanjing-hans'), ('繁體','fanjing'), ('EN','fanjing-en'), ('日本語','fanjing-ja')]

def langbar(cur):
    out = []
    for label, slug in LANGS:
        if slug == cur:
            out.append('    <span class="btn cur" aria-current="page">%s</span>' % label)
        else:
            out.append('    <a class="btn" href="/p/%s.html">%s</a>' % (slug, label))
    return '\n'.join(out)

def head(slug, lang, title, desc, ld, css):
    hrefs = {'fanjing-hans':'zh-Hans','fanjing':'zh-Hant','fanjing-en':'en','fanjing-ja':'ja'}
    alt = '\n'.join('<link rel="alternate" hreflang="%s" href="%s/p/%s.html">' % (v, SITE, k)
                    for k, v in [('fanjing-hans','zh-Hans'),('fanjing','zh-Hant'),
                                 ('fanjing-en','en'),('fanjing-ja','ja')])
    locale = {'zh-Hans':'zh_CN','zh-Hant':'zh_TW','en':'en_US','ja':'ja_JP'}[lang]
    alts = [v for v in ['zh_CN','zh_TW','en_US','ja_JP'] if v != locale]
    return '''<!doctype html>
<html lang="%s">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
<meta name="description" content="%s">
<!-- search metadata -->
<link rel="canonical" href="%s/p/%s.html">
%s
<link rel="alternate" hreflang="x-default" href="%s/p/fanjing-en.html">
<meta name="robots" content="index,follow,max-snippet:-1,max-image-preview:large">
<meta property="og:type" content="book">
<meta property="og:site_name" content="典籍 · Chinese Classics">
<meta property="og:title" content="%s">
<meta property="og:description" content="%s">
<meta property="og:url" content="%s/p/%s.html">
<meta property="og:image" content="%s/img/classics/fanjing-cover.jpg">
<meta property="og:locale" content="%s">
%s
<meta name="twitter:card" content="summary">
<meta name="twitter:title" content="%s">
<meta name="twitter:description" content="%s">
<meta name="twitter:image" content="%s/img/classics/fanjing-cover.jpg">
<script type="application/ld+json">
%s
</script>
<style>
%s
</style>
</head>''' % (lang, esc(title), esc(desc), SITE, slug, alt, SITE,
               esc(title), esc(desc), SITE, slug, SITE, locale,
               '\n'.join('<meta property="og:locale:alternate" content="%s">' % a for a in alts),
               esc(title), esc(desc), SITE,
               json.dumps(ld, ensure_ascii=False, indent=1), css)

WD = {'fanjing':'Q10940271', 'author':'Q10942353'}

def jsonld(slug, lang, title, desc, names, sameas, author_sameas, w, hh):
    others = [s for _, s in LANGS if s != slug]
    g = [{
      "@type": "Book",
      "@id": "%s/p/%s.html#book" % (SITE, slug),
      "name": names[0],
      "alternateName": names[1:],
      "author": {"@id": "%s/p/%s.html#author" % (SITE, slug)},
      "inLanguage": lang,
      "url": "%s/p/%s.html" % (SITE, slug),
      "image": "%s/img/classics/fanjing-cover.jpg" % SITE,
      "isAccessibleForFree": True,
      "bookFormat": "https://schema.org/EBook",
      "isPartOf": {"@id": "%s/p/dianji.html#collection" % SITE},
      "workTranslation": [{"@id": "%s/p/%s.html#book" % (SITE, s)} for s in others],
      "sameAs": sameas,
    }, {
      "@type": "Person",
      "@id": "%s/p/%s.html#author" % (SITE, slug),
      "name": names[-1],
      "sameAs": author_sameas,
    }, {
      "@type": "WebPage",
      "@id": "%s/p/%s.html#webpage" % (SITE, slug),
      "url": "%s/p/%s.html" % (SITE, slug),
      "name": title, "description": desc, "inLanguage": lang,
      "isPartOf": {"@id": "%s/p/dianji.html#collection" % SITE},
      "about": {"@id": "%s/p/%s.html#book" % (SITE, slug)},
      "primaryImageOfPage": {"@id": "%s/p/%s.html#cover" % (SITE, slug)},
      "breadcrumb": {"@id": "%s/p/%s.html#crumbs" % (SITE, slug)},
      "isAccessibleForFree": True,
    }, {
      "@type": "ImageObject",
      "@id": "%s/p/%s.html#cover" % (SITE, slug),
      "url": "%s/img/classics/fanjing-cover.jpg" % SITE,
      "contentUrl": "%s/img/classics/fanjing-cover.jpg" % SITE,
      "width": w, "height": hh,
    }, {
      "@type": "BreadcrumbList",
      "@id": "%s/p/%s.html#crumbs" % (SITE, slug),
      "itemListElement": [
        {"@type": "ListItem", "position": 1, "name": "典籍", "item": "%s/p/dianji.html" % SITE},
        {"@type": "ListItem", "position": 2, "name": names[0], "item": "%s/p/%s.html" % (SITE, slug)},
      ],
    }]
    return {"@context": "https://schema.org", "@graph": g}

# ---------- CSS: the shared classics stylesheet plus this book's two additions ----------
def css_zh():
    css = open('css-hant.css', encoding='utf-8').read()
    css = css.replace('body.ai-on .ai-head::before{content:"篇旨"}',
'''body.ai-on .ai-head::before{content:"篇旨"}
body.ai-on .ai-juan::before{content:"卷旨"}''')
    return css + '''

/* 趙蕤自注。原書刻作雙行小字夾注，這裏縮小一號、換一種顏色，〔　〕是界標。
   全書五成七的篇幅是這些注，所以它們留在正文裏，不歸「註釋」那顆按鈕管——
   那顆按鈕只管本頁自撰的卷旨與篇旨。 */
.zhu{font-size:.86em; color:var(--ink-soft)}
/* 《是非》一篇的「是曰」「非曰」是原書的標目（四庫本刻作小字），不是注 */
.sf{display:inline-block; font-size:.72em; letter-spacing:.14em; text-indent:.14em;
  color:var(--seal); border:1px solid var(--seal-soft); border-radius:3px;
  padding:0 5px; margin-right:.55em; vertical-align:.16em; white-space:nowrap}
h3{font-size:1.06rem; color:var(--ink-soft); letter-spacing:.24em;
  text-indent:.24em; margin:52px 0 2px; font-weight:400}
'''

def css_west(which):
    return open('css-%s.css' % which, encoding='utf-8').read()

# ---------- assemble ----------
def page(slug, lang, title, desc, css, bodyhead, toc, body, colo, js, aibtn):
    ld = JSONLD[slug]
    return (head(slug, lang, title, desc, ld, css) + '''
<body class="">

''' + bodyhead + '''

<div class="bar">
  <div class="grp">
    <a class="btn" href="/p/dianji.html" title="%s">%s</a>
  </div>
%s  <div class="grp langs">
%s
  </div>
</div>

<div class="wrap">
  <nav class="toc" id="toc">
%s
  </nav>

  <main>
    <details class="toc-m"><summary>%s</summary>
%s
    </details>

%s

%s

    <a class="top" href="#">%s</a>
  </main>
</div>

<script>
%s
</script>
</body>
</html>
''' % (SHELF_T[slug][1], SHELF_T[slug][0], aibtn, langbar(slug), toc,
       SHELF_T[slug][2], toc, body, colo, SHELF_T[slug][3], js))

SHELF_T = {
 'fanjing':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'fanjing-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'fanjing-en':   ('Classics', 'Back to the classics shelf', 'Contents', '— top —'),
 'fanjing-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
}
AIBTN = '''  <div class="grp">
    <button id="ai" aria-pressed="false" title="%s">%s <span class="tag">AI</span></button>
  </div>
'''

TITLES = {
 'fanjing':      '反經（長短經）· 繁體全文 · 免費線上閱讀',
 'fanjing-hans': '反经（长短经）· 简体全文 · 免费在线阅读',
 'fanjing-en':   'The Classic of Reversal — Fanjing / Changduanjing (English)',
 'fanjing-ja':   '反経（長短経）— 日本語全訳 · 無料で読めます',
}
DESCS = {
 'fanjing': '《反經》（本名《長短經》）全本繁體：唐趙蕤撰，九卷六十四篇，趙蕤自注佔全書五成六，一併錄出。底本用維基文庫標點本，與四庫全書本逐字對校，異文二千五百餘處；卷四《霸圖》與卷五《七雄略》的錯簡已據四庫本復原，《適變》等五處脫文已補。卷旨篇旨可開關。　全文免費線上閱讀，無需註冊；另有简体版、English 與日本語讀本。',
 'fanjing-hans': '《反经》（本名《长短经》）全本简体：唐赵蕤撰，九卷六十四篇，赵蕤自注占全书五成六，一并录出。底本用维基文库标点本，与四库全书本逐字对校，异文二千五百余处；卷四《霸图》与卷五《七雄略》的错简已据四库本复原，《适变》等五处脱文已补。卷旨篇旨可开关。　全文免费在线阅读，无需注册；另有繁體版、English 与日本語读本。',
 'fanjing-en': 'A complete English reading of the Fanjing, properly the Changduanjing — the Classic of the Long and the Short — written by Zhao Rui of the Tang in 716: nine scrolls, sixty-four chapters on statecraft, judging men, historical precedent and war, of which fifty-six per cent is the author’s own commentary. Free to read in full; also in traditional and simplified Chinese and in Japanese.',
 'fanjing-ja': '『反経』（本名『長短経』）の日本語全訳。唐の趙蕤が開元四年（七一六）に著した九巻六十四篇。全体の五割六分を占める趙蕤自注も訳出した。四庫全書本と全文校合し、底本の錯簡と脱文を正してある。無料・登録不要。繁體字・簡体字・英語版もあります。',
}
NAMES = {
 'fanjing':      ['反經', '長短經', '长短经', 'Fanjing', 'Changduanjing', '趙蕤'],
 'fanjing-hans': ['反经', '长短经', '長短經', 'Fanjing', 'Changduanjing', '赵蕤'],
 'fanjing-en':   ['The Classic of Reversal', 'Fanjing', 'Changduanjing — The Classic of the Long and the Short', '長短經', 'Zhao Rui'],
 'fanjing-ja':   ['反経', '長短経', 'Changduanjing', '長短經', '趙蕤'],
}
LANGCODE = {'fanjing': 'zh-Hant', 'fanjing-hans': 'zh-Hans', 'fanjing-en': 'en', 'fanjing-ja': 'ja'}
SAMEAS = {
 'fanjing':      ['https://www.wikidata.org/entity/Q15928000', 'https://zh.wikipedia.org/wiki/%E9%95%B7%E7%9F%AD%E7%B6%93'],
 'fanjing-hans': ['https://www.wikidata.org/entity/Q15928000', 'https://zh.wikipedia.org/wiki/%E9%95%B7%E7%9F%AD%E7%B6%93'],
 'fanjing-en':   ['https://www.wikidata.org/entity/Q15928000'],
 'fanjing-ja':   ['https://www.wikidata.org/entity/Q15928000'],
}
AUTHOR_SAMEAS = {
 'fanjing':      ['https://www.wikidata.org/entity/Q7210156', 'https://zh.wikipedia.org/wiki/%E8%B5%B5%E8%95%A4'],
 'fanjing-hans': ['https://www.wikidata.org/entity/Q7210156', 'https://zh.wikipedia.org/wiki/%E8%B5%B5%E8%95%A4'],
 'fanjing-en':   ['https://www.wikidata.org/entity/Q7210156'],
 'fanjing-ja':   ['https://www.wikidata.org/entity/Q7210156'],
}
COVER_W, COVER_H = 560, 990
JSONLD = {}
for slug in TITLES:
    g = jsonld(slug, LANGCODE[slug], TITLES[slug], DESCS[slug], NAMES[slug],
               SAMEAS[slug], AUTHOR_SAMEAS[slug], COVER_W, COVER_H)
    book = g['@graph'][0]
    person = g['@graph'][1]
    book['author'] = person          # inline: the SEO test reads book.author.sameAs
    g['@graph'] = [book] + g['@graph'][2:]
    JSONLD[slug] = g

MAST = {
 'fanjing': '''<header class="mast">
  <h1>反經</h1>
  <p class="sub">唐　趙蕤　撰　·　本名《長短經》　·　九卷六十四篇　·　全文免費閱讀</p>
</header>''',
 'fanjing-hans': '''<header class="mast">
  <h1>反经</h1>
  <p class="sub">唐　赵蕤　撰　·　本名《长短经》　·　九卷六十四篇　·　全文免费阅读</p>
</header>''',
 'fanjing-en': '''<header class="mast">
  <h1>The Classic of Reversal<span class="hz">反經 · 長短經</span></h1>
  <p class="sub">Zhao Rui　·　Tang dynasty, 716　·　nine scrolls, sixty-four chapters　·　free to read in full</p>
</header>''',
 'fanjing-ja': '''<header class="mast">
  <h1>反経<span class="hz">はんけい</span></h1>
  <p class="sub">唐　趙蕤　撰　·　本名『長短経』　·　全九巻六十四篇　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}

def write(path, s):
    open(path, 'w', encoding='utf-8').write(s)
    print(path, len(s.encode()), 'bytes')

def load_hans():
    global T, JUAN, PT, NOTES, COLO, PIAN
    T    = json.load(open('text-hans.json'))
    JUAN = json.load(open('juan-hans.json'))
    PT   = {int(k): v for k, v in json.load(open('pian-titles-hans.json')).items()}
    NOTES= json.load(open('notes-hans.json'))
    COLO = json.load(open('colophon-hans.json'))
    PIAN = {r['n']: r for r in T['pian']}

def main():
    js = open('js-hant.js', encoding='utf-8').read().replace("'lzj-ai'", "'fj-ai'")

    body, toc = zh_body()
    zh = page('fanjing', 'zh-Hant', TITLES['fanjing'], DESCS['fanjing'], css_zh(),
              MAST['fanjing'], toc, body, zh_colophon(), js,
              AIBTN % ('本頁自撰的卷旨與篇旨（AI 生成），預設隱藏', '註釋'))
    write(os.path.join(ROOT, 'fanjing.html'), zh)

    load_hans()
    body, toc = zh_body()
    hans = page('fanjing-hans', 'zh-Hans', TITLES['fanjing-hans'], DESCS['fanjing-hans'],
                css_zh(), MAST['fanjing-hans'], toc, body, zh_colophon(), js,
                AIBTN % ('本页自撰的卷旨与篇旨（AI 生成），默认隐藏', '注释'))
    write(os.path.join(ROOT, 'fanjing-hans.html'), hans)

    if EN:
        b, t = west_body(EN, 'en')
        write(os.path.join(ROOT, 'fanjing-en.html'),
              page('fanjing-en', 'en', TITLES['fanjing-en'], DESCS['fanjing-en'],
                   css_west('en'), MAST['fanjing-en'], t, b, west_colophon(EN),
                   open('js-en.js', encoding='utf-8').read(), ''))
    if JA:
        b, t = west_body(JA, 'ja')
        write(os.path.join(ROOT, 'fanjing-ja.html'),
              page('fanjing-ja', 'ja', TITLES['fanjing-ja'], DESCS['fanjing-ja'],
                   css_west('ja'), MAST['fanjing-ja'], t, b, west_colophon(JA),
                   open('js-ja.js', encoding='utf-8').read(), ''))

if __name__ == '__main__':
    main()
