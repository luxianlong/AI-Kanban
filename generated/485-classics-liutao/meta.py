# -*- coding: utf-8 -*-
"""四つの讀本の .meta.json（書架カードの題と抄）を書き出す。"""
import json, os

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
CREATED = '2026-09-19T12:00:00.000000000Z'

META = {
 'liutao': ('六韜 · 繁體版',
   '《六韜》全本繁體：文韜、武韜、龍韜、虎韜、豹韜、犬韜六卷六十篇，武經七書之首。'
   '舊題周呂望（太公望）撰，銀雀山漢簡證其西漢初已行世。底本用維基文庫標點本，'
   '與四部叢刊景宋刊本、四庫全書本兩本逐字對校，得異文六百五十處；'
   '《兵道》的篇次已據二舊本移回《文韜》，《三疑》《烏雲澤兵》兩處脫文已補，'
   '卷末〈底本與校例〉具其詳。卷旨、篇旨與逐段註釋可開關。'),
 'liutao-hans': ('六韬 · 简体版',
   '《六韬》全本简体：文韬、武韬、龙韬、虎韬、豹韬、犬韬六卷六十篇，武经七书之首。'
   '旧题周吕望（姜太公）撰，银雀山汉简证其西汉初已行世。底本用维基文库标点本，'
   '与四部丛刊景宋刊本、四库全书本两本逐字对校，得异文六百五十处；'
   '《兵道》的篇次已据二旧本移回《文韬》，《三疑》《乌云泽兵》两处脱文已补，'
   '卷末《底本与校例》具其详。卷旨、篇旨与逐段注释可开关。'),
 'liutao-en': ('The Six Secret Teachings — Liutao (English)',
   'A complete English reading of the Liutao, the Six Secret Teachings: six scrolls '
   'and sixty chapters ascribed to Lü Wang, the Grand Duke, and the first of the '
   'Seven Military Classics. Collated against the Song woodblock edition reproduced '
   'in the Sibu congkan and against the Siku quanshu text; the order of the chapters '
   'and two lacunae have been restored from them.'),
 'liutao-ja': ('六韜 — Rikutō（日本語）',
   '『六韜』の日本語全訳。文韜・武韜・龍韜・虎韜・豹韜・犬韜の六巻六十篇、'
   '武経七書の筆頭。旧題は周の呂望（太公望）撰。四部叢刊景宋刊本・四庫全書本と'
   '全文校合し、『兵道』の篇次と二箇所の脱文を正した。底本と校例も訳出。'),
}


def main():
    for slug, (title, summary) in META.items():
        p = os.path.join(ROOT, slug + '.html')
        n = os.path.getsize(p)
        json.dump({'slug': slug, 'title': title, 'summary': summary,
                   'createdAt': CREATED, 'bytes': n},
                  open(os.path.join(ROOT, slug + '.meta.json'), 'w', encoding='utf-8'),
                  ensure_ascii=False, indent=2)
        print(slug + '.meta.json', n, 'bytes')


if __name__ == '__main__':
    main()
