# -*- coding: utf-8 -*-
"""Ground the 典籍 pages on the entities Google already knows.

The pages carry Book/WebPage/ImageObject/BreadcrumbList JSON-LD (task #417) but
never said WHICH 孫子兵法 they are about. `sameAs` is the one property that says
it: a Wikidata QID plus the Wikipedia article in the page's own language. That
turns forty-four look-alike pages into four readings of eleven known works.

Every QID below was resolved against the Wikidata API by label and checked
against its `instance of` and sitelinks — in particular 鬼谷子 has separate items
for the book (Q11077874) and the man (Q705744), and 素書 (Q15910050) is not the
三略 (Q1051338) also ascribed to 黃石公. Books with no Wikipedia article anywhere
(小人經) still get their Wikidata item; books with no item at all would get
nothing, and there are none left.

Author links are only added where the page's own JSON-LD already names an
author. The pages that call their attribution a modern fiction don't name one,
and this pass does not give them one.

Run from ~/Dashboard. Idempotent: re-running rewrites the same bytes.
"""
import json, pathlib, re, sys

P = pathlib.Path('frontend/public/p')
W = 'https://www.wikidata.org/entity/'

def wp(lang, title):
    return f'https://{lang}.wikipedia.org/wiki/{title}'

# slug -> (book QID, {wiki lang: article url}, author QID or None, {wiki lang: url})
ENTITIES = {
  'sunzi': ('Q8251', {
      'zh': wp('zh', '%E5%AD%99%E5%AD%90%E5%85%B5%E6%B3%95'),
      'en': wp('en', 'The_Art_of_War'),
      'ja': wp('ja', '%E5%AD%AB%E5%AD%90_(%E6%9B%B8%E7%89%A9)')},
    'Q37151', {
      'zh': wp('zh', '%E5%AD%99%E6%AD%A6'),
      'en': wp('en', 'Sun_Tzu'),
      'ja': wp('ja', '%E5%AD%AB%E6%AD%A6')}),
  'guiguzi': ('Q11077874', {
      'zh': wp('zh', '%E9%AC%BC%E8%B0%B7%E5%AD%90_(%E5%8F%A4%E7%B1%8D)'),
      'en': wp('en', 'Guiguzi'),
      'ja': wp('ja', '%E9%AC%BC%E8%B0%B7%E5%AD%90')},
    None, {}),
  'sanshiliuji': ('Q83590', {
      'zh': wp('zh', '%E4%B8%89%E5%8D%81%E5%85%AD%E8%AE%A1'),
      'en': wp('en', 'Thirty-Six_Stratagems'),
      'ja': wp('ja', '%E5%85%B5%E6%B3%95%E4%B8%89%E5%8D%81%E5%85%AD%E8%A8%88')},
    None, {}),
  # zhwiki only — the Ice Mirror has no article in English or Japanese.
  'bingjian': ('Q18835888', {
      'zh': wp('zh', '%E5%86%B0%E9%89%B4')},
    None, {}),
  'daodejing': ('Q134425', {
      'zh': wp('zh', '%E8%80%81%E5%AD%90_(%E6%9B%B8)'),
      'en': wp('en', 'Tao_Te_Ching'),
      'ja': wp('ja', '%E8%80%81%E5%AD%90%E9%81%93%E5%BE%B3%E7%B5%8C')},
    'Q9333', {
      'zh': wp('zh', '%E8%80%81%E5%AD%90'),
      'en': wp('en', 'Laozi'),
      'ja': wp('ja', '%E8%80%81%E5%AD%90')}),
  'liaofansixun': ('Q6540111', {
      'zh': wp('zh', '%E4%BA%86%E5%87%A1%E5%9B%9B%E8%A8%93'),
      'en': wp('en', 'Liao-Fan%27s_Four_Lessons'),
      'ja': wp('ja', '%E9%99%B0%E9%A8%AD%E9%8C%B2')},
    'Q15926559', {
      'zh': wp('zh', '%E8%A2%81%E9%BB%83'),
      'ja': wp('ja', '%E8%A2%81%E9%BB%84')}),
  # zhwiki only — 素書, not the 三略 also put under the Yellow Stone Elder's name.
  'sushu': ('Q15910050', {
      'zh': wp('zh', '%E7%B4%A0%E4%B9%A6')},
    'Q10567265', {
      'zh': wp('zh', '%E9%BB%84%E7%9F%B3%E5%85%AC'),
      'en': wp('en', 'Huang_Shigong'),
      'ja': wp('ja', '%E9%BB%84%E7%9F%B3%E5%85%AC')}),
  'shangjunshu': ('Q3521719', {
      'zh': wp('zh', '%E5%95%86%E5%90%9B%E4%B9%A6'),
      'en': wp('en', 'The_Book_of_Lord_Shang'),
      'ja': wp('ja', '%E5%95%86%E5%90%9B%E6%9B%B8')},
    'Q351345', {
      'zh': wp('zh', '%E5%95%86%E9%9E%85'),
      'en': wp('en', 'Shang_Yang'),
      'ja': wp('ja', '%E5%95%86%E9%9E%85')}),
  'luozhijing': ('Q18851140', {
      'zh': wp('zh', '%E7%BD%97%E7%BB%87%E7%BB%8F')},
    'Q6473612', {
      'zh': wp('zh', '%E4%BE%86%E4%BF%8A%E8%87%A3'),
      'en': wp('en', 'Lai_Junchen')}),
  # An item exists, with no Wikipedia article in any language — the book is a
  # modern compilation, which is exactly what the page says about itself.
  'xiaorenjing': ('Q18902565', {},
    'Q1131595', {
      'zh': wp('zh', '%E9%A6%AE%E9%81%93'),
      'en': wp('en', 'Feng_Dao'),
      'ja': wp('ja', '%E9%A6%AE%E9%81%93')}),
  'caigentan': ('Q1144765', {
      'zh': wp('zh', '%E8%8F%9C%E6%A0%B9%E8%AD%9A'),
      'en': wp('en', 'Caigentan'),
      'ja': wp('ja', '%E8%8F%9C%E6%A0%B9%E8%AD%9A')},
    'Q5895740', {
      'zh': wp('zh', '%E6%B4%AA%E5%BA%94%E6%98%8E'),
      'en': wp('en', 'Hong_Zicheng'),
      'ja': wp('ja', '%E6%B4%AA%E8%87%AA%E8%AA%A0')}),
}

# Natural pixel size of each cover, read off the file headers on disk.
COVER = {
  'sunzi': (1141, 1024), 'guiguzi': (655, 1019), 'sanshiliuji': (435, 743),
  'bingjian': (430, 746), 'daodejing': (620, 942), 'liaofansixun': (560, 896),
  'sushu': (560, 1000), 'shangjunshu': (560, 986), 'luozhijing': (560, 960),
  'xiaorenjing': (560, 1016), 'caigentan': (560, 874),
}

SUFFIX_LANG = {'': 'zh', '-hans': 'zh', '-en': 'en', '-ja': 'ja'}

def same_as(qid, wikis, lang):
    """Wikidata first — that's the identifier — then one Wikipedia article,
    preferring the reader's own language and falling back to whatever exists."""
    out = [W + qid]
    for cand in (lang, 'zh', 'en', 'ja'):
        if wikis.get(cand):
            out.append(wikis[cand])
            break
    return out

def load_ld(html):
    m = re.search(r'(<script type="application/ld\+json">\n)(.*?)(\n</script>)', html, re.S)
    if not m:
        sys.exit('no JSON-LD block')
    return m, json.loads(m.group(2))

def dump_ld(m, data, html):
    body = json.dumps(data, ensure_ascii=False, indent=1)
    return html[:m.start(2)] + body + html[m.end(2):]

changed = []
for slug, (bq, bw, aq, aw) in ENTITIES.items():
    for suffix, lang in SUFFIX_LANG.items():
        page = P / f'{slug}{suffix}.html'
        html = page.read_text(encoding='utf-8')
        m, data = load_ld(html)
        for node in data['@graph']:
            if node.get('@type') == 'Book':
                node['sameAs'] = same_as(bq, bw, lang)
                author = node.get('author')
                if author is not None and aq:
                    author['sameAs'] = same_as(aq, aw, lang)
            elif node.get('@type') == 'ImageObject':
                w, h = COVER[slug]
                node['width'], node['height'] = w, h
        out = dump_ld(m, data, html)
        if out != html:
            page.write_text(out, encoding='utf-8')
            changed.append(page.name)

# The shelf lists all eleven; name the same entities there so the hub and the
# leaves agree about what they are pointing at.
shelf = P / 'dianji.html'
html = shelf.read_text(encoding='utf-8')
m, data = load_ld(html)
for node in data['@graph']:
    if node.get('@type') != 'ItemList':
        continue
    for li in node['itemListElement']:
        item = li.get('item') or {}
        hit = re.search(r'/p/(\w+?)-hans\.html', item.get('url', ''))
        if hit and hit.group(1) in ENTITIES:
            bq, bw, _, _ = ENTITIES[hit.group(1)]
            item['sameAs'] = same_as(bq, bw, 'zh')
out = dump_ld(m, data, html)
if out != html:
    shelf.write_text(out, encoding='utf-8')
    changed.append(shelf.name)

print(f'{len(changed)} page(s) updated' if changed else 'no change (already grounded)')
