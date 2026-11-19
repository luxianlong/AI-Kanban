# -*- coding: utf-8 -*-
"""茶經 — 四つの讀本を text.json / notes.json / en.json / ja.json から組み立てる。"""
import json, os, re

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
COVER = '/img/classics/chajing-cover.jpg'
COVER_W, COVER_H = 560, 870
AI_KEY = 'cj-ai'

SLUGS = [('简体', 'chajing-hans', 'zh-Hans'), ('繁體', 'chajing', 'zh-Hant'),
         ('EN', 'chajing-en', 'en'), ('日本語', 'chajing-ja', 'ja')]
LANG_OF = {s: l for _, s, l in SLUGS}


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def ink(s, raw=False):
    """本文一段を刻む。raw=True のときは escape しない（EN／JA の譯文は
    <i> や &rsquo; を含むので、書いたままを通す）。

    〔…〕 は陸羽の自注。原書は雙行小字の夾注で、一號小さく色を變える。
           《反經》の趙蕤自注と同じ扱い——本文の一部なので「註釋」の
           ボタンには從わない。
    【…】 は〈四之器〉の器の名。原書は行を改めて一字下げずに立てるが、
           本頁は段の頭の太字にする（見出しではないので目錄には入れない）。
    """
    if not raw:
        s = esc(s)
    s = re.sub(r'【(.+?)】', r'<b class="qm">\1</b>', s)
    s = re.sub(r'〔(.+?)〕', r'<span class="zhu">〔\1〕</span>', s)
    return s


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
                h.append('<p id="%s">%s</p>' % (pid, ink(para)))
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
                h.append('<p id="%s-%d">%s</p>' % (cid, i, ink(para, True)))
    toc.append('<a class="l2" href="#ben">%s</a>' % esc(D['labels']['colophon']))
    return '\n'.join(h), '\n'.join(toc)


def west_colophon(D):
    out = ['<div class="colo">',
           '  <h2 id="ben" class="colo-h">%s</h2>' % esc(D['labels']['colophon'])]
    for para in D['colophon']:
        out.append('  <p>' + para + '</p>')
    out.append('</div>')
    return '\n'.join(out)


# 陸羽自注。原書は雙行小字の夾注。ここは一號小さく色を變え、〔　〕を界標とする。
# 本文の一部なので、「註釋」のボタン（本頁が撰した卷旨・篇旨・段註）には從わない。
# 【　】は〈四之器〉二十四器の名。原書も行頭に立てる。見出しではない。
ZHU_CSS = '''
.zhu{font-size:.86em; color:var(--ink-soft)}
.qm{color:var(--seal); font-weight:600; margin-right:.4em}'''


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
        # 《六韜》の頁からそのまま取っているので、篇旨・卷旨の規則は既にある。
        assert 'content:"篇旨"' in s and 'content:"卷旨"' in s
    return s + ZHU_CSS


def js(which):
    s = open('js-%s.js' % which, encoding='utf-8').read()
    s = s.replace("'lt-ai'", "'%s'" % AI_KEY)
    # 本書に h4 はない
    s = s.replace("main h2[id],main h3[id],main h4[id]", "main h2[id],main h3[id]")
    return s


# ---------- ページ ----------
TITLES = {
 'chajing':      '茶經 · 繁體全文 · 免費線上閱讀',
 'chajing-hans': '茶经 · 简体全文 · 免费在线阅读',
 'chajing-en':   'The Classic of Tea (Chajing) — complete English reading',
 'chajing-ja':   '茶經（茶経）— 日本語全訳 · 無料で読めます',
}
DESCS = {
 'chajing': '《茶經》全本繁體：唐陸羽撰，三卷十篇，世上第一部茶書。'
           '底本用維基文庫標點本（浙江鮑士恭家藏本一系），'
           '通校《四庫全書》文淵閣本，再以國立國會圖書館藏明刊《百川學海》本'
           '逐葉目驗——最古の一支。'
           '〈四之器〉風爐「聖唐滅胡明年鑄」一句，四庫本改作「聖唐年號某年鑄」；'
           '〈八之出〉底本脫湖州一節十九字，據百川本補。'
           '卷旨、篇旨與逐段註釋可開關。　全文免費線上閱讀，無需註冊；'
           '另有简体版、English 與日本語讀本。',
 'chajing-hans': '《茶经》全本简体：唐陆羽撰，三卷十篇，世上第一部茶书。'
           '底本用维基文库标点本（浙江鲍士恭家藏本一系），'
           '通校《四库全书》文渊阁本，再以国立国会图书馆藏明刊《百川学海》本'
           '逐叶目验——今存最古的一支。'
           '〈四之器〉风炉「圣唐灭胡明年铸」一句，四库本改作「圣唐年号某年铸」；'
           '〈八之出〉底本脱湖州一节十九字，据百川本补。'
           '卷旨、篇旨与逐段注释可开关。　全文免费在线阅读，无需注册；'
           '另有繁體版、English 与日本語读本。',
 'chajing-en': 'A complete English reading of the Chajing, the Classic of Tea, '
           'written by Lu Yu in the eighth century — the first book ever '
           'written about tea, in three scrolls and ten chapters: the plant, '
           'the tools, the making, the twenty-four vessels, the boiling, the '
           'drinking, the record, the places, the shortcuts and the scroll. '
           'Every paragraph translated, including Lu Yu\'s own interlinear '
           'notes. Collated against the Siku quanshu text and against a Ming '
           'printing of the Baichuan xuehai, leaf by leaf. Free to read in '
           'full; also in traditional and simplified Chinese and in Japanese.',
 'chajing-ja': '『茶經』（茶経）の日本語全訳。唐の陸羽撰、三巻十篇、'
           '世界で最初の茶の書。陸羽自身の夾注もすべて訳した。'
           '底本は維基文庫の標点本、四庫全書文淵閣本と対校し、'
           '国立国会図書館蔵の明刊『百川學海』本を一葉ずつ目験した。'
           '無料・登録不要。繁体字・簡体字・英語版もあります。',
}
NAMES = {
 'chajing':      ['茶經', '茶经', 'The Classic of Tea', 'Chajing', '陸羽'],
 'chajing-hans': ['茶经', '茶經', 'The Classic of Tea', 'Chajing', '陆羽'],
 'chajing-en':   ['The Classic of Tea', 'Chajing', 'Cha Jing',
                  '茶經', 'Lu Yu'],
 'chajing-ja':   ['茶經', '茶経', 'ちゃきょう', 'The Classic of Tea', '陸羽'],
}
WD_BOOK = 'https://www.wikidata.org/entity/Q259153'
WD_AUTHOR = 'https://www.wikidata.org/entity/Q948270'
WIKI = {
 'chajing':      'https://zh.wikipedia.org/wiki/%E8%8C%B6%E7%BB%8F',
 'chajing-hans': 'https://zh.wikipedia.org/wiki/%E8%8C%B6%E7%BB%8F',
 'chajing-en':   'https://en.wikipedia.org/wiki/The_Classic_of_Tea',
 'chajing-ja':   'https://ja.wikipedia.org/wiki/%E8%8C%B6%E7%B5%8C',
}
# 陸羽は三つの維基すべてに條目がある（#417 の線——無いところには繫がない）。
AUTHOR_WIKI = {
 'chajing':      'https://zh.wikipedia.org/wiki/%E9%99%B8%E7%BE%BD',
 'chajing-hans': 'https://zh.wikipedia.org/wiki/%E9%99%B8%E7%BE%BD',
 'chajing-en':   'https://en.wikipedia.org/wiki/Lu_Yu',
 'chajing-ja':   'https://ja.wikipedia.org/wiki/%E9%99%B8%E7%BE%BD',
}
MAST = {
 'chajing': '''<header class="mast">
  <h1>茶經</h1>
  <p class="sub">唐　陸羽　撰　·　三卷十篇　·　世上第一部茶書　·　全文免費閱讀</p>
</header>''',
 'chajing-hans': '''<header class="mast">
  <h1>茶经</h1>
  <p class="sub">唐　陆羽　撰　·　三卷十篇　·　世上第一部茶书　·　全文免费阅读</p>
</header>''',
 'chajing-en': '''<header class="mast">
  <h1>The Classic of Tea<span class="hz">茶經</span></h1>
  <p class="sub">by Lu Yu, Tang dynasty　·　three scrolls, ten chapters　·　the first book ever written about tea　·　free to read in full</p>
</header>''',
 'chajing-ja': '''<header class="mast">
  <h1>茶經<span class="hz">ちゃきょう</span></h1>
  <p class="sub">唐　陸羽　撰　·　全三巻十篇　·　世界で最初の茶の書　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}
SHELF = {
 'chajing':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'chajing-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'chajing-en':   ('Classics', 'Back to the classics shelf', 'Contents', '— top —'),
 'chajing-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
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
        "sameAs": ([WD_AUTHOR, AUTHOR_WIKI[slug]] if slug in AUTHOR_WIKI
                   else [WD_AUTHOR]),
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
<link rel="alternate" hreflang="x-default" href="%s/p/chajing-en.html">
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
    for slug, tf, nf, hans in (('chajing', 'text.json', 'notes.json', False),
                               ('chajing-hans', 'text-hans.json', 'notes-hans.json', True)):
        T, N = json.load(open(tf)), json.load(open(nf))
        body, toc = zh_body(T, N, hans)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css('hant'), body, toc, zh_colophon(N, hans), js('hant'),
                   AIBTN % (('本页自撰的卷旨、篇旨与逐段注释（AI 生成），默认隐藏', '注释')
                            if hans else
                            ('本頁自撰的卷旨、篇旨與逐段註釋（AI 生成），預設隱藏', '註釋'))))

    T = json.load(open('text.json'))
    for slug, f, which in (('chajing-en', 'en.json', 'en'),
                           ('chajing-ja', 'ja.json', 'ja')):
        if not os.path.exists(f):
            continue
        D = json.load(open(f))
        body, toc = west_body(T, D)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css(which), body, toc, west_colophon(D), js(which), ''))


if __name__ == '__main__':
    main()
