set -e
cd /tmp/fanjing
python3 norm.py      >/dev/null   # glyph table + first normalisation
python3 norm2.py                  # note markers, rare chars, quotes
python3 restruct.py               # 卷四/卷五 re-cut
python3 sku.py       >/dev/null   # the 四庫 witness
python3 align.py     2>/dev/null   # import note boundaries, collect variants
python3 -c "
import json
T=json.load(open('text.json')); K=json.load(open('kinds.json'))
for r in T['pian']: r['paras']=K[str(r['n'])]
json.dump(T, open('text.json','w'), ensure_ascii=False, indent=1)
print('kinds imported')
"
python3 classify.py
python3 supply.py                 # the five passages the base text drops
python3 post.py                   # labels, 云/雲, glyphs, punctuation
node to_hans.js text.json notes.json colophon.json pian-titles.json juan.json >/dev/null
python3 build.py
