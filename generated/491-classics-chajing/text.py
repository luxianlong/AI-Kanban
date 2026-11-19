# -*- coding: utf-8 -*-
"""canon.json → text.json（三卷・十篇・百二十四段）。"""
import json

JUAN = [('卷上', 1, 3), ('卷中', 4, 4), ('卷下', 5, 10)]


def main():
    B = json.load(open('canon.json'))
    P = {q['name']: q for q in B['pian']}
    order = [q['name'] for q in B['pian']]
    out = {'juan': [], 'pian': []}
    for name, a, b in JUAN:
        out['juan'].append({'n': len(out['juan']) + 1, 'name': name,
                            'from': a, 'to': b})
    for i, name in enumerate(order, 1):
        q = P[name]
        out['pian'].append({'n': i, 'name': name, 'title': name,
                            'paras': q['paras']})
    json.dump(out, open('text.json', 'w'), ensure_ascii=False, indent=1)
    print('text.json：%d 卷 %d 篇 %d 段'
          % (len(out['juan']), len(out['pian']),
             sum(len(p['paras']) for p in out['pian'])))


if __name__ == '__main__':
    main()
