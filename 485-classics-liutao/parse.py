# -*- coding: utf-8 -*-
"""底本（維基文庫標點本）→ base.json：六韜六十篇，篇下仍是一整塊。"""
import json, re

JUAN = [
    ('文韜', 1, 11), ('武韜', 12, 17), ('龍韜', 18, 30),
    ('虎韜', 31, 42), ('豹韜', 43, 50), ('犬韜', 51, 60),
]

def main():
    s = open('raw/main.wiki', encoding='utf-8').read()
    # 卷首的《四庫提要》在 Header 的 notes= 裡，本頁不轉錄，只在〈底本與校例〉引其斷語
    body = s[s.index('==文韜=='):]
    body = re.sub(r'\[\[Category:[^\]]*\]\]', '', body)

    pian, cur = [], None
    for line in body.split('\n'):
        line = line.strip()
        if not line:
            continue
        m = re.match(r'^===\s*(.+?)\s*===$', line)
        if m:
            cur = {'title': m.group(1), 'raw': []}
            pian.append(cur)
            continue
        if line.startswith('=='):
            continue
        # 底本在每韜之下多一行「第一篇 文韜」之類。韜是卷不是篇，這是錄文者所加，
        # 與原書六卷六十篇的層級相牴觸，不取（見〈底本與校例〉凡例）。
        if re.match(r'^第[一二三四五六]篇\s', line):
            continue
        cur['raw'].append(line)

    assert len(pian) == 60, len(pian)
    out = {'juan': [], 'pian': []}
    for i, (name, a, b) in enumerate(JUAN, 1):
        out['juan'].append({'n': i, 'name': name, 'from': a, 'to': b})
    for n, p in enumerate(pian, 1):
        t = p['title']
        # 篇題形如「文師第一」。篇次是原書自有的（景宋本作「文師」，四庫本作
        # 「文師第一」），不是本頁所立。
        m = re.match(r'^(.+?)第([一二三四五六七八九十百]+)$', t)
        assert m, t
        out['pian'].append({'n': n, 'title': t, 'name': m.group(1),
                            'text': ''.join(p['raw'])})
    json.dump(out, open('base.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    tot = sum(len(re.sub(r'[^㐀-鿿]', '', p['text'])) for p in out['pian'])
    print('篇', len(out['pian']), '漢字', tot)

if __name__ == '__main__':
    main()
