# -*- coding: utf-8 -*-
"""共通の CSS / JS を既存の讀本から取り出す。

典籍の頁は一枚ものの HTML で、外に置いた共通のスタイルシートを持たない。
《六韜》が本書ともっとも近い（卷 h2 ＋ 逐段註、同じ武經七書）ので、その
四讀本から <style> と <script> を切り出して雛形にする。h3 に係る規則は
本書に要らない——《三略》には篇も章も無いので——が、孤立したセレクタを
残さないよう build.py で落とす。
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
