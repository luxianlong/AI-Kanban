# -*- coding: utf-8 -*-
"""Direct 異體字 pairs (no transitive closure) from the Taiwan MOE 異體字字典
and the 漢語大字典 table. Used only to tell a glyph variant apart from a real
textual variant when the two witnesses disagree."""
import json
pairs = set()
for fn, keep in (('twedu-variants.txt', True), ('hydzd-variants.txt', True), ('cjkvi-variants.txt', True), ('koseki-variants.txt', True), ('jp-old-style.txt', True), ('joyo-variants.txt', True), ('jisx0212-variants.txt', True), ('jisx0213-variants.txt', True), ('dypytz-variants.txt', True)):
    for line in open(fn, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line or line.startswith('#'): continue
        f = line.split(',')
        if len(f) < 3 or f[1].startswith('<'): continue
        a, b = f[0], f[2]
        if len(a) == 1 and len(b) == 1:
            pairs.add(a + b); pairs.add(b + a)
json.dump(sorted(pairs), open('vpairs.json', 'w'), ensure_ascii=False)
print('direct variant pairs', len(pairs) // 2)
for p in ['𤣥玄','宻密','懐懷','𢎞弘','乆久','寛寬','隠隱','歩步','呉吳','已己','侯羽','榖穀','榖谷','吾我','為謂','伯霸']:
    print(p, p in pairs)
