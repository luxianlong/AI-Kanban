# -*- coding: utf-8 -*-
"""/p/dianji.html（手書きの書架頁）に《唐李問對》を一冊足す。

dianji.html は生成物ではなく手書きなので、足すべき箇所は一つではない。
本スクリプトは冪等——すでに tangliwendui があれば何もしない。
"""
import os, json

P = os.path.expanduser('~/Dashboard/frontend/public/p/dianji.html')

ALT = ('上海图书馆藏《唐太宗李衞公問對三卷》白文刊本卷上卷端叶，'
       '半叶八行、行二十字，题「唐太宗李衞公問對卷上」，'
       '正文起「太宗曰：高麗數侵新羅」，题下钤朱文藏书印二方，'
       '下一方作「上海圖書館藏」')

BAY = '''    <li class="bay" data-title="唐李问对" data-imprint="唐 · 全三卷" data-href="/p/tangliwendui-hans.html" data-href-hans="/p/tangliwendui-hans.html" data-title-hans="唐李问对" data-imprint-hans="唐 · 全三卷" data-label-hans="打开《唐李问对》简体读本" data-href-hant="/p/tangliwendui.html" data-title-hant="唐李問對" data-imprint-hant="唐 · 全三卷" data-label-hant="打開《唐李問對》繁體讀本" data-href-en="/p/tangliwendui-en.html" data-title-en="Questions and Replies" data-imprint-en="Tang · three scrolls" data-label-en="Open Questions and Replies (English reading)" data-href-ja="/p/tangliwendui-ja.html" data-title-ja="李衛公問対" data-imprint-ja="唐 · 全三巻" data-label-ja="『李衛公問対』の日本語訳を開く">
      <div class="stand">
        <a class="vol" href="/p/tangliwendui-hans.html" aria-label="打开《唐李问对》简体读本">
          <div class="book" style="--ar:.7089">
            <div class="cover tangliwendui"><img src="/img/classics/tangliwendui-cover.jpg" alt="%s" width="560" height="790" loading="lazy" decoding="async"></div>
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="zh-Hans"><a href="/p/tangliwendui-hans.html">唐李问对</a></h3>
        <p class="imprint" lang="zh-Hans">唐 · 全三卷</p>
        <p class="editions"><a href="/p/tangliwendui-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《唐李问对》简体读本">简体</a><span class="sep">·</span><a href="/p/tangliwendui.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《唐李問對》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/tangliwendui-en.html" hreflang="en" lang="en" aria-label="Open Questions and Replies (English reading)">English</a><span class="sep">·</span><a href="/p/tangliwendui-ja.html" hreflang="ja" lang="ja" aria-label="『李衛公問対』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
''' % ALT

CSS = '''.cover.tangliwendui img{
  /* 这一张是第三个证人自己的卷端叶——上海图书馆藏《唐太宗李衞公問對三卷》
     白文刊本（Wikimedia Commons，PD-scan），半叶八行、行二十字，不出四库
     一系，也没有注解。本页凡改字十二处，都是在这个本子的书影上逐字看过的。
     叶上自右而左是「唐太宗李衞公問對卷上」与全书第一句「太宗曰：高麗數侵
     新羅，朕遣使諭，不奉詔，將討之，如何」，题下钤朱文藏书印二方，下一方
     作「上海圖書館藏」。第七行「太宗曰：晉馬隆討涼州」——底本误作「計涼州」，
     本页所改的第一处就写在这张封面上。
     Commons 的 PDF 缩略图到 1280 宽就封顶，所以用 pdfjpg.py 把页面里贴着的
     JPEG（1652x1211）原样取出；量墨得版匡 y 262..1147、右纵栏 x 620..636、
     左纵栏 x 25，裁 x 0..656、y 243..1168 再缩到 560 宽，所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''

ITEM = '''    {
     "@type": "ListItem",
     "position": 17,
     "name": "唐李问对",
     "alternateName": [
      "唐太宗李卫公问对",
      "Questions and Replies between Tang Taizong and Li Weigong"
     ],
     "item": {
      "@id": "https://youraikanban.com/p/tangliwendui-hans.html#book",
      "url": "https://youraikanban.com/p/tangliwendui-hans.html",
      "sameAs": [
       "https://www.wikidata.org/entity/Q1051352",
       "https://zh.wikipedia.org/wiki/%E5%94%90%E5%A4%AA%E5%AE%97%E6%9D%8E%E5%8D%AB%E5%85%AC%E9%97%AE%E5%AF%B9"
      ]
     }
    }
'''


def main():
    s = open(P, encoding='utf-8').read()
    if 'tangliwendui' in s:
        print('dianji.html: 已有唐李問對，何もしない')
        return
    n = len(s)

    # 1) 書架の最後に一冊（《吳子》の bay の直後）
    tail = '''        <p class="editions"><a href="/p/wuzi-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《吴子》简体读本">简体</a><span class="sep">·</span><a href="/p/wuzi.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《吳子》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/wuzi-en.html" hreflang="en" lang="en" aria-label="Open The Wuzi (English reading)">English</a><span class="sep">·</span><a href="/p/wuzi-ja.html" hreflang="ja" lang="ja" aria-label="『呉子』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''
    assert tail in s, '吳子の bay が見つからない'
    s = s.replace(tail, tail + BAY, 1)

    # 2) 封面の裁ち寸法（吳子の規則の直後に置く）
    marker = '''  width:100%; height:100%; left:0%; top:0%;
}
'''
    i = s.rindex(marker) + len(marker)
    s = s[:i] + CSS + s[i:]

    # 3) JSON-LD の ItemList
    s = s.replace('"numberOfItems": 16,', '"numberOfItems": 17,', 1)
    last = '''      "sameAs": [
       "https://www.wikidata.org/entity/Q1135722",
       "https://zh.wikipedia.org/wiki/%E5%90%B4%E5%AD%90"
      ]
     }
    }
'''
    assert last in s
    s = s.replace(last, last.rstrip('\n')[:-1].rstrip() + '\n    },\n' + ITEM, 1)

    # 4) 題と description の「十六部」を「十七部」に、書名の列に一冊足す
    s = s.replace('十六部中文经典全文', '十七部中文经典全文')
    s = s.replace('十六部中文典籍的全文读本', '十七部中文典籍的全文读本')
    s = s.replace('《吴子》十六部全文读本', '《吴子》《唐李问对》十七部全文读本')
    s = s.replace('Sixteen Chinese classics in full text',
                  'Seventeen Chinese classics in full text')
    s = s.replace('Sixteen Chinese classics in full —',
                  'Seventeen Chinese classics in full —')
    s = s.replace('『呉子』など中国古典十六部', '『呉子』『李衛公問対』など中国古典十七部')

    open(P, 'w', encoding='utf-8').write(s)

    # 5) 書架カードの題と抄（dianji.meta.json）。本を足したら必ず一緒に直す。
    mp = P[:-5] + '.meta.json'
    m = json.load(open(mp, encoding='utf-8'))
    if '唐李问对' not in m['title']:
        m['title'] += ' · 唐李问对'
        m['summary'] = m['summary'].replace('《吴子》，按书影排开',
                                            '《吴子》《唐李问对》，按书影排开')
    m['bytes'] = os.path.getsize(P)
    json.dump(m, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    open(mp, 'a', encoding='utf-8').write('\n')

    # 検算
    assert '十六部' not in s and 'Sixteen Chinese' not in s, '「十六部」が残っている'
    txt = s[s.index('<script type="application/ld+json">') + 35:]
    ld = json.loads(txt[:txt.index('</script>')])
    items = ld['@graph'][-1]['itemListElement'] if '@graph' in ld else None
    print('dianji.html', n, '→', len(s), 'bytes；ItemList',
          len(items) if items else '?', '件')


if __name__ == '__main__':
    main()
