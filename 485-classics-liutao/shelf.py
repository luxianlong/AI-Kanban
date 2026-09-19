# -*- coding: utf-8 -*-
"""/p/dianji.html（手書きの書架頁）に《六韜》を一冊足す。

dianji.html は生成物ではなく手書きなので、足すべき箇所は一つではない。
本スクリプトは冪等——すでに liutao があれば何もしない。
"""
import os, re, json

P = os.path.expanduser('~/Dashboard/frontend/public/p/dianji.html')

BAY = '''    <li class="bay" data-title="六韬" data-imprint="先秦 · 六卷六十篇" data-href="/p/liutao-hans.html" data-href-hans="/p/liutao-hans.html" data-title-hans="六韬" data-imprint-hans="先秦 · 六卷六十篇" data-label-hans="打开《六韬》简体读本" data-href-hant="/p/liutao.html" data-title-hant="六韜" data-imprint-hant="先秦 · 六卷六十篇" data-label-hant="打開《六韜》繁體讀本" data-href-en="/p/liutao-en.html" data-title-en="The Six Secret Teachings" data-imprint-en="pre-Qin · six scrolls, 60 chapters" data-label-en="Open The Six Secret Teachings (English reading)" data-href-ja="/p/liutao-ja.html" data-title-ja="六韜" data-imprint-ja="先秦 · 全六巻六十篇" data-label-ja="『六韜』の日本語訳を開く">
      <div class="stand">
        <a class="vol" href="/p/liutao-hans.html" aria-label="打开《六韬》简体读本">
          <div class="book" style="--ar:.6871">
            <div class="cover liutao"><img src="/img/classics/liutao-cover.jpg" alt="四部丛刊影印常熟瞿氏铁琴铜剑楼藏景宋刊本《六韬》卷第一卷端叶，题「六韜卷第一　文韜　文師」，正文起「文王將田，史編布卜」，钤藏书印四颗" width="560" height="815" loading="lazy" decoding="async"></div>
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="zh-Hans"><a href="/p/liutao-hans.html">六韬</a></h3>
        <p class="imprint" lang="zh-Hans">先秦 · 六卷六十篇</p>
        <p class="editions"><a href="/p/liutao-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《六韬》简体读本">简体</a><span class="sep">·</span><a href="/p/liutao.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《六韜》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/liutao-en.html" hreflang="en" lang="en" aria-label="Open The Six Secret Teachings (English reading)">English</a><span class="sep">·</span><a href="/p/liutao-ja.html" hreflang="ja" lang="ja" aria-label="『六韜』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''

CSS = '''.cover.liutao img{
  /* 这一张是书自己的卷端叶，而且是现存最早的那一支：四部丛刊影印常熟瞿氏
     铁琴铜剑楼藏景宋刊本《六韬》（Wikimedia Commons，公有领域）djvu 第 10 叶，
     卷第一卷端。叶上依次是「六韜卷第一」、韬名「文韜」、篇题「文師」、
     全书第一句「文王將田，史編布卜」，另有藏书印四颗。本页正文正是拿这个
     本子作通校之一。djvu 每叶最大只出到 960x1351；按行/列亮度量出墨在
     x 1..802、y 98..1262，为不使版匡贴边，左右各留数像素，裁到
     x 0..811、y 90..1271，再缩到 560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''

ITEM = '''    {
     "@type": "ListItem",
     "position": 13,
     "name": "六韬",
     "alternateName": [
      "六韜",
      "The Six Secret Teachings — Liutao"
     ],
     "item": {
      "@id": "https://youraikanban.com/p/liutao-hans.html#book",
      "url": "https://youraikanban.com/p/liutao-hans.html",
      "sameAs": [
       "https://www.wikidata.org/entity/Q1187353",
       "https://zh.wikipedia.org/wiki/%E5%85%AD%E9%9F%AC"
      ]
     }
    }
'''


def main():
    s = open(P, encoding='utf-8').read()
    if 'liutao' in s:
        print('dianji.html: 已有六韜，何もしない')
        return
    n = len(s)

    # 1) 書架の最後に一冊
    tail = '''        <p class="editions"><a href="/p/fanjing-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《反经》简体读本">简体</a><span class="sep">·</span><a href="/p/fanjing.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《反經》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/fanjing-en.html" hreflang="en" lang="en" aria-label="Open The Classic of Reversal (English reading)">English</a><span class="sep">·</span><a href="/p/fanjing-ja.html" hreflang="ja" lang="ja" aria-label="『反経』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''
    assert tail in s, '反經の bay が見つからない'
    s = s.replace(tail, tail + BAY, 1)

    # 2) 封面の裁ち寸法（菜根譚の規則の直後に置く）
    marker = '''     560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''
    i = s.rindex(marker) + len(marker)
    s = s[:i] + CSS + s[i:]

    # 3) JSON-LD の ItemList
    s = s.replace('"numberOfItems": 12,', '"numberOfItems": 13,', 1)
    last = '''      "sameAs": [
       "https://www.wikidata.org/entity/Q15928000",
       "https://zh.wikipedia.org/wiki/%E9%95%B7%E7%9F%AD%E7%B6%93"
      ]
     }
    }
'''
    assert last in s
    s = s.replace(last, last.rstrip('\n')[:-1].rstrip() + '\n    },\n' + ITEM, 1)

    # 4) 題と description の「十二部」を「十三部」に、書名の列に《六韬》を足す
    s = s.replace('十二部中文经典全文', '十三部中文经典全文')
    s = s.replace('十二部中文典籍的全文读本', '十三部中文典籍的全文读本')
    s = s.replace('《菜根谭》《反经》十二部全文读本', '《菜根谭》《反经》《六韬》十三部全文读本')
    s = s.replace('Twelve Chinese classics in full text', 'Thirteen Chinese classics in full text')
    s = s.replace('Twelve Chinese classics in full —', 'Thirteen Chinese classics in full —')
    s = s.replace('『菜根譚』『反経』など中国古典十二部', '『菜根譚』『反経』『六韜』など中国古典十三部')

    open(P, 'w', encoding='utf-8').write(s)

    # 5) 書架カードの題と抄（dianji.meta.json）。ここは一度古びたことがある
    #    ので、本を足したら必ず一緒に直す。
    mp = P[:-5] + '.meta.json'
    m = json.load(open(mp, encoding='utf-8'))
    if '六韬' not in m['title']:
        m['title'] += ' · 六韬'
        m['summary'] = m['summary'].replace('《反经》，按书影排开', '《反经》《六韬》，按书影排开')
    m['bytes'] = os.path.getsize(P)
    json.dump(m, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    open(mp, 'a', encoding='utf-8').write('\n')
    # 検算
    assert '十二部' not in s and 'Twelve Chinese' not in s, '「十二部」が残っている'
    txt = s[s.index('<script type="application/ld+json">') + 35:]
    ld = json.loads(txt[:txt.index('</script>')])
    items = ld['@graph'][-1]['itemListElement'] if '@graph' in ld else None
    print('dianji.html', n, '→', len(s), 'bytes；ItemList',
          len(items) if items else '?', '件')


if __name__ == '__main__':
    main()
