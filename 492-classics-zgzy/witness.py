# -*- coding: utf-8 -*-
"""通校本：《四庫全書》文淵閣本《貞觀政要》（元　戈直　集論）の錄文を讀む。

この本は本文に戈直の音註・事註と、二十二家の說を集めた「集論」とを
雙行小字で挾む。{{SK notes|…}} がそれで、入れ子になる。本文だけを拔く。

{{SKchar|N}} は錄文が缺筆字・外字を圖で示したもの。var/skchar*.lua の
對照表で正字に直す（2593=𤣥 は康熙帝玄燁、2592=𢎞 は乾隆帝弘曆の避諱缺筆）。

戈直は自序に「章之不當分者合之、不當合者分之」と書く——今傳わる章の分けかたは
この人が立てたもの。その章數（「凡X章」）を篇ごとに拾って skqs.json に殘す。
"""
import json, re

CN = '一二三四五六七八九十'


def num(s):
    s = re.sub(r'[凡章童附]', '', s)
    if s == '十':
        return 10
    if s.startswith('十'):
        return 10 + CN.index(s[1]) + 1
    if '十' in s:
        a, b = s.split('十')
        return (CN.index(a) + 1) * 10 + (CN.index(b) + 1 if b else 0)
    return CN.index(s) + 1


# 表が「本字」を持たず「描述字」だけを持つとき、描述は
#   「脩」「⿱乖一」「⿰女𧈧 -- 𡟎」「煬(「旦」改為「𠀇」)」
# のように書かれる。一字の漢字を拾い、IDS しか無いものは下の表で正字に直す。
IDS = {'⿱乖一': '垂', '⿱⺾㳄': '茨', '⿵門俞': '𨵦'}


def pick(desc):
    for part in re.split(r'\s*--\s*', desc):
        part = re.sub(r'[（(].*', '', part).strip()
        if len(part) == 1:
            return part
        if part in IDS:
            return IDS[part]
    return '□'


def skchar_table():
    t = {}
    for f, key in (('var/skchar.lua', 'a'), ('var/skchar2.lua', 'b')):
        s = open(f, encoding='utf-8').read()
        for m in re.finditer(r"\['(\d+)'\]=\{(nil|\"(.*?)\")(?:,\s*\"(.*?)\")?\}", s):
            t[key + m.group(1)] = m.group(3) or pick(m.group(4) or '')
    return t


SK = skchar_table()


def strip_notes(t):
    """入れ子の {{SK notes|…}} を落とす。"""
    out, depth, i = [], 0, 0
    while i < len(t):
        if t.startswith('{{SK notes|', i):
            depth += 1
            i += 11
            continue
        if depth:
            if t.startswith('{{', i):
                depth += 1
                i += 2
            elif t.startswith('}}', i):
                depth -= 1
                i += 2
            else:
                i += 1
            continue
        out.append(t[i])
        i += 1
    return ''.join(out)


def plain(t):
    t = strip_notes(t)
    t = re.sub(r'\{\{YL\|(.*?)\}\}', r'\1', t)
    t = re.sub(r'\{\{SK anchor\|(.*?)\}\}', r'\1', t)
    t = re.sub(r'\{\{[^{}]*\}\}', '', t)
    t = re.sub(r'\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]', r'\1', t)
    t = re.sub(r'</?[a-zA-Z][^>]*>', '', t)
    return t


def juan_text(n):
    s = open('raw/skqs-%02d.wiki' % n, encoding='utf-8').read()
    s = s[s.index('<onlyinclude>') + 13:s.index('</onlyinclude>')]
    s = s.replace('<poem>', '').replace('</poem>', '')
    # 外字は先に正字へ直す。註の中にも現れるので、篇の界を切る前に濟ませる
    return re.sub(r'\{\{SKchar(2?)\|(\d+)\}\}',
                  lambda m: SK.get(('b' if m.group(1) else 'a') + m.group(2), '□'), s)


MARK = re.compile(r'\{\{SK notes\|((?:凡)?[一二三四五六七八九十]+[章童][^}]*|'
                  r'附凡[一二三四五六七八九十]+章)\}\}')

# 篇題（底本のもの）。戈直本の卷端題とは食い違うところがある——collate.py が出す。
PIAN = [
 (1, 1, '君道第一'), (1, 2, '政體第二'),
 (2, 3, '任賢第三'), (2, 4, '求諫第四'), (2, 5, '納諫第五'), (2, 6, '直諫'),
 (3, 7, '君臣鑒戒第六'), (3, 8, '擇官第七'), (3, 9, '封建第八'),
 (4, 10, '太子諸王定分第九'), (4, 11, '尊敬師傅第十'),
 (4, 12, '教戒太子諸王第十一'), (4, 13, '規諫太子第十二'),
 (5, 14, '仁義第十三'), (5, 15, '論忠義第十四'), (5, 16, '孝友第十五'),
 (5, 17, '公平第十六'), (5, 18, '誠信第十七'),
 (6, 19, '論儉約第十八'), (6, 20, '論謙讓第十九'), (6, 21, '論仁惻第二十'),
 (6, 22, '論慎所好第二十一'), (6, 23, '論慎言語第二十二'),
 (6, 24, '論杜讒邪第二十三'), (6, 25, '論悔過第二十四'),
 (6, 26, '論奢縱第二十五'), (6, 27, '論貪鄙第二十六'),
 (7, 28, '崇儒學第二十七'), (7, 29, '論文史第二十八'), (7, 30, '論禮樂第二十九'),
 (8, 31, '論務農第三十'), (8, 32, '論刑法第三十一'), (8, 33, '論赦令第三十二'),
 (8, 34, '論貢賦第三十三'), (8, 35, '辨興亡第三十四'),
 (9, 36, '論征伐第三十五'), (9, 37, '論安邊第三十六'),
 (10, 38, '論行幸第三十七'), (10, 39, '論畋獵第三十八'),
 (10, 40, '論災祥第三十九'), (10, 41, '論慎終第四十'),
]


def main():
    out = {'pian': []}
    for jn in range(1, 11):
        s = juan_text(jn)
        ms = [m for m in MARK.finditer(s)]
        want = [x for x in PIAN if x[0] == jn]
        if len(ms) != len(want):
            raise SystemExit('卷%d：篇の界 %d、期待 %d' % (jn, len(ms), len(want)))
        for k, (m, (_, n, title)) in enumerate(zip(ms, want)):
            a = m.end()
            b = ms[k + 1].start() if k + 1 < len(ms) else len(s)
            seg = s[a:b]
            # 次の篇題（anchor の中か地の文）を末尾から落とす
            seg = re.sub(r'(?:\{\{SK anchor\|[^}]*\}\}|[一-鿿]{2,10})\s*$',
                         '', seg.rstrip())
            body = plain(seg)
            body = re.sub(r'貞觀政要卷[一二三四五六七八九十]+\s*(?:<[^>]*>)?\s*$',
                          '', body.strip())
            body = re.sub(r'^[^\u4e00-\u9fff]*\}\}', '', body.strip())
            lines = [x.strip('　 ') for x in body.split('\n')]
            out['pian'].append({
                'n': n, 'juan': jn, 'title': title,
                'zhang': num(m.group(1).split('章')[0].split('童')[0] + '章'),
                'lines': [x for x in lines if x],
                'text': re.sub(r'\s|　', '', body),
            })
    json.dump(out, open('skqs.json', 'w'), ensure_ascii=False, indent=1)
    tot = sum(p['zhang'] for p in out['pian'])
    print('四庫（戈直）本：%d 篇 %d 字、戈直の數える章 %d'
          % (len(out['pian']), sum(len(p['text']) for p in out['pian']), tot))


if __name__ == '__main__':
    main()
