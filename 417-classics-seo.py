# -*- coding: utf-8 -*-
"""Inject search-engine metadata into the 典籍 reading pages.

One pass over the 11 books x 3 readings + the shelf page. Everything written
here is derived from what the pages already say about themselves; nothing is
invented. Run from ~/Dashboard.
"""
import html, json, re, sys, pathlib

ORIGIN = "https://youraikanban.com"
P = pathlib.Path("frontend/public/p")
SHELF = f"{ORIGIN}/p/dianji.html"
COLLECTION_ID = f"{SHELF}#collection"

# slug -> the facts each page already states in its masthead and the shelf's
# data- attributes. `author` is None where the page itself says the attribution
# is a modern fiction (罗织经, 小人经) or where the book is anonymous.
BOOKS = [
  dict(slug="sunzi", en_short="The Art of War", hans="孙子兵法", hant="孫子兵法",
       en="The Art of War — Sunzi", roman="Sunzi, Sun Tzu",
       author=("孙武", "孫武", "Sun Wu"), ascribed=True,
       im_hans="春秋 · 十三篇", im_hant="春秋 · 十三篇",
       im_en="Spring and Autumn period · thirteen chapters",
       cover="sunzi-cover.jpg"),
  dict(slug="guiguzi", en_short="Guiguzi", hans="鬼谷子", hant="鬼谷子",
       en="Guiguzi — The Master of Ghost Valley", roman="Guiguzi, Kuei Ku Tzu",
       author=None, ascribed=False,
       im_hans="战国 · 三卷二十一篇", im_hant="戰國 · 三卷二十一篇",
       im_en="Warring States · three scrolls, twenty-one chapters",
       cover="guiguzi-cover.jpg"),
  dict(slug="sanshiliuji", en_short="The Thirty-Six Stratagems", hans="三十六计", hant="三十六計",
       en="The Thirty-Six Stratagems", roman="Sanshiliu Ji",
       author=None, ascribed=False,
       im_hans="明清 · 六套三十六计", im_hant="明清 · 六套三十六計",
       im_en="Ming–Qing · six sets of six", cover="sanshiliuji-cover.jpg"),
  dict(slug="bingjian", en_short="The Ice Mirror", hans="冰鉴", hant="冰鑑",
       en="The Ice Mirror — Bingjian", roman="Bingjian, Ping Chien",
       author=None, ascribed=False,
       im_hans="清 · 七篇", im_hant="清 · 七篇",
       im_en="Qing · seven chapters", cover="bingjian-cover.jpg"),
  dict(slug="daodejing", en_short="Tao Te Ching (Daodejing)", hans="道德经", hant="道德經",
       en="The Daodejing — Tao Te Ching", roman="Daodejing, Tao Te Ching, Laozi",
       author=("老子", "老子", "Laozi"), ascribed=False,
       im_hans="春秋 · 八十一章", im_hant="春秋 · 八十一章",
       im_en="Spring and Autumn period · eighty-one chapters",
       cover="daodejing-cover.jpg"),
  dict(slug="liaofansixun", en_short="Liaofan’s Four Lessons", hans="了凡四训", hant="了凡四訓",
       en="Liaofan’s Four Lessons", roman="Liaofan Sixun",
       author=("袁了凡", "袁了凡", "Yuan Liaofan"), ascribed=False,
       im_hans="明 · 四篇", im_hant="明 · 四篇", im_en="Ming · four lessons",
       cover="liaofansixun-cover.png"),
  dict(slug="sushu", en_short="Su Shu", hans="素书", hant="素書",
       en="Su Shu — The Plain Book", roman="Sushu",
       author=("黄石公", "黃石公", "the Yellow Stone Elder"), ascribed=True,
       im_hans="秦 · 六章", im_hant="秦 · 六章", im_en="Qin · six chapters",
       cover="sushu-cover.jpg"),
  dict(slug="shangjunshu", en_short="The Book of Lord Shang", hans="商君书", hant="商君書",
       en="The Book of Lord Shang — Shangjunshu", roman="Shangjunshu, Shang Jun Shu",
       author=("商鞅", "商鞅", "Shang Yang"), ascribed=True,
       im_hans="战国 · 五卷二十六篇", im_hant="戰國 · 五卷二十六篇",
       im_en="Warring States · five scrolls, twenty-six chapters",
       cover="shangjunshu-cover.jpg"),
  dict(slug="luozhijing", en_short="The Classic of Netting", hans="罗织经", hant="羅織經",
       en="The Classic of Netting — Luo Zhi Jing", roman="Luozhijing",
       author=None, ascribed=True,
       im_hans="旧题唐 · 十二卷", im_hant="舊題唐 · 十二卷",
       im_en="Ascribed to the Tang · twelve scrolls", cover="luozhijing-cover.jpg"),
  dict(slug="xiaorenjing", en_short="The Classic of the Petty Man", hans="小人经", hant="小人經",
       en="The Classic of the Petty Man — Xiao Ren Jing",
       roman="Xiaorenjing, Rongkujian, 荣枯鉴",
       author=None, ascribed=True,
       im_hans="旧题五代 · 十卷", im_hant="舊題五代 · 十卷",
       im_en="Ascribed to the Five Dynasties · ten scrolls",
       cover="xiaorenjing-cover.jpg"),
  dict(slug="caigentan", en_short="The Vegetable Root Discourses", hans="菜根谭", hant="菜根譚",
       en="The Vegetable Root Discourses — Caigentan", roman="Caigentan, Tsai Ken Tan",
       author=("洪应明", "洪應明", "Hong Yingming"), ascribed=False,
       im_hans="明 · 前后集三五六条", im_hant="明 · 前後集三五六條",
       im_en="Ming · 356 entries in two parts", cover="caigentan-cover.jpg"),
]

VARIANTS = [("hans", "-hans", "zh-Hans", "zh_CN"),
            ("hant", "",      "zh-Hant", "zh_TW"),
            ("en",   "-en",   "en",      "en_US")]

TITLE = {
  "hans": lambda b: f'{b["hans"]} · 简体全文 · 免费在线阅读',
  "hant": lambda b: f'{b["hant"]} · 繁體全文 · 免費線上閱讀',
  "en":   lambda b: f'{b["en_short"]} {b["hans"]} · Free Full English Translation',
}
DESC_TAIL = {
  "hans": lambda b: f'　全文免费在线阅读，无需注册；另有繁體版与 English 译本。',
  "hant": lambda b: f'　全文免費線上閱讀，無需註冊；另有简体版與 English 譯本。',
  "en":   lambda b: (f' Free to read in full — no signup and no paywall; the Chinese '
                     f'original ({b["hans"]}／{b["hant"]}) is one click away.'),
}
SUB_TAIL = {"hans": "　·　全文免费阅读", "hant": "　·　全文免費閱讀",
            "en": "　·　free to read in full"}
SHELF_BTN = {
  "hans": '<a class="btn" href="/p/dianji.html" title="回到典籍书架">典籍</a>',
  "hant": '<a class="btn" href="/p/dianji.html" title="回到典籍書架">典籍</a>',
  "en":   '<a class="btn" href="/p/dianji.html" title="Back to the classics shelf">Classics</a>',
}

def url_of(b, v):
  suf = dict((k, s) for k, s, _, _ in VARIANTS)[v]
  return f'{ORIGIN}/p/{b["slug"]}{suf}.html'

def esc(s):
  """Attribute-safe, and idempotent for text that is already entity-escaped."""
  return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")

def plain(s):
  return html.unescape(s)

def book_node(b, v):
  lang = dict((k, l) for k, _, l, _ in VARIANTS)[v]
  name = {"hans": b["hans"], "hant": b["hant"], "en": b["en"]}[v]
  alts = [b["hans"], b["hant"], b["en"]] + [t.strip() for t in b["roman"].split(",")]
  alts = [a for a in dict.fromkeys(alts) if a != name]
  node = {
    "@type": "Book", "@id": url_of(b, v) + "#book",
    "name": name, "alternateName": alts,
    "inLanguage": lang, "url": url_of(b, v),
    "image": f'{ORIGIN}/img/classics/{b["cover"]}',
    "isAccessibleForFree": True,
    "bookFormat": "https://schema.org/EBook",
    "isPartOf": {"@id": COLLECTION_ID},
  }
  if b["author"]:
    a = {"hans": b["author"][0], "hant": b["author"][1], "en": b["author"][2]}[v]
    node["author"] = {"@type": "Person", "name": a,
                      "alternateName": [x for x in dict.fromkeys(b["author"]) if x != a]}
  if v == "en":
    node["translationOfWork"] = {"@id": url_of(b, "hant") + "#book"}
  else:
    node["workTranslation"] = {"@id": url_of(b, "en") + "#book"}
  return node

def graph(b, v, title, desc):
  u = url_of(b, v)
  lang = dict((k, l) for k, _, l, _ in VARIANTS)[v]
  cover = f'{ORIGIN}/img/classics/{b["cover"]}'
  shelf_name = {"hans": "典籍", "hant": "典籍", "en": "Classics"}[v]
  bname = {"hans": b["hans"], "hant": b["hant"], "en": b["en"]}[v]
  return {"@context": "https://schema.org", "@graph": [
    book_node(b, v),
    {"@type": "WebPage", "@id": u + "#webpage", "url": u, "name": title,
     "description": desc, "inLanguage": lang,
     "isPartOf": {"@id": COLLECTION_ID}, "about": {"@id": u + "#book"},
     "primaryImageOfPage": {"@id": u + "#cover"},
     "breadcrumb": {"@id": u + "#crumbs"}, "isAccessibleForFree": True},
    {"@type": "ImageObject", "@id": u + "#cover", "url": cover, "contentUrl": cover},
    {"@type": "BreadcrumbList", "@id": u + "#crumbs", "itemListElement": [
      {"@type": "ListItem", "position": 1, "name": shelf_name, "item": SHELF},
      {"@type": "ListItem", "position": 2, "name": bname, "item": u}]},
  ]}

def ld(obj):
  return ('<script type="application/ld+json">\n'
          + json.dumps(obj, ensure_ascii=False, indent=1)
          + "\n</script>")

MARK = "<!-- search metadata -->"

def head_block(b, v, locale, title, desc_attr, desc_plain):
  u = url_of(b, v)
  cover = f'{ORIGIN}/img/classics/{b["cover"]}'
  others = [lo for k, _, _, lo in VARIANTS if k != v]
  lines = [MARK,
    f'<link rel="canonical" href="{u}">',
    f'<link rel="alternate" hreflang="zh-Hans" href="{url_of(b,"hans")}">',
    f'<link rel="alternate" hreflang="zh-Hant" href="{url_of(b,"hant")}">',
    f'<link rel="alternate" hreflang="en" href="{url_of(b,"en")}">',
    f'<link rel="alternate" hreflang="x-default" href="{url_of(b,"en")}">',
    '<meta name="robots" content="index,follow,max-snippet:-1,max-image-preview:large">',
    '<meta property="og:type" content="book">',
    '<meta property="og:site_name" content="典籍 · Chinese Classics">',
    f'<meta property="og:title" content="{esc(title)}">',
    f'<meta property="og:description" content="{desc_attr}">',
    f'<meta property="og:url" content="{u}">',
    f'<meta property="og:image" content="{cover}">',
    f'<meta property="og:locale" content="{locale}">']
  lines += [f'<meta property="og:locale:alternate" content="{o}">' for o in others]
  lines += ['<meta name="twitter:card" content="summary">',
    f'<meta name="twitter:title" content="{esc(title)}">',
    f'<meta name="twitter:description" content="{desc_attr}">',
    f'<meta name="twitter:image" content="{cover}">',
    ld(graph(b, v, title, desc_plain))]
  return "\n".join(lines)

def patch_book(b, v, suf, lang, locale):
  path = P / f'{b["slug"]}{suf}.html'
  s = path.read_text(encoding="utf-8")
  if MARK in s:
    print(f"  skip (already done) {path.name}"); return 0
  # --- title ---------------------------------------------------------------
  title = TITLE[v](b)
  s, n = re.subn(r"<title>.*?</title>", lambda m: f"<title>{esc(title)}</title>", s, count=1, flags=re.S)
  assert n == 1, path
  # --- description + the block ---------------------------------------------
  m = re.search(r'<meta name="description" content="(.*?)">\n', s, flags=re.S)
  assert m, path
  desc_attr = m.group(1).rstrip() + esc(DESC_TAIL[v](b))
  desc_plain = plain(desc_attr)
  block = (f'<meta name="description" content="{desc_attr}">\n'
           + head_block(b, v, locale, title, desc_attr, desc_plain) + "\n")
  s = s[:m.start()] + block + s[m.end():]
  # --- masthead: say it is free to read ------------------------------------
  s, n = re.subn(r'(<p class="sub">.*?)(</p>)',
                 lambda mm: mm.group(1) + SUB_TAIL[v] + mm.group(2), s, count=1, flags=re.S)
  assert n == 1, path
  # --- toolbar: a way back to the shelf ------------------------------------
  s, n = re.subn(r'<div class="bar">\n',
                 '<div class="bar">\n  <div class="grp">\n    '
                 + SHELF_BTN[v] + "\n  </div>\n", s, count=1)
  assert n == 1, path
  path.write_text(s, encoding="utf-8")
  print(f"  ok {path.name}")
  return 1

def patch_shelf():
  path = P / "dianji.html"
  s = path.read_text(encoding="utf-8")
  if MARK in s:
    print("  skip (already done) dianji.html"); return 0
  items = []
  for i, b in enumerate(BOOKS, 1):
    items.append({"@type": "ListItem", "position": i, "name": b["hans"],
                  "alternateName": [b["hant"], b["en"]],
                  "item": {"@id": url_of(b, "hans") + "#book"}})
  g = {"@context": "https://schema.org", "@graph": [
    {"@type": "CollectionPage", "@id": COLLECTION_ID, "url": SHELF,
     "name": "典籍 · Chinese Classics",
     "alternateName": ["典籍", "Chinese Classics", "Dianji"],
     "description": ("十一部中文典籍的全文读本，每部备简体、繁體、English 三种，"
                     "免费在线阅读。Eleven Chinese classics in full — simplified, "
                     "traditional and English, free to read."),
     "inLanguage": ["zh-Hans", "zh-Hant", "en"], "isAccessibleForFree": True,
     "hasPart": [{"@id": url_of(b, "hans") + "#book"} for b in BOOKS],
     "mainEntity": {"@id": SHELF + "#list"}},
    {"@type": "ItemList", "@id": SHELF + "#list", "name": "典籍",
     "numberOfItems": len(BOOKS), "itemListOrder": "https://schema.org/ItemListOrderAscending",
     "itemListElement": items},
  ]}
  title = "典籍 · 十一部中文经典全文 · 简体 / 繁體 / English 免费在线阅读"
  desc = ("典籍书架：《孙子兵法》《鬼谷子》《三十六计》《冰鉴》《道德经》《了凡四训》"
          "《素书》《商君书》《罗织经》《小人经》《菜根谭》十一部全文读本，"
          "每部备简体、繁體、English 三种，附注释，免费在线阅读，无需注册。"
          " Eleven Chinese classics in full text — Chinese original and a complete "
          "English translation of each, free to read.")
  s, n = re.subn(r"<title>.*?</title>", f"<title>{esc(title)}</title>", s, count=1, flags=re.S)
  assert n == 1
  m = re.search(r'<meta name="description" content="(.*?)">\n', s, flags=re.S)
  assert m
  block = [f'<meta name="description" content="{esc(desc)}">', MARK,
    '<meta name="robots" content="index,follow,max-snippet:-1,max-image-preview:large">',
    '<meta property="og:type" content="website">',
    '<meta property="og:site_name" content="典籍 · Chinese Classics">',
    f'<meta property="og:title" content="{esc(title)}">',
    f'<meta property="og:description" content="{esc(desc)}">',
    f'<meta property="og:url" content="{SHELF}">',
    f'<meta property="og:image" content="{ORIGIN}/img/classics/sunzi-cover.jpg">',
    '<meta property="og:locale" content="zh_CN">',
    '<meta property="og:locale:alternate" content="zh_TW">',
    '<meta property="og:locale:alternate" content="en_US">',
    '<meta name="twitter:card" content="summary">',
    f'<meta name="twitter:title" content="{esc(title)}">',
    f'<meta name="twitter:description" content="{esc(desc)}">',
    ld(g)]
  s = s[:m.start()] + "\n".join(block) + "\n" + s[m.end():]
  path.write_text(s, encoding="utf-8")
  print("  ok dianji.html")
  return 1

if __name__ == "__main__":
  n = 0
  for b in BOOKS:
    for v, suf, lang, locale in VARIANTS:
      n += patch_book(b, v, suf, lang, locale)
  n += patch_shelf()
  print(f"{n} pages patched")
