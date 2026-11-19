# -*- coding: utf-8 -*-
"""/p/dianji.html（手書きの書架頁）に《吳子》を一冊足す。

dianji.html は生成物ではなく手書きなので、足すべき箇所は一つではない。
本スクリプトは冪等——すでに wuzi があれば何もしない。
"""
import os, json

P = os.path.expanduser('~/Dashboard/frontend/public/p/dianji.html')

ALT = ('《景印文渊阁四库全书》第七二六册《吴子》卷端叶，'
       '题「欽定四庫全書　吳子　吳起初見文侯章句　周　吳起　撰」，'
       '正文起「吳起儒服以兵機見魏文侯」，左侧版心作「欽定四庫全書　吳子　一」')

BAY = '''    <li class="bay" data-title="吴子" data-imprint="先秦 · 全六篇" data-href="/p/wuzi-hans.html" data-href-hans="/p/wuzi-hans.html" data-title-hans="吴子" data-imprint-hans="先秦 · 全六篇" data-label-hans="打开《吴子》简体读本" data-href-hant="/p/wuzi.html" data-title-hant="吳子" data-imprint-hant="先秦 · 全六篇" data-label-hant="打開《吳子》繁體讀本" data-href-en="/p/wuzi-en.html" data-title-en="The Wuzi" data-imprint-en="pre-Qin · six chapters" data-label-en="Open The Wuzi (English reading)" data-href-ja="/p/wuzi-ja.html" data-title-ja="呉子" data-imprint-ja="先秦 · 全六篇" data-label-ja="『呉子』の日本語訳を開く">
      <div class="stand">
        <a class="vol" href="/p/wuzi-hans.html" aria-label="打开《吴子》简体读本">
          <div class="book" style="--ar:.7244">
            <div class="cover wuzi"><img src="/img/classics/wuzi-cover.jpg" alt="%s" width="560" height="773" loading="lazy" decoding="async"></div>
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="zh-Hans"><a href="/p/wuzi-hans.html">吴子</a></h3>
        <p class="imprint" lang="zh-Hans">先秦 · 全六篇</p>
        <p class="editions"><a href="/p/wuzi-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《吴子》简体读本">简体</a><span class="sep">·</span><a href="/p/wuzi.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《吳子》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/wuzi-en.html" hreflang="en" lang="en" aria-label="Open The Wuzi (English reading)">English</a><span class="sep">·</span><a href="/p/wuzi-ja.html" hreflang="ja" lang="ja" aria-label="『呉子』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
''' % ALT

CSS = '''.cover.wuzi img{
  /* 这一张也是本书自己的卷端叶，而且出于本页的通校本之一，并且叶上正写着
     本轮校勘最要紧的那件事：《景印文渊阁四库全书》第七二六册（子部兵家类）
     《吴子》卷端（Wikimedia Commons 所藏「文淵閣四庫全書 0726冊.djvu」
     第六二叶，PD-scan）。右起依次是「欽定四庫全書」「吳子」「吳起初見文侯
     章句」「周　吳起　撰」，接着就是全书第一句「吳起儒服以兵機見魏文侯」；
     ——四库本把《图国》的前半另立一篇、题作「吳起初見文侯章句」，而且通篇
     没有篇次，卷末〈底本与校例〉说的就是这件事，这张封面是它的书影。
     景印本一叶上下排两丁。3840 宽取出后量墨：上一丁的版匡 y 295..2488、
     左右纵栏 x=360 与 x=3427，版心（中缝）在 x 1837..1957。取右半叶，
     四边各留一点纸边，裁 x 1820..3450、y 270..2520，再缩到 560 宽，
     所以整张铺满即可。 */
  width:100%; height:100%; left:0%; top:0%;
}
'''

ITEM = '''    {
     "@type": "ListItem",
     "position": 16,
     "name": "吴子",
     "alternateName": [
      "吴起兵法",
      "The Wuzi — Wu Qi’s Art of War"
     ],
     "item": {
      "@id": "https://youraikanban.com/p/wuzi-hans.html#book",
      "url": "https://youraikanban.com/p/wuzi-hans.html",
      "sameAs": [
       "https://www.wikidata.org/entity/Q1135722",
       "https://zh.wikipedia.org/wiki/%E5%90%B4%E5%AD%90"
      ]
     }
    }
'''


def main():
    s = open(P, encoding='utf-8').read()
    if 'wuzi' in s:
        print('dianji.html: 已有吳子，何もしない')
        return
    n = len(s)

    # 1) 書架の最後に一冊（《司馬法》の bay の直後）
    tail = '''        <p class="editions"><a href="/p/simafa-hans.html" hreflang="zh-Hans" lang="zh-Hans" aria-label="打开《司马法》简体读本">简体</a><span class="sep">·</span><a href="/p/simafa.html" hreflang="zh-Hant" lang="zh-Hant" aria-label="打開《司馬法》繁體讀本">繁體</a><span class="sep">·</span><a href="/p/simafa-en.html" hreflang="en" lang="en" aria-label="Open The Methods of the Sima (English reading)">English</a><span class="sep">·</span><a href="/p/simafa-ja.html" hreflang="ja" lang="ja" aria-label="『司馬法』の日本語訳を開く">日本語</a></p>
      </div>
    </li>
'''
    assert tail in s, '司馬法の bay が見つからない'
    s = s.replace(tail, tail + BAY, 1)

    # 2) 封面の裁ち寸法（司馬法の規則の直後に置く）
    marker = '''  width:100%; height:100%; left:0%; top:0%;
}
'''
    i = s.rindex(marker) + len(marker)
    s = s[:i] + CSS + s[i:]

    # 3) JSON-LD の ItemList
    s = s.replace('"numberOfItems": 15,', '"numberOfItems": 16,', 1)
    last = '''      "sameAs": [
       "https://www.wikidata.org/entity/Q1051357",
       "https://zh.wikipedia.org/wiki/%E5%8F%B8%E9%A9%AC%E6%B3%95"
      ]
     }
    }
'''
    assert last in s
    s = s.replace(last, last.rstrip('\n')[:-1].rstrip() + '\n    },\n' + ITEM, 1)

    # 4) 題と description の「十五部」を「十六部」に、書名の列に《吴子》を足す
    s = s.replace('十五部中文经典全文', '十六部中文经典全文')
    s = s.replace('十五部中文典籍的全文读本', '十六部中文典籍的全文读本')
    s = s.replace('《司马法》十五部全文读本', '《司马法》《吴子》十六部全文读本')
    s = s.replace('Fifteen Chinese classics in full text', 'Sixteen Chinese classics in full text')
    s = s.replace('Fifteen Chinese classics in full —', 'Sixteen Chinese classics in full —')
    s = s.replace('『司馬法』など中国古典十五部', '『司馬法』『呉子』など中国古典十六部')

    open(P, 'w', encoding='utf-8').write(s)

    # 5) 書架カードの題と抄（dianji.meta.json）。本を足したら必ず一緒に直す。
    mp = P[:-5] + '.meta.json'
    m = json.load(open(mp, encoding='utf-8'))
    if '吴子' not in m['title']:
        m['title'] += ' · 吴子'
        m['summary'] = m['summary'].replace('《司马法》，按书影排开', '《司马法》《吴子》，按书影排开')
    m['bytes'] = os.path.getsize(P)
    json.dump(m, open(mp, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    open(mp, 'a', encoding='utf-8').write('\n')

    # 検算
    assert '十五部' not in s and 'Fifteen Chinese' not in s, '「十五部」が残っている'
    txt = s[s.index('<script type="application/ld+json">') + 35:]
    ld = json.loads(txt[:txt.index('</script>')])
    items = ld['@graph'][-1]['itemListElement'] if '@graph' in ld else None
    print('dianji.html', n, '→', len(s), 'bytes；ItemList',
          len(items) if items else '?', '件')


if __name__ == '__main__':
    main()
