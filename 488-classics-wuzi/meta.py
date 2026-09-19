# -*- coding: utf-8 -*-
"""四つの讀本の .meta.json（書架カードの題と抄）を書き出す。"""
import json, os

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
CREATED = '2026-09-19T21:00:00.000000000Z'

META = {
 'wuzi': ('吳子 · 繁體版',
   '《吳子》全本繁體：圖國、料敵、治兵、論將、應變、勵士六篇，正文四千七百二十八字，'
   '武經七書之一，與《孫子》並稱「孫吳」。舊題周吳起撰；吳起學於曾子，'
   '事魏文侯、武侯而守西河，與諸侯大戰七十六，全勝六十四。'
   '底本用維基文庫標點本，與四部叢刊影常熟瞿氏鐵琴銅劍樓藏景宋鈔本、'
   '文淵閣四庫全書本兩本逐字對校，得異文一百〇二條——而本頁正文一字未改，'
   '校語逐條見於各段註釋，二本一致而底本獨異的十處並列於卷末。'
   '卷末〈底本與校例〉另記四庫本的體裁與今本不同一事。篇旨與逐段註釋可開關。'),
 'wuzi-hans': ('吴子 · 简体版',
   '《吴子》全本简体：图国、料敌、治兵、论将、应变、励士六篇，正文四千七百二十八字，'
   '武经七书之一，与《孙子》并称「孙吴」。旧题周吴起撰；吴起学于曾子，'
   '事魏文侯、武侯而守西河，与诸侯大战七十六，全胜六十四。'
   '底本用维基文库标点本，与四部丛刊影常熟瞿氏铁琴铜剑楼藏景宋钞本、'
   '文渊阁四库全书本两本逐字对校，得异文一百〇二条——而本页正文一字未改，'
   '校语逐条见于各段注释，二本一致而底本独异的十处并列于卷末。'
   '卷末《底本与校例》另记四库本的体裁与今本不同一事。篇旨与逐段注释可开关。'),
 'wuzi-en': ('The Wuzi — Wu Qi’s Art of War (English)',
   'A complete English reading of the Wuzi: Planning for the State, Sizing Up the '
   'Enemy, Governing the Army, On the Commander, Responding to Change and Rousing '
   'the Officers — six chapters, 4,728 characters, one of the Seven Military '
   'Classics and, with the Sunzi, half of the pair Chinese tradition calls simply '
   '“Sun and Wu.” Ascribed to Wu Qi (c. 440–381 BC), who studied under Zengzi, held '
   'the West River march for Wei, and fought seventy-six battles with the feudal '
   'lords. Collated character by character against the Sibu congkan facsimile of the '
   'Song-copied manuscript from the Qu family library at Changshu and against the '
   'Wenyuan ge Siku quanshu text; 102 variants recorded, not one emendation made.'),
 'wuzi-ja': ('呉子 — Goshi（日本語）',
   '『呉子』の日本語全訳。圖國・料敵・治兵・論將・應變・勵士の六篇、'
   '本文四千七百二十八字、武経七書の一つで、『孫子』と並んで「孫呉」と称される。'
   '呉起は曾子に学び、魏の文侯・武侯に仕えて西河を守り、諸侯と七十六たび戦って'
   '六十四たび全勝した。四部叢刊が影印した常熟瞿氏鐵琴銅劍樓蔵の景宋鈔本と'
   '文淵閣四庫全書本の二本と全文校合し、異文百二条を得たが、本文は一字も'
   '改めていない。巻末の〈底本と校例〉も訳出。'),
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
