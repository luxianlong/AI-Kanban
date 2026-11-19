#!/usr/bin/env python3
"""Todo 599: swap the Mixer #14 Vimeo embed for the locally saved MP4.
Run after generated/598-english-mixer-video-build.py (idempotent)."""
import os
for d in ('public', 'dist'):
    p = os.path.expanduser(f'~/Dashboard/frontend/{d}/p/english-learning-mixer-video.html')
    s = open(p).read()
    if 'mixer14-hanging-out.mp4' in s:
        continue
    i, j = s.index('  <div class="frame" data-vid="544153615">'), s.index('  <figcaption><b>Mixer 14 Hanging Out</b>')
    s = s[:i] + '''  <div class="frame">
    <video controls preload="metadata" playsinline poster="/img/english-learning-mixer-video/thumb-544153615.jpg" src="/img/english-learning-mixer-video/mixer14-hanging-out.mp4"></video>
  </div>
''' + s[j:]
    s = s.replace('.frame iframe{position:absolute;inset:0;width:100%;height:100%;border:0}',
                  '.frame iframe,.frame video{position:absolute;inset:0;width:100%;height:100%;border:0}')
    s = s.replace('    <a href="https://vimeo.com/544153615" target="_blank" rel="noopener">open on Vimeo ↗</a></figcaption>',
                  '    <a href="/img/english-learning-mixer-video/mixer14-hanging-out.mp4" download>download MP4 (720p, 3:12, 15 MB)</a> ·\n'
                  '    <a href="https://vimeo.com/544153615" target="_blank" rel="noopener">open on Vimeo ↗</a></figcaption>')
    s = s.replace("Videos stream from elllo's Vimeo account;",
                  "The main video is saved here (downloaded from elllo's Vimeo account); the bonus video streams from Vimeo;")
    s = s.replace("document.querySelectorAll('.frame').forEach", "document.querySelectorAll('.frame[data-vid]').forEach")
    open(p, 'w').write(s)
