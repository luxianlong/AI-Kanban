# -*- coding: utf-8 -*-
"""底本（維基文庫《唐李問對》標點本）→ base.json。

この書は三卷、篇も章も無い。中身は太宗と李靖の問答で、底本は
<b>一つの發言を一行（「: 」で始まる行）に切り、一つの問答のまとまりを
空行で隔ててある</b>。だから本頁も段を作らない——一行＝一段、
空行＝問對の章の界である。

底本の本文には打ち間違いの半角空白が十箇所ほど混じっている（「斯得之矣 。」
「遠至 于西域」の類）。これは標點の誤りではなく入力の屑なので落とす。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))
JUAN = ['卷上', '卷中', '卷下']


def main():
    out = []
    for jname in JUAN:
        s = open(f'{D}/raw/base-{jname}.wiki', encoding='utf-8').read()
        s = s[s.index('<onlyinclude>') + len('<onlyinclude>'):]
        s = s[:s.index('</onlyinclude>')]
        paras, chap = [], []
        for block in re.split(r'\n\s*\n', s.strip()):
            lines = [ln.strip() for ln in block.split('\n') if ln.strip()]
            if not lines:
                continue
            chap.append(len(paras) + 1)
            for ln in lines:
                assert ln.startswith(': '), ln[:30]
                p = ln[2:]
                p = re.sub(r'[ 　]+', '', p)       # 入力の屑の空白
                assert not re.search(r'[{}\[\]<>|]', p), p[:40]
                paras.append(p)
        out.append({'name': jname, 'paras': paras, 'chap': chap})
        print('%s  %2d 問對 / %3d 段 / %5d 字（標點込み）'
              % (jname, len(chap), len(paras), sum(len(p) for p in paras)))
    json.dump(out, open(f'{D}/base.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('計', sum(len(j['chap']) for j in out), '問對、',
          sum(len(j['paras']) for j in out), '段')


if __name__ == '__main__':
    main()
