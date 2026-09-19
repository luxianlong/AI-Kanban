# -*- coding: utf-8 -*-
"""四つの讀本の .meta.json（書架カードの題と抄）を書き出す。"""
import json, os

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
CREATED = '2026-09-19T15:00:00.000000000Z'

META = {
 'sanlue': ('三略 · 繁體版',
   '《三略》（黃石公三略）全本繁體：上略、中略、下略三卷，正文三千八百餘字，'
   '武經七書之一。舊題秦圯上老人黃石公授張良，《三略》之名始見於《隋書·經籍志》。'
   '底本用維基文庫標點本，與四庫全書本白文、明劉寅《三畧直解》所載經文兩本逐字對校，'
   '存異文六十二條，改正底本機械之誤二十三處；另以慶長九年伏見版古活字本目驗要處。'
   '分段依劉寅所分之節，卷末〈底本與校例〉具其詳。卷旨與逐段註釋可開關。'),
 'sanlue-hans': ('三略 · 简体版',
   '《三略》（黄石公三略）全本简体：上略、中略、下略三卷，正文三千八百余字，'
   '武经七书之一。旧题秦圯上老人黄石公授张良，《三略》之名始见于《隋书·经籍志》。'
   '底本用维基文库标点本，与四库全书本白文、明刘寅《三略直解》所载经文两本逐字对校，'
   '存异文六十二条，改正底本机械之误二十三处；另以庆长九年伏见版古活字本目验要处。'
   '分段依刘寅所分之节，卷末《底本与校例》具其详。卷旨与逐段注释可开关。'),
 'sanlue-en': ('The Three Strategies of Huang Shigong — Sanlüe (English)',
   'A complete English reading of the Three Strategies of Huang Shigong: Upper, '
   'Middle and Lower, some 3,800 characters, one of the Seven Military Classics and '
   'the book the Yellow Stone Elder is said to have handed Zhang Liang on the bridge '
   'at Xiapi. Collated character by character against the Siku quanshu plain text and '
   'against the classic text carried in Liu Yin’s Ming commentary, with the 1604 '
   'Fushimi movable-type printing read at the decisive places.'),
 'sanlue-ja': ('三略 — Sanryaku（日本語）',
   '『三略』（黄石公三略）の日本語全訳。上略・中略・下略の三巻、本文三千八百余字、'
   '武経七書の一つ。四庫全書本の白文と明・劉寅『三畧直解』所載の経文の二本と'
   '全文校合し、底本の機械的な誤り二十三箇所を改めた。封面と要所の参校には'
   '慶長九年（一六〇四）の伏見版古活字本を用いている。底本と校例も訳出。'),
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
