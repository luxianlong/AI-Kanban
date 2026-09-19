# -*- coding: utf-8 -*-
"""notes/zh1..zh5 と colophon を notes.json にまとめる。"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(D, 'notes'))
import zh1, zh2, zh3, zh4, zh5, colophon           # noqa: E402

MODS = [('p1', zh1), ('p2', zh2), ('p3', zh3), ('p4', zh4), ('p5', zh5)]


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    out = {'pian': {}, 'para': {}, 'colophon': colophon.COLOPHON}
    for (pid, m), j in zip(MODS, T):
        out['pian'][pid] = m.PIAN
        want = {'%s-%d' % (pid, i) for i in range(1, len(j['paras']) + 1)}
        got = set(m.PARA)
        assert want == got, f'{pid}: 足りない {sorted(want-got)} / 余り {sorted(got-want)}'
        out['para'].update(m.PARA)
    json.dump(out, open(f'{D}/notes.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = sum(len(v) for v in out['para'].values()) \
        + sum(len(v) for v in out['pian'].values())
    print('notes.json　篇旨', len(out['pian']), '・段註', len(out['para']),
          '・卷末', len(out['colophon']), '段　計', n, '字')


if __name__ == '__main__':
    main()
