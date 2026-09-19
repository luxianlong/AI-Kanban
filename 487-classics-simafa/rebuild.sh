#!/usr/bin/env bash
# raw/ から四つの頁までを一気に作り直す。raw/ と var/ は fetch.sh が取ってくる。
# opencc-js（繁→簡）と sharp（封面）が要る。どちらもリポジトリの依存では
# ないので、npm install した先を環境変数で渡す：
#   OPENCC=/path/to/opencc-js SHARP=/path/to/sharp bash rebuild.sh
set -euo pipefail
cd "$(dirname "$0")"
python3 assets.py        # 共通の CSS / JS を《六韜》の頁から取り出す
python3 parse.py         # 底本 → base.json（五篇、段は底本のまま）
python3 witness.py       # 通校二本 → sbck.json / wyg.json（外字を還元、巻次を復元）
python3 canon.py         # 底本の機械的な誤り六箇所 → canon.json
python3 align.py         # 逐字對校 → variants.json
python3 text.py          # canon.json → text.json（段は底本のもの。切り直さない）
python3 notes_merge.py   # notes/zh1..zh5 ＋ colophon → notes.json
node to_hans.js          # 繁 → 簡（text.json と notes.json を読むので、
                         #   必ず notes_merge.py の後）
python3 en_merge.py      # notes/en1..en5 ＋ en_colophon → en.json
python3 ja_merge.py      # notes/ja1..ja5 ＋ ja_colophon → ja.json
python3 build.py         # 四つの頁を書き出す
python3 meta.py          # 書架カードの題と抄
python3 shelf.py         # /p/dianji.html に一冊足す（冪等）
python3 verify.py        # 四つの頁の機械検査
node residue.js          # 繁／簡の取り違えを數える（要 opencc-js）
