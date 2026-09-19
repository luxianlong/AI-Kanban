# -*- coding: utf-8 -*-
"""四庫全書文淵閣本を篇ごとに切り出す。

この書には<b>使える標點本が無い</b>。武經七書の他の六部とちがって、
維基文庫の《尉繚子》標點本は伍制令・分塞令・束伍令・經卒令の四篇が
空で、戰威・兵令下などもごく一部しか入っていない（全二十四篇のうち
四篇が皆無、本文は今本の半ばにも屆かない）。だから<b>底本には
四庫全書文淵閣本そのものを取り、句讀と段は本頁が施した</b>。

その四庫本の録文は互いに獨立な二つを取る——

  wyg.json   Kanripo KR3b0006 の WYG 枝。葉の界（<pb:…>）を持つ。
  skqs.json  維基文庫《尉繚子（四庫全書本）》。外字を {{SKchar|…}} で示す。

二つが食い違えば、それは<b>本子の異ではなく録文の誤</b>である
（#487《司馬法》の輪が立てた分際）。食い違いは witness-diff.json に落とし、
要處は上海圖書館藏白文刊本の書影で目驗する。
"""
import re, json, os
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
JUAN = ['卷一', '卷二', '卷三', '卷四', '卷五']
KR = {'卷一': '001', '卷二': '002', '卷三': '003', '卷四': '004', '卷五': '005'}
NUM = '一二三四五六七八九十'
# 篇題（今本の通行する形）。四庫本は「兵敎」に作るが「教」の異體。
PIAN = [
    ('天官', 1), ('兵談', 2), ('制談', 3), ('戰威', 4), ('攻權', 5),
    ('守權', 6), ('十二陵', 7), ('武議', 8), ('將理', 9), ('原官', 10),
    ('治本', 11), ('戰權', 12), ('重刑令', 13), ('伍制令', 14), ('分塞令', 15),
    ('束伍令', 16), ('經卒令', 17), ('勒卒令', 18), ('將令', 19), ('踵軍令', 20),
    ('兵教上', 21), ('兵教下', 22), ('兵令上', 23), ('兵令下', 24),
]
JUAN_OF = {}   # 篇 → 卷（四庫本の分卷。今本の武經七書諸本もこれに同じ）
for _n, _lo, _hi in (('卷一', 1, 5), ('卷二', 6, 10), ('卷三', 11, 15),
                     ('卷四', 16, 20), ('卷五', 21, 24)):
    for _i in range(_lo, _hi + 1):
        JUAN_OF[_i] = _n

# Mandoku の外字。charlist は KR0111=忘 を與え、KR0554 は空欄だが
# 「舍不平隴畝樸□葢之以蔽霜露」の位置で、維基文庫の録文が同じ所に
# {{SKchar|195}} を置く。二つとも「樸樕」の樕。
KRCHAR = {'KR0111': '忘', 'KR0554': '樕'}
# 維基文庫の {{SKchar|…}}。1841 は展開すると「龜」、あとの三つは展開しても
# 空なので、Kanripo の録文が同じ位置に作る字を取った。
SKCHAR = {'3951': '揚', '1841': '龜', '195': '樕', '2982': '忘'}
TITLE = re.compile(r'尉繚子[卷巻][一二三四五]')
HEAD = re.compile(r'^(.{2,3})第([一二三四五六七八九十]+)$')


def dechar(s):
    s = re.sub(r'&(KR\d+);', lambda m: KRCHAR[m.group(1)], s)
    s = re.sub(r'\{\{SKchar\|(\d+)\}\}', lambda m: SKCHAR[m.group(1)], s)
    return s


def zh2int(s):
    if s == '十':
        return 10
    if s.startswith('十'):
        return 10 + NUM.index(s[1]) + 1
    if '十' in s:
        a, b = s.split('十')
        return (NUM.index(a) + 1) * 10 + (NUM.index(b) + 1 if b else 0)
    return NUM.index(s) + 1


def split_pian(lines):
    """行の列 → [(篇題, 篇次, 本文)]。篇題の行を界にする。"""
    out, cur = [], None
    for ln in lines:
        m = HEAD.match(ln)
        if m:
            cur = [m.group(1), zh2int(m.group(2)), '']
            out.append(cur)
            continue
        assert cur is not None, ln
        cur[2] += ln
    return out


def kanripo(juan):
    path = f'{D}/raw/wyg/KR3b0006_{KR[juan]}.txt'
    lines, leaves, leaf = [], [], None
    for ln in open(path, encoding='utf-8').read().split('\n'):
        ln = ln.strip()
        if not ln or ln.startswith('#'):
            continue
        m = re.match(r'^<pb:([^>]+)>¶?$', ln)
        if m:
            leaf = m.group(1).split('_')[-1]
            continue
        ln = re.sub(r'<pb:[^>]+>', '', ln)
        for piece in ln.split('¶'):
            piece = dechar(piece.strip('　 \t'))
            if not piece:
                continue
            assert '&' not in piece, piece
            piece = TITLE.sub('', piece)
            if not piece or piece in ('欽定四庫全書', '周　尉繚　撰', '周尉繚撰'):
                continue
            leaves.append((len(lines), leaf))
            lines.append(piece)
    return lines, leaves


def wikisource(n):
    s = open(f'{D}/raw/skqs-{n}.wiki', encoding='utf-8').read()
    s = s[s.index('<poem>') + 6:]
    s = s[:s.index('</poem>')]
    s = dechar(s)
    s = re.sub(r'\{\{SK anchor\|([^}]*)\}\}', r'\n\1\n', s)
    assert '{{' not in s, s[:200]
    out = []
    for ln in s.split('\n'):
        ln = TITLE.sub('', ln.strip('　 \t'))
        if not ln or ln in ('欽定四庫全書', '周　尉繚　撰', '周尉繚撰'):
            continue
        out.append(ln)
    return out


def main():
    wyg, skqs, diffs = {}, {}, []
    allk, allw = [], []
    for n, j in enumerate(JUAN, 1):
        kl, leaves = kanripo(j)
        wl = wikisource(n)
        kp, wp = split_pian(kl), split_pian(wl)
        assert [p[:2] for p in kp] == [p[:2] for p in wp], (
            [p[:2] for p in kp], [p[:2] for p in wp])
        for (t, i, kt), (_, _, wt) in zip(kp, wp):
            allk.append((j, t, i, kt, leaves))
            allw.append((j, t, i, wt))
            sm = SequenceMatcher(None, kt, wt, autojunk=False)
            for tag, i1, i2, k1, k2 in sm.get_opcodes():
                if tag == 'equal':
                    continue
                diffs.append({'juan': j, 'pian': t, 'at': i1,
                              'kanripo': kt[i1:i2], 'wikisource': wt[k1:k2],
                              'ctx': kt[max(0, i1 - 10):i2 + 10]})
        print('%s  %d 篇 %5d 字' % (j, len(kp), sum(len(p[2]) for p in kp)))

    for j, t, i, kt, _ in allk:
        wyg.setdefault(j, []).append({'pian': t, 'n': i, 'text': kt})
    for j, t, i, wt in allw:
        skqs.setdefault(j, []).append({'pian': t, 'n': i, 'text': wt})

    names = [(p['n'], p['pian']) for j in JUAN for p in wyg[j]]
    assert names == [(n, t.replace('教', '敎')) if False else (n, t)
                     for t, n in PIAN] or True
    got = [(p['pian'], p['n']) for j in JUAN for p in wyg[j]]
    want = [(t.replace('教', '敎'), n) for t, n in PIAN]
    assert got == want, (got, want)

    print('二録文の食い違い', len(diffs), '處——本子の異ではなく録文の誤')
    for d in diffs:
        print('   %s〈%s〉%4d  Kanripo「%s」／維基文庫「%s」　%s'
              % (d['juan'], d['pian'], d['at'], d['kanripo'],
                 d['wikisource'], d['ctx']))
    tot = sum(len(p['text']) for j in JUAN for p in wyg[j])
    print('四庫全書本', tot, '字（無句讀）、', len(PIAN), '篇、', len(JUAN), '卷')

    json.dump(wyg, open(f'{D}/wyg.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump(skqs, open(f'{D}/skqs.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump({'transcription': diffs},
              open(f'{D}/witness-diff.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
