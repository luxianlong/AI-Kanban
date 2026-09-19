# -*- coding: utf-8 -*-
"""分段。底本每篇是一整塊，原書也沒有段——分段與標點都是本頁所加。

兩道界：
  一、說話人換人處。《六韜》通篇是文王／武王與太公的問答，換人即換段，
      這是今本《六韜》通行的分法。
  二、一段仍長於 LIMIT 字時，借四庫本的行界再切。四庫本錄文保留了寫本的
      分行，那是另一支傳本自己的分段，不是本頁憑空所立；切點先在底本上
      對齊，再落到最近的句末。
"""
import json, re
from difflib import SequenceMatcher

LIMIT = 190
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕　 \n\t'
SPEAK = re.compile(
    r'(?<=[。」])(?=(?:文王|武王|太公|太師|史編|周公|召公)'
    r'(?:問|曰|再拜|避席|乃|齋|拜|起))')
ENDS = '。！？'
CLOSERS = '」』'


def plain(s):
    return ''.join(c for c in s if c not in PUNCT)


def cut_by_speaker(t):
    out, last = [], 0
    for m in SPEAK.finditer(t):
        out.append(t[last:m.start()])
        last = m.start()
    out.append(t[last:])
    return [x for x in out if x.strip()]


def siku_breaks(text, paras):
    """四庫本的行界映到底本的字位（去標點後的下標）。"""
    b = plain(text)
    w = plain(''.join(paras))
    sm = SequenceMatcher(None, b, w, autojunk=False)
    # w 中每段起點
    starts, acc = [], 0
    for p in paras[:-1]:
        acc += len(plain(p))
        starts.append(acc)
    m = {}
    for i, j, n in sm.get_matching_blocks():
        for k in range(n):
            m[j + k] = i + k
    return sorted({m[s] for s in starts if s in m})


def split_long(chunk, breaks, base_off):
    """chunk 過長時，在 breaks（底本去標點後的字位）處再切；四庫本在此無行界
    可借時，退一步在句號處切，使每段不過 LIMIT 字。"""
    if len(plain(chunk)) <= LIMIT:
        return [chunk], base_off + len(plain(chunk))
    # 建 chunk 內「去標點下標 → 原串下標」
    idx, pos = [], []
    for i, c in enumerate(chunk):
        if c not in PUNCT:
            idx.append(i)
    inner = [b - base_off for b in breaks
             if base_off < b < base_off + len(idx)]
    out, last = [], 0
    for b in inner:
        cut = idx[b]
        # 落到最近的句末之後
        j = cut
        while j > last and chunk[j - 1] not in ENDS + CLOSERS:
            j -= 1
        while j < len(chunk) and chunk[j] in CLOSERS:
            j += 1
        if j - last < 40 or len(plain(chunk[j:])) < 40:
            continue
        out.append(chunk[last:j])
        last = j
    out.append(chunk[last:])
    out = [x for x in out if x.strip()]
    # 四庫本無行界可借時的退路：在句末切，就近取齊
    final = []
    for x in out:
        while len(plain(x)) > LIMIT:
            want = 0
            k = 0
            for k, ch in enumerate(x):
                if ch not in PUNCT:
                    want += 1
                if want >= LIMIT and ch in ENDS:
                    break
            else:
                break
            k += 1
            while k < len(x) and x[k] in ENDS + CLOSERS:
                k += 1
            if len(plain(x[k:])) < 40:
                break
            final.append(x[:k])
            x = x[k:]
        final.append(x)
    return final, base_off + len(idx)


def reopen(paras):
    """一段話被切成幾段時，依通行排印法，續段各補一個上引號，收口只在末段。
    正文一字未增減——補的只是引號。"""
    out, depth = [], 0
    for x in paras:
        d = depth + x.count('「') - x.count('」')
        out.append('「' * depth + x)
        depth = max(d, 0)
    return out


def main():
    canon = json.load(open('canon.json'))
    siku = json.load(open('siku.json'))
    total = 0
    for p in canon['pian']:
        t = p['text']
        brks = siku_breaks(t, siku[str(p['n'])])
        paras, off = [], 0
        for chunk in cut_by_speaker(t):
            got, off = split_long(chunk, brks, off)
            paras.extend(got)
        paras = reopen(paras)
        p['paras'] = paras
        total += len(paras)
        assert plain(''.join(paras)).replace('', '') == plain(t), p['title']
    json.dump(canon, open('text.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    lens = sorted((len(plain(x)), p['title'])
                  for p in canon['pian'] for x in p['paras'])
    print('段', total, '最長', lens[-3:], '最短', lens[:3])


if __name__ == '__main__':
    main()
