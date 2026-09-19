# -*- coding: utf-8 -*-
"""底本を逐字で通校二本と突き合わせ、variants.json を出す。

「字形差異」（德／徳、歴／歷、宻／密、灋／法 の類）は CJKVI の四つの
異體字表で判定して異文に数えない。残りが文字の異文。
表は直接列挙された一対だけを認める（遞移閉包は取らない）——twedu 表には
「上／二」「丈／支」の類があり、union-find で併せると数歩で無関係の字が
同じ類に落ちる。
"""
import json, os, sys
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕“”‘’[]　 \n\t'
TABLES = ['cjkvi-variants.txt', 'twedu-variants.txt', 'hydzd-variants.txt',
          'jp-old-style.txt']

# 四表に無い寫工・刻工の書きぶり。別の字ではなく、同じ字の書きよう。
EXTRA = [('灋', '法'), ('皷', '鼓'), ('鼔', '鼓'), ('凢', '凡'),
         ('𦞃', '膝'), ('冡', '冢'), ('宻', '密'), ('㐫', '凶'),
         ('𣪚', '散'), ('𫉬', '獲'), ('嵬', '蒐'), ('決', '决'),
         ('𨷖', '鬬'), ('鬦', '鬬'), ('䘮', '喪'), ('偏', '徧'),
         ('祗', '祇'), ('圯', '圮'), ('乗', '乘'), ('羙', '美'),
         ('慊', '謙'), ('叅', '參')]

# 三本の間で通用し、別の字とは言えないもの。異文に数えず、別に数える。
TONGYONG = [('於', '于'), ('無', '无'), ('舍', '舎')]

# 異體字表に互見するが、この書では確かに二つの字。併せてはならない。
BLACK = [('上', '二'), ('丈', '支'), ('一', '壹'), ('無', '毋'), ('毋', '母'),
         ('弗', '不'), ('已', '以'), ('已', '己'), ('後', '后'), ('修', '脩'),
         ('知', '智'), ('伏', '服'), ('內', '納'), ('大', '太'), ('說', '悅'),
         ('偏', '徧'), ('徧', '遍'), ('背', '悖'), ('聚', '相'), ('慊', '謙'),
         ('圯', '沛'), ('止', '正'), ('反', '返'), ('懼', '懾'), ('鼓', '皷'),
         ('仁', '義'), ('身', '方'), ('治', '謂'), ('中', '仁'), ('去', '止'),
         ('眚', '省'), ('社', '杜'), ('緩', '綏'), ('民', '兵'), ('至', '治'),
         ('陷', '誘'), ('狩', '守'), ('路', '不')]


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
    src = sys.argv[1] if len(sys.argv) > 1 else 'canon.json'
    base = json.load(open(f'{D}/{src}', encoding='utf-8'))
    sbck = json.load(open(f'{D}/sbck.json', encoding='utf-8'))
    wyg = json.load(open(f'{D}/wyg.json', encoding='utf-8'))

    out = {'sbck': [], 'wyg': []}
    stat = {'sbck': [0, 0], 'wyg': [0, 0]}     # [字形, 異文]

    for j in base:
        name = j['name']
        b = plain(''.join(j['paras']))
        for which, w in (('sbck', sbck[name]['text']), ('wyg', wyg[name]['text'])):
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
                            'pian': name, 'tag': tag, 'at': i1 + k,
                            'base': cx, 'other': cy,
                            'ctx': b[max(0, i1 + k - 8):i1 + k + 9]})
                    continue
                stat[which][1] += max(len(x), len(y))
                out[which].append({
                    'pian': name, 'tag': tag, 'at': i1, 'base': x, 'other': y,
                    'ctx': b[max(0, i1 - 10):i2 + 10]})

    json.dump(out, open(f'{D}/variants.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    for k, label in (('sbck', '四部叢刊影宋鈔本'), ('wyg', '四庫全書本')):
        print(label, '異文', len(out[k]), '條 /', stat[k][1],
              '字位；字形・通用', stat[k][0], '處')


if __name__ == '__main__':
    main()
