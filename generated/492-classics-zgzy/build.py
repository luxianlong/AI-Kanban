# -*- coding: utf-8 -*-
"""貞觀政要 — 四つの讀本を text.json / notes.json / en.json / ja.json から組み立てる。"""
import json, os, re

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
COVER = '/img/classics/zhenguanzhengyao-cover.jpg'
COVER_W, COVER_H = 560, 773
AI_KEY = 'zg-ai'

SLUGS = [('简体', 'zhenguanzhengyao-hans', 'zh-Hans'),
         ('繁體', 'zhenguanzhengyao', 'zh-Hant'),
         ('EN', 'zhenguanzhengyao-en', 'en'),
         ('日本語', 'zhenguanzhengyao-ja', 'ja')]
LANG_OF = {s: l for _, s, l in SLUGS}


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


# 奏疏・詔答の續きの段。底本が行頭に「:」を置いて本文と分けているところ。
# 見出しではないので目錄には入れない。左に細い罫を引いて一段下げる。
SH_CSS = '''
p.sh{margin-left:1.6em; text-indent:1em !important; border-left:2px solid var(--rule);
  padding-left:1.1em}
body.ai-on .ai.sh-ai{margin-left:1.6em}
.xu{color:var(--ink-soft)}'''


def zh_body(T, N, hans):
    P = {p['n']: p for p in T['pian']}
    h, toc = [], []
    xu_t = '序' if not hans else '序'
    toc.append('<a class="l2" href="#xu">%s</a>' % ('吳兢自序' if not hans else '吴兢自序'))
    h.append('<h2 id="xu">%s</h2>' % ('吳兢自序' if not hans else '吴兢自序'))
    h.append('<div class="ai ai-head ai-juan">%s</div>' % esc(N['juan']['xu']))
    h.append('<p id="xu-1">%s</p>' % esc(T['xu']))
    h.append('<div class="ai" id="ai-xu-1">%s</div>' % esc(N['para']['xu-1']))
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
                cls = ' class="sh"' if p['cont'][i - 1] else ''
                h.append('<p id="%s"%s>%s</p>' % (pid, cls, esc(para)))
                h.append('<div class="ai%s" id="ai-%s">%s</div>'
                         % (' sh-ai' if p['cont'][i - 1] else '', pid,
                            esc(N['para'][pid])))
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


def west_body(T, D):
    P = {p['n']: p for p in T['pian']}
    h, toc = [], []
    xu = D['xu']
    toc.append('<a class="l2" href="#xu">%s<span class="hz">序</span></a>'
               % esc(xu['title']))
    h.append('<h2 id="xu">%s<span class="hz">吳兢自序</span></h2>' % esc(xu['title']))
    h.append('<p class="lead">%s</p>' % xu['head'])
    h.append('<p id="xu-1">%s</p>' % xu['text'])
    for j in T['juan']:
        jid = 'j%d' % j['n']
        jt = D['juan'][jid]
        toc.append('<a class="l2" href="#%s">%s<span class="hz">%s</span></a>'
                   % (jid, esc(jt['title']), esc(j['name'])))
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
                cls = ' class="sh"' if P[n]['cont'][i - 1] else ''
                h.append('<p id="%s-%d"%s>%s</p>' % (cid, i, cls, para))
    toc.append('<a class="l2" href="#ben">%s</a>' % esc(D['labels']['colophon']))
    return '\n'.join(h), '\n'.join(toc)


def west_colophon(D):
    out = ['<div class="colo">',
           '  <h2 id="ben" class="colo-h">%s</h2>' % esc(D['labels']['colophon'])]
    for para in D['colophon']:
        out.append('  <p>' + para + '</p>')
    out.append('</div>')
    return '\n'.join(out)


def css(which):
    s = open('css-%s.css' % which, encoding='utf-8').read()
    drop = [re.compile(r'h4\{[^}]*\}\n'), re.compile(r'\.byline\{[^}]*\}\n'),
            re.compile(r'\.colophon\{[^}]*\}\n'), re.compile(r'\n\.var\{[^}]*\}\n'),
            re.compile(r'p\.lost\{[^}]*\}\n'),
            re.compile(r'h2 \.hz,h3 \.hz,h4 \.hz')]
    for rx in drop[:-1]:
        s = rx.sub('', s)
    s = drop[-1].sub('h2 .hz,h3 .hz', s)
    if which == 'hant':
        assert 'content:"篇旨"' in s and 'content:"卷旨"' in s
    return s + SH_CSS


def js(which):
    s = open('js-%s.js' % which, encoding='utf-8').read()
    s = s.replace("'lt-ai'", "'%s'" % AI_KEY)
    s = s.replace("main h2[id],main h3[id],main h4[id]", "main h2[id],main h3[id]")
    return s


TITLES = {
 'zhenguanzhengyao':      '貞觀政要 · 繁體全文 · 免費線上閱讀',
 'zhenguanzhengyao-hans': '贞观政要 · 简体全文 · 免费在线阅读',
 'zhenguanzhengyao-en':   'The Essentials of Government of the Zhenguan Era '
                          '(Zhenguan zhengyao) — complete English translation',
 'zhenguanzhengyao-ja':   '貞觀政要（貞観政要）— 日本語全訳 · 無料で読めます',
}
DESCS = {
 'zhenguanzhengyao':
   '《貞觀政要》全本繁體：唐吳兢撰，十卷四十篇（直諫附），唐太宗與魏徵、房玄齡、'
   '王珪等君臣問答之語的分類編。底本用維基文庫標點本，通校《四庫全書》文淵閣本'
   '（元戈直集論），再以國立國會圖書館藏慶長五年伏見版古活字本（德川家康刊）'
   '逐葉目驗。改字十八處處處有外證；底本混入的簡體字形五十處已正。'
   '卷旨、篇旨與逐段註釋可開關。　全文免費線上閱讀，無需註冊；'
   '另有简体版、English 與日本語全譯。',
 'zhenguanzhengyao-hans':
   '《贞观政要》全本简体：唐吴兢撰，十卷四十篇（直谏附），唐太宗与魏徵、房玄龄、'
   '王珪等君臣问答之语的分类编。底本用维基文库标点本，通校《四库全书》文渊阁本'
   '（元戈直集论），再以国立国会图书馆藏庆长五年伏见版古活字本（德川家康刊）'
   '逐叶目验。改字十八处处处有外证；底本混入的简体字形五十处已正。'
   '卷旨、篇旨与逐段注释可开关。　全文免费在线阅读，无需注册；'
   '另有繁體版、English 与日本語全译。',
 'zhenguanzhengyao-en':
   'A complete English translation of the Zhenguan zhengyao, the Essentials of '
   'Government of the Zhenguan Era, compiled by Wu Jing around 720 CE: ten '
   'scrolls and forty chapters of what Tang Taizong and his ministers — Wei '
   'Zheng, Fang Xuanling, Wang Gui, Du Ruhui — actually said to each other '
   'about ruling. Every paragraph translated. Collated against the Siku quanshu '
   'text with Ge Zhi\'s commentary and against a Japanese movable-type printing '
   'of 1600 commissioned by Tokugawa Ieyasu. Free to read in full; also in '
   'traditional and simplified Chinese and in Japanese.',
 'zhenguanzhengyao-ja':
   '『貞觀政要』（貞観政要）の日本語全訳。唐の呉兢撰、全十巻四十篇（直諫附）。'
   '唐の太宗と魏徴・房玄齢・王珪ら群臣の問答を主題別に編んだ書で、'
   '日本では北条政子から徳川家康まで読み継がれた。底本は維基文庫の標点本、'
   '四庫全書文淵閣本（元・戈直集論）と対校し、国立国会図書館蔵の'
   '慶長五年伏見版古活字本を目験した。無料・登録不要。'
   '繁体字・簡体字・英語版もあります。',
}
NAMES = {
 'zhenguanzhengyao':      ['貞觀政要', '贞观政要', 'Zhenguan zhengyao',
                           'The Essentials of Government of the Zhenguan Era', '吳兢'],
 'zhenguanzhengyao-hans': ['贞观政要', '貞觀政要', 'Zhenguan zhengyao',
                           'The Essentials of Government of the Zhenguan Era', '吴兢'],
 'zhenguanzhengyao-en':   ['The Essentials of Government of the Zhenguan Era',
                           'Zhenguan zhengyao', 'Chenguan Cheng-yao',
                           '貞觀政要', 'Wu Jing'],
 'zhenguanzhengyao-ja':   ['貞觀政要', '貞観政要', 'じょうがんせいよう',
                           'The Essentials of Government of the Zhenguan Era', '呉兢'],
}
WD_BOOK = 'https://www.wikidata.org/entity/Q5361136'
WD_AUTHOR = 'https://www.wikidata.org/entity/Q9618532'
# 英語版ウィキペディアにはこの書の條目も吳兢の條目も無い（#484 が數えたとおり、
# 英語圈でこの書はほとんど讀まれていない）。#417 の線どおり、無いところには繫がず、
# 英語の頁は Wikidata だけを指す。
WIKI = {
 'zhenguanzhengyao':      'https://zh.wikipedia.org/wiki/%E8%B4%9E%E8%A7%82%E6%94%BF%E8%A6%81',
 'zhenguanzhengyao-hans': 'https://zh.wikipedia.org/wiki/%E8%B4%9E%E8%A7%82%E6%94%BF%E8%A6%81',
 'zhenguanzhengyao-ja':   'https://ja.wikipedia.org/wiki/%E8%B2%9E%E8%A6%B3%E6%94%BF%E8%A6%81',
}
AUTHOR_WIKI = {
 'zhenguanzhengyao':      'https://zh.wikipedia.org/wiki/%E5%90%B3%E5%85%A2_%28670%E5%B9%B4%29',
 'zhenguanzhengyao-hans': 'https://zh.wikipedia.org/wiki/%E5%90%B3%E5%85%A2_%28670%E5%B9%B4%29',
 'zhenguanzhengyao-ja':   'https://ja.wikipedia.org/wiki/%E5%91%89%E5%85%A2',
}
MAST = {
 'zhenguanzhengyao': '''<header class="mast">
  <h1>貞觀政要</h1>
  <p class="sub">唐　吳兢　撰　·　十卷四十篇　·　貞觀君臣問答的分類編　·　全文免費閱讀</p>
</header>''',
 'zhenguanzhengyao-hans': '''<header class="mast">
  <h1>贞观政要</h1>
  <p class="sub">唐　吴兢　撰　·　十卷四十篇　·　贞观君臣问答的分类编　·　全文免费阅读</p>
</header>''',
 'zhenguanzhengyao-en': '''<header class="mast">
  <h1>The Essentials of Government<span class="hz">貞觀政要</span></h1>
  <p class="sub">compiled by Wu Jing, Tang dynasty　·　ten scrolls, forty chapters　·　what Tang Taizong and his ministers said to each other　·　free to read in full</p>
</header>''',
 'zhenguanzhengyao-ja': '''<header class="mast">
  <h1>貞觀政要<span class="hz">じょうがんせいよう</span></h1>
  <p class="sub">唐　呉兢　撰　·　全十巻四十篇　·　太宗と群臣の問答　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}
SHELF = {
 'zhenguanzhengyao':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'zhenguanzhengyao-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'zhenguanzhengyao-en':   ('Classics', 'Back to the classics shelf', 'Contents',
                           '— top —'),
 'zhenguanzhengyao-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
}
AIBTN = '''  <div class="grp">
    <button id="ai" aria-pressed="false" title="%s">%s <span class="tag">AI</span></button>
  </div>
'''


def jsonld(slug):
    lang, names = LANG_OF[slug], NAMES[slug]
    others = [s for _, s, _ in SLUGS if s != slug]
    book = {
      "@type": "Book",
      "@id": "%s/p/%s.html#book" % (SITE, slug),
      "name": names[0], "alternateName": names[1:-1],
      "author": {"@type": "Person",
                 "@id": "%s/p/%s.html#author" % (SITE, slug), "name": names[-1],
                 "sameAs": ([WD_AUTHOR, AUTHOR_WIKI[slug]] if slug in AUTHOR_WIKI
                            else [WD_AUTHOR])},
      "inLanguage": lang, "url": "%s/p/%s.html" % (SITE, slug),
      "image": SITE + COVER, "isAccessibleForFree": True,
      "bookFormat": "https://schema.org/EBook",
      "isPartOf": {"@id": "%s/p/dianji.html#collection" % SITE},
      "workTranslation": [{"@id": "%s/p/%s.html#book" % (SITE, s)} for s in others],
      "sameAs": [WD_BOOK] + ([WIKI[slug]] if slug in WIKI else []),
    }
    return {"@context": "https://schema.org", "@graph": [book, {
      "@type": "WebPage", "@id": "%s/p/%s.html#webpage" % (SITE, slug),
      "url": "%s/p/%s.html" % (SITE, slug),
      "name": TITLES[slug], "description": DESCS[slug], "inLanguage": lang,
      "isPartOf": {"@id": "%s/p/dianji.html#collection" % SITE},
      "about": {"@id": "%s/p/%s.html#book" % (SITE, slug)},
      "primaryImageOfPage": {"@id": "%s/p/%s.html#cover" % (SITE, slug)},
      "breadcrumb": {"@id": "%s/p/%s.html#crumbs" % (SITE, slug)},
      "isAccessibleForFree": True,
    }, {
      "@type": "ImageObject", "@id": "%s/p/%s.html#cover" % (SITE, slug),
      "url": SITE + COVER, "contentUrl": SITE + COVER,
      "width": COVER_W, "height": COVER_H,
    }, {
      "@type": "BreadcrumbList", "@id": "%s/p/%s.html#crumbs" % (SITE, slug),
      "itemListElement": [
        {"@type": "ListItem", "position": 1, "name": "典籍",
         "item": "%s/p/dianji.html" % SITE},
        {"@type": "ListItem", "position": 2, "name": names[0],
         "item": "%s/p/%s.html" % (SITE, slug)}],
    }]}


def head(slug, sheet):
    lang = LANG_OF[slug]
    alt = '\n'.join('<link rel="alternate" hreflang="%s" href="%s/p/%s.html">'
                    % (l, SITE, s) for _, s, l in SLUGS)
    locale = {'zh-Hans': 'zh_CN', 'zh-Hant': 'zh_TW',
              'en': 'en_US', 'ja': 'ja_JP'}[lang]
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
<link rel="alternate" hreflang="x-default" href="%s/p/zhenguanzhengyao-en.html">
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
    for slug, tf, nf, hans in (
            ('zhenguanzhengyao', 'text.json', 'notes.json', False),
            ('zhenguanzhengyao-hans', 'text-hans.json', 'notes-hans.json', True)):
        if not os.path.exists(nf):
            continue
        T, N = json.load(open(tf)), json.load(open(nf))
        body, toc = zh_body(T, N, hans)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css('hant'), body, toc, zh_colophon(N, hans), js('hant'),
                   AIBTN % (('本页自撰的卷旨、篇旨与逐段注释（AI 生成），默认隐藏', '注释')
                            if hans else
                            ('本頁自撰的卷旨、篇旨與逐段註釋（AI 生成），預設隱藏', '註釋'))))

    T = json.load(open('text.json'))
    for slug, f, which in (('zhenguanzhengyao-en', 'en.json', 'en'),
                           ('zhenguanzhengyao-ja', 'ja.json', 'ja')):
        if not os.path.exists(f):
            continue
        D = json.load(open(f))
        body, toc = west_body(T, D)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css(which), body, toc, west_colophon(D), js(which), ''))


if __name__ == '__main__':
    main()
