# -*- coding: utf-8 -*-
import json, re, subprocess, sys

RARE = {
 '[目交]':'𥅟','[目聶]':'𥍉','[目亟]':'𥈂','[目至]':'眰','[目蠻]':'䂅','[目延]':'䀽',
 '[目占]':'䀡','[目兼]':'䁠','[目秦]':'𥉜','[目夾]':'䀹','[玉民]':'珉','[手勾]':'抅',
 '[日江]':'𭥺','[足句]':'跔','[木百]':'栢','[女靡]':'孊','[人由]':'伷','[骨包]':'骲',
 '[舟冓]':'𦩷','[舟鹿]':'𦪇','[王延]':'𤥻','[水應]':'㶐','[羊歷]':'䍽','[日空]':'𣈞',
 '[水垂]':'涶','[祭以言易示]':'𧫕','[絅以言易糸]':'詗',
 '[阜耆]':'隋',          # 底本方括號描述，Unicode 無字；依底本自注「音隨，訓虧也」與《淮南子》通行本作「隋」
 '[口賀]':'⿰口賀','[目效]':'⿰目效',   # Unicode 無字，改用表意文字描述符
 '[脩以矛易心]':'〈脩以矛易心〉',        # 底本以「以某易某」描述，無從還原，仍其舊
}

B = json.load(open('book.json'))

# ---- 1. simplified / old glyph -> common traditional (char-wise, Big5-gated) ----
raw = json.dumps(B, ensure_ascii=False)
chars = sorted(set(re.findall(r'[一-鿿]', raw)))
open('chars.txt','w').write('\n'.join(chars))
conv = json.loads(subprocess.check_output(['node','-e','''
const fs=require('fs'),O=require('opencc-js');
const s2t=O.Converter({from:'cn',to:'tw'});
const out={};
for(const ch of fs.readFileSync('chars.txt','utf8').split('\\n')){ if(ch) out[ch]=s2t(ch); }
process.stdout.write(JSON.stringify(out));
''']).decode())
FIX = {}
for ch, tr in conv.items():
    if ch == tr: continue
    try:
        ch.encode('big5')          # valid in traditional orthography -> leave alone
    except Exception:
        FIX[ch] = tr
json.dump(FIX, open('glyphfix.json','w'), ensure_ascii=False, indent=0)

def fixglyph(s):
    return ''.join(FIX.get(c, c) for c in s)

# ---- 2. per-paragraph normalisation ----
def norm(s):
    for k, v in RARE.items(): s = s.replace(k, v)
    s = fixglyph(s)
    s = s.replace('“','「').replace('”','」').replace('‘','『').replace('’','』')
    s = s.replace('(','\x01').replace(')','\x02').replace('（','\x01').replace('）','\x02')
    s = re.sub(r'\s+','', s)
    return s

def runs(s):
    """split into [(kind, text)] where kind 0=正文 1=趙蕤注"""
    out=[]; buf=[]; depth=0
    for ch in s:
        if ch=='\x01':
            if depth==0:
                if buf: out.append((0,''.join(buf))); buf=[]
            else: buf.append('〔')
            depth+=1
        elif ch=='\x02':
            depth-=1
            if depth==0:
                if buf: out.append((1,''.join(buf))); buf=[]
            elif depth>0: buf.append('〕')
            else: depth=0; buf.append('〕')
        else: buf.append(ch)
    if buf: out.append((depth and 1 or 0, ''.join(buf)))
    return [(k,t) for k,t in out if t]

OUT={'pre':None,'pian':[]}
for i,c in enumerate(B):
    ps=[runs(norm(p)) for p in c['paras']]
    ps=[p for p in ps if p]
    rec={'title': fixglyph(c['title']), 'paras': ps}
    if i==0: OUT['pre']=rec
    else: rec['n']=i; OUT['pian'].append(rec)

json.dump(OUT, open('text.json','w'), ensure_ascii=False, indent=1)
n_main=sum(len(t) for r in [OUT['pre']]+OUT['pian'] for p in r['paras'] for k,t in p if k==0)
n_zhu =sum(len(t) for r in [OUT['pre']]+OUT['pian'] for p in r['paras'] for k,t in p if k==1)
print('篇',len(OUT['pian']),'段',sum(len(r['paras']) for r in [OUT['pre']]+OUT['pian']))
print('正文',n_main,'趙蕤注',n_zhu,'合',n_main+n_zhu)
# quote balance
alltext=''.join(t for r in [OUT['pre']]+OUT['pian'] for p in r['paras'] for k,t in p)
for a,b in [('「','」'),('『','』'),('《','》'),('〔','〕')]:
    print(a,b,alltext.count(a),alltext.count(b))
