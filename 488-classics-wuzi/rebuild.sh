#!/usr/bin/env bash
# raw/ から四つの頁までを一気に作り直す。raw/ と var/ は fetch.sh が、
# 封面は cover.sh が取ってくる。
# opencc-js（繁→簡）と sharp（封面）が要る。どちらもリポジトリの依存では
# ないので、npm install した先を環境変数で渡す：
#   OPENCC=/path/to/opencc-js SHARP=/path/to/sharp bash rebuild.sh
set -euo pipefail
cd "$(dirname "$0")"
python3 assets.py        # 共通の CSS / JS を《司馬法》の頁から取り出す
python3 parse.py         # 底本 → base.json（六篇、段は底本のまま）
python3 witness.py       # 通校二本 → sbck.json / wyg.json（外字を還元、
                         #   四庫本の「呉起初見文侯章句」を圖國に繼ぐ）
python3 canon.py         # 標點と字形だけ直す → canon.json（本文は一字も改めない）
python3 align.py canon.json  # 逐字對校 → variants.json
python3 text.py          # canon.json → text.json（段は底本のもの。切り直さない）
python3 notes_merge.py   # notes/zh1..zh6 ＋ colophon → notes.json
python3 en_merge.py      # notes/en1..en6 ＋ en_colophon → en.json
python3 ja_merge.py      # notes/ja1..ja6 ＋ ja_colophon → ja.json
node to_hans.js          # 繁 → 簡（text.json と notes.json を讀むので、
                         #   必ず notes_merge.py の後）
python3 build.py         # 四つの頁を書き出す
python3 meta.py          # 書架カードの題と抄
python3 shelf.py         # /p/dianji.html に一冊足す（冪等）
python3 verify.py        # 四つの頁の機械検査
node residue.js          # 繁／簡の取り違えを數える（要 opencc-js）
