# -*- coding: utf-8 -*-
"""四つの讀本の .meta.json（書架カードの題と抄）を書き出す。"""
import json, os

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
CREATED = '2026-09-19T23:30:00.000000000Z'

META = {
 'tangliwendui': ('唐李問對 · 繁體版',
   '《唐李問對》（《唐太宗李衛公問對》）全本繁體：卷上、卷中、卷下三卷，'
   '五十問對、一百九十七段，正文一萬零二百九十二字，武經七書之一——'
   '七書中唯一的唐代之書，也是唯一通篇問答的書。'
   '唐太宗與衛國公李靖論兵，綱在奇正、虛實、主客、攻守，'
   '而《孫子》《司馬法》《尉繚子》《太公》諸書逐一在其中被拆開重講。'
   '底本用維基文庫標點本，與四庫全書文淵閣本逐字對校（録文取互相獨立的兩種），'
   '要處另以上海圖書館藏白文刊本的書影目驗，改正底本之誤十二處，存異文一百〇三條。'
   '卷末〈底本與校例〉另記四庫本的分卷與傳世諸本不同一事。卷旨與逐段語譯可開關——註釋層是逐段的白話譯文，字詞與校記附在譯文之後。'),
 'tangliwendui-hans': ('唐李问对 · 简体版',
   '《唐李问对》（《唐太宗李卫公问对》）全本简体：卷上、卷中、卷下三卷，'
   '五十问对、一百九十七段，正文一万零二百九十二字，武经七书之一——'
   '七书中唯一的唐代之书，也是唯一通篇问答的书。'
   '唐太宗与卫国公李靖论兵，纲在奇正、虚实、主客、攻守，'
   '而《孙子》《司马法》《尉缭子》《太公》诸书逐一在其中被拆开重讲。'
   '底本用维基文库标点本，与四库全书文渊阁本逐字对校（录文取互相独立的两种），'
   '要处另以上海图书馆藏白文刊本的书影目验，改正底本之误十二处，存异文一百〇三条。'
   '卷末《底本与校例》另记四库本的分卷与传世诸本不同一事。卷旨与逐段语译可开关——注释层是逐段的白话译文，字词与校记附在译文之后。'),
 'tangliwendui-en': ('Questions and Replies between Tang Taizong and Li Weigong (English)',
   'A complete English reading of the Tang Li wendui: three scrolls, fifty exchanges, '
   '197 speeches, 10,292 characters. One of the Seven Military Classics, the only one '
   'from the Tang and the only one cast throughout as dialogue — the emperor who won the '
   'empire in the field questioning Li Jing, the general who took the Eastern Turks. Its '
   'subjects are the unorthodox and the orthodox, the full and the empty, host and guest, '
   'attack and defence. Collated character by character against the Wenyuan ge Siku '
   'quanshu text, with the decisive readings checked by eye against a plain-text woodblock '
   'edition held by the Shanghai Library; twelve emendations, 103 variants recorded.'),
 'tangliwendui-ja': ('李衛公問対 — Tang Li wendui（日本語）',
   '『李衛公問対』（『唐李問対』）の日本語全訳。巻上・巻中・巻下の三巻、'
   '五十の問答、百九十七段、本文一万二百九十二字。'
   '武経七書のうち唯一の唐代の書であり、唯一全篇が問答の書でもある。'
   '唐の太宗と衛国公李靖が兵を論じ、奇正・虚実・主客・攻守を綱として、'
   '『孫子』『司馬法』『尉繚子』『太公』を一つ一つ解きほぐす。'
   '維基文庫標点本を底本とし、文淵閣四庫全書本と全文校合、'
   '要所は上海図書館蔵の白文刊本の書影で目験した。巻末の〈底本と校例〉も訳出。'),
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
