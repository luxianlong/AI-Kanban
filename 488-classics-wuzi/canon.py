# -*- coding: utf-8 -*-
"""底本の機械的な誤りだけを直し、canon.json を出す。

<b>この一本では、本文の字を一つも改めていない。</b>《司馬法》《三略》の
二輪と違い、底本（維基文庫標點本）には「二本が一致して底本と異なり、かつ
底本の文が通らない」ところが一つも無かったからである。

改字の例は《三略》の輪（#486）と同じ一條だけを用いる——

  <b>底本の文が通らないところだけ二本に從い、通るところは底本のままとし、
  その段の註釋に出校する。</b>

「二本並有にして底本に無ければ補い、二本並無にして底本に有れば削る」という
《司馬法》の輪の機械的な二條は、ここでは採らない。採れば「必先和而造大事」の
「必」や「相遇於大水之澤」の「於」を、文がよく通るのに削ることになる。
二本が一致して底本と異なる十五箇所は一つ殘らず卷末の〈底本と校例〉に列擧した。

ここでするのは三つだけ——

  一、底本が五箇所で用いる「︰」（U+FE30、縦組み用の記號）を「：」に直す。
  二、底本に一度だけ出る「玆」を「茲」に直す（「明主鑒茲」。二本とも「兹」）。
  三、底本のなかで揺れている字形を多數の形に統一する。「陣」二處を「陳」に
      （底本は陳二十一・陣二の混用。叢刊本は全篇「陳」、四庫本は全篇「陣」で、
      どちらも自分のなかでは揺れていない）、「閒」一處を「閑」に
      （底本は閑一・閒一。二本はいずれも「閑」）、「天灶」二處を「天竈」に
      （二本はいずれも「竈」。「灶」は簡体字のほうの形で、底本のこの二字は
      簡繁往復の痕と見てよい——《三略》の輪が底本に十七處見つけたのと同類）。
"""
import json, os

D = os.path.dirname(os.path.abspath(__file__))

# 本文の字を改める箇所。この一本では空である。
FIX = []

# 字形を一つに揃える（語は變えない）。
UNIFY = [('明主鑒玆', '明主鑒茲'), ('陷陣', '陷陳'), ('戰陣', '戰陳'),
         ('馬閒馳逐', '馬閑馳逐'), ('天灶', '天竈')]

# 縦組み用の記號と西文の引號を、本書架の體裁に合わせる。
PUNCT = [('︰', '：'), ('“', '「'), ('”', '」')]


def main():
    base = json.load(open(f'{D}/base.json', encoding='utf-8'))
    counts = {}

    def bump(k, n=1):
        counts[k] = counts.get(k, 0) + n

    for j in base:
        paras = j['paras']
        for pian, old, new, kind in FIX:
            if pian != j['name']:
                continue
            hits = sum(p.count(old) for p in paras)
            assert hits == 1, f'{pian}「{old}」が {hits} 箇所'
            j['paras'] = paras = [p.replace(old, new, 1) for p in paras]
            bump(kind)
        for group, key in ((UNIFY, 'glyph'), (PUNCT, 'punct')):
            for old, new in group:
                for i, p in enumerate(paras):
                    if old in p:
                        bump(key, p.count(old))
                        paras[i] = p.replace(old, new)

    json.dump(base, open(f'{D}/canon.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = sum(v for k, v in counts.items() if k not in ('glyph', 'punct'))
    print('直したところ：', counts, '　本文の字を改めたのは', n, '處')
    assert n == 0, '本文に手を入れた——校例と卷末を書き直すこと'


if __name__ == '__main__':
    main()
