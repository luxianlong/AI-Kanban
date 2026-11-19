# -*- coding: utf-8 -*-
"""notes/ja_xu + notes/ja01..ja10 + notes/ja_colophon → ja.json"""
import json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'notes'))
import ja_colophon, ja_xu
MODS = [__import__('ja%02d' % i) for i in range(1, 11)]


def main():
    t = json.load(open('text.json'))
    out = {'labels': ja_colophon.LABELS, 'colophon': ja_colophon.COLO,
           'xu': ja_xu.XU, 'juan': {}, 'chapters': {}}
    for i, m in enumerate(MODS, 1):
        out['juan']['j%d' % i] = m.JUAN
        for n, d in m.PIAN.items():
            out['chapters']['c%d' % n] = dict(d)
        for k, v in m.PARA.items():
            cid = k.split('-')[0]
            out['chapters'][cid].setdefault('paras', []).append(
                (int(k.split('-')[1]), v))
    for p in t['pian']:
        c = out['chapters']['c%d' % p['n']]
        c['paras'] = [v for _, v in sorted(c.get('paras', []))]
        if len(c['paras']) != len(p['paras']):
            raise SystemExit('《%s》段數不合：%d vs %d'
                             % (p['title'], len(c['paras']), len(p['paras'])))
    if sorted(int(k[1:]) for k in out['chapters']) != list(range(1, 42)):
        raise SystemExit('篇は四十一要る（今 %d）' % len(out['chapters']))
    json.dump(out, open('ja.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    body = ([v for c in out['chapters'].values() for v in c['paras']]
            + [c['head'] for c in out['chapters'].values()]
            + [j['head'] for j in out['juan'].values()]
            + [out['xu']['head'], out['xu']['text']] + out['colophon'])
    print('十卷四十一篇 %d 段、約 %d %s'
          % (sum(len(c['paras']) for c in out['chapters'].values()),
             sum(len(x.split()) for x in body) if 'ja' == 'en'
             else sum(len(x) for x in body),
             '詞' if 'ja' == 'en' else '字'))


if __name__ == '__main__':
    main()
