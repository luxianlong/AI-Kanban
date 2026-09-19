# -*- coding: utf-8 -*-
"""六韜 — 四つの讀本を text.json / notes.json / en.json / ja.json から組み立てる。"""
import json, os, re

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
COVER = '/img/classics/liutao-cover.jpg'
COVER_W, COVER_H = 560, 815
AI_KEY = 'lt-ai'

SLUGS = [('简体', 'liutao-hans', 'zh-Hans'), ('繁體', 'liutao', 'zh-Hant'),
         ('EN', 'liutao-en', 'en'), ('日本語', 'liutao-ja', 'ja')]
LANG_OF = {s: l for _, s, l in SLUGS}


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


# ---------- 中文讀本（繁體が原本、简体はそこから変換） ----------
def zh_body(T, N, hans):
    P = {p['n']: p for p in T['pian']}
    h, toc = [], []
    for j in T['juan']:
        jid = 'j%d' % j['n']
        toc.append('<a class="l2" href="#%s">%s</a>' % (jid, esc(j['name'])))
        h.append('<h2 id="%s">%s</h2>' % (jid, esc(j['name'])))
        h.append('<div class="ai ai-head ai-juan">%s</div>' % esc(N['juan'][jid]))
        for n in range(j['from'], j['to'] + 1):
            p, cid = P[n], 'c%d' % n
            toc.append('<a class="l3" href="#%s">%s</a>' % (cid, esc(p['title'])))
            h.append('<h3 id="%s">%s</h3>' % (cid, esc(p['title'])))
            h.append('<div class="ai ai-head">%s</div>' % esc(N['pian'][cid]))
            for i, para in enumerate(p['paras'], 1):
                pid = '%s-%d' % (cid, i)
                h.append('<p id="%s">%s</p>' % (pid, esc(para)))
                h.append('<div class="ai" id="ai-%s">%s</div>'
                         % (pid, esc(N['para'][pid])))
    toc.append('<a class="l2" href="#ben">%s</a>'
               % ('底本与校例' if hans else '底本與校例'))
    return '\n'.join(h), '\n'.join(toc)


def zh_colophon(N, hans):
    out = ['<div class="colo">',
           '  <h2 id="ben" class="colo-h">%s</h2>'
           % ('底本与校例' if hans else '底本與校例')]
    for para in N['colophon']:
        out.append('  <p>' + para + '</p>')
    out.append('  <p class="colo-ai">%s</p>'
               % ('卷旨、篇旨与逐段注释为 AI 所撰，可用工具栏第一颗按钮开关。' if hans
                  else '卷旨、篇旨與逐段註釋為 AI 所撰，可用工具欄第一顆按鈕開關。'))
    out.append('</div>')
    return '\n'.join(out)


# ---------- EN / JA ----------
def west_body(T, D):
    P = {p['n']: p for p in T['pian']}
    h, toc = [], []
    for j in T['juan']:
        jid = 'j%d' % j['n']
        jt = D['juan'][jid]
        toc.append('<a class="l2" href="#%s">%s</a>' % (jid, esc(jt['title'])))
        h.append('<h2 id="%s">%s<span class="hz">%s</span></h2>'
                 % (jid, esc(jt['title']), esc(j['name'])))
        h.append('<p class="lead">%s</p>' % jt['head'])
        for n in range(j['from'], j['to'] + 1):
            cid, c = 'c%d' % n, D['chapters']['c%d' % n]
            toc.append('<a class="l3" href="#%s">%s<span class="hz">%s</span></a>'
                       % (cid, esc(c['title']), esc(P[n]['name'])))
            h.append('<h3 id="%s">%s<span class="hz">%s</span></h3>'
                     % (cid, esc(c['title']), esc(P[n]['title'])))
            h.append('<p class="lead">%s</p>' % c['head'])
            for i, para in enumerate(c['paras'], 1):
                h.append('<p id="%s-%d">%s</p>' % (cid, i, para))
    toc.append('<a class="l2" href="#ben">%s</a>' % esc(D['labels']['colophon']))
    return '\n'.join(h), '\n'.join(toc)


def west_colophon(D):
    out = ['<div class="colo">',
           '  <h2 id="ben" class="colo-h">%s</h2>' % esc(D['labels']['colophon'])]
    for para in D['colophon']:
        out.append('  <p>' + para + '</p>')
    out.append('</div>')
    return '\n'.join(out)


# ---------- CSS / JS ----------
def css(which):
    s = open('css-%s.css' % which, encoding='utf-8').read()
    # 本書が使わない規則は落とす（孤立したセレクタを残さない）
    drop = [
        re.compile(r'h4\{[^}]*\}\n'),
        re.compile(r'\.byline\{[^}]*\}\n'),
        re.compile(r'\.colophon\{[^}]*\}\n'),
        re.compile(r'\n\.var\{[^}]*\}\n'),
        re.compile(r'p\.lost\{[^}]*\}\n'),
        re.compile(r'h2 \.hz,h3 \.hz,h4 \.hz'),
    ]
    for rx in drop[:-1]:
        s = rx.sub('', s)
    s = drop[-1].sub('h2 .hz,h3 .hz', s)
    if which == 'hant':
        s = s.replace('body.ai-on .ai-head::before{content:"篇旨"}',
                      'body.ai-on .ai-head::before{content:"篇旨"}\n'
                      'body.ai-on .ai-juan::before{content:"卷旨"}')
    return s


def js(which):
    s = open('js-%s.js' % which, encoding='utf-8').read()
    s = s.replace("'sjs-ai'", "'%s'" % AI_KEY)
    # 本書に h4 はない
    s = s.replace("main h2[id],main h3[id],main h4[id]", "main h2[id],main h3[id]")
    return s


# ---------- ページ ----------
TITLES = {
 'liutao':      '六韜 · 繁體全文 · 免費線上閱讀',
 'liutao-hans': '六韬 · 简体全文 · 免费在线阅读',
 'liutao-en':   'The Six Secret Teachings — Liutao (complete English reading)',
 'liutao-ja':   '六韜 — 日本語全訳 · 無料で読めます',
}
DESCS = {
 'liutao': '《六韜》全本繁體：文韜、武韜、龍韜、虎韜、豹韜、犬韜六卷六十篇，'
           '武經七書之首。舊題周呂望（太公望）撰，銀雀山漢簡證其西漢初已行世。'
           '底本用維基文庫標點本，與四部叢刊景宋刊本、四庫全書本兩本逐字對校，'
           '異文六百五十處；《兵道》的篇次已據二舊本復原，兩處脫文已補。'
           '卷旨、篇旨與逐段註釋可開關。　全文免費線上閱讀，無需註冊；'
           '另有简体版、English 與日本語讀本。',
 'liutao-hans': '《六韬》全本简体：文韬、武韬、龙韬、虎韬、豹韬、犬韬六卷六十篇，'
           '武经七书之首。旧题周吕望（姜太公）撰，银雀山汉简证其西汉初已行世。'
           '底本用维基文库标点本，与四部丛刊景宋刊本、四库全书本两本逐字对校，'
           '异文六百五十处；《兵道》的篇次已据二旧本复原，两处脱文已补。'
           '卷旨、篇旨与逐段注释可开关。　全文免费在线阅读，无需注册；'
           '另有繁體版、English 与日本語读本。',
 'liutao-en': 'A complete English reading of the Liutao, the Six Secret Teachings — '
           'six scrolls and sixty chapters ascribed to Lü Wang, the Grand Duke, '
           'counsellor to the founders of the Zhou, and the first of the Seven '
           'Military Classics. Collated against the Song woodblock edition and '
           'the Siku quanshu text. Free to read in full; also in traditional and '
           'simplified Chinese and in Japanese.',
 'liutao-ja': '『六韜』の日本語全訳。文韜・武韜・龍韜・虎韜・豹韜・犬韜の六巻六十篇、'
           '武経七書の筆頭。旧題は周の呂望（太公望）撰。銀雀山漢簡により'
           '前漢初めには成立していたことが知られる。四部叢刊景宋刊本・'
           '四庫全書本と全文校合し、『兵道』の篇次と二箇所の脱文を正してある。'
           '無料・登録不要。繁体字・簡体字・英語版もあります。',
}
NAMES = {
 'liutao':      ['六韜', '六韬', 'Liutao', 'The Six Secret Teachings', '呂望'],
 'liutao-hans': ['六韬', '六韜', 'Liutao', 'The Six Secret Teachings', '吕望'],
 'liutao-en':   ['The Six Secret Teachings', 'Liutao', 'Tai Gong’s Six Secret Teachings',
                 '六韜', 'Lü Wang (Jiang Ziya)'],
 'liutao-ja':   ['六韜', 'りくとう', 'Liutao', 'The Six Secret Teachings', '呂望'],
}
WD_BOOK = 'https://www.wikidata.org/entity/Q1187353'
WD_AUTHOR = 'https://www.wikidata.org/entity/Q701488'
WIKI = {
 'liutao':      'https://zh.wikipedia.org/wiki/%E5%85%AD%E9%9F%AC',
 'liutao-hans': 'https://zh.wikipedia.org/wiki/%E5%85%AD%E9%9F%AC',
 'liutao-en':   'https://en.wikipedia.org/wiki/Six_Secret_Teachings',
 'liutao-ja':   'https://ja.wikipedia.org/wiki/%E5%85%AD%E9%9F%9C',
}
AUTHOR_WIKI = {
 'liutao':      'https://zh.wikipedia.org/wiki/%E5%A7%9C%E5%AD%90%E7%89%99',
 'liutao-hans': 'https://zh.wikipedia.org/wiki/%E5%A7%9C%E5%AD%90%E7%89%99',
 'liutao-en':   'https://en.wikipedia.org/wiki/Jiang_Ziya',
 'liutao-ja':   'https://ja.wikipedia.org/wiki/%E5%A4%AA%E5%85%AC%E6%9C%9B',
}
MAST = {
 'liutao': '''<header class="mast">
  <h1>六韜</h1>
  <p class="sub">舊題 周　呂望（太公望）　撰　·　六卷六十篇　·　武經七書之首　·　全文免費閱讀</p>
</header>''',
 'liutao-hans': '''<header class="mast">
  <h1>六韬</h1>
  <p class="sub">旧题 周　吕望（姜太公）　撰　·　六卷六十篇　·　武经七书之首　·　全文免费阅读</p>
</header>''',
 'liutao-en': '''<header class="mast">
  <h1>The Six Secret Teachings<span class="hz">六韜</span></h1>
  <p class="sub">ascribed to Lü Wang, the Grand Duke　·　six scrolls, sixty chapters　·　first of the Seven Military Classics　·　free to read in full</p>
</header>''',
 'liutao-ja': '''<header class="mast">
  <h1>六韜<span class="hz">りくとう</span></h1>
  <p class="sub">旧題　周　呂望（太公望）　撰　·　全六巻六十篇　·　武経七書の筆頭　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}
SHELF = {
 'liutao':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'liutao-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'liutao-en':   ('Classics', 'Back to the classics shelf', 'Contents', '— top —'),
 'liutao-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
}
AIBTN = '''  <div class="grp">
    <button id="ai" aria-pressed="false" title="%s">%s <span class="tag">AI</span></button>
  </div>
'''


def jsonld(slug):
    lang = LANG_OF[slug]
    names = NAMES[slug]
    others = [s for _, s, _ in SLUGS if s != slug]
    book = {
      "@type": "Book",
      "@id": "%s/p/%s.html#book" % (SITE, slug),
      "name": names[0],
      "alternateName": names[1:-1],
      "author": {
        "@type": "Person",
        "@id": "%s/p/%s.html#author" % (SITE, slug),
        "name": names[-1],
        "sameAs": [WD_AUTHOR, AUTHOR_WIKI[slug]],
      },
      "inLanguage": lang,
      "url": "%s/p/%s.html" % (SITE, slug),
      "image": SITE + COVER,
      "isAccessibleForFree": True,
      "bookFormat": "https://schema.org/EBook",
      "isPartOf": {"@id": "%s/p/dianji.html#collection" % SITE},
      "workTranslation": [{"@id": "%s/p/%s.html#book" % (SITE, s)} for s in others],
      "sameAs": [WD_BOOK, WIKI[slug]],
    }
    return {"@context": "https://schema.org", "@graph": [book, {
      "@type": "WebPage",
      "@id": "%s/p/%s.html#webpage" % (SITE, slug),
      "url": "%s/p/%s.html" % (SITE, slug),
      "name": TITLES[slug], "description": DESCS[slug], "inLanguage": lang,
      "isPartOf": {"@id": "%s/p/dianji.html#collection" % SITE},
      "about": {"@id": "%s/p/%s.html#book" % (SITE, slug)},
      "primaryImageOfPage": {"@id": "%s/p/%s.html#cover" % (SITE, slug)},
      "breadcrumb": {"@id": "%s/p/%s.html#crumbs" % (SITE, slug)},
      "isAccessibleForFree": True,
    }, {
      "@type": "ImageObject",
      "@id": "%s/p/%s.html#cover" % (SITE, slug),
      "url": SITE + COVER, "contentUrl": SITE + COVER,
      "width": COVER_W, "height": COVER_H,
    }, {
      "@type": "BreadcrumbList",
      "@id": "%s/p/%s.html#crumbs" % (SITE, slug),
      "itemListElement": [
        {"@type": "ListItem", "position": 1, "name": "典籍",
         "item": "%s/p/dianji.html" % SITE},
        {"@type": "ListItem", "position": 2, "name": names[0],
         "item": "%s/p/%s.html" % (SITE, slug)},
      ],
    }]}


def head(slug, sheet):
    lang = LANG_OF[slug]
    alt = '\n'.join('<link rel="alternate" hreflang="%s" href="%s/p/%s.html">'
                    % (l, SITE, s) for _, s, l in SLUGS)
    locale = {'zh-Hans': 'zh_CN', 'zh-Hant': 'zh_TW', 'en': 'en_US', 'ja': 'ja_JP'}[lang]
    alts = '\n'.join('<meta property="og:locale:alternate" content="%s">' % v
                     for v in ['zh_CN', 'zh_TW', 'en_US', 'ja_JP'] if v != locale)
    t, d = esc(TITLES[slug]), esc(DESCS[slug])
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
<link rel="alternate" hreflang="x-default" href="%s/p/liutao-en.html">
<meta name="robots" content="index,follow,max-snippet:-1,max-image-preview:large">
<meta property="og:type" content="book">
<meta property="og:site_name" content="典籍 · Chinese Classics">
<meta property="og:title" content="%s">
<meta property="og:description" content="%s">
<meta property="og:url" content="%s/p/%s.html">
<meta property="og:image" content="%s%s">
<meta property="og:locale" content="%s">
%s
<meta name="twitter:card" content="summary">
<meta name="twitter:title" content="%s">
<meta name="twitter:description" content="%s">
<meta name="twitter:image" content="%s%s">
<script type="application/ld+json">
%s
</script>
<style>
%s
</style>
</head>''' % (lang, t, d, SITE, slug, alt, SITE, t, d, SITE, slug, SITE, COVER,
               locale, alts, t, d, SITE, COVER,
               json.dumps(jsonld(slug), ensure_ascii=False, indent=1), sheet)


def page(slug, sheet, body, toc, colo, script, aibtn):
    back, title, contents, top = SHELF[slug]
    langbar = '\n'.join(
        ('    <span class="btn cur" aria-current="page">%s</span>' % lab)
        if s == slug else
        ('    <a class="btn" href="/p/%s.html">%s</a>' % (s, lab))
        for lab, s, _ in SLUGS)
    return head(slug, sheet) + '''
<body class="">

%s

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

<script src="/vendor/toc-fold.js" defer></script>
</body>
</html>
''' % (MAST[slug], title, back, aibtn, langbar, toc, contents, toc,
       body, colo, top, script)


def write(path, s):
    open(path, 'w', encoding='utf-8').write(s)
    print(os.path.basename(path), len(s.encode()), 'bytes')


def main():
    for slug, tf, nf, hans in (('liutao', 'text.json', 'notes.json', False),
                               ('liutao-hans', 'text-hans.json', 'notes-hans.json', True)):
        T, N = json.load(open(tf)), json.load(open(nf))
        body, toc = zh_body(T, N, hans)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css('hant'), body, toc, zh_colophon(N, hans), js('hant'),
                   AIBTN % (('本页自撰的卷旨、篇旨与逐段注释（AI 生成），默认隐藏', '注释')
                            if hans else
                            ('本頁自撰的卷旨、篇旨與逐段註釋（AI 生成），預設隱藏', '註釋'))))

    T = json.load(open('text.json'))
    for slug, f, which in (('liutao-en', 'en.json', 'en'),
                           ('liutao-ja', 'ja.json', 'ja')):
        if not os.path.exists(f):
            continue
        D = json.load(open(f))
        body, toc = west_body(T, D)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css(which), body, toc, west_colophon(D), js(which), ''))


if __name__ == '__main__':
    main()
