# -*- coding: utf-8 -*-
"""共通の CSS / JS を既存の讀本から取り出す。

典籍の各頁は一枚ものの HTML で、共通のスタイルシートを外に持たない。
《六韜》が本書とほぼ同じ形（卷 h2 ＋ 篇 h3 ＋ 卷旨・篇旨・逐段註）
なので、その三讀本から <style> と <script> を切り出して雛形にする。
build.py が本書に要らない規則を落とし、註釋の localStorage キーを差し替える。
"""
import os, re

P = os.path.expanduser('~/Dashboard/frontend/public/p')
SRC = [('liutao', 'hant'), ('liutao-en', 'en'), ('liutao-ja', 'ja')]


def main():
    for slug, out in SRC:
        s = open(os.path.join(P, slug + '.html'), encoding='utf-8').read()
        css = re.search(r'<style>\n(.*?)\n</style>', s, re.S).group(1)
        js = re.search(r'<script>\n(.*?)\n</script>', s, re.S).group(1)
        open('css-%s.css' % out, 'w', encoding='utf-8').write(css)
        open('js-%s.js' % out, 'w', encoding='utf-8').write(js)
        print('css-%s.css %d / js-%s.js %d' % (out, len(css), out, len(js)))


if __name__ == '__main__':
    main()
