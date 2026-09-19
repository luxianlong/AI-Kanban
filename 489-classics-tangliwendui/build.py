# -*- coding: utf-8 -*-
"""唐李問對 — 四つの讀本を text.json / notes.json / en.json / ja.json から組み立てる。

原書の目は卷上・卷中・卷下の三つだけで、篇も章も無い。だから見出しは h2 三つと
卷末の〈底本與校例〉だけ、h3 は一つも立てない（#483 で十部の讀本から撰擬の
小標題七十七條を削り、#486《三略》がそれを踏襲したのと同じ線）。

ただしこの書は問答である。底本は一つの發言を一行に切り、一つの問答の
まとまりを空行で隔てている。その空行を本頁は<b>細い罫一本</b>で寫す
（p.qi）——見出しではないから目次にも載らないし、標題も付けない。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SITE = 'https://youraikanban.com'
COVER = '/img/classics/tangliwendui-cover.jpg'
COVER_W, COVER_H = 560, 790
AI_KEY = 'tl-ai'

SLUGS = [('简体', 'tangliwendui-hans', 'zh-Hans'),
         ('繁體', 'tangliwendui', 'zh-Hant'),
         ('EN', 'tangliwendui-en', 'en'),
         ('日本語', 'tangliwendui-ja', 'ja')]
LANG_OF = {s: l for _, s, l in SLUGS}
JIDS = ['j1', 'j2', 'j3']
# 日本語の頁では、見出しの脇に添える小字を仮名の讀みにする。
# 漢字のままだと「巻上巻上」と二重になるからである（EN は漢字のまま）。
JA_HZ = {'卷上': 'かんじょう', '卷中': 'かんちゅう', '卷下': 'かんげ'}

# 問答の界を寫す罫。見出しではないので目次には出ない。
QI_CSS = '''main p.qi{margin-top:2.6em; position:relative}
main p.qi::before{
  content:""; position:absolute; left:0; right:0; top:-1.3em;
  margin:0 auto; width:46px; height:1px; background:var(--rule); opacity:.85;
}
'''


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def pclass(j, i):
    """i（一起算）が問對の章の頭なら 'qi'。卷の一段目には付けない。"""
    return ' class="qi"' if i in j['chap'] and i != 1 else ''


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
            h.append('<p id="%s"%s>%s</p>' % (pid, pclass(j, i), esc(para)))
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
def west_body(T, X, ja):
    h, toc = [], []
    for k, j in enumerate(T):
        jid = JIDS[k]
        jt = X['juan'][jid]
        hz = JA_HZ[j['name']] if ja else j['name']
        toc.append('<a class="l2" href="#%s">%s<span class="hz">%s</span></a>'
                   % (jid, esc(jt['title']), esc(hz)))
        h.append('<h2 id="%s">%s<span class="hz">%s</span></h2>'
                 % (jid, esc(jt['title']), esc(hz)))
        h.append('<p class="lead">%s</p>' % jt['head'])
        for i in range(1, len(j['paras']) + 1):
            pid = '%s-%d' % (jid, i)
            h.append('<p id="%s"%s>%s</p>' % (pid, pclass(j, i), X['para'][pid]))
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
    # 雛形に取った《三略》の頁も卷だけの書なので、h3 の規則も「篇旨」の
    # 規則も初めから無い。確かめたうえで、問答の界の罫だけを足す。
    s = open('%s/css-%s.css' % (D, which), encoding='utf-8').read()
    assert not re.search(r'h3\{', s), '雛形に h3 の規則が殘っている'
    assert 'content:"篇旨"' not in s, '雛形に篇旨の規則が殘っている'
    # 中文の頁は main > p に字下げの規則があり、EN／JA の頁には無い。
    # 問答の界の罫は、その直後（無ければ p の規則の直後）に足す。
    for anchor in ('main > p{text-indent:2em}\n', 'p{margin:1.05em 0}\n'):
        if anchor in s:
            return s.replace(anchor, anchor + QI_CSS, 1)
    raise AssertionError('雛形に段落の規則が見つからない')


def js(which):
    s = open('%s/js-%s.js' % (D, which), encoding='utf-8').read()
    # 註釋の開閉は中文の頁にしかない。EN／JA の雛形には鍵が出てこない。
    assert (which == 'hant') == ("'sl-ai'" in s), '雛形の註釋の鍵が想定と違う'
    s = s.replace("'sl-ai'", "'%s'" % AI_KEY)
    assert 'main h3[id]' not in s, '雛形の scrollspy が h3 を見ている'
    return s


# ---------- 頁の外まわり ----------
TITLES = {
 'tangliwendui':      '唐李問對（唐太宗李衛公問對）· 繁體全文 · 免費線上閱讀',
 'tangliwendui-hans': '唐李问对（唐太宗李卫公问对）· 简体全文 · 免费在线阅读',
 'tangliwendui-en':   'Questions and Replies between Tang Taizong and Li Weigong '
                      '— complete English reading',
 'tangliwendui-ja':   '李衛公問対（唐李問対）— 日本語全訳 · 無料で読めます',
}
DESCS = {
 'tangliwendui':
   '《唐李問對》（《唐太宗李衛公問對》）全本繁體：卷上、卷中、卷下三卷，'
   '五十問對、一百九十七段，正文一萬零二百九十二字，武經七書之一，'
   '七書中唯一的唐代之書，也是唯一通篇問答的書。唐太宗與衛國公李靖論兵，'
   '所論以奇正、虛實、主客、攻守為綱，而《孫子》《司馬法》《尉繚子》'
   '《太公》諸書逐一在其中被拆開重講。底本用維基文庫標點本，'
   '與四庫全書文淵閣本逐字對校（録文は Kanripo と維基文庫の獨立二種）、'
   '要處は上海圖書館藏白文刊本の書影で目驗，改正底本之誤十二處，存異文一百〇三條。'
   '卷旨與逐段註釋可開關。　全文免費線上閱讀，無需註冊；'
   '另有简体版、English 與日本語讀本。',
 'tangliwendui-hans':
   '《唐李问对》（《唐太宗李卫公问对》）全本简体：卷上、卷中、卷下三卷，'
   '五十问对、一百九十七段，正文一万零二百九十二字，武经七书之一，'
   '七书中唯一的唐代之书，也是唯一通篇问答的书。唐太宗与卫国公李靖论兵，'
   '所论以奇正、虚实、主客、攻守为纲，而《孙子》《司马法》《尉缭子》'
   '《太公》诸书逐一在其中被拆开重讲。底本用维基文库标点本，'
   '与四库全书文渊阁本逐字对校，要处以上海图书馆藏白文刊本书影目验，'
   '改正底本之误十二处，存异文一百〇三条。卷旨与逐段注释可开关。'
   '　全文免费在线阅读，无需注册；另有繁體版、English 与日本語读本。',
 'tangliwendui-en':
   'A complete English reading of the Questions and Replies between Tang Taizong '
   'and Li Weigong (Tang Li wendui) — three scrolls, fifty exchanges, 197 '
   'speeches, 10,292 characters. One of the Seven Military Classics, the only one '
   'from the Tang and the only one cast throughout as dialogue: the emperor who '
   'won the empire in the field questioning Li Jing, the general who took the '
   'Eastern Turks and Xiao Xian. Its subjects are the unorthodox and the '
   'orthodox, the full and the empty, host and guest, attack and defence — '
   'and along the way it takes the Sunzi, the Sima fa, the Wei Liaozi and the '
   'Taigong apart and puts them back together. Collated character by character '
   'against the Wenyuan ge Siku quanshu text, with the decisive readings checked '
   'by eye against a plain-text woodblock edition held by the Shanghai Library. '
   'Free to read in full; also in traditional and simplified Chinese and in '
   'Japanese.',
 'tangliwendui-ja':
   '『李衛公問対』（『唐李問対』）の日本語全訳。巻上・巻中・巻下の三巻、'
   '五十の問答、百九十七段、本文一万二百九十二字。武経七書のうち唯一の'
   '唐代の書であり、唯一全篇が問答の書でもある。唐の太宗と衛国公李靖が'
   '兵を論じ、奇正・虚実・主客・攻守を綱として、『孫子』『司馬法』'
   '『尉繚子』『太公』を一つ一つ解きほぐす。維基文庫標点本を底本とし、'
   '文淵閣四庫全書本と全文校合、要所は上海図書館蔵の白文刊本の書影で'
   '目験した。無料・登録不要。繁体字・簡体字・英語版もあります。',
}
NAMES = {
 'tangliwendui':      ['唐李問對', '唐太宗李衛公問對', '李衛公問對', '問對',
                       'Tang Li wendui', '李靖'],
 'tangliwendui-hans': ['唐李问对', '唐太宗李卫公问对', '李卫公问对', '问对',
                       'Tang Li wendui', '李靖'],
 'tangliwendui-en':   ['Questions and Replies between Tang Taizong and Li Weigong',
                       'Tang Li wendui', 'Li Weigong wendui',
                       'Questions and Replies', '唐李問對', 'Li Jing'],
 'tangliwendui-ja':   ['李衛公問対', 'りえいこうもんたい', '唐李問対', '問対',
                       'Tang Li wendui', '李靖'],
}
WD_BOOK = 'https://www.wikidata.org/entity/Q1051352'
WD_AUTHOR = 'https://www.wikidata.org/entity/Q706792'
WIKI = {
 'tangliwendui':      'https://zh.wikipedia.org/wiki/%E5%94%90%E5%A4%AA%E5%AE%97%E6%9D%8E%E5%8D%AB%E5%85%AC%E9%97%AE%E5%AF%B9',
 'tangliwendui-hans': 'https://zh.wikipedia.org/wiki/%E5%94%90%E5%A4%AA%E5%AE%97%E6%9D%8E%E5%8D%AB%E5%85%AC%E9%97%AE%E5%AF%B9',
 'tangliwendui-en':   'https://en.wikipedia.org/wiki/Questions_and_Replies_between_Tang_Taizong_and_Li_Weigong',
 'tangliwendui-ja':   'https://ja.wikipedia.org/wiki/%E6%9D%8E%E8%A1%9B%E5%85%AC%E5%95%8F%E5%AF%BE',
}
AUTHOR_WIKI = {
 'tangliwendui':      'https://zh.wikipedia.org/wiki/%E6%9D%8E%E9%9D%96',
 'tangliwendui-hans': 'https://zh.wikipedia.org/wiki/%E6%9D%8E%E9%9D%96',
 'tangliwendui-en':   'https://en.wikipedia.org/wiki/Li_Jing_(Tang_dynasty)',
 'tangliwendui-ja':   'https://ja.wikipedia.org/wiki/%E6%9D%8E%E9%9D%96',
}
MAST = {
 'tangliwendui': '''<header class="mast">
  <h1>唐李問對</h1>
  <p class="sub">唐　太宗與李靖問對　·　卷上・卷中・卷下三卷　·　武經七書之一　·　全文免費閱讀</p>
</header>''',
 'tangliwendui-hans': '''<header class="mast">
  <h1>唐李问对</h1>
  <p class="sub">唐　太宗与李靖问对　·　卷上・卷中・卷下三卷　·　武经七书之一　·　全文免费阅读</p>
</header>''',
 'tangliwendui-en': '''<header class="mast">
  <h1>Questions and Replies<span class="hz">唐李問對</span></h1>
  <p class="sub">Tang Taizong and Li Jing, Duke of Wey　·　three scrolls　·　one of the Seven Military Classics　·　free to read in full</p>
</header>''',
 'tangliwendui-ja': '''<header class="mast">
  <h1>李衛公問対<span class="hz">りえいこうもんたい</span></h1>
  <p class="sub">唐　太宗と李靖の問答　·　巻上・巻中・巻下の三巻　·　武経七書の一つ　·　現代語訳　·　全文無料で読めます</p>
</header>''',
}
SHELF = {
 'tangliwendui':      ('典籍', '回到典籍書架', '目　錄', '— 回頂 —'),
 'tangliwendui-hans': ('典籍', '回到典籍书架', '目　录', '— 回顶 —'),
 'tangliwendui-en':   ('Classics', 'Back to the classics shelf', 'Contents', '— top —'),
 'tangliwendui-ja':   ('典籍', '典籍の書架に戻る', '目　次', '— 先頭へ —'),
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
<link rel="alternate" hreflang="x-default" href="%s/p/tangliwendui-en.html">
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
            ('tangliwendui', 'text.json', 'notes.json', False),
            ('tangliwendui-hans', 'text-hans.json', 'notes-hans.json', True)):
        if not os.path.exists(f'{D}/{nf}'):
            print('（まだ無い）', nf); continue
        T = json.load(open(f'{D}/{tf}', encoding='utf-8'))
        N = json.load(open(f'{D}/{nf}', encoding='utf-8'))
        body, toc = zh_body(T, N, hans)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css('hant'), body, toc, zh_colophon(N, hans), js('hant'),
                   AIBTN % (('本页自撰的卷旨与逐段注释（AI 生成），默认隐藏', '注释')
                            if hans else
                            ('本頁自撰的卷旨與逐段註釋（AI 生成），預設隱藏', '註釋'))))

    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    for slug, f, which, ja in (('tangliwendui-en', 'en.json', 'en', False),
                               ('tangliwendui-ja', 'ja.json', 'ja', True)):
        if not os.path.exists(f'{D}/{f}'):
            print('（まだ無い）', f); continue
        X = json.load(open(f'{D}/{f}', encoding='utf-8'))
        body, toc = west_body(T, X, ja)
        write(os.path.join(ROOT, slug + '.html'),
              page(slug, css(which), body, toc, west_colophon(X), js(which), ''))


if __name__ == '__main__':
    main()
