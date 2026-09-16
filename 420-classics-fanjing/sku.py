# -*- coding: utf-8 -*-
"""Parse the 四庫全書本 witness into 篇 → runs [(kind,text)], kind 1 = 趙蕤自注."""
import json, re

def strip(s):
    s = re.sub(r'<!--.*?-->', '', s, flags=re.S)
    s = re.sub(r'<poem>|</poem>', '', s)
    return s

def parse_vol(i):
    w = json.load(open('sk/j%d.json' % i))['parse']['wikitext']
    m = re.search(r'<onlyinclude>(.*)</onlyinclude>', w, re.S)
    s = strip(m.group(1) if m else w)
    s = s.replace('撰霸圖第十七', '撰{{SK anchor|霸圖第十七}}')
    s = s.replace('{{SK anchor|七雄畧苐十八}}', '{{SK anchor|七雄略第十八}}')
    s = s.replace('\n\u3000\u3000懼誡第二十\n', '\n\u3000\u3000{{SK anchor|懼戒第二十}}\n')
    s = s.replace('\n\u3000\u3000釣情第二十二\n', '\n\u3000\u3000{{SK anchor|釣情第二十二}}\n')
    return s

ANCHOR = re.compile(r'\{\{SK anchor\|([^}|]+)\}\}')

def runs(s):
    """{{SK notes|x}} -> kind 1; everything else kind 0. Other templates dropped."""
    out = []; i = 0; n = len(s)
    buf = []
    while i < n:
        if s.startswith('{{', i):
            j = i + 2; depth = 1
            while j < n and depth:
                if s.startswith('{{', j): depth += 1; j += 2
                elif s.startswith('}}', j): depth -= 1; j += 2
                else: j += 1
            inner = s[i+2:j-2]
            name, _, arg = inner.partition('|')
            name = name.strip().lower()
            if name == 'sk notes':
                if buf: out.append([0, ''.join(buf)]); buf = []
                out.append([1, re.sub(r'\{\{[^}]*\}\}', '', arg)])
            elif name == 'skchar':
                buf.append('□')
            elif name == 'yl':
                buf.append(arg)
            elif name == 'sk anchor':
                buf.append('\x00' + arg + '\x00')
            i = j
        else:
            buf.append(s[i]); i += 1
    if buf: out.append([0, ''.join(buf)])
    return out

# 四庫本的字形與卷九的分卷編號，一併歸到通行的篇題上
GLYPH = {'徳':'德','畧':'略','竒':'奇','誡':'戒','㽔':'蕤'}
J9 = ['出軍第四十一','練士第四十二','結營第四十三','道德第四十四','禁令第四十五',
      '教戰第四十六','天時第四十七','地形第四十八','水火第四十九','五間第五十',
      '將體第五十一','料敵第五十二','勢略第五十三','攻心第五十四','伐交第五十五',
      '格形第五十六','蛇勢第五十七','先勝第五十八','圍師第五十九','變通第六十',
      '利害第六十一','奇兵第六十二','掩發第六十三','還師第六十四']
_j9 = iter(J9)
def TITLE_FIX(t, vol):
    t = ''.join(GLYPH.get(c, c) for c in t)
    if vol == 9: return next(_j9)
    if t == '太私第三十': return '大私第三十'
    return t

BOOK = {}
for v in range(1, 10):
    r = runs(parse_vol(v))
    # split on anchors that name a 篇; the 卷 header (篇目 list, and in 卷九 a
    # 卷首小序) sits before the first anchor and is not part of any 篇
    cur = None
    for kind, txt in r:
        if kind == 0 and '\x00' in txt:
            parts = re.split(r'\x00([^\x00]*)\x00', txt)
            # parts: [text, anchor, text, anchor, ...]
            for idx, part in enumerate(parts):
                if idx % 2 == 0:
                    if cur is not None and part.strip(): BOOK[cur].append([0, part])
                else:
                    if re.match(r'^.{1,4}第[一二三四五六七八九十]+$', part):
                        cur = TITLE_FIX(part, v); BOOK.setdefault(cur, [])
                    elif cur is not None:
                        BOOK[cur].append([0, part])
        else:
            if cur is not None: BOOK[cur].append([kind, txt])
json.dump(BOOK, open('sku.json','w'), ensure_ascii=False)
print('篇', len(BOOK))
for k, v in list(BOOK.items()):
    a = sum(len(re.sub(r'\s','',t)) for kk,t in v if kk==0)
    b = sum(len(re.sub(r'\s','',t)) for kk,t in v if kk==1)
    print('%-10s 正文%6d 注%6d' % (k, a, b))
