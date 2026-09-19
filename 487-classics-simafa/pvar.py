# -*- coding: utf-8 -*-
"""註釋を書くときの手控え。異文を段ごとに割りつけて印字するだけ。頁には出ない。"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕　 \n\t'
PID = {'仁本': 'p1', '天子之義': 'p2', '定爵': 'p3', '嚴位': 'p4', '用眾': 'p5'}


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    V = json.load(open(f'{D}/variants.json', encoding='utf-8'))
    only = sys.argv[1] if len(sys.argv) > 1 else None
    by = {}
    for which, label in (('sbck', '叢刊'), ('wyg', '四庫')):
        for d in V[which]:
            by.setdefault(d['pian'], []).append((d['at'], label, d))
    for j in T:
        if only and j['name'] != only:
            continue
        vs = sorted(by.get(j['name'], []))
        print('\n' + '=' * 30, j['name'])
        off = 0
        for i, p in enumerate(j['paras'], 1):
            n = len(''.join(c for c in p if c not in PUNCT))
            hit = [(l, d) for at, l, d in vs if off <= at < off + n]
            print('\n--- %s-%d  (%d 字)' % (PID[j['name']], i, n))
            print(p)
            for l, d in hit:
                agree = ''
                for l2, d2 in hit:
                    if l2 != l and d2['at'] == d['at'] and d2['other'] == d['other']:
                        agree = '★二本一致'
                print('    %s %r → %r   …%s… %s'
                      % (l, d['base'], d['other'], d['ctx'], agree))
            off += n


if __name__ == '__main__':
    main()
