# -*- coding: utf-8 -*-
"""Re-cut 卷四霸圖 / 卷五七雄略, which the punctuated base text has spliced into
one page, and hand both back as separate 篇."""
import json, re

T = json.load(open('text.json'))
by = {r['title']: r for r in T['pian']}
q = by['七雄略十八']['paras']

def flat(p): return ''.join(t for k, t in p)

# --- the splice points, read off the 四庫 witness --------------------------
p1 = q[1]
assert p1[0][0] == 1 and p1[0][1].endswith('百姓道死。'), p1[0][1][-20:]
note_head = p1[0][1]                      # 吳王問伍胥…百姓道死。  (卷四)
rest1 = p1[1:]                            # 臣聞：天下大器也…      (卷五 starts)

p74 = q[74]
s74 = flat(p74)
cut = s74.index('又蒸粟種遺吳王')
# paragraph 74 is one plain run in the base text
assert len(p74) == 1, p74
tail74 = [[p74[0][0], s74[:cut]]]
note_tail = s74[cut:]                     # 又蒸粟種遺吳王…天子之令不行也。(卷四)

qixiong = [rest1] + q[2:74] + [tail74]
badu    = [q[0], [[1, note_head + note_tail]]] + q[75:]

by['霸圖十七']['paras'] = badu
by['七雄略十八']['paras'] = qixiong
json.dump(T, open('text.json', 'w'), ensure_ascii=False, indent=1)

def cc(r, k): return sum(len(t) for p in r['paras'] for kk, t in p if kk == k)
for name in ('霸圖十七', '七雄略十八'):
    r = by[name]
    print(name, '段', len(r['paras']), '正文', cc(r, 0), '注', cc(r, 1))
