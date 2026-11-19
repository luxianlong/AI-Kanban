# -*- coding: utf-8 -*-
"""text.json を讀みやすく吐く。 python3 dump.py <卷> [<卷>...]"""
import json, sys
T = json.load(open('text.json'))
P = {p['n']: p for p in T['pian']}
if sys.argv[1] == 'xu':
    print('[xu-1]', T['xu']); raise SystemExit
for a in sys.argv[1:]:
    j = [x for x in T['juan'] if x['n'] == int(a)][0]
    print('########## %s（篇 %d–%d）' % (j['name'], j['from'], j['to']))
    for n in range(j['from'], j['to'] + 1):
        p = P[n]
        print('\n===== %d 《%s》　戈直「凡%d章」　%d 段 =====' %
              (n, p['title'], p['zhang'], len(p['paras'])))
        for i, s in enumerate(p['paras'], 1):
            print('[c%d-%d]%s %s' % (n, i, '（疏續）' if p['cont'][i - 1] else '', s))
