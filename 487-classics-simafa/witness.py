# -*- coding: utf-8 -*-
"""通校二本を篇ごとに切り出す。

  sbck.json 四部叢刊本《司馬法》三卷（上海涵芬樓借常熟瞿氏鐵琴銅劍樓藏
            影宋鈔本影印。Kanripo KR3b0005 の SBCK 枝）。葉・半葉の界を持つ。
  wyg.json  四庫全書文淵閣本（同 KR3b0005 の WYG 枝）。

どちらも無句讀。Mandoku の外字實體（&KR0002; の類）は KR-Gaiji の
charlist で Unicode の正字に直す。

SBCK のファイルは卷の並びが「上 → 下 → 中」になっている。原本が
「卷上・卷中・卷下」であることは各卷端の題（司馬法卷上／司馬灋卷中／
司馬灋卷下）自身が言っているので、篇名を鍵にして並べ直す。
"""
import re, json, os

D = os.path.dirname(os.path.abspath(__file__))
PIAN = ['仁本', '天子之義', '定爵', '嚴位', '用眾']
HEAD = re.compile(r'^(仁本|天子之義|定爵|嚴位|用衆|用眾)第[一二三四五]$')
# 卷端・卷尾の題。「灋」は「法」の古文で、この本は卷中・卷下の題に使う。
JUAN = re.compile(r'^司馬(法|灋)卷[上中下]$')


def gaiji():
    """KR-Gaiji charlist.org.txt → {'KR0002': '若', ...}"""
    m = {}
    for ln in open(f'{D}/var/kr-charlist.org.txt', encoding='utf-8'):
        f = ln.rstrip('\n').split('\t')
        if len(f) >= 4 and f[0].startswith('KR') and f[3]:
            m[f[0]] = f[3]
    return m


def lines(path, g):
    """本文行だけ。(葉, 行) を返す。"""
    out, leaf = [], None
    for ln in open(path, encoding='utf-8').read().split('\n'):
        ln = ln.strip()
        if not ln or ln.startswith('#'):
            continue
        m = re.match(r'^<pb:([^>]+)>¶?$', ln)
        if m:
            leaf = m.group(1).split('_')[-1]
            continue
        # WYG 枝は SBCK の葉界 <md:...> を本文行の途中に挟む。落とす。
        ln = re.sub(r'<md:[^>]+>', '', ln)
        ln = ln.replace('¶', '').strip('　 ')
        if not ln:
            continue
        ln = re.sub(r'&(KR\d+);', lambda m: g[m.group(1)], ln)
        assert '&' not in ln, ln
        out.append((leaf, ln))
    return out


# 四庫全書本は《用眾第五》の篇題を落としている（維基文庫の四庫本翻刻も
# 同じ——別々の二つの翻刻が揃って落とすからには、翻刻の誤りではなく
# この本自身がそうなのだろう）。《嚴位》の末「自古之政也」の直後、
# 《用眾》の首句がそのまま續く。そこで切る。
WYG_CUT = ('自古之政也', '凡戰之道用寡固')


def cut(rows):
    """篇名の行で切る。卷端・卷尾の題と四庫の卷首は落とす。"""
    out, cur, name = {}, None, None
    for leaf, ln in rows:
        if JUAN.match(ln) or ln.startswith('欽定四庫全書') or ln == '司馬法':
            continue
        m = HEAD.match(ln)
        if m:
            name = m.group(1).replace('用衆', '用眾')
            cur = out.setdefault(name, {'text': '', 'leaves': []})
            continue
        if cur is None:
            continue
        cur['leaves'].append((len(cur['text']), leaf))
        cur['text'] += ln
    return out


def split_wyg(w):
    """四庫本の《嚴位》から《用眾》を切り出す。"""
    if '用眾' in w:
        return w
    t = w['嚴位']['text']
    tail, head = WYG_CUT
    assert t.count(tail + head) == 1
    i = t.index(tail + head) + len(tail)
    lv = w['嚴位']['leaves']
    w['嚴位'] = {'text': t[:i], 'leaves': [x for x in lv if x[0] < i]}
    w['用眾'] = {'text': t[i:], 'leaves': [(x[0] - i, x[1]) for x in lv if x[0] >= i]}
    print('四庫全書本：《用眾第五》の篇題が無い。'
          '《嚴位》末「%s」の後で切った（%d 字）。' % (tail, len(t) - i))
    return w


def main():
    g = gaiji()
    sb = cut(lines(f'{D}/raw/sbck/KR3b0005_000.txt', g))
    wy = split_wyg(cut(lines(f'{D}/raw/wyg/KR3b0005_000.txt', g)))
    for w, label in ((sb, '四部叢刊影宋鈔本'), (wy, '四庫全書本')):
        assert sorted(w) == sorted(PIAN), (label, sorted(w))
        print(label, '　'.join('%s %d 字' % (k, len(w[k]['text'])) for k in PIAN))
    json.dump({k: sb[k] for k in PIAN},
              open(f'{D}/sbck.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump({k: wy[k] for k in PIAN},
              open(f'{D}/wyg.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
