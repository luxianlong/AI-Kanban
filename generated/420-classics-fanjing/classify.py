# -*- coding: utf-8 -*-
import json, collections
D = json.load(open('diffs.json')); V = set(json.load(open('vpairs.json')))
for p in ['𤣥玄','侯候','茍苟','榖穀','𢎞宏','吿告','専專','貎貌','曽曾','譲讓','濬浚','逹達',
          '釡釜','辨辯','讐仇','㫖旨','徴征','尅克','儁雋','㳟恭','駮駁','塲場','䕶護','賫賚',
          '湣閔','縳縛','冇有']:
    V.add(p); V.add(p[::-1])
glyph = []; real = []; lacuna = []
for d in D:
    a, b = d['sku'], d['base']
    if '□' in a and a.strip('□') == '':
        lacuna.append(d); continue   # 四庫本漫漶，底本有字，不算異文
    if d['tag'] == 'replace' and len(a) == len(b) and all(x == y or (x + y) in V for x, y in zip(a, b)):
        glyph.append(d)
    else:
        real.append(d)
json.dump(real, open('diffs-real.json','w'), ensure_ascii=False, indent=0)
json.dump(glyph, open('diffs-glyph.json','w'), ensure_ascii=False, indent=0)
print('字形差異 %d　四庫本闕字 %d　文字異文 %d' % (len(glyph), len(lacuna), len(real)))
kinds = collections.Counter(d['tag'] for d in real)
print(kinds)
c = collections.Counter()
for d in real:
    if d['tag'] == 'replace' and len(d['sku']) <= 2 and len(d['base']) <= 2: c[d['sku'] + '/' + d['base']] += 1
print(' '.join(f'{k}:{v}' for k, v in c.most_common(40)))
per = collections.Counter(d['n'] for d in real)
print('top篇', sorted(per.items(), key=lambda x: -x[1])[:10])
# character volume of the real variants
print('異文字位', sum(max(len(d['sku']), len(d['base'])) for d in real))
