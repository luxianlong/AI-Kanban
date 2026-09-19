# -*- coding: utf-8 -*-
"""四つの頁を機械検査する。錨点・id・タグの開閉・段落 id の一致など。"""
import json, os, re, sys, collections, struct

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SLUGS = ['simafa', 'simafa-hans', 'simafa-en', 'simafa-ja']
VOID = {'meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'col', 'wbr'}
ok = True
T = json.load(open(f'{D}/text.json', encoding='utf-8'))
NPARA = sum(len(j['paras']) for j in T)
NPIAN = len(T)


# 標點と空白を除いた字數。四庫寫本・宋鈔本には基本多文種面の外の字
# （𣪚・𦞃・𫉬 の類）があるので、範圍で絞らず「標點でない字」を數える。
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕“”‘’[]　 \n\t\u00b6')


def nchar(s):
    return sum(1 for c in s if c not in PUNCT)


def fail(*a):
    global ok
    ok = False
    print('  ✗', *a)


def check(slug):
    print('---', slug)
    s = open(os.path.join(ROOT, slug + '.html'), encoding='utf-8').read()
    body = s[s.index('<body'):]

    ids = re.findall(r'\sid="([^"]+)"', body)
    dup = [k for k, v in collections.Counter(ids).items() if v > 1]
    if dup:
        fail('id 重複', dup[:5])
    idset = set(ids)
    hrefs = set(re.findall(r'href="#([^"]+)"', body))
    dangling = sorted(h for h in hrefs if h and h not in idset)
    if dangling:
        fail('錨点が空を指す', dangling[:5])

    clean = re.sub(r'<script.*?</script>', '', body, flags=re.S)
    clean = re.sub(r'<style.*?</style>', '', clean, flags=re.S)
    depth = collections.Counter()
    for m in re.finditer(r'<(/?)([a-zA-Z][\w-]*)([^>]*?)(/?)>', clean):
        close, tag, self_close = m.group(1), m.group(2).lower(), m.group(4)
        if tag in VOID or self_close:
            continue
        depth[tag] += -1 if close else 1
    depth.pop('html', None)
    bad = {t: n for t, n in depth.items() if n != 0}
    if bad:
        fail('タグの開閉が合わない', bad)

    h2 = re.findall(r'<h2 id="([^"]+)"', body)
    h3 = re.findall(r'<h3[ >]', body)
    paras = [i for i in ids if re.fullmatch(r'p\d-\d+', i)]
    notes = [i for i in ids if re.fullmatch(r'ai-p\d-\d+', i)]
    toc = re.findall(r'<a class="l\d" href="#([^"]+)"', body)
    print('   h2 %d / h3 %d / 段 %d / 段註 %d / 目次 %d(×2)'
          % (len(h2), len(h3), len(paras), len(notes), len(toc) // 2))
    if len(h2) != NPIAN + 1:        # 五篇 ＋ 底本と校例
        fail('h2 が %d でない' % (NPIAN + 1), len(h2))
    if h3:
        fail('h3 がある（《司馬法》の篇の下に章は無い）', len(h3))
    if len(paras) != NPARA:
        fail('段が %d でない' % NPARA, len(paras))
    # 註釈が本文の生 HTML を持ち込んでいないか（#486 の頁は <b> が生で出た）
    if '&lt;b&gt;' in body or '&lt;i&gt;' in body:
        fail('註釈のなかの HTML タグが escape されて字として出ている')
    if slug in ('simafa', 'simafa-hans'):
        if len(notes) != NPARA:
            fail('段註が %d でない' % NPARA, len(notes))
        if body.count('class="ai ai-head"') != NPIAN:
            fail('篇旨の数', body.count('class="ai ai-head"'))
        if 'ai-juan::before{content:"卷旨"}' in s:
            fail('卷旨の規則が残っている（本書に卷は無い）')
        if 'ai-head::before{content:"篇旨"}' not in s:
            fail('篇旨の規則が無い')
    else:
        if notes:
            fail('EN/JA に開閉式の註がある')
        if 'id="ai"' in body:
            fail('EN/JA に註釈ボタンがある')
    half = toc[:len(toc) // 2]
    if half != toc[len(toc) // 2:]:
        fail('左の目次とモバイル目次が食い違う')
    if half != h2:
        fail('目次の並びが本文の見出しと違う', half, h2)
    # 讀本の切替が四つ揃い、自分だけが cur になっているか
    for other in SLUGS:
        if other == slug:
            continue
        if '/p/%s.html' % other not in body:
            fail('%s への切替が無い' % other)
    if body.count('aria-current="page"') != 1:
        fail('現在頁の印が %d 個' % body.count('aria-current="page"'))
    return paras


def cover():
    p = os.path.expanduser('~/Dashboard/frontend/public/img/classics/simafa-cover.jpg')
    if not os.path.exists(p):
        fail('封面が無い'); return
    b = open(p, 'rb').read()
    i, w, h = 2, None, None
    while i < len(b):
        if b[i] != 0xFF:
            break
        m = b[i + 1]
        if m in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack('>HH', b[i + 5:i + 9]); break
        i += 2 + struct.unpack('>H', b[i + 2:i + 4])[0]
    print('封面 %sx%s, %d bytes' % (w, h, len(b)))
    import build
    if (w, h) != (build.COVER_W, build.COVER_H):
        fail('封面の寸法が build.py の COVER_W/H と違う', w, h)
    # 書架の <img> の width/height と --ar も合っているか
    sh = open(os.path.join(ROOT, 'dianji.html'), encoding='utf-8').read()
    m = re.search(r'<div class="cover simafa"><img [^>]*width="(\d+)" height="(\d+)"', sh)
    if not m:
        fail('書架に封面の <img> が無い')
    elif (int(m.group(1)), int(m.group(2))) != (w, h):
        fail('書架の width/height が封面と違う', m.group(1), m.group(2))
    m = re.search(r'--ar:\.(\d+)"><\s*\n?\s*<div class="cover simafa"', sh) \
        or re.search(r'--ar:\.(\d+)">\s*\n\s*<div class="cover simafa"', sh)
    if m:
        ar = float('0.' + m.group(1))
        if abs(ar - w / h) > 0.001:
            fail('書架の --ar が封面の比と違う', ar, round(w / h, 4))
    else:
        fail('書架に --ar が見つからない')


def main():
    sets = {s: check(s) for s in SLUGS}
    base = sets['simafa']
    for s, v in sets.items():
        if v != base:
            fail('%s の段落 id が繁體と揃っていない' % s)
    print('--- 段落 id は四読本で完全に一致' if ok else '--- 不一致あり')

    n = sum(nchar(p) for j in T for p in j['paras'])
    print('本文 %d 字、段 %d、篇 %d' % (n, NPARA, NPIAN))
    if n != 3420:
        fail('本文が 3420 字でない', n)
    # 影宋鈔本と篇ごとの字數が一致するか（改字のあとの檢算）
    sb = json.load(open(f'{D}/sbck.json', encoding='utf-8'))
    same = True
    for j in T:
        a = sum(nchar(p) for p in j['paras'])
        b = nchar(sb[j['name']]['text'])
        if a != b:
            same = False
            fail('%s の字數が影宋鈔本と違う' % j['name'], a, b)
    if same:
        print('五篇とも字數は影宋鈔本と同じ')
    cover()
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
