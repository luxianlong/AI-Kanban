# -*- coding: utf-8 -*-
"""《羣書治要》卷第三十七が抄する尉繚子 → qszy.json。

<b>この書の證人でいちばん古いのはこれである。</b>貞觀五年（六三一）
魏徵ら奉敕撰。中國では早く亡び、日本に殘った——金澤文庫本を尾張藩が
天明年閒に刊し、四部叢刊がその上海涵芬樓藏本を影印した。今傳わる
どの尉繚子の本子よりも四百年以上古い。

抄であって全文ではない。天官・兵談・戰威・兵令の四篇の一部だけを取り、
しかも本文が今本とかなり違う。だから<b>通校本としては扱わない</b>——
機械對校はするが、その結果は「異文」ではなく「唐初の別系統がどう
讀んでいたか」として註と卷末に出す。

  * 治要の〈兵談〉は今本の〈兵談〉と〈戰威〉の兩方に散らばる。
  * 治要の〈戰威〉は今本の〈戰威〉の中ほど。
  * 治要の〈兵令〉は今本の〈兵令上〉。
  * 治要だけにあって今本に無い句がある（「斷紂頭、懸之白旗」など）。

割註（〈…〉で括られた雙行の小字）は魏徵らが附けたものではなく、
抄した原書に元からある註とされる。尉繚子の抄にはこれが無い。
"""
import json, os, re
from difflib import SequenceMatcher

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕　 ')

# 治要の抄を、今本のどの篇に當たるかで切る。切った所の句は下で檢べる。
CUTS = ['天官', '兵談', '戰威', '兵令']


def text():
    t = json.load(open(f'{D}/raw/qszy37.json', encoding='utf-8'))
    t = t['parse']['text']['*']
    t = re.sub(r'<style.*?</style>', '', t, flags=re.S)
    t = re.sub(r'<[^>]+>', '', t)
    t = re.sub(r'\s+', '', t)
    t = t.replace('​', '').replace('&#8203;', '')
    i = t.rindex('尉繚子天官')
    s = t[i + 3:]
    s = s[:s.index('羣書治要卷第三十七')]
    assert '〈' not in s, '尉繚子の抄には割註が無いはず'
    return s


def plain(s):
    return ''.join(c for c in s if c not in PUNCT)


def main():
    s = text()
    # 篇題で切る
    pos = [(name, s.index(name)) for name in CUTS]
    for (a, i), (b, k) in zip(pos, pos[1:]):
        assert i < k, (a, b)
    out = {}
    for n, (name, i) in enumerate(pos):
        e = pos[n + 1][1] if n + 1 < len(pos) else len(s)
        out[name] = s[i + len(name):e]
    for k, v in out.items():
        print('治要〈%s〉%4d 字' % (k, len(v)))
    print('計', sum(len(v) for v in out.values()), '字')

    # 今本と突き合わせる。治要は抄なので、今本の側を「治要にある句の並び」で
    # 拾う。共通部分列を取って、治要にしか無い句・今本にしか無い句を出す。
    base = json.load(open(f'{D}/base.json', encoding='utf-8'))
    body = {}
    for j in base:
        for p in j['pian']:
            body[p['pian']] = plain(''.join(p['paras']))
    PAIR = [('天官', ['天官']), ('兵談', ['兵談', '戰威']),
            ('戰威', ['戰威']), ('兵令', ['兵令上'])]
    diffs = []
    for qname, bnames in PAIR:
        q = out[qname]
        b = ''.join(body[x] for x in bnames)
        sm = SequenceMatcher(None, b, q, autojunk=False)
        same = sum(i2 - i1 for tag, i1, i2, _, _ in sm.get_opcodes()
                   if tag == 'equal')
        run = [(i1, i2, k1, k2, tag) for tag, i1, i2, k1, k2
               in sm.get_opcodes() if tag != 'equal']
        for i1, i2, k1, k2, tag in run:
            if max(i2 - i1, k2 - k1) < 2:
                continue
            diffs.append({'qszy': qname, 'pian': '／'.join(bnames), 'tag': tag,
                          'today': b[i1:i2], 'tang': q[k1:k2],
                          'ctx': b[max(0, i1 - 12):i2 + 12]})
        print('治要〈%s〉↔ 今本〈%s〉  共通 %d 字 / 治要 %d 字（%.0f%%）、'
              '二字以上の差 %d 處'
              % (qname, '／'.join(bnames), same, len(q), 100 * same / len(q),
                 len([d for d in diffs if d['qszy'] == qname])))
    json.dump({'excerpt': out, 'diff': diffs},
              open(f'{D}/qszy.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('二字以上の差', len(diffs), '處 → qszy.json')


if __name__ == '__main__':
    main()
