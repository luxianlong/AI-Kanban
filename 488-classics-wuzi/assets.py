# -*- coding: utf-8 -*-
"""共通の CSS / JS を既存の讀本から取り出す。

典籍の頁は一枚ものの HTML で、外に置いた共通のスタイルシートを持たない。
《司馬法》が本書ともっとも近い（同じ武經七書、篇だけで章が無く、篇旨と
逐段註を持つ）ので、その四讀本から <style> と <script> を切り出して
雛形にする。
"""
import os, re

P = os.path.expanduser('~/Dashboard/frontend/public/p')
SRC = [('simafa', 'hant'), ('simafa-en', 'en'), ('simafa-ja', 'ja')]


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
