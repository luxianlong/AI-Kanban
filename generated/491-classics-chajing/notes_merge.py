# -*- coding: utf-8 -*-
"""notes/zh1..zh3 + notes/colophon → notes.json（繁體）。"""
import json, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'notes'))
import zh1, zh2, zh3, colophon


def main():
    t = json.load(open('text.json'))
    out = {'juan': {}, 'pian': {}, 'para': {}, 'colophon': colophon.COLO}
    for i, m in enumerate((zh1, zh2, zh3), 1):
        out['juan']['j%d' % i] = m.JUAN
        for n, s in m.PIAN.items():
            out['pian']['c%d' % n] = s
        out['para'].update(m.PARA)
    want = ['c%d-%d' % (p['n'], i)
            for p in t['pian'] for i in range(1, len(p['paras']) + 1)]
    miss = [k for k in want if k not in out['para']]
    extra = [k for k in out['para'] if k not in want]
    if miss or extra:
        raise SystemExit('段註對不上：缺 %r 多 %r' % (miss[:5], extra[:5]))
    if sorted(int(k[1:]) for k in out['pian']) != list(range(1, 11)):
        raise SystemExit('篇旨不足十')
    # 段註・篇旨・卷旨は build.py が escape するので、生の HTML を混ぜない。
    for v in (list(out['para'].values()) + list(out['pian'].values())
              + list(out['juan'].values())):
        assert '<' not in v and '>' not in v, v[:60]
    json.dump(out, open('notes.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = (sum(len(v) for v in out['juan'].values())
         + sum(len(v) for v in out['pian'].values())
         + sum(len(v) for v in out['para'].values()))
    print('卷旨 %d、篇旨 %d、段註 %d，共 %d 字（卷末 %d 段 %d 字）' %
          (len(out['juan']), len(out['pian']), len(out['para']), n,
           len(out['colophon']), sum(len(x) for x in out['colophon'])))


if __name__ == '__main__':
    main()
