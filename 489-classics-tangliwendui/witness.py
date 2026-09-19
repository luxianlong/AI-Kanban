# -*- coding: utf-8 -*-
"""通校本を卷ごとに切り出す。

この一本の通校本は<b>一つ</b>、四庫全書文淵閣本である。ただしその録文は
互いに獨立な二つを取った——

  wyg.json   Kanripo KR3b0010 の WYG 枝。葉の界（<pb:…>）を持つ。
  skqs.json  維基文庫《李衛公問對（四庫全書本）》。外字を {{SKchar|…}} で
             示す。

二つが食い違えば、それは<b>本子の異ではなく録文の誤</b>である——#487
《司馬法》の輪が四庫本で立てた分際をそのまま用いる。食い違いは
witness-diff.json に落とし、要處は書影で目驗する。

四庫全書本には句讀が無く、段も無い。提要（KR3b0010_000）は本文ではないが、
この書の辨僞の話がそこに盡きているので、別に提要.txt として取っておく。
"""
import re, json, os
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
JUAN = ['卷上', '卷中', '卷下']
KR = {'卷上': '001', '卷中': '002', '卷下': '003'}
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕“”‘’　 ')
# 維基文庫の {{SKchar|…}}（四庫の書影に見える異體の形）→ 通用の形。
# 番號の指す字は API で {{SKchar|n}} を展開して確かめた。3951 だけは
# 展開しても空で、Kanripo の録文が同じ位置に「揚」を作る（未嘗戮一揚干）。
TITLE = re.compile(r'李[衛衞]公問對[卷巻][上中下]')
SKCHAR = {'1000': '弊', '1841': '龜', '2962': '發', '3951': '揚',
          '958': '再', '1452': '隸'}


def kanripo(juan):
    """Kanripo WYG 枝 → (本文, [(位置, 葉)])。"""
    path = f'{D}/raw/wyg/KR3b0010_{KR[juan]}.txt'
    text, leaves, leaf = '', [], None
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
            piece = piece.strip('　 \t')
            if not piece:
                continue
            assert '&' not in piece, piece
            piece = TITLE.sub('', piece)   # 卷端・卷末の題（衞／巻 の異體も）
            if not piece or piece == '欽定四庫全書':
                continue
            leaves.append((len(text), leaf))
            text += piece
    return text, leaves


def wikisource(juan):
    """維基文庫の四庫本録文 → 本文。"""
    s = open(f'{D}/raw/skqs-{juan}.wiki', encoding='utf-8').read()
    s = s[s.index('<poem>') + 6:]
    s = s[:s.index('</poem>')]
    s = re.sub(r'\{\{SKchar\|(\d+)\}\}', lambda m: SKCHAR[m.group(1)], s)
    s = re.sub(r'\{\{SK anchor\|[^}]*\}\}', '', s)
    assert '{{' not in s, s[:200]
    out = []
    for ln in s.split('\n'):
        ln = TITLE.sub('', ln.strip('　 \t'))
        if not ln or ln == '欽定四庫全書':
            continue
        out.append(ln)
    return ''.join(out)


def main():
    wyg, skqs, diffs = {}, {}, []
    for j in JUAN:
        t, leaves = kanripo(j)
        w = wikisource(j)
        wyg[j] = {'text': t, 'leaves': leaves}
        skqs[j] = {'text': w}
        sm = SequenceMatcher(None, t, w, autojunk=False)
        for tag, i1, i2, k1, k2 in sm.get_opcodes():
            if tag == 'equal':
                continue
            diffs.append({'juan': j, 'at': i1, 'kanripo': t[i1:i2],
                          'wikisource': w[k1:k2],
                          'ctx': t[max(0, i1 - 10):i2 + 10]})
        print('%s  四庫本 %4d 字（Kanripo と維基文庫の二録文）' % (j, len(t)))
    print('二録文の食い違い', len(diffs), '處——本子の異ではなく録文の誤')
    for d in diffs:
        print('   %s %4d  Kanripo「%s」／維基文庫「%s」　%s'
              % (d['juan'], d['at'], d['kanripo'], d['wikisource'], d['ctx']))

    # ---- 分卷が今本と違う ----------------------------------------------
    # 四庫本の卷上は「葢古法節制信可重也」で終わり、卷中は「太宗幸靈州回」
    # から始まる——今の通行本より 833 字はやい。卷中／卷下の界も同じだけ
    # ずれている。提要が「世所行本不分卷數、今依文獻通考定爲三卷」と言う
    # とおり、この分卷は四庫の館臣が立てたものである。上海圖書館藏の
    # 白文刊本は今本と同じ所で卷を切っている（書影で目驗した。卷末題
    #「唐太宗李衞公問對卷上」が「卿得之矣」の直後に立つ）。
    # 對校は今本の卷立てでするので、ここで切り直す。
    full = ''.join(wyg[j]['text'] for j in JUAN)
    own = []
    o = 0
    for j in JUAN:
        o += len(wyg[j]['text'])
        own.append(o)
    base = json.load(open(f'{D}/base.json', encoding='utf-8'))
    cuts, pos = [], 0
    for j in base[:-1]:
        tail = ''.join(c for c in ''.join(j['paras']) if c not in PUNCT)[-8:]
        k = full.find(tail, pos)
        assert k > 0, tail
        cuts.append(k + len(tail))
        pos = cuts[-1]
    cuts.append(len(full))
    out, s0 = {}, 0
    for j, e in zip(JUAN, cuts):
        out[j] = {'text': full[s0:e], 'siku_own': None}
        s0 = e
    for j, mine, theirs in zip(JUAN, cuts, own):
        print('%s  今本の界 %5d ／ 四庫本自身の界 %5d（差 %+d 字）'
              % (j, mine, theirs, theirs - mine))
    json.dump(out, open(f'{D}/wyg.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump(skqs, open(f'{D}/skqs.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump({'transcription': diffs,
               'siku_own_cuts': own, 'received_cuts': cuts},
              open(f'{D}/witness-diff.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    for j in JUAN:
        print('%s  四庫本（今本の卷立てに切り直したもの）%4d 字'
              % (j, len(out[j]['text'])))


if __name__ == '__main__':
    main()
