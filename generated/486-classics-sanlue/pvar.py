# -*- coding: utf-8 -*-
"""異文を段ごとに割りつける。註釋を書くときの手控え（頁には出ない）。"""
import json, os
D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·'
T = json.load(open(f'{D}/text.json', encoding='utf-8'))
V = json.load(open(f'{D}/variants.json', encoding='utf-8'))
JID = {'上略': 'j1', '中略': 'j2', '下略': 'j3'}
rows = {}
for j in T:
    off = 0
    for i, p in enumerate(j['paras'], 1):
        n = len([c for c in p if c not in PUNCT])
        rows['%s-%d' % (JID[j['name']], i)] = (j['name'], off, off + n)
        off += n
out = {}
for k, label in (('siku', '四庫'), ('zhijie', '直解')):
    for r in V[k]:
        for pid, (ju, a, b) in rows.items():
            if ju == r['juan'] and a <= r['at'] < b:
                out.setdefault(pid, []).append(
                    '%s「%s」→「%s」' % (label, r['base'], r['other']))
                break
for pid in rows:
    if pid in out:
        print(pid, ' / '.join(out[pid]))
