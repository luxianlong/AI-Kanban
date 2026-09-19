# -*- coding: utf-8 -*-
"""四つの讀本の .meta.json（書架カードの題と抄）を書き出す。"""
import json, os

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
CREATED = '2026-09-19T16:00:00.000000000Z'

META = {
 'weiliaozi': ('尉繚子 · 繁體版',
   '《尉繚子》全本繁體：五卷二十四篇，武經七書之一。'
   '武經七書裡唯一網上沒有完整標點本的一部——維基文庫的標點本有四篇全空，'
   '所以本頁取《四庫全書》文淵閣本，句讀與分段自出，'
   '並由機器逐字核對「去掉句讀即與四庫本同」。'
   '另與唐貞觀五年《羣書治要》所抄四篇對校：那是今存最古的證人，'
   '保存了今本已佚的「斷紂頭，懸之白旗」與〈戰威〉脫落的一整節。'
   '改字二處，都在上海圖書館藏白文刊本的書影上看過。'
   '卷旨、篇旨與逐段註釋可開關。'),
 'weiliaozi-hans': ('尉缭子 · 简体版',
   '《尉缭子》全本简体：五卷二十四篇，武经七书之一。'
   '武经七书里唯一网上没有完整标点本的一部——维基文库的标点本有四篇全空，'
   '所以本页取《四库全书》文渊阁本，句读与分段自出，'
   '并由机器逐字核对「去掉句读即与四库本同」。'
   '另与唐贞观五年《群书治要》所抄四篇对校：那是今存最古的证人，'
   '保存了今本已佚的「断纣头，悬之白旗」与〈战威〉脱落的一整节。'
   '改字二处，都在上海图书馆藏白文刊本的书影上看过。'
   '卷旨、篇旨与逐段注释可开关。'),
 'weiliaozi-en': ('Weiliaozi (English)',
   'A complete English reading of the Weiliaozi, one of the Seven Military '
   'Classics: five scrolls and twenty-four chapters on regulation, discipline '
   'and military law, including the only body of Warring States army statutes '
   'transmitted as text. It is the only one of the seven with no usable '
   'punctuated edition online, so the punctuation here is supplied and checked '
   'by machine against the Siku quanshu text, and collated against the Tang '
   'anthology of 631 and a Shanghai Library woodblock edition.'),
 'weiliaozi-ja': ('尉繚子 — Utsuryōshi（日本語）',
   '『尉繚子』の日本語全訳。五巻二十四篇、武経七書の一。'
   '武経七書のうちネット上に完全な標点本がない唯一の書なので、'
   '四庫全書文淵閣本に本頁で句読を施し、'
   '「句読を除けば四庫本と一字たがわぬ」ことを機械で検証した。'
   '唐貞観五年『群書治要』が抄する四篇とも対校し、'
   '今本が失った「紂の頭を断ちて之を白旗に懸く」などを註に出した。'),
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
