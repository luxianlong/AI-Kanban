import json,urllib.request,urllib.parse,time,re,sys
UA={'User-Agent':'Mozilla/5.0 (X11; Linux x86_64) dianji-research/1.0'}
def fetch(url,as_json=True):
    req=urllib.request.Request(url,headers=UA)
    for i in range(3):
        try:
            r=urllib.request.urlopen(req,timeout=40).read().decode('utf-8','replace')
            return json.loads(r) if as_json else r
        except Exception as e:
            if i==2: return {'__err':str(e)} if as_json else ''
            time.sleep(2)
BOOKS=[
 ("六韬","Six Secret Teachings","Liu Tao"),
 ("三略","Three Strategies of Huang Shigong","San Lue"),
 ("尉缭子","Wei Liaozi","Wei Liao Tzu"),
 ("司马法","Methods of the Sima","Sima Fa"),
 ("吴子","Wuzi","Wu Tzu"),
 ("唐李问对","Questions and Replies between Tang Taizong and Li Weigong","Tang Taizong Li Weigong Wendui"),
 ("贞观政要","Zhenguan zhengyao","Essentials of Government of the Zhenguan"),
 ("茶经","Classic of Tea","Cha Jing Lu Yu"),
 ("洗冤集录","Washing Away of Wrongs","Xiyuan Jilu"),
 ("梦溪笔谈","Dream Pool Essays","Mengxi Bitan"),
 ("颜氏家训","Family Instructions for the Yan Clan","Yanshi Jiaxun"),
 ("呻吟语","Groaning Words","Shenyinyu Lu Kun"),
 ("围炉夜话","Weilu Yehua","Wei Lu Ye Hua"),
 ("小窗幽记","Xiaochuang Youji","Hsiao chuang yu chi"),
 ("人物志","Treatise on Human Abilities","Renwu zhi Liu Shao"),
 ("千字文","Thousand Character Classic","Qianziwen"),
 ("太乙金华宗旨","Secret of the Golden Flower","Taiyi Jinhua Zongzhi"),
 ("荀子","Xunzi","Hsun Tzu"),
 ("淮南子","Huainanzi","Huai nan tzu"),
 ("管子","Guanzi","Kuan tzu"),
 ("吕氏春秋","Lushi Chunqiu","Lu shih chun chiu"),
 ("伤寒论","Shanghan Lun","Treatise on Cold Damage"),
 ("黄帝内经","Huangdi Neijing","Yellow Emperor Inner Canon"),
 ("世说新语","New Account of the Tales of the World","Shishuo Xinyu"),
 ("十八史略","Shiba shilue","Juhachi shiryaku"),
 ("传习录","Instructions for Practical Living","Chuan hsi lu"),
 ("说苑","Garden of Persuasions","Shuo yuan"),
 ("忍经","Renjing classic of forbearance","Jen ching"),
 ("百战奇略","Baizhan Qilue","Hundred Unorthodox Strategies"),
 ("纪效新书","Jixiao Xinshu","New Treatise on Military Efficiency"),
 ("随园食单","Recipes from the Garden of Contentment","Suiyuan shidan"),
 ("天工开物","Tiangong Kaiwu","Exploitation of the Works of Nature"),
 ("齐民要术","Qimin Yaoshu","Essential Techniques for the Welfare of the People"),
]
res={}
for zh,en,alt in BOOKS:
    e={}
    d=fetch('https://en.wikisource.org/w/api.php?action=query&format=json&list=search&srlimit=5&srsearch='+urllib.parse.quote(f'"{en}"'))
    e['wikisource']=[h['title'] for h in d.get('query',{}).get('search',[])] if 'query' in d else ['ERR']
    d=fetch('https://en.wikisource.org/w/api.php?action=query&format=json&list=search&srlimit=5&srsearch='+urllib.parse.quote(f'"{alt}"'))
    e['wikisource_alt']=[h['title'] for h in d.get('query',{}).get('search',[])] if 'query' in d else ['ERR']
    q=urllib.parse.quote(f'title:("{en}") OR title:("{alt}")')
    d=fetch(f'https://archive.org/advancedsearch.php?q={q}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=language&rows=6&output=json')
    e['archive']=[f"{x.get('year')}|{x.get('language')}|{x.get('title')}|{x['identifier']}" for x in d.get('response',{}).get('docs',[])] if 'response' in d else ['ERR']
    h=fetch('https://www.gutenberg.org/ebooks/search/?query='+urllib.parse.quote(en+' '+alt),as_json=False)
    e['gutenberg']=re.findall(r'class="title">([^<]{0,80})',h)[:6]
    res[zh]=e
    print('###',zh,en)
    for k,v in e.items(): print('  ',k,':',v)
    sys.stdout.flush()
json.dump(res,open('gapcheck.json','w'),ensure_ascii=False,indent=1)
