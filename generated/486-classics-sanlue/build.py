# -*- coding: utf-8 -*-
"""三略 — 四つの讀本を text.json / notes.json / en.json / ja.json から組み立てる。

《三略》には篇も章も無い。原書の目は上略・中略・下略の三つだけなので、
見出しも h2 三つと卷末の〈底本與校例〉だけ。撰んだ小標題は立てない
（#483 で十部の讀本から撰擬の小標題七十七條を削ったのと同じ線）。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
COVER = '/img/classics/sanlue-cover.jpg'
COVER_W, COVER_H = 560, 795
AI_KEY = 'sl-ai'

SLUGS = [('简体', 'sanlue-hans', 'zh-Hans'), ('繁體', 'sanlue', 'zh-Hant'),
         ('EN', 'sanlue-en', 'en'), ('日本語', 'sanlue-ja', 'ja')]
LANG_OF = {s: l for _, s, l in SLUGS}
JIDS = ['j1', 'j2', 'j3']


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


# ---------- 中文讀本（繁體が原本、简体はそこから変換） ----------
def zh_body(T, N, hans):
    h, toc = [], []
    for k, j in enumerate(T):
        jid = JIDS[k]
        toc.append('<a class="l2" href="#%s">%s</a>' % (jid, esc(j['name'])))
        h.append('<h2 id="%s">%s</h2>' % (jid, esc(j['name'])))
        h.append('<div class="ai ai-head ai-juan">%s</div>' % esc(N['juan'][jid]))
        for i, para in enumerate(j['paras'], 1):
            pid = '%s-%d' % (jid, i)
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
               % ('卷旨与逐段注释为 AI 所撰，可用工具栏第一颗按钮开关。' if hans
                  else '卷旨與逐段註釋為 AI 所撰，可用工具欄第一顆按鈕開關。'))
    out.append('</div>')
    return '\n'.join(out)


# ---------- EN / JA ----------
def west_body(T, X):
    h, toc = [], []
    for k, j in enumerate(T):
        jid = jt = JIDS[k]
        jt = X['juan'][jid]
        toc.append('<a class="l2" href="#%s">%s<span class="hz">%s</span></a>'
                   % (jid, esc(jt['title']), esc(j['name'])))
        h.append('<h2 id="%s">%s<span class="hz">%s</span></h2>'
                 % (jid, esc(jt['title']), esc(j['name'])))
        h.append('<p class="lead">%s</p>' % jt['head'])
        for i in range(1, len(j['paras']) + 1):
            pid = '%s-%d' % (jid, i)
            h.append('<p id="%s">%s</p>' % (pid, X['para'][pid]))
    toc.append('<a class="l2" href="#ben">%s</a>' % esc(X['labels']['colophon']))
    return '\n'.join(h), '\n'.join(toc)


def west_colophon(X):
    out = ['<div class="colo">',
           '  <h2 id="ben" class="colo-h">%s</h2>' % esc(X['labels']['colophon'])]
    for para in X['colophon']:
        out.append('  <p>' + para + '</p>')
    out.append('</div>')
    return '\n'.join(out)


# ---------- CSS / JS ----------
def css(which):
    s = open('%s/css-%s.css' % (D, which), encoding='utf-8').read()
    # 本書に h3 は無い。孤立したセレクタを残さないよう、規則ごと落とすか
    # h2 だけの形に書き換える。
    s = re.sub(r'h3\{[^}]*\}\n', '', s)
    s = s.replace('h2 .hz,h3 .hz', 'h2 .hz')
    s = s.replace('  nav.toc a.l3{padding-left:20px}\n', '')
    s = s.replace('details.toc-m a.l3{padding-left:16px} ', '')
    s = s.replace('nav.toc .hz,details.toc-m .hz', 'nav.toc .hz,details.toc-m .hz')
    if which == 'hant':
        s = s.replace('body.ai-on .ai-head::before{content:"篇旨"}\n', '')
    return s


def js(which):
    s = open('%s/js-%s.js' % (D, which), encoding='utf-8').read()
    s = s.replace("'lt-ai'", "'%s'" % AI_KEY)
    s = s.replace('main h2[id],main h3[id]', 'main h2[id]')
    return s


# ---------- 頁の外まわり ----------
TITLES = {
 'sanlue':      '三略（黃石公三略）· 繁體全文 · 免費線上閱讀',
 'sanlue-hans': '三略（黄石公三略）· 简体全文 · 免费在线阅读',
 'sanlue-en':   'The Three Strategies of Huang Shigong — complete English reading',
 'sanlue-ja':   '三略（黄石公三略）— 日本語全訳 · 無料で読めます',
}
DESCS = {
 'sanlue': '《三略》（黃石公三略）全本繁體：上略、中略、下略三卷，正文三千八百餘字，'
           '武經七書之一。舊題秦圯上老人黃石公授張良。底本用維基文庫標點本，'
           '與四庫全書本《黃石公三畧》白文、明劉寅《三畧直解》所載經文兩本逐字對校，'
           '改正底本機械之誤二十二處（簡繁回轉十六、形近而訛四、脫字一、不成義一），'
           '存異文六十二條；分段依劉寅所分之節。卷旨與逐段註釋可開關。'
           '　全文免費線上閱讀，無需註冊；另有简体版、English 與日本語讀本。',
 'sanlue-hans': '《三略》（黄石公三略）全本简体：上略、中略、下略三卷，正文三千八百余字，'
           '武经七书之一。旧题秦圯上老人黄石公授张良。底本用维基文库标点本，'
           '与四库全书本《黄石公三略》白文、明刘寅《三略直解》所载经文两本逐字对校，'
           '改正底本机械之误二十二处（简繁回转十六、形近而讹四、脱字一、不成义一），'
           '存异文六十二条；分段依刘寅所分之节。卷旨与逐段注释可开关。'
           '　全文免费在线阅读，无需注册；另有繁體版、English 与日本語读本。',
 'sanlue-en': 'A complete English reading of the Three Strategies of Huang Shigong '
           '(Sanlüe) — Upper, Middle and Lower, some 3,800 characters, one of the '
           'Seven Military Classics and the book the Yellow Stone Elder is said to '
           'have handed Zhang Liang on the bridge at Xiapi. Collated character by '
           'character against the Siku quanshu plain text and the classic text '
           'carried in Liu Yin’s Ming commentary, with the 1604 Fushimi movable-type '
           'printing consulted at the decisive readings. Free to read in full; also '
           'in traditional and simplified Chinese and in Japanese.',
 'sanlue-ja': '『三略』（黄石公三略）の日本語全訳。上略・中略・下略の三巻、本文三千八百余字、'
           '武経七書の一つ。秦の圯上の老人 黄石公が張良に授けたと伝える兵書。'
           '四庫全書本の白文と明・劉寅『三略直解』所載の経文の二本と全文校合し、'
           '底本の機械的な誤り二十二箇所を改めた。慶長九年（一六〇四）の伏見版古活字本も'
           '要所で実見している。無料・登録不要。繁体字・簡体字・英語版もあります。',
}
NAMES = {
 'sanlue':      ['三略', '黃石公三略', '三畧', 'Sanlue',
                 'The Three Strategies of Huang Shigong', '黃石公'],
 'sanlue-hans': ['三略', '黄石公三略', '三畧', 'Sanlue',
                 'The Three Strategies of Huang Shigong', '黄石公'],
 'sanlue-en':   ['The Three Strategies of Huang Shigong', 'Sanlue', 'San Lüe',
                 'Three Strategies', '三略', 'Huang Shigong (the Yellow Stone Elder)'],
 'sanlue-ja':   ['三略', 'さんりゃく', '黄石公三略', 'Sanlue',
                 'The Three Strategies of Huang Shigong', '黄石公'],
}
WD_BOOK = 'https://www.wikidata.org/entity/Q1051338'
WD_AUTHOR = 'https://www.wikidata.org/entity/Q10567265'
WIKI = {
 'sanlue':      'https://zh.wikipedia.org/wiki/%E4%B8%89%E7%95%A5',
 'sanlue-hans': 'https://zh.wikipedia.org/wiki/%E4%B8%89%E7%95%A5',
 'sanlue-en':   'https://en.wikipedia.org/wiki/Three_Strategies_of_Huang_Shigong',
 'sanlue-ja':   'https://ja.wikipedia.org/wiki/%E4%B8%89%E7%95%A5',
}
AUTHOR_WIKI = {
 'sanlue':      'https://zh.wikipedia.org/wiki/%E9%BB%84%E7%9F%B3%E5%85%AC',
 'sanlue-hans': 'https://zh.wikipedia.org/wiki/%E9%BB%84%E7%9F%B3%E5%85%AC',
 'sanlue-en':   'https://en.wikipedia.org/wiki/Huang_Shigong',
 'sanlue-ja':   'https://ja.wikipedia.org/wiki/%E9%BB%84%E7%9F%B3%E5%85%AC',
}
MAST = {
 'sanlue': '''<header class="mast">
  <h1>三略</h1>
  <p class="sub">舊題 秦　黃石公　撰　·　上略・中略・下略三卷　·　武經七書之一　·　全文免費閱讀</p>
</header>''',
 'sanlue-hans': '''<header class="mast">
  <h1>三略</h1>
  <p class="sub">旧题 秦　黄石公　撰　·　上略・中略・下略三卷　·　武经七书之一　·　全文免费阅读</p>
</header>''',
 'sanlue-en': '''<header class="mast">
  <h1>The Three Strategies<span class="hz">三略</span></h1>
  <p class="sub">ascribed to Huang Shigong, the Yellow Stone Elder　·　Upper, Middle and Lower　·　one of the Seven Military Classics　·　free to read in full</p>
</header>''',
 'sanlue-ja': '''<header class="mast">
  <h1>三略<span class="hz">さんりゃく</span></h1>
  <p class="sub">旧題　秦　黄石公　撰　·　上略・中略・下略の三巻　·　武経七書の一つ　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}
SHELF = {
 'sanlue':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'sanlue-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'sanlue-en':   ('Classics', 'Back to the classics shelf', 'Contents', '— top —'),
 'sanlue-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
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
<link rel="alternate" hreflang="x-default" href="%s/p/sanlue-en.html">
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
    for slug, tf, nf, hans in (('sanlue', 'text.json', 'notes.json', False),
                               ('sanlue-hans', 'text-hans.json', 'notes-hans.json', True)):
        T = json.load(open(f'{D}/{tf}', encoding='utf-8'))
        N = json.load(open(f'{D}/{nf}', encoding='utf-8'))
        body, toc = zh_body(T, N, hans)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css('hant'), body, toc, zh_colophon(N, hans), js('hant'),
                   AIBTN % (('本页自撰的卷旨与逐段注释（AI 生成），默认隐藏', '注释')
                            if hans else
                            ('本頁自撰的卷旨與逐段註釋（AI 生成），預設隱藏', '註釋'))))

    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    for slug, f, which in (('sanlue-en', 'en.json', 'en'),
                           ('sanlue-ja', 'ja.json', 'ja')):
        if not os.path.exists(f'{D}/{f}'):
            print('（まだ無い）', f); continue
        X = json.load(open(f'{D}/{f}', encoding='utf-8'))
        body, toc = west_body(T, X)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css(which), body, toc, west_colophon(X), js(which), ''))


if __name__ == '__main__':
    main()
