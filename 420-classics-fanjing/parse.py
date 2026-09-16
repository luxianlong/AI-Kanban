# -*- coding: utf-8 -*-
import json, re, os

def strip_templates_keep_star(s):
    """Remove wiki templates except {{*|...}} which we turn into 〔...〕 notes."""
    out=[]; i=0; n=len(s)
    while i < n:
        if s.startswith('{{', i):
            if s.startswith('{{*|', i):
                depth=1; j=i+4; start=j
                while j < n and depth>0:
                    if s.startswith('{{', j): depth+=1; j+=2
                    elif s.startswith('}}', j): depth-=1; j+=2
                    else: j+=1
                inner = s[start:j-2]
                out.append('\x01' + strip_templates_keep_star(inner) + '\x02')
                i=j
            else:
                depth=1; j=i+2; start=j
                while j < n and depth>0:
                    if s.startswith('{{', j): depth+=1; j+=2
                    elif s.startswith('}}', j): depth-=1; j+=2
                    else: j+=1
                inner = s[start:j-2]
                name = inner.split('|')[0].strip().lower()
                if name == 'yl':                      # {{YL|開元四年}} -> plain text
                    out.append(inner.split('|',1)[1] if '|' in inner else '')
                # header / PD-old / wikipedia -> drop
                i=j
        else:
            out.append(s[i]); i+=1
    return ''.join(out)

def clean(s):
    m=re.search(r'<onlyinclude>(.*)</onlyinclude>', s, re.S)
    if m: s=m.group(1)
    s=strip_templates_keep_star(s)
    s=re.sub(r'__NOEDITSECTION__','',s)
    s=re.sub(r'\[\[[^\]|]*\|([^\]]*)\]\]', r'\1', s)
    s=re.sub(r'\[\[([^\]]*)\]\]', r'\1', s)
    s=re.sub(r'-\{([^}]*)\}-', r'\1', s)
    s=re.sub(r"'''?", '', s)
    s=re.sub(r'<[^>]+>', '', s)
    # 〔...〕 in-source notes -> same marker as {{*|}}
    s=s.replace('〔','\x01').replace('〕','\x02')
    return s

BOOK=[]
titles=[l for l in open('titles.txt').read().split() if l]
for t in titles:
    w=json.load(open('pages/'+t.replace('/','__')+'.json'))['parse']['wikitext']
    body=clean(w)
    paras=[p.strip() for p in re.split(r'\n\s*\n', body) if p.strip()]
    BOOK.append({'title': t.split('/')[-1], 'paras': paras})

json.dump(BOOK, open('book.json','w'), ensure_ascii=False, indent=1)
print('chapters', len(BOOK), 'paras', sum(len(c['paras']) for c in BOOK))
print('chars', sum(len(re.sub(r'\s','',''.join(c['paras']))) for c in BOOK))
# sanity: leftover markup
bad=set()
for c in BOOK:
    for p in c['paras']:
        for ch in '{}[]|<>*=':
            if ch in p: bad.add((c['title'],ch))
print('leftover markup:', sorted(bad)[:20])
