# -*- coding: utf-8 -*-
"""四つの頁を機械検査する。錨点・id・タグの開閉・段落 id の一致など。"""
import json, os, re, sys, collections, struct

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SLUGS = ['tangliwendui', 'tangliwendui-hans', 'tangliwendui-en', 'tangliwendui-ja']
VOID = {'meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'col', 'wbr'}
ok = True
T = json.load(open(f'{D}/text.json', encoding='utf-8'))
NPARA = sum(len(j['paras']) for j in T)
NJUAN = len(T)
NCHAP = sum(len(j['chap']) for j in T)
# 問答の界の罫は、各卷の一段目には付けない。
NQI = NCHAP - NJUAN

PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕“”‘’[]　 \n\t¶')


def nchar(s):
    return sum(1 for c in s if c not in PUNCT)


def canon_apply(p):
    """canon.py が底本に掛ける處理（十二處の改字・字形・標點）を再現する。"""
    import canon
    for juan, old, new, why in canon.FIX:
        p = p.replace(old, new)
    for old, new in canon.UNIFY + canon.PUNCT:
        p = p.replace(old, new)
    return p


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
    paras = [i for i in ids if re.fullmatch(r'j\d-\d+', i)]
    notes = [i for i in ids if re.fullmatch(r'ai-j\d-\d+', i)]
    qi = len(re.findall(r'<p id="j\d-\d+" class="qi">', body))
    toc = re.findall(r'<a class="l\d" href="#([^"]+)"', body)
    print('   h2 %d / h3 %d / 段 %d / 段註 %d / 問答の罫 %d / 目次 %d(×2)'
          % (len(h2), len(h3), len(paras), len(notes), qi, len(toc) // 2))
    if len(h2) != NJUAN + 1:        # 三卷 ＋ 底本と校例
        fail('h2 が %d でない' % (NJUAN + 1), len(h2))
    if h3:
        fail('h3 がある（《唐李問對》の卷の下に篇も章も無い）', len(h3))
    if len(paras) != NPARA:
        fail('段が %d でない' % NPARA, len(paras))
    if qi != NQI:
        fail('問答の界の罫が %d でない' % NQI, qi)
    if '&lt;b&gt;' in body or '&lt;i&gt;' in body:
        fail('註釈のなかの HTML タグが escape されて字として出ている')
    if 'main p.qi::before' not in s:
        fail('問答の界の罫の規則が無い')
    if slug in ('tangliwendui', 'tangliwendui-hans'):
        if len(notes) != NPARA:
            fail('段註が %d でない' % NPARA, len(notes))
        if body.count('class="ai ai-head ai-juan"') != NJUAN:
            fail('卷旨の数', body.count('class="ai ai-head ai-juan"'))
        if 'ai-juan::before{content:"卷旨"}' not in s:
            fail('卷旨の規則が無い')
        if 'content:"篇旨"' in s:
            fail('篇旨の規則が残っている（本書に篇は無い）')
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
    for other in SLUGS:
        if other == slug:
            continue
        if '/p/%s.html' % other not in body:
            fail('%s への切替が無い' % other)
    if body.count('aria-current="page"') != 1:
        fail('現在頁の印が %d 個' % body.count('aria-current="page"'))
    return paras


def cover():
    p = os.path.expanduser(
        '~/Dashboard/frontend/public/img/classics/tangliwendui-cover.jpg')
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
    sh = open(os.path.join(ROOT, 'dianji.html'), encoding='utf-8').read()
    m = re.search(r'<div class="cover tangliwendui"><img [^>]*width="(\d+)" height="(\d+)"', sh)
    if not m:
        fail('書架に封面の <img> が無い')
    elif (int(m.group(1)), int(m.group(2))) != (w, h):
        fail('書架の width/height が封面と違う', m.group(1), m.group(2))
    m = re.search(r'--ar:\.(\d+)">\s*\n\s*<div class="cover tangliwendui"', sh)
    if m:
        ar = float('0.' + m.group(1))
        if abs(ar - w / h) > 0.001:
            fail('書架の --ar が封面の比と違う', ar, round(w / h, 4))
    else:
        fail('書架に --ar が見つからない')


def main():
    sets = {s: check(s) for s in SLUGS}
    base = sets['tangliwendui']
    for s, v in sets.items():
        if v != base:
            fail('%s の段落 id が繁體と揃っていない' % s)
    print('--- 段落 id は四読本で完全に一致' if ok else '--- 不一致あり')

    n = sum(nchar(p) for j in T for p in j['paras'])
    print('本文 %d 字、段 %d、問對 %d、卷 %d' % (n, NPARA, NCHAP, NJUAN))
    if n != 10292:
        fail('本文が 10292 字でない', n)
    # 本文は「底本＋canon.py の處理」と逐字同じか。改字は十二處だけのはず。
    import canon
    B = json.load(open(f'{D}/base.json', encoding='utf-8'))
    bad = 0
    for jb, jt in zip(B, T):
        pb = [canon_apply(p) for p in jb['paras']]
        if pb != jt['paras']:
            bad += sum(1 for x, y in zip(pb, jt['paras']) if x != y)
    if bad:
        fail('text.json が底本＋canon.py の變換と一致しない', bad, '段')
    else:
        print('本文は底本に canon.py の十二處の改字と字形・標點の處理だけを'
              '掛けたものと逐字一致（改字 %d 處）' % len(canon.FIX))
    if len(canon.FIX) != 12:
        fail('改字が十二處でない', len(canon.FIX))
    # 改めた十二處が本当に頁に出ているか
    page = open(os.path.join(ROOT, 'tangliwendui.html'), encoding='utf-8').read()
    # 本文の段落だけを取り出す。註釋と凡例は底本の誤りを引用するので除く。
    bodytext = '\n'.join(re.findall(r'<p id="j\d-\d+"[^>]*>(.*?)</p>', page))
    for juan, old, new, why in canon.FIX:
        if new not in bodytext:
            fail('改めた形が本文に無い', new)
        if old in bodytext:
            fail('底本の誤りが本文に残っている', old)
        if old not in page:
            fail('底本の形が註釋に出校されていない', old)
    # 卷ごとの字數を四庫本と並べて印字
    wy = json.load(open(f'{D}/wyg.json', encoding='utf-8'))
    for j in T:
        print('   %-4s %5d 字　四庫本 %5d' % (j['name'],
              sum(nchar(p) for p in j['paras']), nchar(wy[j['name']]['text'])))
    cover()
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
