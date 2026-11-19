# -*- coding: utf-8 -*-
"""底本を四庫全書文淵閣本（元　戈直　集論）と逐字で突き合わせ、collate.json を出す。

字形の異なり（德／徳、高／髙、遠／逺、內／内 の類）は CJKVI の四つの異體字表で
判定して異文に數えない。表は直に竝べられた一對だけを認める（遞移閉包は取らない
——臺灣教育部の表には「上／二」「丈／支」の類があり、併せてゆくと數歩で無關係の
字が同じ類に落ちる）。#487 の線をそのまま踏む。

この書に特有の事情が一つある。戈直本は唐の避諱字をそのまま殘す——
「治」を「理」に、「民」を「人」に、「世」を「代」に作るのがそれで、
太宗の諱（世民）と高宗の諱（治）を避けたもの。底本はこれを本字に復している。
これは字形でも異文でもなく**避諱**なので、別に數えて別に列べる。
"""
import json, os, sys
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = '，。、；：？！「」『』（）《》〈〉…—·．〔〕【】“”‘’︰,. 　\n\t:'
TABLES = ['cjkvi-variants.txt', 'twedu-variants.txt', 'hydzd-variants.txt',
          'jp-old-style.txt']

# 四表に無い、寫工の書きぶり。別の字ではなく、同じ字の書きよう。
EXTRA = [('護', '䕶'), ('譖', '譛'), ('玉', '玊'), ('曾', '曽'), ('拔', '㧞'),
         ('專', '専'), ('達', '逹'), ('暴', '𭧂'), ('泰', '㤗'), ('幾', '㡬'),
         ('裕', '𥙿'), ('瓜', '𤓰'), ('事', '亊'), ('兇', '㐫'), ('聊', '𦕅'),
         ('侯', '𠉀'), ('榷', '㩁'), ('斫', '斵'), ('系', '繋'), ('讬', '託'),
         ('癧', '癘'), ('颎', '熲'), ('己', '已'), ('眀', '明'), ('䇿', '策'), ('㫖', '旨'), ('𡚁', '弊'), ('䘮', '喪'),
         ('乆', '久'), ('觧', '解'), ('靣', '面'), ('尅', '克'), ('踈', '疏'),
         ('聼', '聽'), ('凢', '凡'), ('𤣥', '玄'), ('𢎞', '弘'), ('𨽻', '隸'),
         ('𫝊', '傳'), ('𩔖', '類'), ('𦵏', '葬'), ('噐', '器'), ('䝉', '蒙'),
         ('刼', '劫'), ('盖', '蓋'), ('葢', '蓋'), ('冝', '宜'), ('𠫵', '參'),
         ('叅', '參'), ('㕘', '參'), ('冐', '冒'), ('虖', '乎'), ('厯', '歷'),
         ('厰', '廠'), ('冨', '富'), ('邉', '邊'), ('邊', '邉'), ('闗', '關'),
         ('敎', '教'), ('隂', '陰'), ('陽', '陽'), ('歘', '欻'), ('䏻', '能')]

# 唐諱。戈直本が殘し、底本が本字に復したもの。異文ではない。
TABOO = [('治', '理'), ('民', '人'), ('世', '代'), ('世', '世'), ('葉', '世')]

# 異體字表に互見するが、この書では確かに二つの字。併せてはならない。
BLACK = [ ('已', '以'), ('己', '以'), ('後', '后'), ('修', '脩'),
         ('知', '智'), ('大', '太'), ('說', '悅'), ('反', '返'), ('止', '正'),
         ('木', '本'), ('無', '毋'), ('徵', '微'), ('辟', '闢'), ('雕', '凋'),
         ('異', '易'), ('弟', '第'), ('少', '小'), ('內', '納'), ('見', '現'),
         ('被', '披'), ('陳', '陣'), ('適', '謫'), ('禽', '擒'), ('孰', '熟'),
         ('由', '猶'), ('尚', '上'), ('女', '汝'), ('說', '說'), ('雅', '疋')]

# 三本の間で通用し、別の字とは言えないもの。異文に數えず、別に數える。
TONGYONG = [('於', '于'), ('無', '无'), ('並', '并'), ('迺', '乃'), ('嘆', '歎'),
            ('姦', '奸'), ('茲', '兹'), ('御', '禦'), ('傭', '庸'), ('以', '已'),
            ('後', '后'), ('雕', '凋'), ('栗', '慄'), ('湣', '愍'), ('倘', '儻'),
            ('弘', '宏'), ('只', '祇'), ('只', '秪'), ('辟', '闢'), ('鄉', '嚮'),
            ('會', '曾'), ('讚', '贊'), ('穫', '獲'), ('仇', '讐'), ('玩', '翫'),
            ('彩', '綵'), ('暗', '闇'), ('途', '塗'), ('置', '寘'), ('常', '嘗'),
            ('胄', '冑'), ('謅', '謟'), ('猜', '狷'), ('剪', '翦')]


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
    taboo = set()
    for a, b in TABOO:
        taboo.add((a, b)); taboo.add((b, a))
    return (lambda a, b: a == b or (a, b) in pairs), (lambda a, b: (a, b) in taboo)


def plain(s):
    return ''.join(c for c in s if c not in PUNCT)


def main():
    same, is_taboo = load_classes()
    src = sys.argv[1] if len(sys.argv) > 1 else 'base.json'
    B = json.load(open(os.path.join(D, src), encoding='utf-8'))
    W = {p['n']: p for p in json.load(open(os.path.join(D, 'skqs.json'),
                                           encoding='utf-8'))['pian']}
    out, nform, npos, nhui = [], 0, 0, 0
    for q in B['pian']:
        b = plain(''.join(q['paras']))
        w = W[q['n']]['text']
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
                    if is_taboo(cx, cy):
                        nhui += 1
                        continue
                    npos += 1
                    out.append({'pian': q['title'], 'n': q['n'], 'at': i1 + k,
                                'base': cx, 'sk': cy,
                                'ctx': b[max(0, i1 + k - 10):i1 + k + 11]})
                continue
            npos += max(len(x), len(y))
            out.append({'pian': q['title'], 'n': q['n'], 'at': i1,
                        'base': x, 'sk': y,
                        'ctx': b[max(0, i1 - 12):i2 + 12]})

    json.dump(out, open(os.path.join(D, 'collate.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('底本 %d 字 / 四庫本 %d 字。異文 %d 條・%d 字位、'
          '字形と通用 %d 處、唐諱 %d 處'
          % (sum(len(plain(''.join(q['paras']))) for q in B['pian']),
             sum(len(W[q['n']]['text']) for q in B['pian']),
             len(out), npos, nform, nhui))
    if '-v' in sys.argv:
        for r in out:
            print('  %-10s 底「%s」四庫「%s」　%s'
                  % (r['pian'], r['base'], r['sk'], r['ctx']))


if __name__ == '__main__':
    main()
