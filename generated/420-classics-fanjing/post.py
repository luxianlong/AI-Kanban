# -*- coding: utf-8 -*-
"""Everything that happens to the text after the 四庫 kinds are imported and the
five missing passages are supplied."""
import json, re

T = json.load(open('text.json'))
P = {r['n']: r for r in T['pian']}
recs = [T['pre']] + T['pian']

# 1. 《是非》's 「是曰」「非曰」 are the book's own headings, not notes (the 四庫本
#    cuts them in the small type it uses for notes, which is why they arrive as
#    kind 1). Give them a kind of their own.
n_lbl = 0
for para in P[14]['paras']:
    if para and para[0][1][:2] in ('是曰', '非曰'):
        lbl, rest = para[0][1][:2], para[0][1][2:]
        para[0] = [2, lbl]
        if rest: para.insert(1, [0, rest])
        n_lbl += 1

# 2. The base text writes 「雲」 for 「云」 wherever the word is the verb "to say".
FIX_YUN = ['不雲乎', '其說雲何', '周雲成康', '真子輿雲', '而雲無名', '雲并州近',
           '嶠常雲錢', '聲雲堅敗', '皆雲南軍至', '其鄰人亦雲', '豈雲易哉']
# 3. Simplified forms that survived the Big5 gate because the glyph also exists
#    in Big5 as a different character, plus two one-off slips.
FIX_GLYPH = {'体': '體', '胜': '勝', '党': '黨', '圣': '聖', '与': '與', '万': '萬',
             '扰': '擾', '庄': '莊', '怀': '懷', '异': '異'}
n_yun = n_gl = 0
for r in recs:
    for para in r['paras']:
        for run in para:
            t = run[1]
            for f in FIX_YUN:
                if f in t: t = t.replace(f, f.replace('雲', '云')); n_yun += 1
            t2 = re.sub(r'雲(?=[：「『])', '云', t)
            n_yun += sum(1 for a, b in zip(t, t2) if a != b)
            t = t2
            t3 = ''.join(FIX_GLYPH.get(c, c) for c in t)
            n_gl += sum(1 for a, b in zip(t, t3) if a != b)
            run[1] = t3.replace('好听世俗', '好聽世俗').replace('么麼', '幺麼')

# 4. The imported note boundary swallows the stop that stood OUTSIDE the base
#    text's own bracket, so a note ends 「…也。。」. Push the surplus back out.
PUNCT = '。，、；：？！'
n_mv = 0
for r in recs:
    for para in r['paras']:
        i = 0
        while i < len(para):
            k, t = para[i]
            m = re.search(r'[%s]+$' % PUNCT, t)
            if m and len(m.group(0)) >= 2:
                keep, spill = m.group(0)[0], m.group(0)[1:]
                para[i][1] = t[:m.start()] + keep
                if i + 1 < len(para) and para[i + 1][0] != k:
                    para[i + 1][1] = spill + para[i + 1][1]
                else:
                    para.insert(i + 1, [0 if k else 1, spill])
                n_mv += len(spill)
            i += 1

# 4b. A dozen more sit in the middle of a run, where there is nowhere to move
#     them to; reduce them to one.
n_col = 0
for r in recs:
    for para in r['paras']:
        for run in para:
            t2 = re.sub(r'([%s])[%s]+' % (PUNCT, PUNCT), r'\1', run[1])
            n_col += len(run[1]) - len(t2)
            run[1] = t2

# 5. Two places the base text is wrong on its own, both showing as a doubled stop.
for r in T['pian']:
    for para in r['paras']:
        for i, (k, t) in enumerate(list(para)):
            if '立吳王晏子業業字子業，是為湣帝。，亦為' in t:
                head, rest = t.split('立吳王晏子業業字子業，是為湣帝。，亦為', 1)
                para[i] = [k, head + '立吳王晏子業']
                para.insert(i + 1, [1, '字子業，是為湣帝。'])
                para.insert(i + 2, [k, '，亦為' + rest])
                break
            if '所備皆急。、巴、漢奇兵' in t:
                para[i] = [k, t.replace('所備皆急。、巴、漢奇兵', '所備皆急。巴、漢奇兵')]

# coalesce and drop empties
for r in recs:
    for para in r['paras']:
        out = []
        for k, t in para:
            if not t: continue
            if out and out[-1][0] == k: out[-1][1] += t
            else: out.append([k, t])
        para[:] = out
    r['paras'] = [p for p in r['paras'] if p]

json.dump(T, open('text.json', 'w'), ensure_ascii=False, indent=1)
def cc(k): return sum(len(t) for r in recs for p in r['paras'] for kk, t in p if kk == k)
print('是非標目 %d　云/雲 %d　字形 %d　標點移出 %d　合併 %d' % (n_lbl, n_yun, n_gl, n_mv, n_col))
print('段 %d　正文 %d　趙蕤注 %d　標目 %d　合 %d' %
      (sum(len(r['paras']) for r in recs), cc(0), cc(1), cc(2), cc(0) + cc(1) + cc(2)))
left = sum(len(re.findall(r'[%s]{2,}' % PUNCT, t)) for r in recs for p in r['paras'] for k, t in p)
print('一個 run 裡的連續標點：', left)
