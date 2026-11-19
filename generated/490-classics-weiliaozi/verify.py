# -*- coding: utf-8 -*-
"""四つの頁を機械検査する。錨点・id・タグの開閉・段落 id の一致など。"""
import json, os, re, sys, collections

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SLUGS = ['weiliaozi', 'weiliaozi-hans', 'weiliaozi-en', 'weiliaozi-ja']
VOID = {'meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'col', 'wbr'}
ok = True


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

    # タグの開閉（script/style の中は数えない）
    clean = re.sub(r'<script.*?</script>', '', body, flags=re.S)
    clean = re.sub(r'<style.*?</style>', '', clean, flags=re.S)
    depth = collections.Counter()
    for m in re.finditer(r'<(/?)([a-zA-Z][\w-]*)([^>]*?)(/?)>', clean):
        close, tag, attrs, self_close = m.group(1), m.group(2).lower(), m.group(3), m.group(4)
        if tag in VOID or self_close:
            continue
        depth[tag] += -1 if close else 1
    # body から切り出しているので </html> だけ相方がいない。除く。
    depth.pop('html', None)
    bad = {t: n for t, n in depth.items() if n != 0}
    if bad:
        fail('タグの開閉が合わない', bad)

    h2 = re.findall(r'<h2 id="([^"]+)"', body)
    h3 = re.findall(r'<h3 id="([^"]+)"', body)
    paras = [i for i in ids if re.fullmatch(r'c\d+-\d+', i)]
    notes = [i for i in ids if re.fullmatch(r'ai-c\d+-\d+', i)]
    toc = re.findall(r'<a class="l[23]" href="#([^"]+)"', body)
    print('   h2 %d / h3 %d / 段 %d / 段註 %d / 目次 %d(×2)'
          % (len(h2), len(h3), len(paras), len(notes), len(toc) // 2))
    if len(h2) != 6:            # 五卷 + 底本と校例
        fail('h2 が 6 でない', len(h2))
    if len(h3) != 24:
        fail('h3 が 24 でない', len(h3))
    if len(paras) != 140:
        fail('段が 140 でない', len(paras))
    if slug in ('weiliaozi', 'weiliaozi-hans'):
        if len(notes) != 140:
            fail('段註が 140 でない', len(notes))
        if body.count('class="ai ai-head"') != 24 or body.count('ai-head ai-juan') != 5:
            fail('篇旨/卷旨の数', body.count('class="ai ai-head"'),
                 body.count('ai-head ai-juan'))
    else:
        if notes:
            fail('EN/JA に開閉式の註がある')
        if 'id="ai"' in body:
            fail('EN/JA に註釈ボタンがある')
    # 目次は左と mobile の二重
    half = toc[:len(toc) // 2]
    if half != toc[len(toc) // 2:]:
        fail('左の目次とモバイル目次が食い違う')
    # 目次の並びは h2/h3 の並びと同じでなければならない
    want, P = [], re.findall(r'<h[23] id="([^"]+)"', body)
    if half != P:
        fail('目次の並びが本文の見出しと違う')
    return paras


def main():
    sets = {s: check(s) for s in SLUGS}
    base = sets['weiliaozi']
    for s, v in sets.items():
        if v != base:
            fail('%s の段落 id が繁體と揃っていない' % s)
    print('--- 段落 id は四読本で完全に一致' if ok else '--- 不一致あり')

    # 本文の字数
    T = json.load(open('text.json'))
    n = sum(len(re.sub(r'[^㐀-鿿]', '', ''.join(p['paras']))) for p in T['pian'])
    print('本文 漢字 %d 字、段 %d、篇 %d、卷 %d'
          % (n, sum(len(p['paras']) for p in T['pian']),
             len(T['pian']), len(T['juan'])))
    # 本文＝四庫本＋句讀＋改字二處。base.py が逐字で檢べているが、
    # 頁に出たものについても改めて同じことを檢べる。
    PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕　 ')
    W = json.load(open('wyg.json'))
    want = ''.join(x['text'] for j in ['卷一', '卷二', '卷三', '卷四', '卷五']
                   for x in W[j])
    got = ''.join(c for p in T['pian'] for x in p['paras'] for c in x
                  if c not in PUNCT)
    if len(got) != len(want):
        fail('本文の字數が四庫本と違う', len(got), len(want))
    else:
        diff = [(i, a, b) for i, (a, b) in enumerate(zip(want, got)) if a != b]
        if [(a, b) for _, a, b in diff] != [('形', '刑'), ('心', '必')]:
            fail('四庫本との相違が改字二處と一致しない', diff[:6])
        else:
            print('本文＝四庫本＋句讀、改めたのは形→刑・心→必の二字だけ')

    # 封面の寸法
    from struct import unpack
    cov = os.path.expanduser('~/Dashboard/frontend/public/img/classics/'
                             'weiliaozi-cover.jpg')
    d = open(cov, 'rb').read()
    i, w, h = 2, None, None
    while i < len(d):
        if d[i] != 0xFF:
            break
        m = d[i + 1]
        if m in (0xC0, 0xC1, 0xC2):
            h, w = unpack('>HH', d[i + 5:i + 9])
            break
        i += 2 + unpack('>H', d[i + 2:i + 4])[0]
    if (w, h) != (560, 796):
        fail('封面の寸法', w, h)
    else:
        print('封面 %dx%d、%d bytes' % (w, h, len(d)))

    # 註釋・卷末に「「X」…作「X」」という自分を取り消す校語が無いこと
    for f in ('notes.json', 'notes-hans.json'):
        N = json.load(open(f))
        txt = ' '.join(list(N['para'].values()) + list(N['pian'].values())
                       + list(N['juan'].values()) + N['colophon'])
        bad = [m.group(0) for m in
               re.finditer(r'「([\u4e00-\u9fff])」[^「」]{0,12}作「\1」', txt)]
        if bad:
            fail('%s に自分を取り消す校語' % f, bad[:3])
    if ok:
        print('自分を取り消す校語 0 處')

    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
