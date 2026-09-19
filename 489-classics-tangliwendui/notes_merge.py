# -*- coding: utf-8 -*-
"""notes/zh1..zh3 と colophon を notes.json にまとめる。"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(D, 'notes'))
import zh1, zh2, zh3, colophon                       # noqa: E402

MODS = [('j1', zh1), ('j2', zh2), ('j3', zh3)]


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    out = {'juan': {}, 'para': {}, 'colophon': colophon.COLOPHON}
    for (jid, m), j in zip(MODS, T):
        out['juan'][jid] = m.JUAN
        want = {'%s-%d' % (jid, i) for i in range(1, len(j['paras']) + 1)}
        got = set(m.PARA)
        assert want == got, f'{jid}: 足りない {sorted(want-got)} / 余り {sorted(got-want)}'
        out['para'].update(m.PARA)
    # 段註と卷旨に生の HTML を混ぜない（build.py が escape するので字として出る）。
    for v in list(out['para'].values()) + list(out['juan'].values()):
        assert '<' not in v and '>' not in v, v[:40]
    json.dump(out, open(f'{D}/notes.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = sum(len(v) for v in out['para'].values()) \
        + sum(len(v) for v in out['juan'].values())
    print('notes.json　卷旨', len(out['juan']), '・段註', len(out['para']),
          '・卷末', len(out['colophon']), '段　計', n, '字')


if __name__ == '__main__':
    main()
