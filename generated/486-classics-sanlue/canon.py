# -*- coding: utf-8 -*-
"""底本の機械的な誤りだけを直し、canon.json を出す。

方針は一つ——**底本の文が通らないところだけ、両証本に従う**。
通る異文は底本のまま残し、その段の注釈に出校する。直すのは次の四類：

  簡繁回転 17  壹→一 12・誌→志 2・蔔→卜・征→徵・灶→竈
               （底本は一度簡体を経ている。twedu の異體字表は
                 「一／壹」「卜／蔔」「志／誌」「徵／征」を並べており、
                 繁体化の道具がそれを拾ったと見える。）
  形近而訛  4  盯→明・遣→遺・乘→乖・裹→裏
  脱字      1  「良將之用」→「良將之用兵」
  不成義    1  「四民用靈」→「四民用虛」

いずれも四庫白文本・三畧直解本の二本が一致して底本と異なる箇所。
字形の統一（于→於、奸→姦）もここで行う。
"""
import json, os, re

D = os.path.dirname(os.path.abspath(__file__))

# (卷, 舊, 新, 前後の目印, 類)  目印で一意に定まるところだけ直す。
FIX = [
    # ── 簡繁回轉（底本が一度簡体を経た痕） ──
    ('中略', '降心服誌', '降心服志', 'conv'),
    ('中略', '勇者好行其誌', '勇者好行其誌', 'conv'),     # 下で個別に
    ('中略', '不得為吏士蔔問', '不得為吏士卜問', 'conv'),
    ('上略', '軍灶未炊', '軍竈未炊', 'conv'),
    ('下略', '乖者亡之征', '乖者亡之徵', 'conv'),
    ('下略', '五者壹體也', '五者一體也', 'conv'),
    ('下略', '不可無壹焉', '不可無一焉', 'conv'),
    ('下略', '廢壹善', '廢一善', 'conv'),
    ('下略', '賞壹惡', '賞一惡', 'conv'),
    ('下略', '壹令逆', '一令逆', 'conv'),
    ('下略', '壹惡施', '一惡施', 'conv'),
    ('下略', '利壹害百', '利一害百', 'conv'),
    ('下略', '利壹害萬', '利一害萬', 'conv'),
    ('下略', '去壹利百', '去一利百', 'conv'),
    ('下略', '去壹利萬', '去一利萬', 'conv'),
    ('中略', '夫人眾壹合', '夫人眾一合', 'conv'),
    ('中略', '威權壹與', '威權一與', 'conv'),
    # ── 形近而訛 ──
    ('上略', '將之盯誡也', '將之明誡也', 'shape'),
    ('上略', '則策無遣', '則策無遺', 'shape'),
    ('上略', '乘眾不可使伐人', '乖眾不可使伐人', 'shape'),
    ('上略', '以罰為裹', '以罰為裏', 'shape'),
    # ── 脱字 ──
    ('上略', '昔者，良將之用，有饋簞醪者', '昔者，良將之用兵，有饋簞醪者', 'lack'),
    # ── 不成義 ──
    ('下略', '四民用靈', '四民用虛', 'sense'),
]

# 書中で揺れている字形を多数の一形に統一する（字形だけ、字は変えない）。
UNIFY = [('通志于眾', '通志於眾'), ('不易于身', '不易於身'),
         ('動違于眾', '動違於眾'), ('德合於己', '德合於己')]


def main():
    base = json.load(open(f'{D}/base.json', encoding='utf-8'))
    counts = {}
    for j in base:
        paras = j['paras']
        for k, (juan, old, new, kind) in enumerate(FIX):
            if juan != j['name'] or old == new:
                continue
            hits = sum(p.count(old) for p in paras)
            assert hits == 1, f'{juan}「{old}」が {hits} 箇所'
            j['paras'] = paras = [p.replace(old, new, 1) for p in paras]
            counts[kind] = counts.get(kind, 0) + 1
        for old, new in UNIFY:
            if old == new:
                continue
            for i, p in enumerate(paras):
                if old in p:
                    paras[i] = p.replace(old, new)
                    counts['unify'] = counts.get('unify', 0) + 1

    # 「勇者好行其誌」は誌が二度目。個別に。
    for j in base:
        for i, p in enumerate(j['paras']):
            if '好行其誌' in p:
                j['paras'][i] = p.replace('好行其誌', '好行其志')
                counts['conv'] = counts.get('conv', 0) + 1

    # 「奸／姦」を書中多数の一形に。
    n_jian = sum(p.count('姦') for j in base for p in j['paras'])
    n_gan = sum(p.count('奸') for j in base for p in j['paras'])
    keep, drop = ('姦', '奸') if n_jian >= n_gan else ('奸', '姦')
    for j in base:
        for i, p in enumerate(j['paras']):
            if drop in p:
                counts['unify'] = counts.get('unify', 0) + p.count(drop)
                j['paras'][i] = p.replace(drop, keep)
    print(f'姦 {n_jian} / 奸 {n_gan} → 「{keep}」に統一')

    json.dump(base, open(f'{D}/canon.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('改めたところ：', counts,
          '　計', sum(v for k, v in counts.items() if k != 'unify'), '處（字形統一を除く）')


if __name__ == '__main__':
    main()
