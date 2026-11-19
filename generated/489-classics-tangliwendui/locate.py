# -*- coding: utf-8 -*-
"""底本の或る句が、上海圖書館藏白文刊本（PDF 三十六頁）のどの頁に出るかを見積る。

この本は半葉八行・行二十字。卷端の葉は題が一行を取るので本文七行。
PDF の一頁が一葉（半葉二つ）だから、一頁ほぼ三百二十字。書影の目驗は
この見積りで頁を出し、前後一頁を見れば必ず當たる。
"""
import json, os, re, sys

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕“”‘’　 ')


def flat():
    B = json.load(open(f'{D}/base.json', encoding='utf-8'))
    return ''.join(c for j in B for p in j['paras'] for c in p if c not in PUNCT)


def main():
    t = flat()
    for q in sys.argv[1:]:
        i = t.find(q)
        if i < 0:
            print('%-14s 見つからない' % q); continue
        # 卷端の題と卷末の題が三卷ぶん、行を食う分を見込む
        print('%-16s 位置 %5d　→ 第 %.1f 頁あたり　%s'
              % (q, i, 1 + (i + 180) / 320.0, t[max(0, i - 12):i + 14]))


if __name__ == '__main__':
    main()
