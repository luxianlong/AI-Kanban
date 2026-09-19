# -*- coding: utf-8 -*-
"""底本（維基文庫《司馬法》標點本）→ base.json。

《司馬法》は五篇。底本は標點のうえ段落まで切ってある——ここが《三略》と
違うところで、あちらは底本が一塊だったので劉寅の節界を借りた。こちらは
底本自身の分段をそのまま使い、本頁は段を一つも作らない。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
PIAN = ['仁本', '天子之義', '定爵', '嚴位', '用眾']


def main():
    s = open(f'{D}/raw/main.wiki', encoding='utf-8').read()
    body = s[s.index('__NOEDITSECTION__') + len('__NOEDITSECTION__'):]
    body = body.split('{{PD-old}}')[0]
    parts = re.split(r'^==(.+?)==\s*$', body, flags=re.M)
    out = []
    for k in range(1, len(parts), 2):
        name = parts[k].strip()
        paras = [p.strip() for p in parts[k + 1].split('\n') if p.strip()]
        assert not any(p.startswith(('{{', '*', '[[Category', '[[category'))
                       for p in paras), f'{name}: ウィキの記法が本文に混じっている'
        out.append({'name': name, 'paras': paras})
        print('%-6s %2d 段  %4d 字（標點込み）'
              % (name, len(paras), sum(len(p) for p in paras)))
    assert [j['name'] for j in out] == PIAN, [j['name'] for j in out]
    json.dump(out, open(f'{D}/base.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
