# -*- coding: utf-8 -*-
"""底本を四庫全書文淵閣本と逐字で突き合わせ、collate.json を出す。

字形の異なり（爲／為、高／髙、內／内、遠／逺 の類）は CJKVI の四つの
異體字表で判定して異文に數えない。表は直に竝べられた一對だけを認める
（遞移閉包は取らない——臺灣教育部の表には「上／二」「丈／支」の類があり、
併せてゆくと數歩で無關係の字が同じ類に落ちる）。#487 の線をそのまま踏む。
"""
import json, os, sys
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕【】“”‘’︰,. 　\n\t'
TABLES = ['cjkvi-variants.txt', 'twedu-variants.txt', 'hydzd-variants.txt',
          'jp-old-style.txt']
SKQS_PIAN = {'一之源': '一茶之源', '二之具': '二茶之具', '三之造': '三茶之造',
             '四之器': '四茶之噐', '五之煮': '五茶之煑', '六之飲': '六茶之飲',
             '七之事': '七茶之事', '八之出': '八茶之出', '九之略': '九茶之略',
             '十之圖': '十茶之圖'}

# 四表に無い、刻工・寫工の書きぶり。別の字ではなく、同じ字の書きよう。
EXTRA = [('䆫', '窗'), ('䆫', '窻'), ('窗', '窻'), ('羹', '羮'), ('煮', '煑'),
         ('凢', '凡'), ('噐', '器'), ('冑', '胄'), ('筯', '箸'), ('𨫀', '銙'),
         ('搗', '擣'), ('蓋', '葢'), ('並', '竝'), ('彈', '弾')]

# 三本の間で通用し、別の字とは言えないもの。異文に數えず、別に數える。
TONGYONG = [('於', '于'), ('無', '无'), ('舍', '舎'), ('並', '并')]

# 異體字表に互見するが、この書では確かに二つの字。併せてはならない。
BLACK = [('採', '采'), ('荼', '茶'), ('揚', '楊'), ('育', '毓'), ('斧', '釜'),
         ('上', '一'), ('上', '二'), ('赤', '尺'), ('簞', '箄'), ('准', '準'),
         ('籝', '嬴'), ('京', '謂'), ('滅', '年'), ('胡', '號'), ('明', '某'),
         ('紐', '細'), ('模', '摸'), ('內', '納'), ('自', '白'), ('曰', '昍'),
         ('漳', '鄭'), ('褒', '襄'), ('始', '台'), ('拍', '煬'), ('鎚', '錘'),
         ('已', '以'), ('已', '己'), ('後', '后'), ('修', '脩'), ('知', '智'),
         ('大', '太'), ('說', '悅'), ('反', '返'), ('止', '正'), ('木', '本')]


def load_classes():
    pairs = set()
    for t in TABLES:
        for line in open(os.path.join(D, 'var', t), encoding='utf-8'):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split(',')
            if len(parts) != 3:
                continue
            a, rel, b = parts
            if rel.startswith('<') or len(a) != 1 or len(b) != 1:
                continue
            pairs.add((a, b)); pairs.add((b, a))
    for a, b in EXTRA + TONGYONG:
        pairs.add((a, b)); pairs.add((b, a))
    for a, b in BLACK:
        pairs.discard((a, b)); pairs.discard((b, a))
    return lambda a, b: a == b or (a, b) in pairs


def plain(s):
    return ''.join(c for c in s if c not in PUNCT)


def main():
    same = load_classes()
    src = sys.argv[1] if len(sys.argv) > 1 else 'base.json'
    B = json.load(open(os.path.join(D, src), encoding='utf-8'))
    W = json.load(open(os.path.join(D, 'wyg.json'), encoding='utf-8'))
    flat = {p: plain(t) for j in W for p, t in W[j].items()}

    out, nform, npos = [], 0, 0
    for q in B['pian']:
        b = plain(''.join(q['paras']))
        w = flat[SKQS_PIAN[q['name']]]
        for tag, i1, i2, k1, k2 in SequenceMatcher(None, b, w,
                                                   autojunk=False).get_opcodes():
            if tag == 'equal':
                continue
            x, y = b[i1:i2], w[k1:k2]
            if tag == 'replace' and len(x) == len(y):
                for k, (cx, cy) in enumerate(zip(x, y)):
                    if same(cx, cy):
                        nform += cx != cy
                        continue
                    npos += 1
                    out.append({'pian': q['name'], 'at': i1 + k,
                                'base': cx, 'wyg': cy,
                                'ctx': b[max(0, i1 + k - 9):i1 + k + 10]})
                continue
            npos += max(len(x), len(y))
            out.append({'pian': q['name'], 'at': i1, 'base': x, 'wyg': y,
                        'ctx': b[max(0, i1 - 11):i2 + 11]})

    json.dump(out, open(os.path.join(D, 'collate.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('底本 %d 字 / 四庫本 %d 字。異文 %d 條・%d 字位、字形と通用 %d 處'
          % (sum(len(plain(''.join(q['paras']))) for q in B['pian']),
             sum(len(v) for v in flat.values()), len(out), npos, nform))
    for r in out:
        print('  %-4s 底「%s」四庫「%s」　%s' % (r['pian'], r['base'], r['wyg'], r['ctx']))


if __name__ == '__main__':
    main()
