import json,urllib.request,urllib.parse,time,sys,csv
UA={'User-Agent':'dianji-research/1.0 (luxianlongmicrosoft@gmail.com)'}
def get(url):
    req=urllib.request.Request(url,headers=UA)
    for i in range(4):
        try: return json.load(urllib.request.urlopen(req,timeout=40))
        except urllib.error.HTTPError as e:
            if e.code==404: return {'__404':True}
            if i==3: return {'__err':str(e)}
            time.sleep(2)
        except Exception as e:
            if i==3: return {'__err':str(e)}
            time.sleep(2)
rows=[r for r in csv.reader(open('cands.tsv'),delimiter='\t') if r and r[0].strip()]
def resolve(wiki,titles):
    out={}
    for i in range(0,len(titles),20):
        b=[t for t in titles[i:i+20] if t]
        d=get(f'https://{wiki}.wikipedia.org/w/api.php?action=query&format=json&redirects=1&titles='+urllib.parse.quote('|'.join(b)))
        norm={}
        for r in d.get('query',{}).get('normalized',[]): norm[r['to']]=r['from']
        for r in d.get('query',{}).get('redirects',[]): norm[r['to']]=norm.get(r['from'],r['from'])
        for pid,p in d.get('query',{}).get('pages',{}).items():
            t=p['title']; orig=norm.get(t,t)
            out[orig]= None if int(pid)<0 else t
    return out
en=resolve('en',[r[1] for r in rows])
ja=resolve('ja',[r[2] for r in rows])
def views(wiki,title):
    if not title: return None
    t=urllib.parse.quote(title.replace(' ','_'),safe='')
    d=get(f'https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/{wiki}.wikipedia/all-access/user/{t}/monthly/20250901/20260831')
    if '__404' in d or 'items' not in d: return 0
    return sum(i['views'] for i in d['items'])
res=[]
for zh,ent,jat,cat in rows:
    ea=en.get(ent); jaa=ja.get(jat)
    ev=views('en',ea); jv=views('ja',jaa)
    res.append({'zh':zh,'cat':cat,'en_title':ea,'ja_title':jaa,'en_views':ev,'ja_views':jv})
    print(f"{zh}\t{cat}\t{ea}\t{ev}\t{jaa}\t{jv}",flush=True)
json.dump(res,open('pageviews.json','w'),ensure_ascii=False,indent=1)
