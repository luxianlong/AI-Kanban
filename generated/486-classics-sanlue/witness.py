# -*- coding: utf-8 -*-
"""通校二本を巻ごとに切り出す。

  siku.json   四庫全書本《黃石公三畧》白文（無句讀）。葉・半葉の界も持つ。
  zhijie.json 四庫全書本《三畧直解》（明 劉寅）の經文だけ。劉寅の註は
              字下げ一字で始まるので、行頭が字下げでない行だけを拾えば
              經文が残る。劉寅が切った節の界（＝註が挟まる所）も持つ。
"""
import re, json, os

D = os.path.dirname(os.path.abspath(__file__))


def read(path):
    """(kind, value) の列。kind は 'PB'（葉界）か 'L'（本文行）。"""
    out = []
    for ln in open(path, encoding='utf-8').read().split('\n'):
        if ln.startswith('#') or not ln.strip():
            continue
        m = re.match(r'^<pb:([^>]+)>¶?$', ln)
        if m:
            out.append(('PB', m.group(1)))
        else:
            out.append(('L', ln.rstrip('¶')))
    return out


def siku(n):
    """白文本の一巻。卷首の「欽定四庫全書」「黃石公三畧卷上」は落とす。"""
    rows = read(f'{D}/raw/wyg/KR3b0007_{n:03d}.txt')
    text, leaf, leaves = '', None, []
    started = False
    for kind, v in rows:
        if kind == 'PB':
            leaf = v.split('_')[-1]
            continue
        s = v.strip('　 ')
        if not started:
            if s.startswith('欽定四庫全書') or s.startswith('黃石公三畧卷') \
               or s.startswith('黄石公三畧卷'):
                continue
            started = True
        if s.startswith('黃石公三畧卷') or s.startswith('黄石公三畧卷'):
            continue          # 卷末の尾題
        leaves.append((len(text), leaf))
        text += s
    return {'text': text, 'leaves': leaves}


def zhijie(n):
    """《三畧直解》の經文。行頭が字下げでない行だけ。"""
    rows = read(f'{D}/raw/zhijie/KR3b0008_{n:03d}.txt')
    secs, cur = [], []
    started = False
    for kind, v in rows:
        if kind == 'PB':
            continue
        if v.startswith('　'):           # 劉寅の註
            if cur:
                secs.append(''.join(cur)); cur = []
            continue
        s = v.strip('　 ')
        if s.startswith('欽定四庫全書') or s.startswith('三畧直解卷'):
            continue
        started = True
        cur.append(s)
    if cur:
        secs.append(''.join(cur))
    return secs


if __name__ == '__main__':
    sk = {name: siku(i) for i, name in ((1, '上略'), (2, '中略'), (3, '下略'))}
    zj = {name: zhijie(i) for i, name in ((1, '上略'), (2, '中略'), (3, '下略'))}
    for k in sk:
        print(f'{k}  四庫白文 {len(sk[k]["text"])} 字 / {len(sk[k]["leaves"])} 行'
              f'　直解經文 {sum(len(s) for s in zj[k])} 字 / {len(zj[k])} 節')
    json.dump(sk, open(f'{D}/siku.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump(zj, open(f'{D}/zhijie.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
