# -*- coding: utf-8 -*-
"""底本逐字對校景宋本與四庫本，輸出 variants.json。

「字形差異」（虛／虚、啟／啓、陰／隂、騎／𮪍 之類）依 CJKVI 的四種異體字表
判定，不計入異文；其餘為文字異文，逐條備查。
"""
import json, re, sys
from difflib import SequenceMatcher

PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕[]　 \n\t'
TABLES = ['cjkvi-variants.txt', 'twedu-variants.txt', 'hydzd-variants.txt',
          'jp-old-style.txt', 'joyo-variants.txt']

# 四表未收的舊刻字形。都是宋刻／四庫寫本的書手字，不是別的字：
#   𮪍(U+2EA4D) 即「騎」的俗寫，𥹭 即「粱」，⿰扌𭃄 之類則由 CJKVI 收去。
EXTRA = [('𮪍', '騎'), ('𥹭', '粱'), ('𭰹', '深'), ('𨺚', '隆'),
         ('歩', '步'), ('⺊', '卜'), ('亾', '亡'), ('冨', '富'),
         ('刼', '劫'), ('𫝆', '今'), ('𭏸', '壞'), ('𤰞', '卑'),
         ('𭧂', '暴'), ('𠆸', '技'), ('姦', '奸'), ('愼', '慎')]

# 三個本子之間通用無別的字，不是異文，另計。
TONGYONG = [('於', '于'), ('無', '无')]

# 異體字表裡互見、但在本書裡確是兩個字的，不得歸併。
BLACK = [('上', '二'), ('丈', '支'), ('一', '壹'), ('三', '參'), ('二', '貳'),
         ('七', '柒'), ('十', '拾'), ('百', '佰'), ('千', '仟'),
         ('無', '毋'), ('毋', '母'), ('弗', '不'), ('已', '以'),
         ('烏', '鳥'), ('陳', '陣'), ('後', '后'), ('彊', '疆'),
         ('修', '脩'), ('知', '智'), ('伏', '服'), ('內', '納'),
         ('大', '太'), ('說', '悅'), ('責', '債'), ('食', '飼'),
         ('反', '返'), ('女', '汝'), ('見', '現'), ('被', '披'),
         ('華', '花'), ('信', '伸'), ('孰', '熟'), ('錯', '措')]


def load_classes():
    """『甲是乙的異體』的直接對，不作遞移閉包。

    CJKVI 的 twedu 表裡有「上／二」「丈／支」這類條目，一旦作 union-find
    併起來，幾步之內就把不相干的字併進同一個等價類（實測 60 個篇題會塌成
    52 個）。所以這裡只認直接列出的一對，兩個方向都算；再由 BLACK 剔掉在
    古文裡確是兩個字、表裡卻互見的幾對。
    """
    pairs = set()
    for t in TABLES:
        try:
            f = open('var/' + t, encoding='utf-8')
        except FileNotFoundError:
            print('缺表', t, file=sys.stderr)
            continue
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split(',')
            if len(parts) != 3:
                continue
            a, rel, b = parts
            if rel.startswith('<') or len(a) != 1 or len(b) != 1:
                continue
            pairs.add((a, b))
            pairs.add((b, a))
    for a, b in EXTRA:
        pairs.add((a, b))
        pairs.add((b, a))
    for a, b in TONGYONG:
        pairs.add((a, b))
        pairs.add((b, a))
    for a, b in BLACK:
        pairs.discard((a, b))
        pairs.discard((b, a))
    return lambda a, b: a == b or (a, b) in pairs


def plain(s):
    return ''.join(c for c in s if c not in PUNCT)


def main():
    same = load_classes()
    base = json.load(open('canon.json'))
    song = json.load(open('song.json'))
    siku = json.load(open('siku.json'))


    out = {'song': [], 'siku': []}
    stat = {'song': [0, 0], 'siku': [0, 0]}   # [字形, 異文]

    for p in base['pian']:
        b = plain(p['text'])
        for which, book in (('song', song), ('siku', siku)):
            w = book.get(p['name']) if which == 'song' else book.get(str(p['n']))
            if w is None:
                out[which].append({'pian': p['n'], 'title': p['title'],
                                   'missing': True})
                continue
            w = plain(''.join(w) if isinstance(w, list) else w)
            sm = SequenceMatcher(None, b, w, autojunk=False)
            for tag, i1, i2, j1, j2 in sm.get_opcodes():
                if tag == 'equal':
                    continue
                x, y = b[i1:i2], w[j1:j2]
                # 等長替換逐字拆開：字形之異剔除，餘下的每個字位各為一條，
                # 免得一串裡混進一個舊字形就把整串算成異文（寶全／寳完）。
                if tag == 'replace' and len(x) == len(y):
                    for k, (cx, cy) in enumerate(zip(x, y)):
                        if cx == cy:
                            continue
                        if same(cx, cy):
                            stat[which][0] += 1
                            continue
                        stat[which][1] += 1
                        out[which].append({
                            'pian': p['n'], 'title': p['title'], 'tag': tag,
                            'at': i1 + k, 'base': cx, 'other': cy,
                            'ctx': b[max(0, i1 + k - 8):i1 + k + 9],
                        })
                    continue
                stat[which][1] += max(len(x), len(y))
                out[which].append({
                    'pian': p['n'], 'title': p['title'], 'tag': tag,
                    'at': i1, 'base': x, 'other': y,
                    'ctx': b[max(0, i1 - 8):i2 + 8],
                })
    json.dump(out, open('variants.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    for k in ('song', 'siku'):
        n = len([r for r in out[k] if not r.get('missing')])
        print({'song': '景宋本', 'siku': '四庫本'}[k],
              '異文', n, '條 /', stat[k][1], '字位；字形差異', stat[k][0], '處')


if __name__ == '__main__':
    main()
