# -*- coding: utf-8 -*-
"""notes/en1..en5 と en_colophon を en.json にまとめる。"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(D, 'notes'))
import en1, en2, en3, en4, en5, en_colophon                 # noqa: E402

MODS = [('p1', en1), ('p2', en2), ('p3', en3), ('p4', en4), ('p5', en5)]


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    out = {'pian': {}, 'para': {}, 'colophon': en_colophon.COLOPHON,
           'labels': en_colophon.LABELS}
    for (pid, m), j in zip(MODS, T):
        out['pian'][pid] = {'title': m.TITLE, 'head': m.HEAD}
        want = {'%s-%d' % (pid, i) for i in range(1, len(j['paras']) + 1)}
        got = set(m.PARA)
        assert want == got, f'{pid}: 足りない {sorted(want-got)} / 余り {sorted(got-want)}'
        out['para'].update(m.PARA)
    json.dump(out, open(f'{D}/en.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    w = sum(len(v.split()) for v in out['para'].values()) \
        + sum(len(v['head'].split()) for v in out['pian'].values()) \
        + sum(len(v.split()) for v in out['colophon'])
    print('en.json　段', len(out['para']), '・篇', len(out['pian']),
          '・卷末', len(out['colophon']), '段　約', w, 'words')


if __name__ == '__main__':
    main()
