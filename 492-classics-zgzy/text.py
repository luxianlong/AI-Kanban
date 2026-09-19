# -*- coding: utf-8 -*-
"""canon.json → text.json（十卷・四十一篇・三百八十八段）。"""
import json

JUAN = [('卷一', 1, 2), ('卷二', 3, 6), ('卷三', 7, 9), ('卷四', 10, 13),
        ('卷五', 14, 18), ('卷六', 19, 27), ('卷七', 28, 30),
        ('卷八', 31, 35), ('卷九', 36, 37), ('卷十', 38, 41)]


def main():
    B = json.load(open('canon.json'))
    W = {p['n']: p for p in json.load(open('skqs.json'))['pian']}
    P = {q['n']: q for q in B['pian']}
    out = {'xu': B['xu'], 'juan': [], 'pian': []}
    for i, (name, a, b) in enumerate(JUAN, 1):
        out['juan'].append({'n': i, 'name': name, 'from': a, 'to': b})
    for n in range(1, 42):
        q = P[n]
        out['pian'].append({'n': n, 'name': q['title'], 'title': q['title'],
                            'zhang': W[n]['zhang'],
                            'cont': q['cont'], 'paras': q['paras']})
    json.dump(out, open('text.json', 'w'), ensure_ascii=False, indent=1)
    print('text.json：%d 卷 %d 篇 %d 段（戈直の數える章 %d）'
          % (len(out['juan']), len(out['pian']),
             sum(len(p['paras']) for p in out['pian']),
             sum(p['zhang'] for p in out['pian'])))


if __name__ == '__main__':
    main()
