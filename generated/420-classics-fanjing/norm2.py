# -*- coding: utf-8 -*-
import json, re

RARE = json.load(open('rare.json')) if False else {
 '[目交]':'𥅟','[目聶]':'𥍉','[目亟]':'𥈂','[目至]':'眰','[目蠻]':'䂅','[目延]':'䀽',
 '[目占]':'䀡','[目兼]':'䁠','[目秦]':'𥉜','[目夾]':'䀹','[玉民]':'珉','[手勾]':'抅',
 '[日江]':'𭥺','[足句]':'跔','[木百]':'栢','[女靡]':'孊','[人由]':'伷','[骨包]':'骲',
 '[舟冓]':'𦩷','[舟鹿]':'𦪇','[王延]':'𤥻','[水應]':'㶐','[羊歷]':'䍽','[日空]':'𣈞',
 '[水垂]':'涶','[祭以言易示]':'𧫕','[絅以言易糸]':'詗','[阜耆]':'隋',
 '[口賀]':'⿰口賀','[目效]':'⿰目效','[脩以矛易心]':'〈脩以矛易心〉',
}
FIX = json.load(open('glyphfix.json'))
B = json.load(open('book.json'))
by = {c['title']: c for c in B}

# ---- editorial repairs to the base text's note markers (all recorded in 校例) ----
p = by['七雄略十八']['paras']
assert '百姓道死。越臣聞' in p[1]
p[1] = p[1].replace('百姓道死。越臣聞', '百姓道死。\x02臣聞', 1)

p = by['君德第九']['paras']
assert p[26].startswith('《武帝贊》曰')
p[26] = '\x01' + p[26]

p = by['是非十四']['paras']
assert p[2].endswith('\x02')
p[2] = p[2][:-1]

p = by['懼戒二十']['paras']
assert p[14].endswith(')')
p[14] = p[14][:-1]

def fixglyph(s): return ''.join(FIX.get(c, c) for c in s)

def norm(s):
    for k, v in RARE.items(): s = s.replace(k, v)
    s = fixglyph(s)
    s = s.replace('“','「').replace('”','」').replace('‘','『').replace('’','』')
    s = s.replace('(','\x01').replace('（','\x01').replace(')','\x02').replace('）','\x02')
    return re.sub(r'\s+', '', s)

LABELS = {'是曰','非曰'}

OUT = {'pre': None, 'pian': []}
for ci, c in enumerate(B):
    depth = 0
    paras = []
    for src in c['paras']:
        s = norm(src)
        out = []; buf = []
        for ch in s:
            if ch == '\x01':
                if depth == 0:
                    if buf: out.append([0, ''.join(buf)]); buf = []
                else: buf.append('〔')
                depth += 1
            elif ch == '\x02':
                depth -= 1
                if depth == 0:
                    if buf: out.append([1, ''.join(buf)]); buf = []
                elif depth > 0: buf.append('〕')
                else: raise SystemExit('unbalanced in ' + c['title'])
            else: buf.append(ch)
        if buf: out.append([1 if depth else 0, ''.join(buf)])
        out = [r for r in out if r[1]]
        for r in out:
            if r[0] == 1 and r[1] in LABELS: r[0] = 2   # 是曰／非曰 structural label
        if out: paras.append(out)
    if depth: raise SystemExit('open at end of ' + c['title'])
    rec = {'title': fixglyph(c['title']), 'paras': paras}
    if ci == 0: OUT['pre'] = rec
    else: rec['n'] = ci; OUT['pian'].append(rec)

json.dump(OUT, open('text.json','w'), ensure_ascii=False, indent=1)
recs = [OUT['pre']] + OUT['pian']
def cc(k): return sum(len(t) for r in recs for p in r['paras'] for kk,t in p if kk==k)
print('篇', len(OUT['pian']), '段', sum(len(r['paras']) for r in recs))
print('正文', cc(0), '趙蕤注', cc(1), '是非標目', cc(2), '合', cc(0)+cc(1)+cc(2))
all_t = ''.join(t for r in recs for p in r['paras'] for k,t in p)
for a,b in [('「','」'),('『','』'),('《','》'),('〔','〕')]:
    print(a, b, all_t.count(a), all_t.count(b))
