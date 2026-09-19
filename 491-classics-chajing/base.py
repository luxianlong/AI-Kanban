# -*- coding: utf-8 -*-
"""底本（維基文庫《茶經》、浙江鮑士恭家藏本に基づく標點本）を讀み、
篇・段・陸羽の夾注に分けて base.json に落とす。

底本の約束：
  {{*|…}}      陸羽の自注（原書は雙行小字の夾注）
  <br>         段の界
  {{僻字|X}}   底本が「僻字」と斷った字。字はそのまま生きている
  '''X'''      〈四之器〉の器の名（原書は行頭に一字下げず立てる）
  [[File:讀音茂.png|15px]]  底本が Unicode を當てられず畫像で示した字
"""
import json, re

PIAN = [('卷上', 1, '一之源'), ('卷上', 2, '二之具'), ('卷上', 3, '三之造'),
        ('卷中', 4, '四之器'),
        ('卷下', 5, '五之煮'), ('卷下', 6, '六之飲'), ('卷下', 7, '七之事'),
        ('卷下', 8, '八之出'), ('卷下', 9, '九之略'), ('卷下', 10, '十之圖')]

# 底本が畫像で示した字。八之出「明州鄮縣」——鄮は U+9134、Unicode にある。
IMG = {'讀音茂.png': '鄮'}


def load(name):
    s = open('raw/base-%s.wiki' % name, encoding='utf-8').read()
    s = s[s.index('| notes'):]                       # header を落とす
    s = s[s.index('}}') + 2:]
    s = s.split('{{PD-old}}')[0].replace('{{footer}}', '')
    s = re.sub(r'\[\[File:([^|\]]+)[^\]]*\]\]', lambda m: IMG[m.group(1)], s)
    s = re.sub(r'\{\{僻字\|(.)\}\}', r'\1', s)
    s = re.sub(r'\{\{\*\|(.*?)\}\}', r'〔\1〕', s, flags=re.S)
    s = re.sub(r"'''(.+?)'''+", r'【\1】', s)          # 器の名
    s = s.replace('<br>', '\n')
    ln = [x.strip() for x in s.split('\n') if x.strip()]
    if name == '四之器':
        # 器の名は行を改めて立ててあるので、すぐ下の説明と一段に綴じる
        out = []
        for x in ln:
            if out and out[-1].startswith('【') and not x.startswith('【'):
                out[-1] += x
            else:
                out.append(x)
        ln = out
    if name == '七之事':
        # 卷頭の代ごとの人名の目は原書では一續き。一段に綴じる
        k = next(i for i, x in enumerate(ln) if x.startswith('《'))
        ln = ['　'.join(ln[:k])] + ln[k:]
    return ln


def main():
    out = {'pian': []}
    for juan, n, name in PIAN:
        paras = load(name)
        out['pian'].append({'n': n, 'juan': juan, 'name': name, 'paras': paras})
    json.dump(out, open('base.json', 'w'), ensure_ascii=False, indent=1)
    tot = sum(len(re.sub(r'[〔〕【】，。：；、？！「」『』《》·　 ]', '', p))
              for q in out['pian'] for p in q['paras'])
    print('底本：%d 篇 %d 段 %d 字（句讀と界標を除く）'
          % (len(out['pian']), sum(len(q['paras']) for q in out['pian']), tot))
    for q in out['pian']:
        print('  %-4s %2d 段' % (q['name'], len(q['paras'])))


if __name__ == '__main__':
    main()
