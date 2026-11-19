# -*- coding: utf-8 -*-
"""PDF に埋まっている頁の JPEG をそのまま取り出す。

    python3 pdfjpg.py <pdf> <頁番號（一起算）> <出力先>

Commons が出す PDF の縮小圖は一二八〇幅で頭打ちになるが、この本の元の
頁は一六五二幅ある。ラスタライザ（pdftoppm の類）はこの機械に無いので、
/DCTDecode の圖形オブジェクトを順に拾って、そのまま書き出す。圖書館の
掃描 PDF は一頁に一枚の JPEG を貼っただけなので、これで元の畫が得られる。
"""
import re, sys


def main(path, page, out):
    d = open(path, 'rb').read()
    n = 0
    for m in re.finditer(rb'\d+ 0 obj\s*<<(.{0,800}?)>>\s*stream\r?\n', d, re.S):
        hdr = m.group(1)
        if b'/Subtype /Image' not in hdr:
            continue
        n += 1
        if n != page:
            continue
        assert b'/DCTDecode' in hdr, hdr[:200]
        s = m.end()
        e = d.index(b'endstream', s)
        blob = d[s:e].rstrip(b'\r\n')
        assert blob[:2] == b'\xff\xd8', '先頭が JPEG でない'
        open(out, 'wb').write(blob)
        w = re.search(rb'/Width (\d+)', hdr).group(1).decode()
        h = re.search(rb'/Height (\d+)', hdr).group(1).decode()
        print('%s 第 %d 頁 → %s（%sx%s、%d bytes）'
              % (path, page, out, w, h, len(blob)))
        return
    raise SystemExit('第 %d 頁が無い（圖は %d 枚）' % (page, n))


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]), sys.argv[3])
