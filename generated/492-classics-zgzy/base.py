# -*- coding: utf-8 -*-
"""底本（維基文庫《貞觀政要》十卷の標點本）を讀み、卷・篇・段に分けて
base.json に落とす。章の界は witness.py が四庫（戈直）本から取る。

底本の約束：
  ==篇題==     篇の界
  {{YL|貞觀二年}}  年號へのリンク。字はそのまま生きている
  {{!|𨵦|⿵門俞}} Unicode に在る字と、その IDS 記述
  :            奏疏・詔答の續きの段（一つ前の段と同じ章に屬する）
  -{里}-       字形變換よけ。中身だけ生かす
  <u>高藏</u>  專名號。標籤だけ落とす
"""
import json, re, os

JUAN = [('卷一', 1, 2), ('卷二', 3, 6), ('卷三', 7, 9), ('卷四', 10, 13),
        ('卷五', 14, 18), ('卷六', 19, 27), ('卷七', 28, 30),
        ('卷八', 31, 35), ('卷九', 36, 37), ('卷十', 38, 41)]


def clean(s):
    s = re.sub(r'\{\{YL\|([^|}]*)(?:\|[^}]*)?\}\}', r'\1', s)
    # {{!|𨵦|⿵門俞}} — Unicode に在る字と、その IDS 記述。字のほうを生かす
    s = re.sub(r'\{\{!\|([^|}]+)(?:\|[^}]*)?\}\}', r'\1', s)
    s = re.sub(r'-\{(.*?)\}-', r'\1', s)
    s = re.sub(r'</?u>', '', s)
    s = s.replace('{{!}}', '|')
    s = re.sub(r'\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]', r'\1', s)
    return s


def load(n):
    s = open('raw/base-%02d.wiki' % n, encoding='utf-8').read()
    s = s[s.index('}}') + 2:]
    s = re.split(r'\{\{footer', s)[0]
    out, cur = [], None
    for ln in s.split('\n'):
        ln = ln.strip()
        if not ln:
            continue
        m = re.match(r'^(=+)\s*(.+?)\s*=+$', ln)
        if m:
            if len(m.group(1)) == 2:          # ==篇題==
                cur = {'title': m.group(2), 'paras': []}
                out.append(cur)
            # ===房玄齡=== の類は維基文庫が足した小見出し。原書に無いので捨てる
            # （戈直本は「任賢第三　凡八章」と數えるだけで、人ごとの題を立てない）
            continue
        cur['paras'].append(clean(ln))
    return out


def main():
    pian, n = [], 0
    for jn, (name, a, b) in enumerate(JUAN, 1):
        got = load(jn)
        if len(got) != b - a + 1:
            raise SystemExit('%s：篇數 %d、期待 %d' % (name, len(got), b - a + 1))
        for p in got:
            n += 1
            p['n'], p['juan'] = n, name
            pian.append(p)
    xu = open('raw/base-xu.wiki', encoding='utf-8').read()
    xu = clean(xu[xu.index('<onlyinclude>') + 13:xu.index('</onlyinclude>')]).strip()
    out = {'xu': xu, 'pian': pian}
    json.dump(out, open('base.json', 'w'), ensure_ascii=False, indent=1)
    tot = sum(len(re.sub(r'[，。：；、？！「」『』《》〈〉·：\s]', '', p))
              for q in pian for p in q['paras'])
    print('底本：%d 篇 %d 段 %d 字（句讀を除く）'
          % (len(pian), sum(len(q['paras']) for q in pian), tot))
    print('自序 %d 字' % len(re.sub(r'[，。：；、？！「」『』《》〈〉·\s]', '', xu)))


if __name__ == '__main__':
    main()
