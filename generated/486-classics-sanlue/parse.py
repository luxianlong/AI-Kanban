# -*- coding: utf-8 -*-
"""底本（維基文庫《三略》標點本）→ base.json。

三卷に切るだけ。標點はあるが段落は切られていないので、卷ごとに一塊のまま
持っておく。分段は後で seg.py が劉寅の節界に従って行う。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))


def main():
    s = open(f'{D}/raw/main.wiki', encoding='utf-8').read()
    body = s[s.index('__NOEDITSECTION__') + len('__NOEDITSECTION__'):]
    body = body.split('{{秦朝作品}}')[0]          # 卷末のテンプレートと分類を落とす
    parts = re.split(r'^==(.+?)==\s*$', body, flags=re.M)
    out = []
    for k in range(1, len(parts), 2):
        name = parts[k].strip()
        paras = [p.strip() for p in parts[k + 1].split('\n') if p.strip()]
        assert not any(p.startswith(('{{', '[[Category', '[[category')) for p in paras), \
            f'{name}: ウィキの記法が本文に混じっている'
        out.append({'name': name, 'paras': paras})
        print(name, len(paras), '塊', sum(len(p) for p in paras), '字（標點込み）')
    assert [j['name'] for j in out] == ['上略', '中略', '下略'], [j['name'] for j in out]
    json.dump(out, open(f'{D}/base.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
