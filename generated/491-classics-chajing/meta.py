# -*- coding: utf-8 -*-
"""四つの讀本の .meta.json（書架カードの題と抄）を書き出す。"""
import json, os

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
CREATED = '2026-09-19T17:10:00.000000000Z'

META = {
 'chajing': ('茶經 · 繁體版',
   '《茶經》全本繁體：唐陸羽撰，三卷十篇，世上第一部茶書。'
   '底本用維基文庫標點本（浙江鮑士恭家藏本一系），通校《四庫全書》文淵閣本'
   '（互相獨立的兩種錄文，七千一百十六字相異零處），'
   '再以國立國會圖書館藏明刊《百川學海》本逐葉目驗——今存最古的一支。'
   '〈四之器〉風爐「聖唐滅胡明年鑄」，四庫本改作「聖唐年號某年鑄」；'
   '〈八之出〉底本脫湖州一節十九字，據百川本補。改字十六處。'
   '陸羽自注一併收入，卷旨、篇旨與逐段語譯可開關——註釋層是逐段的白話譯文，字詞與校記附在譯文之後。'),
 'chajing-hans': ('茶经 · 简体版',
   '《茶经》全本简体：唐陆羽撰，三卷十篇，世上第一部茶书。'
   '底本用维基文库标点本（浙江鲍士恭家藏本一系），通校《四库全书》文渊阁本'
   '（互相独立的两种录文，七千一百十六字相异零处），'
   '再以国立国会图书馆藏明刊《百川学海》本逐叶目验——今存最古的一支。'
   '〈四之器〉风炉「圣唐灭胡明年铸」，四库本改作「圣唐年号某年铸」；'
   '〈八之出〉底本脱湖州一节十九字，据百川本补。改字十六处。'
   '陆羽自注一并收入，卷旨、篇旨与逐段语译可开关——注释层是逐段的白话译文，字词与校记附在译文之后。'),
 'chajing-en': ('The Classic of Tea (English)',
   'A complete English reading of the Chajing, written by Lu Yu in the eighth '
   'century — the first book ever written about tea. Three scrolls and ten '
   'chapters: the plant, the tools, the making, the twenty-four vessels, the '
   'boiling, the drinking, the whole written record of tea before the Tang, '
   'the tea districts of the empire, what may be left out in the open air, and '
   'the scroll to hang beside your seat. Every paragraph is translated, '
   'including Lu Yu&#39;s own interlinear notes. Collated against the Siku '
   'quanshu text and read leaf by leaf against a Ming printing of the Baichuan '
   'xuehai, the earliest surviving line of the text.'),
 'chajing-ja': ('茶經 — Chakyō（日本語）',
   '『茶經』の日本語全訳。唐の陸羽撰、三巻十篇、世界で最初の茶の書。'
   '陸羽自身の夾注もすべて訳した。'
   '底本は維基文庫の標点本、四庫全書文淵閣本（独立した録文二種、'
   '七千百十六字で食い違い零）と対校し、'
   '国立国会図書館蔵の明刊『百川學海』本を一葉ずつ目験した。'
   '〈四之器〉の風炉「聖唐滅胡明年鑄」を四庫本が「聖唐年號某年鑄」に'
   '改めていること、〈八之出〉が湖州の一節十九字を脱することなどを'
   '巻末に記す。'),
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
