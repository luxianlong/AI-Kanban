# -*- coding: utf-8 -*-
"""四つの讀本の .meta.json（書架カードの題と抄）を書き出す。"""
import json, os

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
CREATED = '2026-09-19T17:00:00.000000000Z'

META = {
 'simafa': ('司馬法 · 繁體版',
   '《司馬法》全本繁體：仁本、天子之義、定爵、嚴位、用眾五篇，正文三千四百二十字，'
   '武經七書之一。《漢書·藝文志》著錄《軍禮司馬法》百五十五篇於六藝略禮類，'
   '今所存者只此五篇。底本用維基文庫標點本，與四部叢刊影常熟瞿氏鐵琴銅劍樓藏'
   '景宋鈔本、四庫全書本兩本逐字對校，存異文六十七條；底本誤收《太平御覽》'
   '所引逸文十八字，已刪，另補脫字一、正不可通者四。卷末〈底本與校例〉具其詳。'
   '篇旨與逐段註釋可開關。'),
 'simafa-hans': ('司马法 · 简体版',
   '《司马法》全本简体：仁本、天子之义、定爵、严位、用众五篇，正文三千四百二十字，'
   '武经七书之一。《汉书·艺文志》著录《军礼司马法》百五十五篇于六艺略礼类，'
   '今所存者只此五篇。底本用维基文库标点本，与四部丛刊影常熟瞿氏铁琴铜剑楼藏'
   '景宋钞本、四库全书本两本逐字对校，存异文六十七条；底本误收《太平御览》'
   '所引逸文十八字，已删，另补脱字一、正不可通者四。卷末《底本与校例》具其详。'
   '篇旨与逐段注释可开关。'),
 'simafa-en': ('The Methods of the Sima — Sima fa (English)',
   'A complete English reading of the Methods of the Sima: Benevolence the Root, '
   'The Duty of the Son of Heaven, Determining Rank, Strict Positioning and '
   'Employing Numbers — five chapters, 3,420 characters, one of the Seven Military '
   'Classics and the oldest surviving layer of Chinese military thought, entered in '
   'the Han bibliography not among the military writers but under the classics, in '
   'the section on rites. Collated character by character against the Sibu congkan '
   'facsimile of the Song-copied manuscript from the Qu family library at Changshu '
   'and against the Siku quanshu text.'),
 'simafa-ja': ('司馬法 — Shiba hō（日本語）',
   '『司馬法』の日本語全訳。仁本・天子之義・定爵・厳位・用衆の五篇、'
   '本文三千四百二十字、武経七書の一つ。『漢書』芸文志のいう『軍禮司馬法』'
   '百五十五篇のうち、今に伝わるのはこの五篇だけである。四部叢刊が影印した'
   '常熟瞿氏鉄琴銅剣楼蔵の景宋鈔本と四庫全書本の二本と全文校合し、'
   '底本が誤って本文に取り込んでいた『太平御覧』所引の逸文十八字を削った。'
   '巻末の〈底本と校例〉も訳出。'),
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
