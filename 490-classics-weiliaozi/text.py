# -*- coding: utf-8 -*-
"""canon.json → text.json（build.py が讀む形）。

卷は五、篇は二十四。<b>篇より下の界は原書に無い</b>——章も節も小標題も
無いので、本頁も立てない（#483 の線）。段だけは讀むために切った。
それが本頁の仕事であることは卷末に明記してある。
"""
import json, os

D = os.path.dirname(os.path.abspath(__file__))
PUNCT = set('，。、；：？！「」『』（）《》〈〉…—·．〔〕　 ')
NUM = '一二三四五六七八九十'


def cn(n):
    if n <= 10:
        return NUM[n - 1]
    if n < 20:
        return '十' + NUM[n - 11]
    return NUM[n // 10 - 1] + '十' + (NUM[n % 10 - 1] if n % 10 else '')


def nchar(s):
    return sum(1 for c in s if c not in PUNCT)


def main():
    B = json.load(open(f'{D}/canon.json', encoding='utf-8'))
    juan, pian, tot = [], [], 0
    for n, j in enumerate(B, 1):
        ps = j['pian']
        juan.append({'n': n, 'name': j['name'],
                     'from': ps[0]['n'], 'to': ps[-1]['n']})
        for p in ps:
            c = sum(nchar(x) for x in p['paras'])
            tot += c
            pian.append({'n': p['n'], 'name': p['pian'],
                         'title': '%s第%s' % (p['pian'], cn(p['n'])),
                         'paras': p['paras']})
        print('%s  %s第%s—%s第%s  %d 篇 %3d 段 %5d 字'
              % (j['name'], ps[0]['pian'], cn(ps[0]['n']),
                 ps[-1]['pian'], cn(ps[-1]['n']), len(ps),
                 sum(len(p['paras']) for p in ps),
                 sum(nchar(x) for p in ps for x in p['paras'])))
    json.dump({'juan': juan, 'pian': pian},
              open(f'{D}/text.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('計 %d 卷 %d 篇 %d 段 %d 字　最長の段 %d 字'
          % (len(juan), len(pian), sum(len(p['paras']) for p in pian), tot,
             max(len(x) for p in pian for x in p['paras'])))


if __name__ == '__main__':
    main()
