# -*- coding: utf-8 -*-
"""四庫全書文淵閣本の錄文二種を篇ごとに切り、突き合わせる。

  raw/wyg/KR3i0019_00[123].txt  Kanripo KR3i0019 の WYG 枝
  raw/skqs-卷[上中下].wiki       維基文庫《茶經 (四庫全書本)》

どちらも四庫本の錄文だが、起こした人が別。二つが一致するところは
四庫本がそう刻されていると見てよい。食い違うところは錄文の誤りの
疑いがあるので、witness-diff.json に出して一つずつ見る。

出力：wyg.json / skqs.json / witness-diff.json
"""
import json, re, difflib

JUAN = ['卷上', '卷中', '卷下']
PIAN = ['一茶之源', '二茶之具', '三茶之造', '四茶之噐', '五茶之煑',
        '六茶之飲', '七茶之事', '八茶之出', '九茶之略', '十茶之圖']


def kr_charmap():
    """Mandoku の外字實體 &KRxxxx; → Unicode 正字。"""
    m = {}
    for line in open('var/kr-charlist.org.txt', encoding='utf-8'):
        f = line.split('|')
        if len(f) > 3 and f[1].strip().startswith('KR'):
            code, uni = f[1].strip(), f[2].strip()
            if uni and len(uni) <= 2 and uni != '?':
                m['&%s;' % code] = uni
    return m


def load_wyg():
    """Kanripo の一卷を、本文と夾注（丸括弧、行を割って刻す小字）に分ける。"""
    cm = kr_charmap()
    out = {}
    for i, j in enumerate(JUAN, 1):
        s = open('raw/wyg/KR3i0019_%03d.txt' % i, encoding='utf-8').read()
        s = s[s.index('<pb:'):]
        s = re.sub(r'<pb:[^>]*>', '', s)
        s = s.replace('¶', '').replace('\n', '')
        for k, v in cm.items():
            s = s.replace(k, v)
        s = re.sub(r'&KR\d+;', '�', s)          # 對照表に無い外字
        # 夾注は (前半/後半) の形で二行に割れている。斜線を取って一續きに。
        s = re.sub(r'\(([^()]*)\)', lambda m: '〔%s〕' % m.group(1).replace('/', ''), s)
        s = s.replace('　', '')
        out[j] = s
    return out


def load_skqs():
    out = {}
    for j in JUAN:
        s = open('raw/skqs-%s.wiki' % j, encoding='utf-8').read()
        s = s[s.index('<poem>') + 6:s.index('</poem>')]
        s = re.sub(r'\{\{SKchar2?\|\d+\}\}', '\ufffd', s)
        s = re.sub(r'\{\{SK notes\|([^{}]*)\}\}', r'〔\1〕', s)
        s = re.sub(r'\{\{SK anchor\|([^{}]*)\}\}', r'\1', s)
        s = s.replace('　', '').replace('\n', '')
        out[j] = s
    return out


def join_notes(s):
    return s.replace('〕〔', '')


def cut(s, juan):
    """一卷の文字列を篇ごとに切る。篇題は四庫本の「一茶之源」の形。"""
    d, keys = {}, [p for p in PIAN if p in s]
    for k, nxt in zip(keys, keys[1:] + [None]):
        a = s.index(k) + len(k)
        b = s.index(nxt) if nxt else len(s)
        d[k] = s[a:b]
    # 卷頭の「欽定四庫全書茶經卷上唐陸羽撰」と卷末の尾題を落とす
    if keys:
        last = keys[-1]
        d[last] = re.sub(r'茶經%s$' % juan, '', d[last])
    return d


def main():
    wyg, skqs = load_wyg(), load_skqs()
    W = {j: cut(join_notes(wyg[j]), j) for j in JUAN}
    S = {j: cut(join_notes(skqs[j]), j) for j in JUAN}
    json.dump(W, open('wyg.json', 'w'), ensure_ascii=False, indent=1)
    json.dump(S, open('skqs.json', 'w'), ensure_ascii=False, indent=1)

    diff, n = [], 0
    for j in JUAN:
        for p in W[j]:
            a, b = W[j][p], S[j].get(p, '')
            for tag, i1, i2, k1, k2 in difflib.SequenceMatcher(None, a, b).get_opcodes():
                if tag == 'equal':
                    continue
                x, y = a[i1:i2], b[k1:k2]
                if len(x) == len(y) and all(
                        c == d or '\ufffd' in (c, d) for c, d in zip(x, y)):
                    continue        # 一方が外字の印を付けただけ
                n += 1
                diff.append({'pian': p, 'wyg': a[i1:i2], 'skqs': b[k1:k2],
                             'ctx': a[max(0, i1 - 12):i2 + 12]})
    json.dump(diff, open('witness-diff.json', 'w'), ensure_ascii=False, indent=1)
    tot = sum(len(W[j][p]) for j in JUAN for p in W[j])
    print('四庫本 %d 字（夾注の〔〕込み）、二錄文の食い違い %d 處' % (tot, n))
    for d in diff:
        print('  %s  WYG「%s」/ 維基「%s」  …%s…'
              % (d['pian'], d['wyg'], d['skqs'], d['ctx']))


if __name__ == '__main__':
    main()
