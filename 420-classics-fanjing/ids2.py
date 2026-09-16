import sys,re
comp={}
for line in open('ids.txt',encoding='utf-8'):
    if line.startswith('#'): continue
    f=line.rstrip('\n').split('\t')
    if len(f)<3: continue
    ch=f[1]
    for d in f[2:]:
        d=re.sub(r'\[[A-Z]+\]$','',d)
        comp.setdefault(d,[]).append(ch)
VAR={'玉':['玉','王'],'手':['手','扌'],'水':['水','氵'],'人':['人','亻'],'足':['足','𧾷'],
     '心':['心','忄'],'言':['言','訁'],'金':['金','釒'],'食':['食','飠'],'糸':['糸','纟'],
     '犬':['犬','犭'],'肉':['肉','月'],'艸':['艸','艹'],'阜':['阜','阝'],'邑':['邑','阝']}
def find(a,b):
    out=[]
    for x in VAR.get(a,[a]):
        for y in VAR.get(b,[b]):
            for pat in ('⿰'+x+y,'⿱'+x+y,'⿰'+y+x,'⿱'+y+x):
                out+=comp.get(pat,[])
    return sorted(set(out))
for pair in sys.argv[1:]:
    print(pair, find(pair[0],pair[1]))
