# -*- coding: utf-8 -*-
"""底本を逐字で通校二本と突き合わせ、variants.json を出す。

「字形差異」（徳／德、强／強、擥／攬、圗／圖 の類）は CJKVI の四つの
異體字表で判定して異文に数えない。残りが文字の異文。
表は直接列挙された一対だけを認める（遞移閉包は取らない）——twedu 表には
「上／二」「丈／支」の類があり、union-find で併せると数歩で無関係の字が
同じ類に落ちる。
"""
import json, re, sys, os
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕[]　 \n\t'
TABLES = ['cjkvi-variants.txt', 'twedu-variants.txt', 'hydzd-variants.txt',
          'jp-old-style.txt']

# 四表に無い四庫寫本の書手字。別の字ではなく、同じ字の書きぶり。
EXTRA = [('䜟', '讖'), ('䇿', '策'), ('㑹', '會'), ('𤼵', '發'),
         ('㫖', '旨'), ('冝', '宜'), ('叅', '參'), ('𭰹', '深'),
         ('歩', '步'), ('冨', '富'), ('𫝆', '今'), ('姦', '奸'),
         ('愼', '慎'), ('闗', '關'), ('𨺚', '隆'), ('𠀋', '丈'),
         ('隱', '隠'), ('兇', '㐫'), ('內', '内'), ('鬥', '鬭'),
         ('聽', '聴'), ('娛', '娯'), ('胸', '胷'), ('輒', '輙')]

# 三本の間で通用し、別の字とは言えないもの。異文に数えず、別に数える。
TONGYONG = [('於', '于'), ('無', '无'), ('已', '己')]

# 異體字表に互見するが、この書では確かに二つの字。併せてはならない。
BLACK = [('上', '二'), ('丈', '支'), ('一', '壹'), ('三', '參'), ('二', '貳'),
         ('七', '柒'), ('十', '拾'), ('百', '佰'), ('千', '仟'),
         ('無', '毋'), ('毋', '母'), ('弗', '不'), ('已', '以'),
         ('後', '后'), ('彊', '疆'), ('修', '脩'), ('知', '智'),
         ('伏', '服'), ('內', '納'), ('大', '太'), ('說', '悅'),
         ('志', '誌'), ('卜', '蔔'), ('徵', '征'), ('遺', '遣'),
         ('居', '活'), ('怨', '寃'), ('賦', '稅'), ('緩', '綏'),
         ('如', '知'), ('人', '怨'), ('祐', '佑'), ('隱', '隠')]


def load_classes():
    pairs = set()
    for t in TABLES:
        try:
            f = open(f'{D}/var/{t}', encoding='utf-8')
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
    base = json.load(open(f'{D}/canon.json', encoding='utf-8'))
    siku = json.load(open(f'{D}/siku.json', encoding='utf-8'))
    zhij = json.load(open(f'{D}/zhijie.json', encoding='utf-8'))

    out = {'siku': [], 'zhijie': []}
    stat = {'siku': [0, 0], 'zhijie': [0, 0]}     # [字形, 異文]

    for j in base:
        name = j['name']
        b = plain(''.join(j['paras']))
        for which, w in (('siku', siku[name]['text']),
                         ('zhijie', ''.join(zhij[name]))):
            w = plain(w)
            sm = SequenceMatcher(None, b, w, autojunk=False)
            for tag, i1, i2, j1, j2 in sm.get_opcodes():
                if tag == 'equal':
                    continue
                x, y = b[i1:i2], w[j1:j2]
                if tag == 'replace' and len(x) == len(y):
                    for k, (cx, cy) in enumerate(zip(x, y)):
                        if cx == cy:
                            continue
                        if same(cx, cy):
                            stat[which][0] += 1
                            continue
                        stat[which][1] += 1
                        out[which].append({
                            'juan': name, 'tag': tag, 'at': i1 + k,
                            'base': cx, 'other': cy,
                            'ctx': b[max(0, i1 + k - 8):i1 + k + 9]})
                    continue
                stat[which][1] += max(len(x), len(y))
                out[which].append({
                    'juan': name, 'tag': tag, 'at': i1, 'base': x, 'other': y,
                    'ctx': b[max(0, i1 - 8):i2 + 8]})

    json.dump(out, open(f'{D}/variants.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    for k, label in (('siku', '四庫白文本'), ('zhijie', '三畧直解本')):
        print(label, '異文', len(out[k]), '條 /', stat[k][1],
              '字位；字形・通用', stat[k][0], '處')


if __name__ == '__main__':
    main()
