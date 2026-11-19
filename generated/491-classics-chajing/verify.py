# -*- coding: utf-8 -*-
"""四つの頁を機械検査する。錨点・id・タグの開閉・段落 id の一致など。"""
import json, os, re, sys, collections

ROOT = os.path.expanduser('~/Dashboard/frontend/public/p')
SLUGS = ['chajing', 'chajing-hans', 'chajing-en', 'chajing-ja']
VOID = {'meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'col', 'wbr'}
ZHU = {}
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
    if len(h2) != 4:            # 三卷 + 底本と校例
        fail('h2 が 4 でない', len(h2))
    if len(h3) != 10:
        fail('h3 が 10 でない', len(h3))
    if len(paras) != 124:
        fail('段が 124 でない', len(paras))
    # 陸羽の自注と〈四之器〉の器の名。四讀本で同じ數でなければならない。
    print('   陸羽自注 %d / 器の名 %d'
          % (body.count('class="zhu"'), body.count('class="qm"')))
    if body.count('class="qm"') != 25:
        fail('器の名が 25 でない', body.count('class="qm"'))
    ZHU[slug] = body.count('class="zhu"')
    if slug in ('chajing', 'chajing-hans'):
        if len(notes) != 124:
            fail('段註が 124 でない', len(notes))
        if body.count('class="ai ai-head"') != 10 or body.count('ai-head ai-juan') != 3:
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
    base = sets['chajing']
    for s, v in sets.items():
        if v != base:
            fail('%s の段落 id が繁體と揃っていない' % s)
    print('--- 段落 id は四読本で完全に一致' if ok else '--- 不一致あり')
    # 陸羽の自注は譯文でも一つ殘らず譯す。四讀本で數が揃わなければ落ちている。
    if len(set(ZHU.values())) != 1:
        fail('陸羽自注の數が讀本ごとに違う', ZHU)
    else:
        print('陸羽自注 %d 條、四読本で同数' % next(iter(ZHU.values())))

    # 本文の字数
    T = json.load(open('text.json'))
    n = sum(len(re.sub(r'[^㐀-鿿]', '', ''.join(p['paras']))) for p in T['pian'])
    print('本文 漢字 %d 字、段 %d、篇 %d、卷 %d'
          % (n, sum(len(p['paras']) for p in T['pian']),
             len(T['pian']), len(T['juan'])))
    # 本文 ＝ 底本 ＋ 句讀の正規化 ＋ 字形の統一 ＋ 改字十六處。
    # canon.py がその通りに作っているが、頁に出たものを底本から作り直して
    # 逐字で照合する——途中で「うっかり直す」ことを防ぐための檢査。
    import canon
    B = json.load(open('base.json'))
    rebuilt = []
    for q in B['pian']:
        ps = [canon.apply(canon.apply(x, canon.PUNCT), canon.FORM)
              for x in q['paras']]
        for pian, old, new in canon.MEND:
            if pian == q['name']:
                ps = [x.replace(old, new) for x in ps]
        for pian, old, new, _ in canon.EDITS:
            if pian == q['name']:
                ps = [x.replace(old, new) for x in ps]
        rebuilt.append(''.join(ps))
    got = [''.join(p['paras']) for p in T['pian']]
    if rebuilt != got:
        bad = [T['pian'][i]['name'] for i in range(len(got))
               if rebuilt[i] != got[i]]
        fail('本文が「底本＋正規化＋改字十六處」と一致しない', bad)
    else:
        print('本文 ＝ 底本 ＋ 句讀の正規化 ＋ 字形の統一 ＋ 改字 %d 處'
              % len(canon.EDITS))

    # 封面の寸法
    from struct import unpack
    cov = os.path.expanduser('~/Dashboard/frontend/public/img/classics/'
                             'chajing-cover.jpg')
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
    if (w, h) != (560, 714):
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
