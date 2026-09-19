# -*- coding: utf-8 -*-
"""底本の機械的な誤りだけを直し、canon.json を出す。

改める範圍は三つだけ——

  (一) 二本が一致して底本と異なり、かつ底本の文が通らないところ。
  (二) 二本に並んである字を底本が落としているところ（脫字）。
  (三) 二本のどちらにも無い文字が底本に入っているところ（衍文）。

一本だけが異なるところ、二本が一致しても底本の文が通るところは、**一字も
動かさず**その段の註釋に出校する。だから《天子之義》の「是以明其義也」は
底本のまま殘る——四庫本は「明其仁也」に作り、下文の「六德」と數が合うのは
四庫本のほうなのだが、證人が一本しかないので本頁は改めない。註でその旨を
言う。

字形の類（皷／鼓）は一つだけ多數の形に統一する。「灋／法」は統一しない——
底本も二本も「使灋在己曰專、與下畏法曰法」と一句のうちに兩形を兼ね用いて
おり、書物自身の書きぶりだからである。「于／於」も同じ。《仁本》の祭告の
一段だけが「于」なのは影宋鈔本と同じで、底本の亂れではない。
"""
import json, os

D = os.path.dirname(os.path.abspath(__file__))

# (篇, 舊, 新, 類)  目印で一意に定まるところだけ直す。
FIX = [
    # ── 二本一致・底本不可通 ──
    # 「治之」が重出し、「治之爲正」では上の「以義治之」と語が食い違う。
    ('仁本', '以義治之，治之為正。', '以義治之之謂正。', 'both'),
    # 「權出於戰、不出於仁也」——權が仁から出ないのは當り前で、意をなさない。
    # 二本は「不出於中人」（中人＝なみの人）。
    ('仁本', '不出於仁也。', '不出於中人。', 'both'),
    # 「御枚」は語でない。二本は「銜枚」——枚をくわえて聲を立てぬこと。
    ('嚴位', '御枚誓糗', '銜枚誓糗', 'both'),
    # 「或止不路」も語でない。二本は「或止於路」。
    ('用眾', '敵人或止不路，', '敵人或止於路，', 'both'),
    # ── 脫字（二本並有） ──
    ('定爵', '七曰百官無淫服。', '七曰百官宜無淫服。', 'lack'),
    # ── 衍文（二本並無） ──
    # 「春不東征、秋不西伐。月食班師、所以省戰」は《太平御覽》二十が引く
    # 《司馬法》の逸文で、今本五篇の文ではない。底本はこれを《仁本》の
    # 中に入れ、頭に「戰，」、末に「也」を足している。十八字を削る。
    ('仁本', '雖戰可也。戰，春不東征，秋不西伐，月食班師，所以省戰也。故仁見親',
             '雖戰可也。故仁見親', 'extra'),
]

# 字形を一つに揃える（字は變えない）。
UNIFY = [('成列而皷', '成列而鼓'), ('以智决', '以智決')]

# 引號を本書架の體裁に合わせる。
QUOTE = [('“', '「'), ('”', '」')]


def main():
    base = json.load(open(f'{D}/base.json', encoding='utf-8'))
    counts = {}
    for j in base:
        paras = j['paras']
        for pian, old, new, kind in FIX:
            if pian != j['name']:
                continue
            hits = sum(p.count(old) for p in paras)
            assert hits == 1, f'{pian}「{old}」が {hits} 箇所'
            j['paras'] = paras = [p.replace(old, new, 1) for p in paras]
            counts[kind] = counts.get(kind, 0) + 1
        for old, new in UNIFY + QUOTE:
            for i, p in enumerate(paras):
                if old in p:
                    counts['glyph' if (old, new) in UNIFY else 'quote'] = \
                        counts.get('glyph' if (old, new) in UNIFY else 'quote', 0) \
                        + p.count(old)
                    paras[i] = p.replace(old, new)

    json.dump(base, open(f'{D}/canon.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    n = sum(v for k, v in counts.items() if k in ('both', 'lack', 'extra'))
    print('改めたところ：', counts, '　本文に手を入れたのは', n, '處')


if __name__ == '__main__':
    main()
