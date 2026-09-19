# -*- coding: utf-8 -*-
"""notes/ja1..ja6 + ja_colophon → ja.json"""
import json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'notes'))
import ja1, ja2, ja3, ja4, ja5, ja6, ja_colophon


def main():
    t = json.load(open('text.json'))
    out = {'labels': ja_colophon.LABELS, 'colophon': ja_colophon.COLO,
           'juan': {}, 'chapters': {}}
    for i, m in enumerate((ja1, ja2, ja3, ja4, ja5, ja6), 1):
        out['juan']['j%d' % i] = m.JUAN
        for n, d in m.PIAN.items():
            out['chapters']['c%d' % n] = dict(d)
        for k, v in m.PARA.items():
            cid, idx = k.split('-')
            out['chapters'][cid].setdefault('paras', []).append((int(idx), v))
    for p in t['pian']:
        c = out['chapters']['c%d' % p['n']]
        c['paras'] = [v for _, v in sorted(c['paras'])]
        if len(c['paras']) != len(p['paras']):
            raise SystemExit('《%s》段數不合：%d vs %d'
                             % (p['title'], len(c['paras']), len(p['paras'])))
    if sorted(int(k[1:]) for k in out['chapters']) != list(range(1, 61)):
        raise SystemExit('章數不足六十')
    json.dump(out, open('ja.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = sum(len(v) for c in out['chapters'].values() for v in c['paras']) \
        + sum(len(c['head']) for c in out['chapters'].values()) \
        + sum(len(j['head']) for j in out['juan'].values()) \
        + sum(len(x) for x in out['colophon'])
    print('六巻・六十篇・%d 段、約 %d 字'
          % (sum(len(c['paras']) for c in out['chapters'].values()), n))


if __name__ == '__main__':
    main()
