# -*- coding: utf-8 -*-
"""分段。段の界は本頁が決めたのではなく、劉寅《三畧直解》が決めている。

《三畧》には篇も章も無い。三巻がそのまま一続きの文で、底本（維基文庫）は
段落を切っていない（上略が一塊）。そこで劉寅が註を挟んだところ——つまり
劉寅が一節と見たところ——をそのまま段の界に採る。上 66・中 21・下 34、
計 121 段。底本と直解を逐字で対応づけ、直解の節界に当たる底本の位置を
求め、その直前の句点・分号・引用の閉じで切る。
"""
import json, os, re
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·'
CUT = '。！？'          # ここで切る
SOFT = '；'             # 句点が見つからないときはこれでも切る


def strip_map(s):
    """句読点を除いた文字列と、その各字の元の位置。"""
    plain, idx = [], []
    for i, c in enumerate(s):
        if c not in PUNCT:
            plain.append(c); idx.append(i)
    return ''.join(plain), idx


def main():
    canon = json.load(open(f'{D}/canon.json', encoding='utf-8'))
    zhij = json.load(open(f'{D}/zhijie.json', encoding='utf-8'))
    out = []
    for j in canon:
        name = j['name']
        text = ''.join(j['paras'])
        plain, idx = strip_map(text)
        wit = ''.join(''.join(c for c in s if c not in PUNCT) for s in zhij[name])
        # 直解の節界（累積字数）
        bounds, acc = [], 0
        for s in zhij[name][:-1]:
            acc += len(''.join(c for c in s if c not in PUNCT))
            bounds.append(acc)
        # 底本 ←→ 直解 の対応表
        sm = SequenceMatcher(None, plain, wit, autojunk=False)
        m = {}
        for i1, j1, n in sm.get_matching_blocks():
            for k in range(n):
                m[j1 + k] = i1 + k
        cuts = []
        for b in bounds:
            # 直解の b 文字目に当たる底本の位置
            p = None
            for d in range(0, 12):
                if b + d in m: p = m[b + d]; break
                if b - d in m: p = m[b - d] + d; break
            if p is None:
                continue
            q = idx[min(p, len(idx) - 1)]
            # その位置の直前にある句点（無ければ分号）まで戻す
            best = None
            for back in range(0, 40):
                c = q - 1 - back
                if c < 0: break
                if text[c] in CUT or (text[c] in '」』' and c and text[c-1] in CUT):
                    best = c + 1; break
            if best is None:
                for back in range(0, 40):
                    c = q - 1 - back
                    if c < 0: break
                    if text[c] in SOFT:
                        best = c + 1; break
            if best is None:
                continue
            while best < len(text) and text[best] in '」』':
                best += 1
            if not cuts or best > cuts[-1]:
                cuts.append(best)
        paras, prev = [], 0
        for c in cuts + [len(text)]:
            seg = text[prev:c].strip()
            if seg:
                paras.append(seg)
            prev = c
        # 一つの引用が段をまたぐときは、通行の排印法に従い続く段の頭に
        # 上引號を補う（閉じは末段だけ）。本文の字は一字も増減しない。
        depth = 0
        for i, p in enumerate(paras):
            opened = depth + p.count('\u300c') - p.count('\u300d')
            assert opened >= 0, f'{name} 第{i+1}段：引號が閉じすぎ'
            if depth:
                paras[i] = '\u300c' * depth + p
            depth = opened
        assert depth == 0, f'{name}：引號が {depth} 個開いたまま'
        out.append({'name': name, 'paras': paras})
        print(f'{name}  直解 {len(zhij[name])} 節 → 本頁 {len(paras)} 段'
              f'（最長 {max(len(p) for p in paras)} 字）')
        rebuilt = ''.join(paras)
        assert rebuilt.replace('\u300c', '') == text.replace('\n', '').replace('\u300c', ''), \
            f'{name}: 本文が変わった'
    json.dump(out, open(f'{D}/text.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('計', sum(len(j['paras']) for j in out), '段')


if __name__ == '__main__':
    main()
