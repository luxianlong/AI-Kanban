# -*- coding: utf-8 -*-
"""notes/ja1..ja6 と ja_colophon を ja.json にまとめる。"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(D, 'notes'))
import ja1, ja2, ja3, ja4, ja5, ja6, ja_colophon                 # noqa: E402

MODS = [('p1', ja1), ('p2', ja2), ('p3', ja3),
        ('p4', ja4), ('p5', ja5), ('p6', ja6)]


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    out = {'pian': {}, 'para': {}, 'colophon': ja_colophon.COLOPHON,
           'labels': ja_colophon.LABELS}
    for (pid, m), j in zip(MODS, T):
        out['pian'][pid] = {'title': m.TITLE, 'head': m.HEAD}
        want = {'%s-%d' % (pid, i) for i in range(1, len(j['paras']) + 1)}
        got = set(m.PARA)
        assert want == got, f'{pid}: 足りない {sorted(want-got)} / 余り {sorted(got-want)}'
        out['para'].update(m.PARA)
    json.dump(out, open(f'{D}/ja.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = sum(len(v) for v in out['para'].values()) \
        + sum(len(v['head']) for v in out['pian'].values()) \
        + sum(len(v) for v in out['colophon'])
    print('ja.json　段', len(out['para']), '・篇', len(out['pian']),
          '・巻末', len(out['colophon']), '段　約', n, '字')


if __name__ == '__main__':
    main()
