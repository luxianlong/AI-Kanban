# -*- coding: utf-8 -*-
"""/p/dianji.html（手書きの書架頁）に《茶經》を一冊足す。

dianji.html は生成物ではなく手書きなので、足すべき箇所は一つではない。
本スクリプトは冪等——すでに chajing があれば何もしない。
"""
import os, json

P = os.path.expanduser('~/Dashboard/frontend/public/p/dianji.html')
ALT = ('国立国会图书馆藏明刊《百川學海》壬集《茶經》卷上卷端叶，'
       '题「茶經卷上」「一之源」，撰者行作「唐　竟陵陸羽」，'
       '正文起「茶者，南方之嘉木也。一尺、二尺，迺至數十尺」，钤朱文藏书印三方')

BAY = '''    <li class="bay" data-title="茶经" data-imprint="唐 · 三卷十篇" data-href="/p/chajing-hans.html" data-href-hans="/p/chajing-hans.html" data-title-hans="茶经" data-imprint-hans="唐 · 三卷十篇" data-label-hans="打开《茶经》简体读本" data-href-hant="/p/chajing.html" data-title-hant="茶經" data-imprint-hant="唐 · 三卷十篇" data-label-hant="打開《茶經》繁體讀本" data-href-en="/p/chajing-en.html" data-title-en="The Classic of Tea" data-imprint-en="Tang · three scrolls, 10 chapters" data-label-en="Open The Classic of Tea (English reading)" data-href-ja="/p/chajing-ja.html" data-title-ja="茶經" data-imprint-ja="唐 · 全三巻十篇" data-label-ja="『茶經』の日本語訳を開く">
      <div class="stand">
        <a class="vol" href="/p/chajing-hans.html" aria-label="打开《茶经》简体读本">
          <div class="book" style="--ar:.7843">
            <div class="cover chajing"><img src="/img/classics/chajing-cover.jpg" alt="%s" width="560" height="714" loading="lazy" decoding="async"></div>
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="zh-Hans"><a href="/p/chajing-hans.html">茶经</a></h3>
        <p class="imprint" lang="zh-Hans">唐 · 三卷十篇</p>
        <p class="editions"><a href="/p/chajing-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《茶经》简体读本">简体</a><span class="sep">·</span><a href="/p/chajing.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《茶經》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/chajing-en.html" hreflang="en" lang="en" aria-label="Open The Classic of Tea (English reading)">English</a><span class="sep">·</span><a href="/p/chajing-ja.html" hreflang="ja" lang="ja" aria-label="『茶經』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
''' % ALT

CSS = '''.cover.chajing img{
  /* 这一张是国立国会图书馆藏的明刊《百川學海》壬集本（请求记号 特1-2229，
     Wikimedia Commons，PD-scan）。《百川學海》是宋咸淳间左圭所辑，今存
     《茶經》最早的一支就在这部丛书里；此本是明代的重刊，与四库一系不同源。
     叶上自右而左是「茶經卷上」「一之源」「唐　竟陵陸羽」与全书第一句
     「茶者，南方之嘉木也。一尺、二尺，迺至數十尺」，下钤朱文藏书印三方。
     第三行的篇题「一之源」正是本页从底本而不从四库本的根据——四库本此处
     作「一茶之源」；撰者行「唐　竟陵陸羽」四库本也作「唐　陸羽　撰」。
     PDF 的一页是一个见开（5847x5047），第三图的左半叶才是卷上的卷端。
     pdfjpg.py 把贴着的 JPEG 原样取出，inkscan.js 按列/行亮度量墨：左半叶
     的墨在 x 317..2967、y 614..3990。留一点纸边，裁 x 300..2990、
     y 590..4020，再缩到 560 宽（→560x714），所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''

ITEM = '''    {
     "@type": "ListItem",
     "position": 19,
     "name": "茶经",
     "alternateName": [
      "茶經",
      "The Classic of Tea"
     ],
     "item": {
      "@id": "https://youraikanban.com/p/chajing-hans.html#book",
      "url": "https://youraikanban.com/p/chajing-hans.html",
      "sameAs": [
       "https://www.wikidata.org/entity/Q259153",
       "https://zh.wikipedia.org/wiki/%E8%8C%B6%E7%BB%8F"
      ]
     }
    }
'''


def main():
    s = open(P, encoding='utf-8').read()
    if 'chajing' in s:
        print('dianji.html: 已有茶經，何もしない')
        return
    n = len(s)

    # 1) 書架の最後に一冊（《尉繚子》の bay の直後）
    tail = '''        <p class="editions"><a href="/p/weiliaozi-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《尉缭子》简体读本">简体</a><span class="sep">·</span><a href="/p/weiliaozi.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《尉繚子》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/weiliaozi-en.html" hreflang="en" lang="en" aria-label="Open Weiliaozi (English reading)">English</a><span class="sep">·</span><a href="/p/weiliaozi-ja.html" hreflang="ja" lang="ja" aria-label="『尉繚子』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''
    assert tail in s, '尉繚子の bay が見つからない'
    s = s.replace(tail, tail + BAY, 1)

    # 2) 封面の裁ち寸法（尉繚子の規則の直後に置く）
    marker = '''     x 0..656、y 160..1092，再缩到 560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''
    assert marker in s, '尉繚子の cover 規則が見つからない'
    i = s.index(marker) + len(marker)
    s = s[:i] + CSS + s[i:]

    # 3) JSON-LD の ItemList
    s = s.replace('"numberOfItems": 18,', '"numberOfItems": 19,', 1)
    last = '''      "sameAs": [
       "https://www.wikidata.org/entity/Q1051326",
       "https://zh.wikipedia.org/wiki/%E5%B0%89%E7%BC%AD%E5%AD%90"
      ]
     }
    }
'''
    assert last in s
    s = s.replace(last, last.rstrip('\n') + ',\n' + ITEM, 1)

    # 4) 「十八部」を「十九部」に、書名の列に《茶经》を足す
    s = s.replace('十八部中文经典全文', '十九部中文经典全文')
    s = s.replace('十八部中文典籍的全文读本', '十九部中文典籍的全文读本')
    s = s.replace('《唐李问对》《尉缭子》十八部全文读本',
                  '《唐李问对》《尉缭子》《茶经》十九部全文读本')
    s = s.replace('Eighteen Chinese classics in full', 'Nineteen Chinese classics in full')
    s = s.replace('中国古典十八部', '中国古典十九部')
    s = s.replace('『尉繚子』など', '『尉繚子』『茶經』など')

    open(P, 'w', encoding='utf-8').write(s)

    # 5) 書架カードの題と抄（dianji.meta.json）。本を足したら必ず一緒に直す。
    mp = P[:-5] + '.meta.json'
    m = json.load(open(mp, encoding='utf-8'))
    if '茶经' not in m['title']:
        m['title'] += ' · 茶经'
        m['summary'] = m['summary'].replace('《尉缭子》，按书影排开',
                                            '《尉缭子》《茶经》，按书影排开')
    m['bytes'] = os.path.getsize(P)
    json.dump(m, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    open(mp, 'a', encoding='utf-8').write('\n')

    # 検算
    assert '十八部' not in s and 'Eighteen Chinese' not in s, '「十八部」が残っている'
    assert s.count('class="bay"') == 19, s.count('class="bay"')
    txt = s[s.index('<script type="application/ld+json">') + 35:]
    ld = json.loads(txt[:txt.index('</script>')])
    items = ld['@graph'][-1]['itemListElement']
    assert [x['position'] for x in items] == list(range(1, 20)), 'position が飛んでいる'
    print('dianji.html', n, '→', len(s), 'bytes；書架 %d 冊、ItemList %d 件'
          % (s.count('class="bay"'), len(items)))


if __name__ == '__main__':
    main()
