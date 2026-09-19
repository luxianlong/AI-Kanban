# -*- coding: utf-8 -*-
"""把底本的篇次還原到兩個通校本一致的舊次第。

底本（維基文庫標點本）把《兵道》從《文韜》裡移進了《武韜》，並跟着把
發啟、文啟、文伐、順啟四篇各降一位：

    底本   文韜十一篇（文師…賞罰）　武韜六篇（發啟十二、文啟十三、文伐十四、
           順啟十五、兵道十六、三疑十七）
    景宋本 文韜十二篇（文師…賞罰、兵道）　武韜五篇（發啟、文啟、文伐、順啟、三疑）
    四庫本 同景宋本，且篇次明書「兵道第十二」「文啓第十四」「文伐第十五」
           「順啟第十六」「三疑第十七」

兩個各自獨立的舊本一致，底本孤證，故從舊本。本頁的篇次即由此定。
"""
import json, re

MOVE = {16: 12, 12: 13, 13: 14, 14: 15, 15: 16}
JUAN = [('文韜', 1, 12), ('武韜', 13, 17), ('龍韜', 18, 30),
        ('虎韜', 31, 42), ('豹韜', 43, 50), ('犬韜', 51, 60)]
NUM = '一二三四五六七八九十'


def cn(n):
    if n < 11:
        return NUM[n - 1]
    if n < 20:
        return '十' + (NUM[n - 11] if n > 10 else '')
    return NUM[n // 10 - 1] + '十' + (NUM[n % 10 - 1] if n % 10 else '')


def main():
    base = json.load(open('base.json'))
    pian = []
    for p in base['pian']:
        n = MOVE.get(p['n'], p['n'])
        pian.append({'n': n, 'name': p['name'],
                     'title': p['name'] + '第' + cn(n), 'text': p['text']})
    pian.sort(key=lambda p: p['n'])
    assert [p['n'] for p in pian] == list(range(1, 61))
    out = {'juan': [{'n': i, 'name': nm, 'from': a, 'to': b}
                    for i, (nm, a, b) in enumerate(JUAN, 1)],
           'pian': pian}
    json.dump(out, open('canon.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    for j in out['juan']:
        print(j['name'], j['to'] - j['from'] + 1, '篇：',
              '、'.join(p['name'] for p in pian if j['from'] <= p['n'] <= j['to']))


if __name__ == '__main__':
    main()
