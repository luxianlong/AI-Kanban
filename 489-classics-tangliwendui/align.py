# -*- coding: utf-8 -*-
"""底本を逐字で四庫全書本と突き合わせ、variants.json を出す。

「字形差異」（德／徳、歴／歷、宻／密、灋／法 の類）は CJKVI の四つの
異體字表で判定して異文に数えない。残りが文字の異文。
表は直接列挙された一対だけを認める（遞移閉包は取らない）——twedu 表には
「上／二」「丈／支」の類があり、union-find で併せると数歩で無関係の字が
同じ類に落ちる。
"""
import json, os, sys
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕“”‘’︰[]　 \n\t'
TABLES = ['cjkvi-variants.txt', 'twedu-variants.txt', 'hydzd-variants.txt',
          'jp-old-style.txt']

# 四表に無い寫工・刻工の書きぶり。別の字ではなく、同じ字の書きよう。
EXTRA = [('玄', '\U00024765'), ('鬬', '鬪'), ('鬬', '鬭'), ('護', '䕶'),
         ('旨', '㫖'), ('蔾', '藜'), ('說', '説'), ('筭', '算'), ('嚻', '囂'),
         ('迴', '廻'), ('爲', '為'), ('德', '徳'), ('遠', '逺'), ('久', '乆'),
         ('陰', '隂'), ('蓋', '葢'), ('斷', '㫁'), ('尋', '㝷'), ('群', '羣'),
         ('眾', '衆'), ('來', '来'), ('奇', '竒'), ('陳', '陣'), ('陳', '陳'),
         ('稟', '禀'), ('卻', '却'), ('豫', '預'), ('隸', '𨽻'), ('發', '𤼵'),
         ('弊', '獘'), ('弊', '𡚁'), ('再', '𠕂'), ('冀', '兾'), ('勅', '勑'),
         ('敕', '勑'), ('刊', '刋'), ('剏', '創'), ('慝', '匿')]

# 三本の間で通用し、別の字とは言えないもの。異文に数えず、別に数える。
TONGYONG = [('於', '于'), ('案', '按'), ('常', '嘗'), ('校', '較'),
            ('繇', '由'), ('闇', '暗'), ('仇', '讐'), ('材', '才'),
            ('偏', '徧'), ('候', '堠'), ('太', '大'), ('蕃', '番'),
            ('爾', '耳'), ('已', '以'), ('修', '脩'), ('無', '无')]

# 異體字表に互見するが、この書では確かに二つの字。併せてはならない。
BLACK = [('計', '討'), ('貴', '責'), ('正', '步'), ('國', '圓'),
         ('謂', '誦'), ('孫', '荀'), ('昭', '召'), ('無', '為'),
         ('平', '安'), ('未', '難'), ('部', '述'), ('前', '師'),
         ('虞', '漢'), ('背', '備'), ('武', '伍'), ('加', '如'),
         ('申', '甲'), ('惡', '患'), ('決', '法'), ('祖', '帝'),
         ('張', '曹'), ('子', '仲'), ('鄧', '曹'), ('帥', '師'),
         ('一', '二'), ('三', '二'), ('百', '千'), ('千', '十'),
         ('聖', '神'), ('矣', '也'), ('哉', '乎'), ('云', '曰'),
         ('拘', '說'), ('臣', '人'), ('固', '故'), ('蓋', '乃'),
         ('詞', '辭'), ('致', '制'), ('所', '為'), ('之', '力'),
         ('吾', '我'), ('兵', '師'), ('乎', '于'), ('武', '子'),
         ('即', '宜'), ('而', '以'), ('為', '以'), ('曾', '嘗'),
         ('內', '納'), ('大', '太'), ('已', '己'), ('已', '巳'),
         ('己', '巳'), ('無', '毋'), ('毋', '母'), ('弗', '不'),
         ('後', '后'), ('知', '智'), ('伏', '服'), ('反', '返'),
         ('止', '正'), ('君', '居'), ('上', '二'), ('丈', '支'),
         ('一', '壹'), ('令', '今'), ('常', '當'), ('再', '冄')]

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


def canon_map():
    """EXTRA と TONGYONG に列擧した對だけで、字を代表の形に寄せる表。

    對校の前にこれを掛けておかないと、「爭／争」のように一字だけ形の違う
    ところで SequenceMatcher が insert と delete に割れ、同じ一字が二條の
    異文に數えられてしまう（寫本・刻本の書きぶりの差は本文の異同ではない）。
    表に載せた對だけを寄せる——CJKVI の大表は遞移閉包を取ると無關係の字まで
    一つに落ちるので、ここでは使わない。
    """
    rep = {}
    for a, b in EXTRA + TONGYONG:
        ra, rb = rep.get(a, a), rep.get(b, b)
        if ra == rb:
            continue
        for k, v in list(rep.items()):
            if v == rb:
                rep[k] = ra
        rep[b] = ra
        rep.setdefault(a, ra)
    return rep


REP = canon_map()


def norm(s):
    return ''.join(REP.get(c, c) for c in s)


def main():
    same = load_classes()
    src = sys.argv[1] if len(sys.argv) > 1 else 'canon.json'
    base = json.load(open(f'{D}/{src}', encoding='utf-8'))
    wyg = json.load(open(f'{D}/wyg.json', encoding='utf-8'))

    out = {'wyg': []}
    stat = {'wyg': [0, 0]}                     # [字形, 異文]

    for j in base:
        name = j['name']
        b = plain(''.join(j['paras']))
        for which, w in (('wyg', wyg[name]['text']),):
            w = plain(w)
            bn, wn = norm(b), norm(w)
            sm = SequenceMatcher(None, bn, wn, autojunk=False)
            for tag, i1, i2, j1, j2 in sm.get_opcodes():
                if tag == 'equal':
                    # 代表の形に寄せた結果として一致した位置。書きぶりの差を
                    # 見失わないよう、ここで元の字を突き合わせて數える。
                    stat[which][0] += sum(1 for cx, cy in zip(b[i1:i2], w[j1:j2])
                                          if cx != cy)
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
                    'ctx': b[max(0, i1 - 10):i2 + 10]})

    json.dump(out, open(f'{D}/variants.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    for k, label in (('wyg', '四庫全書文淵閣本'),):
        print(label, '異文', len(out[k]), '條 /', stat[k][1],
              '字位；字形・通用', stat[k][0], '處')


if __name__ == '__main__':
    main()
