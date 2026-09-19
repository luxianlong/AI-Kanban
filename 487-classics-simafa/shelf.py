# -*- coding: utf-8 -*-
"""/p/dianji.html（手書きの書架頁）に《司馬法》を一冊足す。

dianji.html は生成物ではなく手書きなので、足すべき箇所は一つではない。
本スクリプトは冪等——すでに simafa があれば何もしない。
"""
import os, json

P = os.path.expanduser('~/Dashboard/frontend/public/p/dianji.html')

ALT = ('《四部丛刊》影印常熟瞿氏铁琴铜剑楼藏景宋钞本《司马法》卷上卷端叶，'
       '题「司馬法卷上　仁本第一」，正文起「古者以仁爲本，以義治之之謂正」，'
       '右侧钤朱文藏书印二颗')

BAY = '''    <li class="bay" data-title="司马法" data-imprint="先秦 · 全五篇" data-href="/p/simafa-hans.html" data-href-hans="/p/simafa-hans.html" data-title-hans="司马法" data-imprint-hans="先秦 · 全五篇" data-label-hans="打开《司马法》简体读本" data-href-hant="/p/simafa.html" data-title-hant="司馬法" data-imprint-hant="先秦 · 全五篇" data-label-hant="打開《司馬法》繁體讀本" data-href-en="/p/simafa-en.html" data-title-en="The Methods of the Sima" data-imprint-en="pre-Qin · five chapters" data-label-en="Open The Methods of the Sima (English reading)" data-href-ja="/p/simafa-ja.html" data-title-ja="司馬法" data-imprint-ja="先秦 · 全五篇" data-label-ja="『司馬法』の日本語訳を開く">
      <div class="stand">
        <a class="vol" href="/p/simafa-hans.html" aria-label="打开《司马法》简体读本">
          <div class="book" style="--ar:.6596">
            <div class="cover simafa"><img src="/img/classics/simafa-cover.jpg" alt="%s" width="560" height="849" loading="lazy" decoding="async"></div>
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="zh-Hans"><a href="/p/simafa-hans.html">司马法</a></h3>
        <p class="imprint" lang="zh-Hans">先秦 · 全五篇</p>
        <p class="editions"><a href="/p/simafa-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《司马法》简体读本">简体</a><span class="sep">·</span><a href="/p/simafa.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《司馬法》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/simafa-en.html" hreflang="en" lang="en" aria-label="Open The Methods of the Sima (English reading)">English</a><span class="sep">·</span><a href="/p/simafa-ja.html" hreflang="ja" lang="ja" aria-label="『司馬法』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
''' % ALT

CSS = '''.cover.simafa img{
  /* 这一张是本书自己的卷端叶，而且出于本页的通校本之一：《四部丛刊》影印
     的常熟瞿氏铁琴铜剑楼藏景宋钞本《司马法》卷上第一叶（Wikimedia Commons
     所藏 Sibu Congkan 0343 第 138 叶，公有领域；维基文库的《司马法（四部
     丛刊本）》卷第上用的也正是这个 djvu 的 138–147 叶）。叶上依次是
     「司馬法卷上」、篇题「仁本第一」与全书第一句「古者以仁爲本，以義治之
     之謂正」，右侧钤朱文藏书印二颗。《四部丛刊》的牌记自述「上海涵芬樓借
     常熟瞿氏鐵琴銅劍樓影宋鈔本印行」——与《六韬》的封面出自同一家藏书楼。
     djvu 一叶 2600x4578，按 1920 宽取出后量墨：版匡上 y=300、下 y=2626、
     右栏 x=1630，左栏较淡，约 x=114。留一点纸边，裁到 x 70..1680、
     y 250..2690，再缩到 560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''

ITEM = '''    {
     "@type": "ListItem",
     "position": 15,
     "name": "司马法",
     "alternateName": [
      "司馬穰苴兵法",
      "The Methods of the Sima — Sima fa"
     ],
     "item": {
      "@id": "https://youraikanban.com/p/simafa-hans.html#book",
      "url": "https://youraikanban.com/p/simafa-hans.html",
      "sameAs": [
       "https://www.wikidata.org/entity/Q1051357",
       "https://zh.wikipedia.org/wiki/%E5%8F%B8%E9%A9%AC%E6%B3%95"
      ]
     }
    }
'''


def main():
    s = open(P, encoding='utf-8').read()
    if 'simafa' in s:
        print('dianji.html: 已有司馬法，何もしない')
        return
    n = len(s)

    # 1) 書架の最後に一冊（《三略》の bay の直後）
    tail = '''        <p class="editions"><a href="/p/sanlue-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《三略》简体读本">简体</a><span class="sep">·</span><a href="/p/sanlue.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《三略》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/sanlue-en.html" hreflang="en" lang="en" aria-label="Open The Three Strategies (English reading)">English</a><span class="sep">·</span><a href="/p/sanlue-ja.html" hreflang="ja" lang="ja" aria-label="『三略』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''
    assert tail in s, '三略の bay が見つからない'
    s = s.replace(tail, tail + BAY, 1)

    # 2) 封面の裁ち寸法（三略の規則の直後に置く）
    marker = '''  width:100%; height:100%; left:0%; top:0%;
}
'''
    i = s.rindex(marker) + len(marker)
    s = s[:i] + CSS + s[i:]

    # 3) JSON-LD の ItemList
    s = s.replace('"numberOfItems": 14,', '"numberOfItems": 15,', 1)
    last = '''      "sameAs": [
       "https://www.wikidata.org/entity/Q1051338",
       "https://zh.wikipedia.org/wiki/%E4%B8%89%E7%95%A5"
      ]
     }
    }
'''
    assert last in s
    s = s.replace(last, last.rstrip('\n')[:-1].rstrip() + '\n    },\n' + ITEM, 1)

    # 4) 題と description の「十四部」を「十五部」に、書名の列に《司马法》を足す
    s = s.replace('十四部中文经典全文', '十五部中文经典全文')
    s = s.replace('十四部中文典籍的全文读本', '十五部中文典籍的全文读本')
    s = s.replace('《六韬》《三略》十四部全文读本', '《六韬》《三略》《司马法》十五部全文读本')
    s = s.replace('Fourteen Chinese classics in full text', 'Fifteen Chinese classics in full text')
    s = s.replace('Fourteen Chinese classics in full —', 'Fifteen Chinese classics in full —')
    s = s.replace('『六韜』『三略』など中国古典十四部', '『六韜』『三略』『司馬法』など中国古典十五部')

    open(P, 'w', encoding='utf-8').write(s)

    # 5) 書架カードの題と抄（dianji.meta.json）。本を足したら必ず一緒に直す。
    mp = P[:-5] + '.meta.json'
    m = json.load(open(mp, encoding='utf-8'))
    if '司马法' not in m['title']:
        m['title'] += ' · 司马法'
        m['summary'] = m['summary'].replace('《三略》，按书影排开', '《三略》《司马法》，按书影排开')
    m['bytes'] = os.path.getsize(P)
    json.dump(m, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    open(mp, 'a', encoding='utf-8').write('\n')

    # 検算
    assert '十四部' not in s and 'Fourteen Chinese' not in s, '「十四部」が残っている'
    txt = s[s.index('<script type="application/ld+json">') + 35:]
    ld = json.loads(txt[:txt.index('</script>')])
    items = ld['@graph'][-1]['itemListElement'] if '@graph' in ld else None
    print('dianji.html', n, '→', len(s), 'bytes；ItemList',
          len(items) if items else '?', '件')


if __name__ == '__main__':
    main()
