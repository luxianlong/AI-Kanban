# -*- coding: utf-8 -*-
"""Collate the punctuated 維基文庫 base text against the 四庫全書本 witness.
Two jobs: (1) import the 四庫本's note boundaries, which the base text marks
unevenly; (2) list the 異文."""
import json, re, subprocess, difflib, sys, unicodedata

SKU  = json.load(open('sku.json'))
T    = json.load(open('text.json'))
PT   = {int(k): v for k, v in json.load(open('pian-titles.json')).items()}
PIAN = {r['n']: r for r in T['pian']}

PUNCT = set('，。、；：？！「」『』《》（）〔〕—…·　 \n\r\t')

def chars_of(runs, drop_punct):
    out = []
    for kind, txt in runs:
        for ch in txt:
            if ch in PUNCT or ch.isspace():
                if drop_punct: continue
            out.append((ch, kind))
    return out

# --- comparison key: fold old glyph forms so 說/説, 德/徳 etc. compare equal ---
allchars = set()
for n in range(1, 65):
    for k, t in SKU[PT[n]]: allchars.update(t)
    for p in PIAN[n]['paras']:
        for k, t in p: allchars.update(t)
for k, t in ([(0, ''.join(t for p in T['pre']['paras'] for kk, t in p))]): allchars.update(t)
cl = sorted(c for c in allchars if '㐀' <= c <= '鿿' or c > '￿')
open('allchars.txt', 'w').write('\n'.join(cl))
KEY = json.loads(subprocess.check_output(['node', '-e', '''
const fs=require('fs'),O=require('opencc-js');
const t2s=O.Converter({from:'t',to:'cn'});
const out={};
for(const ch of fs.readFileSync('allchars.txt','utf8').split('\\n')) if(ch) out[ch]=t2s(ch);
process.stdout.write(JSON.stringify(out));
''']).decode())
EXTRA = {   # variants OpenCC leaves alone but which are the same character here
 '䇿':'策','乗':'乘','甞':'尝','寔':'实','凖':'准','䛕':'谀','䝉':'蒙','恱':'悦','粤':'越',
 '柰':'奈','曵':'曳','冩':'写','徃':'往','㧞':'拔','刼':'劫','刧':'劫','㨗':'捷','歴':'历',
 '歬':'前','踈':'疏','疎':'疏','虖':'呼','竒':'奇','㢲':'巽','冨':'富','㝡':'最','隂':'阴',
 '揔':'总','緫':'总','揽':'览','攬':'览','擥':'览','㮣':'概','葢':'盖','盖':'盖','厯':'历',
 '徳':'德','悳':'德','噐':'器','冝':'宜','冦':'寇','冢':'冢','凟':'渎','刅':'刃','刦':'劫',
 '劒':'剑','劔':'剑','厺':'去','吿':'告','囬':'回','囯':'国','圗':'图','増':'增','壱':'壹',
 '姧':'奸','姦':'奸','嵗':'岁','巻':'卷','幷':'并','弃':'弃','怱':'匆','恵':'惠','悞':'误',
 '愽':'博','懴':'忏','戞':'戛','抝':'拗','拏':'拿','敺':'驱','斈':'学','旣':'既','昬':'昏',
 '晉':'晋','晋':'晋','朶':'朵','栁':'柳','棃':'梨','楽':'乐','榦':'干','檝':'楫','歎':'叹',
 '殺':'杀','毉':'医','沍':'冱','泝':'溯','洩':'泄','渉':'涉','溌':'泼','潜':'潜','灋':'法',
 '灾':'灾','焔':'焰','煇':'辉','爲':'为','犲':'豺','珎':'珍','瑯':'琅','畆':'亩','畧':'略',
 '疉':'叠','痩':'瘦','皐':'皋','臯':'皋','盗':'盗','眞':'真','睂':'眉','矦':'侯','砕':'碎',
 '祕':'秘','禆':'裨','秊':'年','稭':'秸','竒':'奇','筞':'策','粧':'妆','絶':'绝','綂':'统',
 '緜':'绵','纎':'纤','纔':'才','罸':'罚','羣':'群','聦':'聪','聰':'聪','聴':'听','肬':'疣',
 '脩':'修','臈':'腊','舉':'举','莭':'节','萬':'万','葠':'参','蔵':'藏','虗':'虚','虜':'虏',
 '蚤':'早','蠧':'蠹','衆':'众','裏':'里','觧':'解','託':'托','踁':'胫','躰':'体','軰':'辈',
 '輙':'辄','逺':'远','遯':'遁','鄉':'乡','醻':'酬','鈎':'钩','鍳':'鉴','鑑':'鉴','鑒':'鉴',
 '閒':'间','闗':'关','闘':'斗','隷':'隶','靣':'面','靈':'灵','韵':'韵','顔':'颜','餝':'饰',
 '駈':'驱','髙':'高','鬦':'斗','鬬':'斗','鮮':'鲜','麤':'粗','黙':'默','齊':'齐','龍':'龙',
 '㽔':'蕤','偹':'备','俻':'备','㑹':'会','㕘':'参','㣲':'微','㨿':'据','䟽':'疏','㳂':'沿',
 '揔':'总','冑':'胄','胄':'胄','厠':'厕','刺':'刺','剏':'创','剙':'创','刱':'创',
}
def key(s):
    return ''.join(EXTRA.get(c, KEY.get(c, c)) for c in s)

def align_one(n):
    a = chars_of(SKU[PT[n]], True)
    b = chars_of([r for p in PIAN[n]['paras'] for r in p], False)
    bi = [i for i, (c, k) in enumerate(b) if c not in PUNCT and not c.isspace()]
    ka = key(''.join(c for c, k in a))
    kb = key(''.join(b[i][0] for i in bi))
    sm = difflib.SequenceMatcher(None, ka, kb, autojunk=False)
    kinds = [None] * len(b)
    diffs = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == 'equal':
            for off in range(i2 - i1):
                kinds[bi[j1 + off]] = a[i1 + off][1]
        else:
            ctx_b = ''.join(b[bi[j]][0] for j in range(j1, j2))
            ctx_a = ''.join(a[i][0] for i in range(i1, i2))
            if ctx_a or ctx_b:
                lead = ''.join(b[bi[j]][0] for j in range(max(0, j1 - 8), j1))
                diffs.append({'n': n, 'tag': tag, 'sku': ctx_a, 'base': ctx_b, 'lead': lead})
    # unmatched base characters inherit the run they sit in
    last = 0
    for i in range(len(b)):
        if kinds[i] is None: kinds[i] = last
        else: last = kinds[i]
    return b, kinds, diffs, sm.ratio()

if __name__ == '__main__':
    out = {}; alldiffs = []; tot = 0
    for n in range(1, 65):
        b, kinds, diffs, ratio = align_one(n)
        # rebuild paragraphs with the imported kinds
        paras = []; pos = 0
        for p in PIAN[n]['paras']:
            ln = sum(len(t) for k, t in p)
            seg = [(b[pos + i][0], kinds[pos + i]) for i in range(ln)]
            pos += ln
            runs = []
            for ch, k in seg:
                if runs and runs[-1][0] == k: runs[-1][1].append(ch)
                else: runs.append([k, [ch]])
            paras.append([[k, ''.join(cs)] for k, cs in runs])
        out[n] = paras
        alldiffs += diffs
        tot += len(diffs)
        print('%-12s ratio %.4f  異文 %d' % (PT[n], ratio, len(diffs)), file=sys.stderr)
    json.dump(out, open('kinds.json', 'w'), ensure_ascii=False)
    json.dump(alldiffs, open('diffs.json', 'w'), ensure_ascii=False, indent=0)
    print('TOTAL 異文', tot, file=sys.stderr)
