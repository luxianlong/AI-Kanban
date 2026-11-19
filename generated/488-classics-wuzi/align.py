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
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕“”‘’︰[]　 \n\t'
TABLES = ['cjkvi-variants.txt', 'twedu-variants.txt', 'hydzd-variants.txt',
          'jp-old-style.txt']

# 四表に無い寫工・刻工の書きぶり。別の字ではなく、同じ字の書きよう。
EXTRA = [('吳', '吴'), ('吳', '呉'), ('吴', '呉'), ('將', '将'), ('寇', '冦'),
         ('讓', '譲'), ('爭', '争'), ('涉', '渉'), ('銜', '㗸'), ('冑', '胄'),
         ('玄', '\U00024765'), ('騷', '騒'), ('茲', '兹'), ('茲', '玆'),
         ('久', '乆'), ('陰', '隂'), ('遠', '逺'), ('為', '爲'), ('悅', '恱'),
         ('莊', '荘'), ('群', '羣'), ('歲', '歳'), ('隱', '隠'), ('德', '徳'),
         ('搏', '𢫦'), ('陷', '䧟'), ('鬥', '鬬'), ('鬥', '𨷖'), ('鬥', '鬦'),
         ('乘', '乗'), ('溫', '温'), ('涼', '凉'), ('戟', '㦸'), ('狸', '貍'),
         ('己', '已'), ('己', '巳'), ('已', '巳'), ('卻', '却'), ('賁', '貪'),
         ('決', '决'), ('壞', '壊'), ('弩', '弩'), ('慚', '慙'), ('嘗', '甞'),
         ('饗', '享'), ('殘', '残'), ('燒', '焼'), ('鼙', '鞞'), ('獲', '\U0002B266'), ('被', '\U0002C4B3')]

# 三本の間で通用し、別の字とは言えないもの。異文に数えず、別に数える。
TONGYONG = [('於', '于'), ('凌', '陵'), ('閒', '閑'), ('逸', '佚'), ('爾', '耳'),
            ('繆', '穆'), ('祅', '妖'), ('匈', '洶'), ('說', '悅'), ('修', '脩'),
            ('陳', '陣'), ('無', '无')]

# 異體字表に互見するが、この書では確かに二つの字。併せてはならない。
BLACK = [('上', '二'), ('丈', '支'), ('一', '壹'), ('無', '毋'), ('毋', '母'),
         ('弗', '不'), ('已', '以'), ('後', '后'), ('知', '智'), ('伏', '服'),
         ('內', '納'), ('大', '太'), ('偏', '徧'), ('徧', '遍'), ('背', '悖'),
         ('反', '返'), ('止', '正'), ('丈', '大'), ('君', '居'), ('冰', '木'),
         ('剖', '刋'), ('進', '盡'), ('取', '斬'), ('民', '氏'), ('未', '大'),
         ('險', '隂'), ('易', '陽'), ('審', '令'), ('能', '制'), ('達', '遠'),
         ('乎', '寡'), ('常', '當'), ('廄', '燒'), ('罷', '退'), ('進', '對'),
         ('用', '進'), ('一', '二'), ('卒', '年'), ('千', '干'), ('知', '之'),
         ('利', '力'), ('恐', '忌'), ('敵', '馳'), ('功', '易'), ('熒', '營'),
         ('立', '止'), ('令', '今'), ('政', '治'), ('車', '軍'), ('羊', '馬'),
         ('布', '施'), ('歸', '走'), ('數', '服'), ('矣', '也'), ('與', '以'),
         ('使', '者'), ('已', '先'), ('無', '勿'), ('而', '爾')]

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
    sbck = json.load(open(f'{D}/sbck.json', encoding='utf-8'))
    wyg = json.load(open(f'{D}/wyg.json', encoding='utf-8'))

    out = {'sbck': [], 'wyg': []}
    stat = {'sbck': [0, 0], 'wyg': [0, 0]}     # [字形, 異文]

    for j in base:
        name = j['name']
        b = plain(''.join(j['paras']))
        for which, w in (('sbck', sbck[name]['text']), ('wyg', wyg[name]['text'])):
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
