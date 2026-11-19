import sys,re,json
comp={}
for line in open('ids.txt',encoding='utf-8'):
    if line.startswith('#'): continue
    f=line.rstrip('\n').split('\t')
    if len(f)<3: continue
    ch=f[1]
    for d in f[2:]:
        d=re.sub(r'\[[A-Z]+\]$','',d)
        comp.setdefault(d,[]).append(ch)
def find(a,b):
    out=[]
    for pat in ('⿰'+a+b,'⿱'+a+b,'⿰'+b+a):
        out+=comp.get(pat,[])
    return out
for pair in sys.argv[1:]:
    a,b=pair[0],pair[1]
    print(pair, find(a,b))
