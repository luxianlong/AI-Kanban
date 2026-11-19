# -*- coding: utf-8 -*-
"""底本の或る句が、上海圖書館藏白文刊本の掃描 PDF の第何頁に出るかを見積る。

    python3 locate.py 故戰者心本乎

この本は半葉八行・行二十字。掃描の一頁が見開き一つで、<b>右の半葉が先、
左の半葉が後</b>（漢籍は右から讀む）。第一頁だけは左に卷端の半葉が一つ
あるだけ（右は遊紙）。だから讀む順の半葉番號 h は

    h=1 → 頁1（左）、h=2 → 頁2（右）、h=3 → 頁2（左）、
    h=4 → 頁3（右）、h=5 → 頁3（左）、…（h≧2 で 頁 = h//2 + 1）

篇題・卷題はそれぞれ一行を占め、卷の變り目では新しい葉に移るものと見て
數える。あくまで見積りなので、出た頁の前後も見ること。書影の目驗は
これで葉を出した。手控えであって、頁には出ない。
"""
import json, os, sys

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕　 ')
COL, ROW = 20, 8            # 行二十字、半葉八行


def stream():
    """(見積り上の字位, 本文の字位) の列。篇題・卷題の行も字數に數える。"""
    base = json.load(open(f'{D}/base.json', encoding='utf-8'))
    pos, out, text = 0, [], ''
    for k, j in enumerate(base):
        if k:                                   # 卷の變り目は新しい葉から
            pos = -(-pos // (COL * ROW)) * COL * ROW
        pos += COL                              # 「尉繚子卷一」の行
        for p in j['pian']:
            pos += COL                          # 篇題の行
            for c in ''.join(p['paras']):
                if c in PUNCT:
                    continue
                out.append(pos)
                text += c
                pos += 1
    return text, out


def page(pos):
    h = pos // (COL * ROW) + 1
    return (1 if h == 1 else h // 2 + 1), ('左' if h % 2 else '右')


def main(q):
    text, out = stream()
    i = text.find(q)
    if i < 0:
        sys.exit('底本に無い句：' + q)
    p, side = page(out[i])
    print('「%s」 本文第 %d 字 → 掃描 第 %d 頁の%s半葉あたり'
          % (q, i + 1, p, side))
    print('   …%s…' % text[max(0, i - 20):i + len(q) + 20])


if __name__ == '__main__':
    main(sys.argv[1])
