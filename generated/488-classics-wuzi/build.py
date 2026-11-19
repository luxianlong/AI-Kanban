# -*- coding: utf-8 -*-
"""吳子 — 四つの讀本を text.json / notes.json / en.json / ja.json から組み立てる。

原書の目は六篇（圖國・料敵・治兵・論將・應變・勵士）だけで、篇の下に章も節も
無い。だから見出しは h2 六つと卷末の〈底本與校例〉、h3 は一つも立てない
（#483 で十部の讀本から撰擬の小標題七十七條を削ったのと同じ線）。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
COVER = '/img/classics/wuzi-cover.jpg'
COVER_W, COVER_H = 560, 773
AI_KEY = 'wz-ai'

SLUGS = [('简体', 'wuzi-hans', 'zh-Hans'), ('繁體', 'wuzi', 'zh-Hant'),
         ('EN', 'wuzi-en', 'en'), ('日本語', 'wuzi-ja', 'ja')]
LANG_OF = {s: l for _, s, l in SLUGS}
PIDS = ['p1', 'p2', 'p3', 'p4', 'p5', 'p6']
# 篇題は原書のもの。篇次（第一…第六）も底本と叢刊本にある（四庫本には無い
# ——卷末の〈底本與校例〉を見よ）。
ORDINAL = ['第一', '第二', '第三', '第四', '第五', '第六']


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
    # 雛形に取った《司馬法》の頁も篇だけの書なので、h3 の規則も「卷旨」の
    # 規則も初めから無い。念のため殘っていないことだけ確かめて、そのまま使う。
    s = open('%s/css-%s.css' % (D, which), encoding='utf-8').read()
    assert not re.search(r'h3\{', s) and 'ai-juan' not in s, '雛形に h3／卷旨の規則が殘っている'
    return s


def js(which):
    s = open('%s/js-%s.js' % (D, which), encoding='utf-8').read()
    s = s.replace("'sf-ai'", "'%s'" % AI_KEY)
    assert 'main h3[id]' not in s, '雛形の scrollspy が h3 を見ている'
    return s


# ---------- 頁の外まわり ----------
TITLES = {
 'wuzi':      '吳子（吳起兵法） · 繁體全文 · 免費線上閱讀',
 'wuzi-hans': '吴子（吴起兵法） · 简体全文 · 免费在线阅读',
 'wuzi-en':   'The Wuzi (Wu Qi\u2019s Art of War) \u2014 complete English reading',
 'wuzi-ja':   '呉子 \u2014 日本語全訳 · 無料で読めます',
}
DESCS = {
 'wuzi': '《吳子》全本繁體：圖國、料敵、治兵、論將、應變、勵士六篇，正文四千七百二十八字，'
         '武經七書之一，與《孫子》並稱「孫吳」。吳起事魏文侯、武侯而守西河，'
         '與諸侯大戰七十六，全勝六十四。「勇之於將，乃數分之一爾」「以治為勝」'
         '「一人投命，足懼千夫」皆出此書。底本用維基文庫標點本，與四部叢刊影常熟瞿氏'
         '鐵琴銅劍樓藏景宋鈔本、四庫全書文淵閣本兩本逐字對校，得異文一百〇二條，'
         '而本頁正文一字未改，校語逐條見於各段註釋。篇旨與逐段註釋可開關。'
         '　全文免費線上閱讀，無需註冊；另有简体版、English 與日本語讀本。',
 'wuzi-hans': '《吴子》全本简体：图国、料敌、治兵、论将、应变、励士六篇，正文四千七百二十八字，'
         '武经七书之一，与《孙子》并称「孙吴」。吴起事魏文侯、武侯而守西河，'
         '与诸侯大战七十六，全胜六十四。「勇之于将，乃数分之一尔」「以治为胜」'
         '「一人投命，足惧千夫」皆出此书。底本用维基文库标点本，与四部丛刊影常熟瞿氏'
         '铁琴铜剑楼藏景宋钞本、四库全书文渊阁本两本逐字对校，得异文一百〇二条，'
         '而本页正文一字未改，校语逐条见于各段注释。篇旨与逐段注释可开关。'
         '　全文免费在线阅读，无需注册；另有繁體版、English 与日本語读本。',
 'wuzi-en': 'A complete English reading of the Wuzi \u2014 Planning for the State, '
         'Sizing Up the Enemy, Governing the Army, On the Commander, Responding to '
         'Change and Rousing the Officers: six chapters, 4,728 characters, one of the '
         'Seven Military Classics and, with Sunzi, half of the pair the Chinese '
         'tradition calls simply \u201cSun and Wu.\u201d Ascribed to Wu Qi (c. 440\u2013381 BC), '
         'who held the West River march for Wei and fought seventy-six battles with the '
         'feudal lords. Collated character by character against the Sibu congkan '
         'facsimile of the Song-copied manuscript from the Qu family library and '
         'against the Wenyuan ge Siku quanshu text. Free to read in full; also in '
         'traditional and simplified Chinese and in Japanese.',
 'wuzi-ja': '『呉子』の日本語全訳。圖國・料敵・治兵・論將・應變・勵士の六篇、'
         '本文四千七百二十八字、武経七書の一つで、『孫子』と並んで「孫呉」と称される。'
         '呉起（前四四〇頃—前三八一）は魏の文侯・武侯に仕えて西河を守り、'
         '諸侯と七十六たび戦って六十四たび全勝した。四部叢刊が影印した常熟瞿氏'
         '鐵琴銅劍樓蔵の景宋鈔本と文淵閣四庫全書本の二本と全文校合し、'
         '異文百二条を得たが、本文は一字も改めていない。無料・登録不要。'
         '繁体字・簡体字・英語版もあります。',
}
NAMES = {
 'wuzi':      ['吳子', '吳起兵法', '吳子兵法', 'Wuzi', 'The Wuzi', '吳起'],
 'wuzi-hans': ['吴子', '吴起兵法', '吴子兵法', 'Wuzi', 'The Wuzi', '吴起'],
 'wuzi-en':   ['The Wuzi', 'Wuzi', 'Wu-tzu', 'Master Wu\u2019s Art of War', '吳子',
               'Wu Qi'],
 'wuzi-ja':   ['呉子', 'ごし', '呉起兵法', 'Wuzi', 'The Wuzi', '呉起'],
}
WD_BOOK = 'https://www.wikidata.org/entity/Q1135722'
WD_AUTHOR = 'https://www.wikidata.org/entity/Q698899'
WIKI = {
 'wuzi':      'https://zh.wikipedia.org/wiki/%E5%90%B4%E5%AD%90',
 'wuzi-hans': 'https://zh.wikipedia.org/wiki/%E5%90%B4%E5%AD%90',
 'wuzi-en':   'https://en.wikipedia.org/wiki/Wuzi',
 'wuzi-ja':   'https://ja.wikipedia.org/wiki/%E5%91%89%E5%AD%90',
}
AUTHOR_WIKI = {
 'wuzi':      'https://zh.wikipedia.org/wiki/%E5%90%B4%E8%B5%B7',
 'wuzi-hans': 'https://zh.wikipedia.org/wiki/%E5%90%B4%E8%B5%B7',
 'wuzi-en':   'https://en.wikipedia.org/wiki/Wu_Qi',
 'wuzi-ja':   'https://ja.wikipedia.org/wiki/%E5%91%89%E8%B5%B7',
}
MAST = {
 'wuzi': '''<header class="mast">
  <h1>吳子</h1>
  <p class="sub">周　吳起　撰　·　圖國・料敵・治兵・論將・應變・勵士六篇　·　武經七書之一　·　全文免費閱讀</p>
</header>''',
 'wuzi-hans': '''<header class="mast">
  <h1>吴子</h1>
  <p class="sub">周　吴起　撰　·　图国・料敌・治兵・论将・应变・励士六篇　·　武经七书之一　·　全文免费阅读</p>
</header>''',
 'wuzi-en': '''<header class="mast">
  <h1>The Wuzi<span class="hz">吳子</span></h1>
  <p class="sub">by Wu Qi of Wei　·　six chapters　·　one of the Seven Military Classics　·　free to read in full</p>
</header>''',
 'wuzi-ja': '''<header class="mast">
  <h1>呉子<span class="hz">ごし</span></h1>
  <p class="sub">周　呉起　撰　·　圖國・料敵・治兵・論將・應變・勵士の六篇　·　武経七書の一つ　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}
SHELF = {
 'wuzi':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'wuzi-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'wuzi-en':   ('Classics', 'Back to the classics shelf', 'Contents', '— top —'),
 'wuzi-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
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
<link rel="alternate" hreflang="x-default" href="%s/p/wuzi-en.html">
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
    for slug, tf, nf, hans in (('wuzi', 'text.json', 'notes.json', False),
                               ('wuzi-hans', 'text-hans.json', 'notes-hans.json', True)):
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
    for slug, f, which in (('wuzi-en', 'en.json', 'en'),
                           ('wuzi-ja', 'ja.json', 'ja')):
        if not os.path.exists(f'{D}/{f}'):
            print('（まだ無い）', f); continue
        X = json.load(open(f'{D}/{f}', encoding='utf-8'))
        body, toc = west_body(T, X)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css(which), body, toc, west_colophon(X), js(which), ''))


if __name__ == '__main__':
    main()
