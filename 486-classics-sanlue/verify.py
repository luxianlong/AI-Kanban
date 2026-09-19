# -*- coding: utf-8 -*-
"""四つの頁を機械検査する。錨点・id・タグの開閉・段落 id の一致など。"""
import json, os, re, sys, collections

D = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SLUGS = ['sanlue', 'sanlue-hans', 'sanlue-en', 'sanlue-ja']
VOID = {'meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'col', 'wbr'}
ok = True
T = json.load(open(f'{D}/text.json', encoding='utf-8'))
NPARA = sum(len(j['paras']) for j in T)


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
    toc = re.findall(r'<a class="l\d" href="#([^"]+)"', body)
    print('   h2 %d / h3 %d / 段 %d / 段註 %d / 目次 %d(×2)'
          % (len(h2), len(h3), len(paras), len(notes), len(toc) // 2))
    if len(h2) != 4:            # 上略・中略・下略 ＋ 底本と校例
        fail('h2 が 4 でない', len(h2))
    if h3:
        fail('h3 がある（《三略》に篇章は無い）', len(h3))
    if len(paras) != NPARA:
        fail('段が %d でない' % NPARA, len(paras))
    if slug in ('sanlue', 'sanlue-hans'):
        if len(notes) != NPARA:
            fail('段註が %d でない' % NPARA, len(notes))
        if body.count('ai-head ai-juan') != 3:
            fail('卷旨の数', body.count('ai-head ai-juan'))
        if 'ai-head::before{content:"篇旨"}' in s:
            fail('篇旨の規則が残っている（本書に篇は無い）')
    else:
        if notes:
            fail('EN/JA に開閉式の註がある')
        if 'id="ai"' in body:
            fail('EN/JA に註釈ボタンがある')
    half = toc[:len(toc) // 2]
    if half != toc[len(toc) // 2:]:
        fail('左の目次とモバイル目次が食い違う')
    heads = re.findall(r'<h2 id="([^"]+)"', body)
    if half != heads:
        fail('目次の並びが本文の見出しと違う', half, heads)
    # 讀本の切替が四つ揃い、自分だけが cur になっているか
    for other in SLUGS:
        if other == slug:
            continue
        if '/p/%s.html' % other not in body:
            fail('%s への切替が無い' % other)
    if body.count('aria-current="page"') != 1:
        fail('現在頁の印が %d 個' % body.count('aria-current="page"'))
    return paras


def main():
    sets = {s: check(s) for s in SLUGS}
    base = sets['sanlue']
    for s, v in sets.items():
        if v != base:
            fail('%s の段落 id が繁體と揃っていない' % s)
    print('--- 段落 id は四読本で完全に一致' if ok else '--- 不一致あり')

    n = sum(len(re.sub(r'[^㐀-鿿]', '', p)) for j in T for p in j['paras'])
    print('本文 漢字 %d 字、段 %d、卷 %d' % (n, NPARA, len(T)))
    if n != 3816:   # 底本 3815 ＋ 補った脫字一字
        fail('本文が 3816 字でない', n)

    # 封面が実在し、build.py の言う寸法であるか
    import struct
    cov = os.path.expanduser('~/Dashboard/frontend/public/img/classics/sanlue-cover.jpg')
    if not os.path.exists(cov):
        fail('封面が無い')
    else:
        b = open(cov, 'rb').read()
        i, w, h = 2, None, None
        while i < len(b):
            if b[i] != 0xFF:
                break
            m = b[i + 1]
            if m in (0xC0, 0xC1, 0xC2):
                h, w = struct.unpack('>HH', b[i + 5:i + 9]); break
            i += 2 + struct.unpack('>H', b[i + 2:i + 4])[0]
        print('封面 %sx%s, %d bytes' % (w, h, len(b)))
        if (w, h) != (560, 795):
            fail('封面の寸法が build.py の COVER_W/H と違う', w, h)
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
