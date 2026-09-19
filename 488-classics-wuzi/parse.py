# -*- coding: utf-8 -*-
"""底本（維基文庫《吳子》標點本）→ base.json。

《吳子》は六篇（圖國・料敵・治兵・論將・應變・勵士）。底本は標點のうえ
段落まで切ってある——《司馬法》と同じで、本頁は段を一つも作らない。
{{gototop}} の類のテンプレートは本文でないので落とす。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
PIAN = ['圖國', '料敵', '治兵', '論將', '應變', '勵士']
ORD = ['第一', '第二', '第三', '第四', '第五', '第六']


def main():
    s = open(f'{D}/raw/main.wiki', encoding='utf-8').read()
    body = s[s.index('==圖國第一=='):]
    for junk in ('{{PD-old}}', '[[Category', '[[category'):
        if junk in body:
            body = body[:body.index(junk)]
    parts = re.split(r'^==\s*(.+?)\s*==\s*$', body, flags=re.M)
    out = []
    for k in range(1, len(parts), 2):
        head = parts[k].strip()
        paras = [p.strip() for p in parts[k + 1].split('\n') if p.strip()]
        paras = [p for p in paras if not p.startswith('{{')]
        assert not any(p.startswith(('*', '[[', '|', '<')) for p in paras), head
        name = head
        for o in ORD:
            name = name.replace(o, '')
        out.append({'name': name, 'paras': paras})
        print('%-4s %2d 段  %4d 字（標點込み）'
              % (name, len(paras), sum(len(p) for p in paras)))
    assert [j['name'] for j in out] == PIAN, [j['name'] for j in out]
    json.dump(out, open(f'{D}/base.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('計', sum(len(j['paras']) for j in out), '段')


if __name__ == '__main__':
    main()
