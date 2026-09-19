# -*- coding: utf-8 -*-
"""兩個通校本 → song.json（景宋本）、siku.json（四庫本），各按篇切開。"""
import json, re, html, unicodedata

NUM = '一二三四五六七八九十'
JUAN_N = ['一', '二', '三', '四', '五', '六']
PIAN_NAMES = None  # 由 base.json 供給


def strip_tags(t):
    t = re.sub(r'<span class="pagenum.*?</span></span>', '', t, flags=re.S)
    t = re.sub(r'<style.*?</style>', '', t, flags=re.S)
    t = re.sub(r'<table.*?</table>', '', t, flags=re.S)
    t = re.sub(r'<sup.*?</sup>', '', t, flags=re.S)
    t = t.replace('</p>', '\n')
    t = re.sub(r'<[^>]+>', '', t)
    return html.unescape(t)


def song():
    """四部叢刊景宋刊本。篇題在原版是低四格另起一行，錄文保留了那四個全角空格，
    篇題行即由此認出——景宋本的篇題不繫篇次（作「文師」不作「文師第一」），
    且用的是宋刻的舊字形（盈虚、發啓、隂符、竒兵、絶道、壘虚、鳥雲、敎戰、
    武𮪍士、戰歩……），故與底本的篇名按異體字表歸併後比對。"""
    import align
    same = align.load_classes()

    base = json.load(open('canon.json'))
    names = [p['name'] for p in base['pian']]

    def match(t):
        for n in names:
            if len(n) == len(t) and all(same(a, b) for a, b in zip(n, t)):
                return n
        return None
    out, order = {}, []
    for jn in JUAN_N:
        d = json.load(open('raw/sbck_%s.json' % jn))
        t = strip_tags(d['parse']['text']['*'])
        cur = None
        for raw in t.split('\n'):
            l = raw.strip()
            if not l:
                continue
            if re.match(r'^六韜卷第[一二三四五六]$', l):
                continue
            if raw.startswith('\u3000'):        # 低格的篇題行
                nm = match(l)
                if nm is None:                  # 卷端的韜名（文韜、武韜……）也低格
                    if l in ('文韜', '武韜', '龍韜', '虎韜', '豹韜', '犬韜'):
                        continue
                    # 景宋本獨有的篇題寫法，逐條記下
                    nm = SONG_ONLY.get(l)
                    if nm is None:
                        raise SystemExit('景宋本篇題對不上：%r（卷%s）' % (l, jn))
                cur = nm
                out[cur] = []
                order.append((jn, cur, l))
                continue
            if cur:
                out[cur].append(l)
    json.dump([{'juan': j, 'name': n, 'as_printed': a} for j, n, a in order],
              open('song-order.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    return {k: ''.join(v) for k, v in out.items()}


# 景宋本的篇題與底本不同者。兩條都是篇題本身的異文，不是舊字形：
#   分兵／分合 —— 景宋本作「分兵」，底本與四庫本作「分合」。
#   鳥雲／烏雲 —— 景宋本兩篇皆作「鳥」，底本與四庫本作「烏」；篇末自解此名
#   曰「鳥散而雲合」（景宋本）／「烏散而雲合」（底本、四庫本）。
SONG_ONLY = {'分兵': '分合', '鳥雲山兵': '烏雲山兵', '鳥雲澤兵': '烏雲澤兵',
             '三陳': '三陣'}


def siku():
    """四庫全書本。無句讀；篇界多由 {{SK anchor|文師第一}} 標出，但篇題首字若是
    Unicode 無字的寫本字形（如「發」作 {{SKchar|2962}}），錄文就不加 anchor。
    所以這裡不認 anchor，改認「某某第幾」這個形狀，並要求篇次正好是下一個——
    四庫本的篇次自成一序（兵道第十二），與底本不同，正是本頁要查的東西。
    行界是四庫本自己的分段，另存 siku-paras.json 供覆核本頁的分段。"""
    NUMS = {}
    def cn(n):
        d = '一二三四五六七八九十'
        if n < 11:
            return d[n - 1]
        if n < 20:
            return '十' + d[n - 11] if n > 10 else '十'
        t = d[n // 10 - 1] + '十'
        return t + (d[n % 10 - 1] if n % 10 else '')
    for i in range(1, 61):
        NUMS[cn(i)] = i

    out, order, cur, nxt = {}, [], None, 1
    for n in range(1, 7):
        d = json.load(open('raw/sk_%d.json' % n))
        c = list(d['query']['pages'].values())[0]['revisions'][0]['slots']['main']['*']
        c = re.sub(r'<!--.*?-->', '', c, flags=re.S)
        c = re.sub(r'\{\{SKchar\|(\d+)\}\}', lambda m: SKCHAR.get(m.group(1), '□'), c)
        for l in c.split('\n'):
            l = l.strip().replace('\u3000', '')
            if not l:
                continue
            m = re.match(r'^\{\{SK anchor\|(.+?)\}\}$', l)
            if m:
                l = m.group(1)
            if l.startswith('{{') or l.startswith('<') or l.startswith('[['):
                continue
            if l in ('欽定四庫全書', '文韜', '武韜', '龍韜', '虎韜', '豹韜', '犬韜'):
                continue
            if re.match(r'^六韜卷[一二三四五六]$', l):
                continue
            m = re.match(r'^(.{1,5})第([一二三四五六七八九十]+)$', l)
            if m and NUMS.get(m.group(2)) == nxt:
                cur = nxt
                out[cur] = []
                order.append({'juan': n, 'n': nxt, 'as_printed': l})
                nxt += 1
                continue
            if cur:
                out[cur].append(l)
    json.dump(order, open('siku-order.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    return {str(k): v for k, v in out.items()}


# 四庫本錄文裡 Unicode 無字的寫本字形，依《四庫》影本還原正字。
SKCHAR = {'2962': '發', '1866': '紛', '2963': '發'}


def main():
    s = song()
    k = siku()
    json.dump(s, open('song.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    json.dump(k, open('siku.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('景宋本 篇', len(s), '字', sum(len(v) for v in s.values()))
    print('四庫本 篇', len(k), '字', sum(len(''.join(v)) for v in k.values()),
          '段', sum(len(v) for v in k.values()))
    if len(k) != 60:
        raise SystemExit('四庫本只切出 %d 篇' % len(k))


if __name__ == '__main__':
    main()
