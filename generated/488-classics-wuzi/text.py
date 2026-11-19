# -*- coding: utf-8 -*-
"""canon.json → text.json。

《司馬法》の輪と同じで、段を作る仕事はない。底本（維基文庫標點本）が
既に段を切っており、通校二本は無句讀で段も無い。だから<b>段の界は底本の
もの</b>で、本頁が立てたものではない。ここでは字數と段數を數え直して
text.json に寫すだけ。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))


def main():
    T = json.load(open(f'{D}/canon.json', encoding='utf-8'))
    tot = 0
    for j in T:
        n = sum(len(re.sub(r'[^㐀-鿿]', '', p)) for p in j['paras'])
        tot += n
        print('%-6s %2d 段 %4d 字　最長 %d 字'
              % (j['name'], len(j['paras']), n,
                 max(len(p) for p in j['paras'])))
    json.dump(T, open(f'{D}/text.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('計', sum(len(j['paras']) for j in T), '段', tot, '字')


if __name__ == '__main__':
    main()
