# -*- coding: utf-8 -*-
"""司馬法 — 四つの讀本を text.json / notes.json / en.json / ja.json から組み立てる。

原書の目は五篇（仁本・天子之義・定爵・嚴位・用眾）だけで、篇の下に章も節も
無い。だから見出しは h2 五つと卷末の〈底本與校例〉、h3 は一つも立てない
（#483 で十部の讀本から撰擬の小標題七十七條を削ったのと同じ線）。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
COVER = '/img/classics/simafa-cover.jpg'
COVER_W, COVER_H = 560, 849
AI_KEY = 'sf-ai'

SLUGS = [('简体', 'simafa-hans', 'zh-Hans'), ('繁體', 'simafa', 'zh-Hant'),
         ('EN', 'simafa-en', 'en'), ('日本語', 'simafa-ja', 'ja')]
LANG_OF = {s: l for _, s, l in SLUGS}
PIDS = ['p1', 'p2', 'p3', 'p4', 'p5']
# 篇題は原書のもの。篇次（第一…第五）も原書にある。
ORDINAL = ['第一', '第二', '第三', '第四', '第五']


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


# ---------- 中文讀本（繁體が原本、简体はそこから変換） ----------
def zh_body(T, N, hans):
    h, toc = [], []
    for k, j in enumerate(T):
        pid = PIDS[k]
        title = j['name'] + ORDINAL[k]
        toc.append('<a class="l2" href="#%s">%s</a>' % (pid, esc(title)))
        h.append('<h2 id="%s">%s</h2>' % (pid, esc(title)))
        h.append('<div class="ai ai-head">%s</div>' % esc(N['pian'][pid]))
        for i, para in enumerate(j['paras'], 1):
            sid = '%s-%d' % (pid, i)
            h.append('<p id="%s">%s</p>' % (sid, esc(para)))
            h.append('<div class="ai" id="ai-%s">%s</div>'
                     % (sid, esc(N['para'][sid])))
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
               % ('篇旨与逐段注释为 AI 所撰，可用工具栏第一颗按钮开关。' if hans
                  else '篇旨與逐段註釋為 AI 所撰，可用工具欄第一顆按鈕開關。'))
    out.append('</div>')
    return '\n'.join(out)


# ---------- EN / JA ----------
def west_body(T, X):
    h, toc = [], []
    for k, j in enumerate(T):
        pid = PIDS[k]
        pt = X['pian'][pid]
        hz = j['name'] + ORDINAL[k]
        toc.append('<a class="l2" href="#%s">%s<span class="hz">%s</span></a>'
                   % (pid, esc(pt['title']), esc(hz)))
        h.append('<h2 id="%s">%s<span class="hz">%s</span></h2>'
                 % (pid, esc(pt['title']), esc(hz)))
        h.append('<p class="lead">%s</p>' % pt['head'])
        for i in range(1, len(j['paras']) + 1):
            sid = '%s-%d' % (pid, i)
            h.append('<p id="%s">%s</p>' % (sid, X['para'][sid]))
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
    if which == 'hant':
        # 卷が無いので「卷旨」の角標は要らない。「篇旨」だけ残す。
        s = s.replace('body.ai-on .ai-juan::before{content:"卷旨"}\n', '')
    return s


def js(which):
    s = open('%s/js-%s.js' % (D, which), encoding='utf-8').read()
    s = s.replace("'lt-ai'", "'%s'" % AI_KEY)
    s = s.replace('main h2[id],main h3[id]', 'main h2[id]')
    return s


# ---------- 頁の外まわり ----------
TITLES = {
 'simafa':      '司馬法 · 繁體全文 · 免費線上閱讀',
 'simafa-hans': '司马法 · 简体全文 · 免费在线阅读',
 'simafa-en':   'The Methods of the Sima (Sima fa) — complete English reading',
 'simafa-ja':   '司馬法 — 日本語全訳 · 無料で読めます',
}
DESCS = {
 'simafa': '《司馬法》全本繁體：仁本、天子之義、定爵、嚴位、用眾五篇，正文三千四百餘字，'
           '武經七書之一。《漢書·藝文志》著錄《軍禮司馬法》百五十五篇，今所存者只此五篇。'
           '底本用維基文庫標點本，與四部叢刊影常熟瞿氏鐵琴銅劍樓藏景宋鈔本、四庫全書本'
           '兩本逐字對校，存異文六十七條；底本誤收《太平御覽》所引逸文十八字，已刪；'
           '另補脫字、正形訛五處。篇旨與逐段註釋可開關。'
           '　全文免費線上閱讀，無需註冊；另有简体版、English 與日本語讀本。',
 'simafa-hans': '《司马法》全本简体：仁本、天子之义、定爵、严位、用众五篇，正文三千四百余字，'
           '武经七书之一。《汉书·艺文志》著录《军礼司马法》百五十五篇，今所存者只此五篇。'
           '底本用维基文库标点本，与四部丛刊影常熟瞿氏铁琴铜剑楼藏景宋钞本、四库全书本'
           '两本逐字对校，存异文六十七条；底本误收《太平御览》所引逸文十八字，已删；'
           '另补脱字、正形讹五处。篇旨与逐段注释可开关。'
           '　全文免费在线阅读，无需注册；另有繁體版、English 与日本語读本。',
 'simafa-en': 'A complete English reading of the Methods of the Sima (Sima fa) — '
           'Benevolence the Root, The Duty of the Son of Heaven, Fixing Rank, Strict '
           'Positioning and Employing Numbers: five chapters, some 3,400 characters, '
           'one of the Seven Military Classics and the oldest layer of Chinese military '
           'thought to survive. Collated character by character against the Sibu congkan '
           'facsimile of the Song-copied manuscript from the Qu family library and '
           'against the Siku quanshu text. Free to read in full; also in traditional and '
           'simplified Chinese and in Japanese.',
 'simafa-ja': '『司馬法』の日本語全訳。仁本・天子之義・定爵・嚴位・用眾の五篇、'
           '本文三千四百余字、武経七書の一つ。『漢書』芸文志のいう『軍禮司馬法』'
           '百五十五篇のうち、今に伝わるのはこの五篇だけである。四部叢刊が影印した'
           '常熟瞿氏鐵琴銅劍樓蔵の景宋鈔本と四庫全書本の二本と全文校合し、'
           '底本が誤って本文に取り込んでいた逸文十八字を削った。無料・登録不要。'
           '繁体字・簡体字・英語版もあります。',
}
NAMES = {
 'simafa':      ['司馬法', '司馬穰苴兵法', '軍禮司馬法', 'Sima fa',
                 'The Methods of the Sima', '司馬穰苴'],
 'simafa-hans': ['司马法', '司马穰苴兵法', '军礼司马法', 'Sima fa',
                 'The Methods of the Sima', '司马穰苴'],
 'simafa-en':   ['The Methods of the Sima', 'Sima fa', 'Ssu-ma fa',
                 'The Marshal’s Art of War', '司馬法',
                 'Sima Rangju (Tian Rangju)'],
 'simafa-ja':   ['司馬法', 'しばほう', '司馬穰苴兵法', 'Sima fa',
                 'The Methods of the Sima', '司馬穰苴'],
}
WD_BOOK = 'https://www.wikidata.org/entity/Q1051357'
WD_AUTHOR = 'https://www.wikidata.org/entity/Q1052070'
WIKI = {
 'simafa':      'https://zh.wikipedia.org/wiki/%E5%8F%B8%E9%A9%AC%E6%B3%95',
 'simafa-hans': 'https://zh.wikipedia.org/wiki/%E5%8F%B8%E9%A9%AC%E6%B3%95',
 'simafa-en':   'https://en.wikipedia.org/wiki/The_Methods_of_the_Sima',
 'simafa-ja':   'https://ja.wikipedia.org/wiki/%E5%8F%B8%E9%A6%AC%E6%B3%95',
}
AUTHOR_WIKI = {
 'simafa':      'https://zh.wikipedia.org/wiki/%E7%94%B0%E7%A9%B0%E8%8B%B4',
 'simafa-hans': 'https://zh.wikipedia.org/wiki/%E7%94%B0%E7%A9%B0%E8%8B%B4',
 'simafa-en':   'https://en.wikipedia.org/wiki/Sima_Rangju',
 'simafa-ja':   'https://ja.wikipedia.org/wiki/%E5%8F%B8%E9%A6%AC%E7%A9%B0%E8%8B%B4',
}
MAST = {
 'simafa': '''<header class="mast">
  <h1>司馬法</h1>
  <p class="sub">舊題 齊　司馬穰苴　撰　·　仁本・天子之義・定爵・嚴位・用眾五篇　·　武經七書之一　·　全文免費閱讀</p>
</header>''',
 'simafa-hans': '''<header class="mast">
  <h1>司马法</h1>
  <p class="sub">旧题 齐　司马穰苴　撰　·　仁本・天子之义・定爵・严位・用众五篇　·　武经七书之一　·　全文免费阅读</p>
</header>''',
 'simafa-en': '''<header class="mast">
  <h1>The Methods of the Sima<span class="hz">司馬法</span></h1>
  <p class="sub">ascribed to Sima Rangju of Qi　·　five chapters　·　one of the Seven Military Classics　·　free to read in full</p>
</header>''',
 'simafa-ja': '''<header class="mast">
  <h1>司馬法<span class="hz">しばほう</span></h1>
  <p class="sub">旧題　齊　司馬穰苴　撰　·　仁本・天子之義・定爵・嚴位・用眾の五篇　·　武経七書の一つ　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}
SHELF = {
 'simafa':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'simafa-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'simafa-en':   ('Classics', 'Back to the classics shelf', 'Contents', '— top —'),
 'simafa-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
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
<link rel="alternate" hreflang="x-default" href="%s/p/simafa-en.html">
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
    for slug, tf, nf, hans in (('simafa', 'text.json', 'notes.json', False),
                               ('simafa-hans', 'text-hans.json', 'notes-hans.json', True)):
        if not os.path.exists(f'{D}/{nf}'):
            print('（まだ無い）', nf); continue
        T = json.load(open(f'{D}/{tf}', encoding='utf-8'))
        N = json.load(open(f'{D}/{nf}', encoding='utf-8'))
        body, toc = zh_body(T, N, hans)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css('hant'), body, toc, zh_colophon(N, hans), js('hant'),
                   AIBTN % (('本页自撰的篇旨与逐段注释（AI 生成），默认隐藏', '注释')
                            if hans else
                            ('本頁自撰的篇旨與逐段註釋（AI 生成），預設隱藏', '註釋'))))

    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    for slug, f, which in (('simafa-en', 'en.json', 'en'),
                           ('simafa-ja', 'ja.json', 'ja')):
        if not os.path.exists(f'{D}/{f}'):
            print('（まだ無い）', f); continue
        X = json.load(open(f'{D}/{f}', encoding='utf-8'))
        body, toc = west_body(T, X)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css(which), body, toc, west_colophon(X), js(which), ''))


if __name__ == '__main__':
    main()
