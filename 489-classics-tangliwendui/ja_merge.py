# -*- coding: utf-8 -*-
"""notes/ja1..ja3 と ja_colophon を ja.json にまとめる。"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(D, 'notes'))
import ja1, ja2, ja3, ja_colophon             # noqa: E402

MODS = [('j1', ja1), ('j2', ja2), ('j3', ja3)]


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    out = {'juan': {}, 'para': {}, 'colophon': ja_colophon.COLOPHON,
           'labels': ja_colophon.LABELS}
    for (jid, m), j in zip(MODS, T):
        out['juan'][jid] = {'title': m.TITLE, 'head': m.HEAD}
        want = {'%s-%d' % (jid, i) for i in range(1, len(j['paras']) + 1)}
        got = set(m.PARA)
        assert want == got, f'{jid}: 足りない {sorted(want-got)} / 余り {sorted(got-want)}'
        out['para'].update(m.PARA)
    json.dump(out, open(f'{D}/ja.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = sum(len(v) for v in out['para'].values())
    print('ja.json　段', len(out['para']), '・卷', len(out['juan']),
          '・卷末', len(out['colophon']), '段　計', n, '字')


if __name__ == '__main__':
    main()
