import json,urllib.request,urllib.parse,re,sys,time
UA={'User-Agent':'dianji-research/1.0 (luxianlongmicrosoft@gmail.com)'}
def get(url):
    req=urllib.request.Request(url,headers=UA)
    for i in range(3):
        try: return json.load(urllib.request.urlopen(req,timeout=40))
        except Exception as e:
            if i==2: return {'__err':str(e)}
            time.sleep(2)
CJK=re.compile(r'[㐀-䶿一-鿿豈-﫿]')
def wikitext(titles):
    total=0
    for i in range(0,len(titles),10):
        d=get('https://zh.wikisource.org/w/api.php?action=query&format=json&prop=revisions&rvprop=content&rvslots=main&titles='+urllib.parse.quote('|'.join(titles[i:i+10])))
        for p in (d.get('query',{}).get('pages',{}) or {}).values():
            rv=p.get('revisions')
            if not rv: continue
            t=rv[0]['slots']['main']['*']
            t=re.sub(r'\{\{[^{}]*\}\}',' ',t)
            t=re.sub(r'\{\{[^{}]*\}\}',' ',t)
            t=re.sub(r'<ref[^>]*>.*?</ref>',' ',t,flags=re.S)
            t=re.sub(r'<[^>]+>',' ',t)
            t=re.sub(r'\[\[[^\]|]*\|','[[',t)
            total+=len(CJK.findall(t))
    return total
def subpages(prefix,exclude=r'[（(]|全覽|集註|索引|目錄'):
    d=get('https://zh.wikisource.org/w/api.php?action=query&format=json&generator=allpages&gapprefix='+urllib.parse.quote(prefix)+'&gaplimit=500')
    ts=[p['title'] for p in (d.get('query',{}).get('pages',{}) or {}).values()]
    bad=re.compile(exclude)
    return [t for t in ts if (t==prefix or t.startswith(prefix+'/')) and not bad.search(t)]
targets=['六韜','三略','司馬法','吳子','貞觀政要','茶經','洗冤集錄','顏氏家訓','呻吟語','圍爐夜話','人物志','千字文','蒙求','太乙金華宗旨','忍經','百戰奇略','菜根譚','淮南子','世說新語','說苑','夢溪筆談']
for t in targets:
    ps=subpages(t)
    n=wikitext(ps)
    print(f"{t}\t{len(ps)} pages\t{n} 字")
    sys.stdout.flush()
# special: 尉繚子/全覽
print('尉繚子(全覽)\t',wikitext(['尉繚子/全覽']))
print('唐李問對/ctext n/a')
