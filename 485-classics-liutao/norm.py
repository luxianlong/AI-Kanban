# -*- coding: utf-8 -*-
"""底本文字的清理：維基標記、混入的簡體、書中自亂的字形。改後寫回 canon.json。

底本是眾手錄入的，同一個詞在不同篇裡寫法不一：為／爲、強／彊、奸／姦、
旗／旂、陣／陳並見。本頁一律取多數的那一形，只改字形不改文字，逐類記數。
"""
import json, re

MARKUP = [
    (re.compile(r'-\{(.*?)\}-'), r'\1'),          # -{斗}- 是字詞轉換標記
    (re.compile(r'\{\{PD-old\}\}'), ''),          # 版權模板
    (re.compile(r'\{\{\*\|(.*?)\}\}'), ''),       # 錄文者所加的星號小注，非原文
]

# 混入的簡體（各一見，皆在成串的繁體之中）
SIMP = [('无', '無'), ('于', '於'), ('决', '決'), ('摇', '搖'),
        ('厩', '廄'), ('栖', '棲')]

# 同書自亂的字形，取多數形
GLYPH = [('爲', '為'), ('彊', '強'), ('姦', '奸'), ('旂', '旗')]

# 「陳」在本書裡二十五見，除《文啟》「陳其政教」（陳列）與《五音》「勾陳」
# （星名）外，其餘二十三處都是軍陣之「陣」，與書中已有的三十六個「陣」同詞。
# 一律作「陣」，那兩處留「陳」。
KEEP_CHEN = ['陳其政教', '勾陳']


def main():
    d = json.load(open('canon.json'))
    n = {}
    for p in d['pian']:
        t = p['text']
        for rx, rep in MARKUP:
            t, k = rx.subn(rep, t)
            n['標記'] = n.get('標記', 0) + k
        for a, b in SIMP + GLYPH:
            k = t.count(a)
            if k:
                t = t.replace(a, b)
                n[a + '→' + b] = n.get(a + '→' + b, 0) + k
        holds = []
        for w in KEEP_CHEN:
            while w in t:
                t = t.replace(w, '%d' % len(holds), 1)
                holds.append(w)
        k = t.count('陳')
        if k:
            t = t.replace('陳', '陣')
            n['陳→陣'] = n.get('陳→陣', 0) + k
        for i, w in enumerate(holds):
            t = t.replace('%d' % i, w)
        p['text'] = t
        # 篇題跟着正文走：《三陳第三十二》的「陳」與正文「天陳、地陳、人陳」
        # 是同一個詞，四庫本篇題正作「三陣第三十二」。
        if p['name'] == '三陳':
            p['name'], p['title'] = '三陣', '三陣第三十二'
            n['陳→陣'] = n.get('陳→陣', 0) + 1
    json.dump(d, open('canon.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('；'.join('%s %d' % (k, v) for k, v in sorted(n.items()) if v))


if __name__ == '__main__':
    main()
