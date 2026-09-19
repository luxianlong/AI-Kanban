# -*- coding: utf-8 -*-
"""punct/ に書いた句讀つきの本文 → base.json。

<b>この書には使える標點本が無い</b>（witness.py の冒頭を見よ）。だから
底本は四庫全書文淵閣本そのもので、句讀と段の界は本頁が施した。
ここで機械が檢べるのは一事だけ——<b>句讀と段の界を取り除くと、
四庫本（wyg.json）の本文と逐字で同一になるか</b>。一字でも違えば止まる。
"""
import json, os, sys, importlib.util
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
JUAN = ['卷一', '卷二', '卷三', '卷四', '卷五']
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕　 ')


def load(name):
    spec = importlib.util.spec_from_file_location(name, f'{D}/punct/{name}.py')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def plain(s):
    return ''.join(c for c in s if c not in PUNCT)


def main():
    wyg = json.load(open(f'{D}/wyg.json', encoding='utf-8'))
    out, bad = [], 0
    for n, jn in enumerate(JUAN, 1):
        m = load('j%d' % n)
        assert m.J == jn, (m.J, jn)
        want = {p['pian']: p['text'] for p in wyg[jn]}
        order = [(p['pian'], p['n']) for p in wyg[jn]]
        got = [(t.replace('教', '敎'), i) for t, i, _ in m.P]
        assert got == order, (got, order)
        pian = []
        for title, idx, paras in m.P:
            key = title.replace('教', '敎')
            mine = plain(''.join(paras))
            if mine != want[key]:
                bad += 1
                print('！ %s〈%s〉句讀を除いた本文が四庫本と違う（%d 字 / %d 字）'
                      % (jn, title, len(mine), len(want[key])))
                sm = SequenceMatcher(None, want[key], mine, autojunk=False)
                for tag, i1, i2, k1, k2 in sm.get_opcodes():
                    if tag == 'equal':
                        continue
                    print('    %-7s 四庫「%s」／本頁「%s」  …%s…'
                          % (tag, want[key][i1:i2], mine[k1:k2],
                             want[key][max(0, i1 - 12):i2 + 12]))
            pian.append({'pian': title, 'n': idx, 'paras': paras})
        out.append({'name': jn, 'pian': pian})
        print('%s  %d 篇 %3d 段 %5d 字'
              % (jn, len(pian), sum(len(p['paras']) for p in pian),
                 sum(len(plain(''.join(p['paras']))) for p in pian)))
    if bad:
        sys.exit('！ %d 篇で本文が四庫本と一致しない' % bad)
    json.dump(out, open(f'{D}/base.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    np = sum(len(p['paras']) for j in out for p in j['pian'])
    nc = sum(len(plain(''.join(p['paras']))) for j in out for p in j['pian'])
    print('計 %d 卷 %d 篇 %d 段 %d 字（句讀を除く）——四庫本と逐字一致'
          % (len(out), sum(len(j['pian']) for j in out), np, nc))


if __name__ == '__main__':
    main()
