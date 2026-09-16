# -*- coding: utf-8 -*-
"""The 四庫 witness supplies the note boundaries; the base text supplies the
punctuation. Where a sentence stop sat OUTSIDE the base text's own bracket and
the imported boundary pulled it inside, the note ends 「…也。。」 — one stop the
note's own, one the main text's. Push the surplus back out."""
import json, re

P = '。，、；：？！'
T = json.load(open('text.json'))

moved = 0
for r in [T['pre']] + T['pian']:
    for para in r['paras']:
        i = 0
        while i < len(para):
            k, t = para[i]
            m = re.search(r'[%s]+$' % P, t)
            if m and len(m.group(0)) >= 2:
                keep, spill = m.group(0)[0], m.group(0)[1:]
                para[i][1] = t[:m.start()] + keep
                if i + 1 < len(para) and para[i + 1][0] != k:
                    para[i + 1][1] = spill + para[i + 1][1]
                else:
                    para.insert(i + 1, [0 if k else 1, spill])
                moved += len(spill)
            i += 1
        # coalesce again
        out = []
        for k, t in para:
            if out and out[-1][0] == k: out[-1][1] += t
            else: out.append([k, t])
        para[:] = [x for x in out if x[1]]

json.dump(T, open('text.json', 'w'), ensure_ascii=False, indent=1)
print('punctuation characters moved out of notes:', moved)
left = 0
for r in [T['pre']] + T['pian']:
    for p in r['paras']:
        for k, t in p:
            left += len(re.findall(r'[%s]{2,}' % P, t))
print('doubled punctuation left inside a run:', left)

# A second pass for the stops that straddle a boundary: the note ends 「…矣。」 and
# the main text resumes 「。懷帝崩」. Keep one.
T = json.load(open('text.json'))
dropped = 0
for r in [T['pre']] + T['pian']:
    for para in r['paras']:
        for i in range(len(para) - 1):
            a, b = para[i], para[i + 1]
            while a[1] and b[1] and a[1][-1] in P and b[1][0] in P:
                b[1] = b[1][1:]; dropped += 1
        para[:] = [x for x in para if x[1]]

# two spots the base text gets wrong on its own, both visible as a doubled stop
for r in T['pian']:
    for para in r['paras']:
        for i, (k, t) in enumerate(para):
            if '立吳王晏子業業字子業，是為湣帝。，亦為' in t:
                head, rest = t.split('立吳王晏子業業字子業，是為湣帝。，亦為', 1)
                para[i] = [k, head + '立吳王晏子業']
                para.insert(i + 1, [1, '字子業，是為湣帝。'])
                para.insert(i + 2, [k, '，亦為' + rest])
            elif '所備皆急。、巴、漢奇兵' in t:
                para[i] = [k, t.replace('所備皆急。、巴、漢奇兵', '所備皆急。巴、漢奇兵')]
json.dump(T, open('text.json', 'w'), ensure_ascii=False, indent=1)
print('duplicate stops dropped across boundaries:', dropped)
