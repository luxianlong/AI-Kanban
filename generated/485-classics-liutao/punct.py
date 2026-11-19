# -*- coding: utf-8 -*-
"""底本標點的三處硬傷，改正後寫回 canon.json。

一、《文伐第十五》十二節，底本在「一曰：」「二曰：」……之後各下一個上引號而
    從不收口——全篇十四個「只兩個」，剩下十二個是這麼來的。十二節本在太公
    一段話之內，再加一層引號也該用『』；今依通行標點，徑刪這十二個。
二、《軍略第三十五》「為之奈何？」太公曰：」太公曰：「凡帥師將眾……」——
    「太公曰：」重出，前一個連引號一併衍。刪。
三、三處全形／半形空格（《文師第一》），刪。
"""
import json, re

DITTO = ('為之奈何？」太公曰：」太公曰：「', '為之奈何？」太公曰：「')


def main():
    c = json.load(open('canon.json'))
    n_quote = n_ditto = n_space = 0
    for p in c['pian']:
        t = p['text']
        if p['n'] == 15:
            t, k = re.subn(r'((?:[一二三四五六七八九十]+)曰：)「', r'\1', t)
            n_quote += k
        if p['n'] == 35 and DITTO[0] in t:
            t = t.replace(*DITTO)
            n_ditto += 1
        t2 = re.sub(r'\s+', '', t)
        n_space += len(t) - len(t2)
        t = t2
        # 」。 → 。」（收口在句號之後）
        t = t.replace('」。', '。」').replace('』。', '。』')
        p['text'] = t
    json.dump(c, open('canon.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    bad = [(p['n'], p['text'].count('「'), p['text'].count('」'))
           for p in c['pian'] if p['text'].count('「') != p['text'].count('」')
           or p['text'].count('『') != p['text'].count('』')]
    print('刪衍引號', n_quote, '刪重出', n_ditto, '刪空白', n_space,
          '；引號不配平的篇：', bad)
    if bad:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
