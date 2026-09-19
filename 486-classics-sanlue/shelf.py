# -*- coding: utf-8 -*-
"""/p/dianji.html（手書きの書架頁）に《三略》を一冊足す。

dianji.html は生成物ではなく手書きなので、足すべき箇所は一つではない。
本スクリプトは冪等——すでに sanlue があれば何もしない。
"""
import os, re, json

P = os.path.expanduser('~/Dashboard/frontend/public/p/dianji.html')

ALT = ('日本国立国会图书馆藏庆长九年（1604）伏见版古活字本《黄石公三略》'
       '卷之上卷端叶，题「黃石公三略卷之上　上略」，正文起'
       '「夫主將之法，務擥英雄之心」，钤朱印二颗')

BAY = '''    <li class="bay" data-title="三略" data-imprint="秦 · 上中下三卷" data-href="/p/sanlue-hans.html" data-href-hans="/p/sanlue-hans.html" data-title-hans="三略" data-imprint-hans="秦 · 上中下三卷" data-label-hans="打开《三略》简体读本" data-href-hant="/p/sanlue.html" data-title-hant="三略" data-imprint-hant="秦 · 上中下三卷" data-label-hant="打開《三略》繁體讀本" data-href-en="/p/sanlue-en.html" data-title-en="The Three Strategies" data-imprint-en="Qin · three books" data-label-en="Open The Three Strategies (English reading)" data-href-ja="/p/sanlue-ja.html" data-title-ja="三略" data-imprint-ja="秦 · 上中下三巻" data-label-ja="『三略』の日本語訳を開く">
      <div class="stand">
        <a class="vol" href="/p/sanlue-hans.html" aria-label="打开《三略》简体读本">
          <div class="book" style="--ar:.7044">
            <div class="cover sanlue"><img src="/img/classics/sanlue-cover.jpg" alt="%s" width="560" height="795" loading="lazy" decoding="async"></div>
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="zh-Hans"><a href="/p/sanlue-hans.html">三略</a></h3>
        <p class="imprint" lang="zh-Hans">秦 · 上中下三卷</p>
        <p class="editions"><a href="/p/sanlue-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《三略》简体读本">简体</a><span class="sep">·</span><a href="/p/sanlue.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《三略》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/sanlue-en.html" hreflang="en" lang="en" aria-label="Open The Three Strategies (English reading)">English</a><span class="sep">·</span><a href="/p/sanlue-ja.html" hreflang="ja" lang="ja" aria-label="『三略』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
''' % ALT

CSS = '''.cover.sanlue img{
  /* 这一张是本书自己的卷端叶，而且出于一个有名有姓、有年月的本子：日本
     国立国会图书馆藏庆长九年（1604）伏见版古活字本《黄石公三略》（请求
     记号 WA7-22，Wikimedia Commons，公有领域）第 2 叶，卷之上卷端。叶上
     依次是「黃石公三略卷之上」、篇题「上略」、全书第一句「夫主將之法，
     務擥英雄之心」，另有朱印二颗，以及后人手书的校语「講作怨／直作人」。
     这个本子是德川家康命圆光寺以木活字印行的，卷末刊记自述曾据《三略
     讲义》《三略直解》二注本校改——本页正文的要处正是拿它目验的。
     PDF 原寸 10150x8693，按 4200 宽取出后量墨：版匡 x 200..1975、
     y 435..2840（右边的竖栏 x=1970 最浓）。留一点纸边，裁到 x 175..1995、
     y 345..2930，再缩到 560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''

ITEM = '''    {
     "@type": "ListItem",
     "position": 14,
     "name": "三略",
     "alternateName": [
      "黄石公三略",
      "The Three Strategies of Huang Shigong — Sanlue"
     ],
     "item": {
      "@id": "https://youraikanban.com/p/sanlue-hans.html#book",
      "url": "https://youraikanban.com/p/sanlue-hans.html",
      "sameAs": [
       "https://www.wikidata.org/entity/Q1051338",
       "https://zh.wikipedia.org/wiki/%E4%B8%89%E7%95%A5"
      ]
     }
    }
'''


def main():
    s = open(P, encoding='utf-8').read()
    if 'sanlue' in s:
        print('dianji.html: 已有三略，何もしない')
        return
    n = len(s)

    # 1) 書架の最後に一冊
    tail = '''        <p class="editions"><a href="/p/liutao-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《六韬》简体读本">简体</a><span class="sep">·</span><a href="/p/liutao.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《六韜》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/liutao-en.html" hreflang="en" lang="en" aria-label="Open The Six Secret Teachings (English reading)">English</a><span class="sep">·</span><a href="/p/liutao-ja.html" hreflang="ja" lang="ja" aria-label="『六韜』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''
    assert tail in s, '六韜の bay が見つからない'
    s = s.replace(tail, tail + BAY, 1)

    # 2) 封面の裁ち寸法（六韜の規則の直後に置く）
    marker = '''  width:100%; height:100%; left:0%; top:0%;
}
'''
    i = s.rindex(marker) + len(marker)
    s = s[:i] + CSS + s[i:]

    # 3) JSON-LD の ItemList
    s = s.replace('"numberOfItems": 13,', '"numberOfItems": 14,', 1)
    last = '''      "sameAs": [
       "https://www.wikidata.org/entity/Q1187353",
       "https://zh.wikipedia.org/wiki/%E5%85%AD%E9%9F%AC"
      ]
     }
    }
'''
    assert last in s
    s = s.replace(last, last.rstrip('\n')[:-1].rstrip() + '\n    },\n' + ITEM, 1)

    # 4) 題と description の「十三部」を「十四部」に、書名の列に《三略》を足す
    s = s.replace('十三部中文经典全文', '十四部中文经典全文')
    s = s.replace('十三部中文典籍的全文读本', '十四部中文典籍的全文读本')
    s = s.replace('《反经》《六韬》十三部全文读本', '《反经》《六韬》《三略》十四部全文读本')
    s = s.replace('Thirteen Chinese classics in full text', 'Fourteen Chinese classics in full text')
    s = s.replace('Thirteen Chinese classics in full —', 'Fourteen Chinese classics in full —')
    s = s.replace('『反経』『六韜』など中国古典十三部', '『反経』『六韜』『三略』など中国古典十四部')

    open(P, 'w', encoding='utf-8').write(s)

    # 5) 書架カードの題と抄（dianji.meta.json）。ここは一度古びたことが
    #    あるので、本を足したら必ず一緒に直す。
    mp = P[:-5] + '.meta.json'
    m = json.load(open(mp, encoding='utf-8'))
    if '三略' not in m['title']:
        m['title'] += ' · 三略'
        m['summary'] = m['summary'].replace('《六韬》，按书影排开', '《六韬》《三略》，按书影排开')
    m['bytes'] = os.path.getsize(P)
    json.dump(m, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    open(mp, 'a', encoding='utf-8').write('\n')

    # 検算
    assert '十三部' not in s and 'Thirteen Chinese' not in s, '「十三部」が残っている'
    txt = s[s.index('<script type="application/ld+json">') + 35:]
    ld = json.loads(txt[:txt.index('</script>')])
    items = ld['@graph'][-1]['itemListElement'] if '@graph' in ld else None
    print('dianji.html', n, '→', len(s), 'bytes；ItemList',
          len(items) if items else '?', '件')


if __name__ == '__main__':
    main()
