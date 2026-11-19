# -*- coding: utf-8 -*-
"""notes/en1..en3 と en_colophon を en.json にまとめる。"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(D, 'notes'))
import en1, en2, en3, en_colophon                     # noqa: E402

MODS = [('j1', en1), ('j2', en2), ('j3', en3)]


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    out = {'juan': {}, 'para': {}, 'colophon': en_colophon.COLOPHON,
           'labels': en_colophon.LABELS}
    for (jid, m), j in zip(MODS, T):
        out['juan'][jid] = {'title': m.TITLE, 'head': m.HEAD}
        want = {'%s-%d' % (jid, i) for i in range(1, len(j['paras']) + 1)}
        got = set(m.PARA)
        assert want == got, f'{jid}: 足りない {sorted(want-got)} / 余り {sorted(got-want)}'
        out['para'].update(m.PARA)
    json.dump(out, open(f'{D}/en.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    w = sum(len(v.split()) for v in out['para'].values()) \
        + sum(len(v['head'].split()) for v in out['juan'].values()) \
        + sum(len(v.split()) for v in out['colophon'])
    print('en.json　段', len(out['para']), '・卷', len(out['juan']),
          '・卷末', len(out['colophon']), '段　約', w, 'words')


if __name__ == '__main__':
    main()
