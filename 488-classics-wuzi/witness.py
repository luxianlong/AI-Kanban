# -*- coding: utf-8 -*-
"""通校二本を篇ごとに切り出す。

  sbck.json 四部叢刊本《吳子》二卷（上海涵芬樓借常熟瞿氏鐵琴銅劍樓藏
            景宋鈔本影印。Kanripo KR3b0004 の SBCK 枝）。葉の界を持つ。
  wyg.json  四庫全書文淵閣本（同 KR3b0004 の WYG 枝）。

どちらも無句讀。Mandoku の外字實體（&KR0016; の類）は KR-Gaiji の
charlist で Unicode の正字に直す。

四庫全書本には篇次（第一…第六）が無く、そのうえ<b>《圖國第一》の前半を
「呉起初見文侯章句」という別立ての一篇にしている</b>——吳起が儒服で
魏文侯に見える冒頭の一段（「皆起之功也」まで）がそれで、四庫本の「圖國」は
「呉子曰、昔之圖國家者」から始まる。今本六篇と數を合わせるため、ここでは
二つを繼いで《圖國》一篇とし、繼ぎ目を控えておく。
"""
import re, json, os

D = os.path.dirname(os.path.abspath(__file__))
PIAN = ['圖國', '料敵', '治兵', '論將', '應變', '勵士']
# 二本は「將」を「将」に作る。篇名の異體をすべて受ける。
ALIAS = {'圖國': '圖國', '啚國': '圖國', '料敵': '料敵', '治兵': '治兵',
         '論將': '論將', '論将': '論將', '應變': '應變', '応變': '應變',
         '勵士': '勵士', '励士': '勵士'}
HEAD = re.compile(r'^(圖國|料敵|治兵|論將|論将|應變|勵士)(第[一二三四五六])?$')
# 四庫本が《圖國》の前半に立てた別題。
WYG_EXTRA = '呉起初見文侯章句'
# 卷端・卷尾の題、四庫の卷首。
DROP = re.compile(r'^(吳|吴|呉)子卷[上下]$')
FRONT = ('欽定四庫全書', '吳子', '吴子', '呉子', '周吳起撰', '周呉起撰')


def gaiji():
    """KR-Gaiji charlist.org.txt → {'KR0016': '騎', ...}"""
    m = {}
    for ln in open(f'{D}/var/kr-charlist.org.txt', encoding='utf-8'):
        f = ln.rstrip('\n').split('\t')
        if len(f) >= 4 and f[0].startswith('KR') and f[3]:
            # 字の後ろの「?」は Mandoku の「同定に自信なし」の印。
            # 本書に出る二つ（KR0108 奮?・KR0300 投?）はどちらも底本と合う。
            m[f[0]] = f[3].rstrip('?')
    return m


def frags(path, g):
    """本文の斷片だけ。(葉, 斷片) を返す。

    SBCK 枝は一行が一つの斷片。WYG 枝は SBCK の行界を ¶ で本文中に
    抱き込んでいるので、¶ で割ってから同じように扱う。
    """
    out, leaf = [], None
    for ln in open(path, encoding='utf-8').read().split('\n'):
        ln = ln.strip()
        if not ln or ln.startswith('#'):
            continue
        m = re.match(r'^<pb:([^>]+)>¶?$', ln)
        if m:
            leaf = m.group(1).split('_')[-1]
            continue
        ln = re.sub(r'<pb:[^>]+>', '', ln)
        ln = re.sub(r'<md:[^>]+>', '', ln)        # WYG 枝が挟む SBCK の葉界
        ln = ln.replace('&(', '&（')
        for piece in ln.split('¶'):
            piece = piece.strip('　 \t')
            if not piece:
                continue
            piece = re.sub(r'&(KR\d+);', lambda m: g[m.group(1)], piece)
            assert '&' not in piece, piece
            out.append((leaf, piece))
    return out


def cut(rows, extra=None):
    """篇名の斷片で切る。卷端・卷尾の題と四庫の卷首は落とす。"""
    out, cur, order = {}, None, []
    for leaf, s in rows:
        if DROP.match(s) or s in FRONT or re.fullmatch(r'[周齊]?[吳吴呉]起?撰?', s):
            continue
        name = None
        m = HEAD.match(s)
        if m:
            name = ALIAS[m.group(1)]
        elif extra and s == extra:
            name = extra
        if name:
            assert name not in out, '篇名が二度出る: ' + name
            cur = out.setdefault(name, {'text': '', 'leaves': []})
            order.append(name)
            continue
        if cur is None:
            continue
        cur['leaves'].append((len(cur['text']), leaf))
        cur['text'] += s
    return out, order


def main():
    g = gaiji()
    sb, sb_order = cut(frags(f'{D}/raw/sbck/KR3b0004_000.txt', g))
    wy, wy_order = cut(frags(f'{D}/raw/wyg/KR3b0004_000.txt', g), WYG_EXTRA)

    assert sb_order == PIAN, sb_order
    assert wy_order == [WYG_EXTRA] + PIAN, wy_order
    # 四庫本の「呉起初見文侯章句」を《圖國》の頭に繼ぐ。
    pre, tu = wy.pop(WYG_EXTRA), wy['圖國']
    n = len(pre['text'])
    print('四庫全書本：《圖國》の前半を「%s」という別題の一篇に立てている'
          '（%d 字）。今本に合わせて繼いだ。' % (WYG_EXTRA, n))
    wy['圖國'] = {'text': pre['text'] + tu['text'],
                  'leaves': pre['leaves'] + [(i + n, l) for i, l in tu['leaves']],
                  'seam': n}

    for w, label in ((sb, '四部叢刊影宋鈔本'), (wy, '四庫全書本')):
        assert sorted(w) == sorted(PIAN), (label, sorted(w))
        print(label, '　'.join('%s %d 字' % (k, len(w[k]['text'])) for k in PIAN),
              '　計', sum(len(w[k]['text']) for k in PIAN))
    json.dump({k: sb[k] for k in PIAN},
              open(f'{D}/sbck.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    json.dump({k: wy[k] for k in PIAN},
              open(f'{D}/wyg.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
