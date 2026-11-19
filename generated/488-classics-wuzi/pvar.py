# -*- coding: utf-8 -*-
"""異文を段ごとに割りつけて印字するだけの手控え。頁には出ない。

註釋を書くとき、どの段にどの異文が落ちるかを見るために使う。
`python3 pvar.py [篇名]`
"""
import json, os, sys
import align as A

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = A.PUNCT


def plain(s):
    return ''.join(c for c in s if c not in PUNCT)


def main():
    T = json.load(open(f'{D}/text.json', encoding='utf-8'))
    V = json.load(open(f'{D}/variants.json', encoding='utf-8'))
    want = sys.argv[1] if len(sys.argv) > 1 else None
    for k, j in enumerate(T, 1):
        if want and j['name'] != want:
            continue
        # 段の頭が篇のなかで何字目か
        bounds, n = [], 0
        for p in j['paras']:
            bounds.append(n)
            n += len(plain(p))
        rows = [(d['at'], w, d) for w in ('sbck', 'wyg') for d in V[w]
                if d['pian'] == j['name']]
        print('=' * 10, j['name'])
        for i, p in enumerate(j['paras'], 1):
            lo = bounds[i - 1]
            hi = bounds[i] if i < len(bounds) else n
            here = [(a, w, d) for a, w, d in rows if lo <= a < hi]
            if not here:
                continue
            print(' [%s-%d] %s' % ('p%d' % k, i, p[:24] + '…'))
            for a, w, d in sorted(here):
                print('    %s 底[%s] 他[%s]  …%s…'
                      % ('叢' if w == 'sbck' else '庫',
                         d['base'], d['other'], d['ctx']))


if __name__ == '__main__':
    main()
