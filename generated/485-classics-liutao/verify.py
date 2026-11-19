# -*- coding: utf-8 -*-
"""四つの頁を機械検査する。錨点・id・タグの開閉・段落 id の一致など。"""
import json, os, re, sys, collections

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SLUGS = ['liutao', 'liutao-hans', 'liutao-en', 'liutao-ja']
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
    if len(h2) != 7:            # 六韜 + 底本と校例
        fail('h2 が 7 でない', len(h2))
    if len(h3) != 60:
        fail('h3 が 60 でない', len(h3))
    if len(paras) != 323:
        fail('段が 323 でない', len(paras))
    if slug in ('liutao', 'liutao-hans'):
        if len(notes) != 323:
            fail('段註が 323 でない', len(notes))
        if body.count('class="ai ai-head"') != 60 or body.count('ai-head ai-juan') != 6:
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
    base = sets['liutao']
    for s, v in sets.items():
        if v != base:
            fail('%s の段落 id が繁體と揃っていない' % s)
    print('--- 段落 id は四読本で完全に一致' if ok else '--- 不一致あり')

    # 本文の字数
    T = json.load(open('text.json'))
    n = sum(len(re.sub(r'[^㐀-鿿]', '', p['text'])) for p in T['pian'])
    print('本文 漢字 %d 字、段 %d、篇 %d、卷 %d'
          % (n, sum(len(p['paras']) for p in T['pian']),
             len(T['pian']), len(T['juan'])))
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
