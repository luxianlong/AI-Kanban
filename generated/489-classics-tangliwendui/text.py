# -*- coding: utf-8 -*-
"""canon.json → text.json。

段を作る仕事はない。底本（維基文庫標點本）が既に一つの發言を一行に切って
おり、空行で問對の章を隔てている。四庫全書本は無句讀で段も無い。だから
<b>段の界も章の界も底本のもの</b>で、本頁が立てたものではない。
ここでは字數・段數・章數を數え直して text.json に寫すだけ。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕　 ')


def nchar(s):
    return sum(1 for c in s if c not in PUNCT)


def main():
    T = json.load(open(f'{D}/canon.json', encoding='utf-8'))
    tot = 0
    for j in T:
        n = sum(nchar(p) for p in j['paras'])
        tot += n
        print('%-4s %2d 問對 %3d 段 %5d 字　最長の段 %d 字'
              % (j['name'], len(j['chap']), len(j['paras']), n,
                 max(len(p) for p in j['paras'])))
    json.dump(T, open(f'{D}/text.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('計', sum(len(j['chap']) for j in T), '問對、',
          sum(len(j['paras']) for j in T), '段、', tot, '字')


if __name__ == '__main__':
    main()
