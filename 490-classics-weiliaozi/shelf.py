# -*- coding: utf-8 -*-
"""/p/dianji.html（手書きの書架頁）に《尉繚子》を一冊足す。

dianji.html は生成物ではなく手書きなので、足すべき箇所は一つではない。
本スクリプトは冪等——すでに weiliaozi があれば何もしない。
"""
import os, re, json

P = os.path.expanduser('~/Dashboard/frontend/public/p/dianji.html')
ALT = '上海图书馆藏《尉繚子》白文刊本卷一卷端叶，半叶八行、行二十字，题「尉繚子卷一　天官第一」，正文起「梁惠王問尉繚子曰：黃帝刑德可以百勝，有之乎」，题下钤朱文藏书印二方'

BAY = '''    <li class="bay" data-title="尉缭子" data-imprint="先秦 · 五卷二十四篇" data-href="/p/weiliaozi-hans.html" data-href-hans="/p/weiliaozi-hans.html" data-title-hans="尉缭子" data-imprint-hans="先秦 · 五卷二十四篇" data-label-hans="打开《尉缭子》简体读本" data-href-hant="/p/weiliaozi.html" data-title-hant="尉繚子" data-imprint-hant="先秦 · 五卷二十四篇" data-label-hant="打開《尉繚子》繁體讀本" data-href-en="/p/weiliaozi-en.html" data-title-en="Weiliaozi" data-imprint-en="pre-Qin · five scrolls, 24 chapters" data-label-en="Open Weiliaozi (English reading)" data-href-ja="/p/weiliaozi-ja.html" data-title-ja="尉繚子" data-imprint-ja="先秦 · 全五巻二十四篇" data-label-ja="『尉繚子』の日本語訳を開く">
      <div class="stand">
        <a class="vol" href="/p/weiliaozi-hans.html" aria-label="打开《尉缭子》简体读本">
          <div class="book" style="--ar:.7035">
            <div class="cover weiliaozi"><img src="/img/classics/weiliaozi-cover.jpg" alt="%s" width="560" height="796" loading="lazy" decoding="async"></div>
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="zh-Hans"><a href="/p/weiliaozi-hans.html">尉缭子</a></h3>
        <p class="imprint" lang="zh-Hans">先秦 · 五卷二十四篇</p>
        <p class="editions"><a href="/p/weiliaozi-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《尉缭子》简体读本">简体</a><span class="sep">·</span><a href="/p/weiliaozi.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《尉繚子》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/weiliaozi-en.html" hreflang="en" lang="en" aria-label="Open Weiliaozi (English reading)">English</a><span class="sep">·</span><a href="/p/weiliaozi-ja.html" hreflang="ja" lang="ja" aria-label="『尉繚子』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
''' % ALT

CSS = '''.cover.weiliaozi img{
  /* 这一张也是上海图书馆藏的白文刊本（Wikimedia Commons，PD-scan），
     半叶八行、行二十字，四周单边，不出四库一系，也没有注解——本页两处改字
     都是在它的书影上一字一字看过的。叶上自右而左是「尉繚子卷一」「天官第一」
     与全书第一句「梁惠王問尉繚子曰：黃帝刑德可以百勝，有之乎」，题下钤朱文
     藏书印二方。第三行的「刑德」正是本页所改的第一处——四库本作「形德」。
     PDF 的一页是一个见开（1503x1134），第一页右边是遊纸、左边才是卷端半叶。
     pdfjpg.py 把贴着的 JPEG 原样取出，按列/行亮度量墨：半叶的右纵栏在
     x 633..644，上下横栏在 y 180..189 与 y 1062..1073。留一点纸边，裁
     x 0..656、y 160..1092，再缩到 560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''

ITEM = '''    {
     "@type": "ListItem",
     "position": 18,
     "name": "尉缭子",
     "alternateName": [
      "尉繚子",
      "Weiliaozi"
     ],
     "item": {
      "@id": "https://youraikanban.com/p/weiliaozi-hans.html#book",
      "url": "https://youraikanban.com/p/weiliaozi-hans.html",
      "sameAs": [
       "https://www.wikidata.org/entity/Q1051326",
       "https://zh.wikipedia.org/wiki/%E5%B0%89%E7%BC%AD%E5%AD%90"
      ]
     }
    }
'''


def main():
    s = open(P, encoding='utf-8').read()
    if 'weiliaozi' in s:
        print('dianji.html: 已有尉繚子，何もしない')
        return
    n = len(s)

    # 1) 書架の最後に一冊（《唐李問對》の bay の直後）
    tail = '''        <p class="editions"><a href="/p/tangliwendui-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《唐李问对》简体读本">简体</a><span class="sep">·</span><a href="/p/tangliwendui.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《唐李問對》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/tangliwendui-en.html" hreflang="en" lang="en" aria-label="Open Questions and Replies (English reading)">English</a><span class="sep">·</span><a href="/p/tangliwendui-ja.html" hreflang="ja" lang="ja" aria-label="『李衛公問対』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''
    assert tail in s, '唐李問對の bay が見つからない'
    s = s.replace(tail, tail + BAY, 1)

    # 2) 封面の裁ち寸法（唐李問對の規則の直後に置く）
    marker = '''     左纵栏 x 25，裁 x 0..656、y 243..1168 再缩到 560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''
    assert marker in s, '唐李問對の cover 規則が見つからない'
    i = s.index(marker) + len(marker)
    s = s[:i] + CSS + s[i:]

    # 3) JSON-LD の ItemList
    s = s.replace('"numberOfItems": 17,', '"numberOfItems": 18,', 1)
    last = '''      "sameAs": [
       "https://www.wikidata.org/entity/Q1051352",
       "https://zh.wikipedia.org/wiki/%E5%94%90%E5%A4%AA%E5%AE%97%E6%9D%8E%E5%8D%AB%E5%85%AC%E9%97%AE%E5%AF%B9"
      ]
     }
    }
'''
    assert last in s
    s = s.replace(last, last.rstrip('\n') + ',\n' + ITEM, 1)

    # 4) 「十七部」を「十八部」に、書名の列に《尉缭子》を足す
    s = s.replace('十七部中文经典全文', '十八部中文经典全文')
    s = s.replace('十七部中文典籍的全文读本', '十八部中文典籍的全文读本')
    s = s.replace('《吴子》《唐李问对》十七部全文读本',
                  '《吴子》《唐李问对》《尉缭子》十八部全文读本')
    s = s.replace('Seventeen Chinese classics in full', 'Eighteen Chinese classics in full')
    s = s.replace('中国古典十七部', '中国古典十八部')
    s = s.replace('『李衛公問対』など', '『李衛公問対』『尉繚子』など')

    open(P, 'w', encoding='utf-8').write(s)

    # 5) 書架カードの題と抄（dianji.meta.json）。本を足したら必ず一緒に直す。
    mp = P[:-5] + '.meta.json'
    m = json.load(open(mp, encoding='utf-8'))
    if '尉缭子' not in m['title']:
        m['title'] += ' · 尉缭子'
        m['summary'] = m['summary'].replace('《唐李问对》，按书影排开',
                                            '《唐李问对》《尉缭子》，按书影排开')
    m['bytes'] = os.path.getsize(P)
    json.dump(m, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    open(mp, 'a', encoding='utf-8').write('\n')

    # 検算
    assert '十七部' not in s and 'Seventeen Chinese' not in s, '「十七部」が残っている'
    assert s.count('class="bay"') == 18, s.count('class="bay"')
    txt = s[s.index('<script type="application/ld+json">') + 35:]
    ld = json.loads(txt[:txt.index('</script>')])
    items = ld['@graph'][-1]['itemListElement']
    assert [x['position'] for x in items] == list(range(1, 19)), 'position が飛んでいる'
    print('dianji.html', n, '→', len(s), 'bytes；書架 %d 冊、ItemList %d 件'
          % (s.count('class="bay"'), len(items)))


if __name__ == '__main__':
    main()
