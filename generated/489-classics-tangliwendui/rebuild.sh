#!/usr/bin/env bash
# raw/ から四つの頁までを一気に作り直す。raw/ と var/ は fetch.sh が、
# 封面は cover.sh が取ってくる。
# opencc-js（繁→簡）と sharp（封面）が要る。どちらもリポジトリの依存では
# ないので、npm install した先を環境変数で渡す：
#   OPENCC=/path/to/opencc-js SHARP=/path/to/sharp bash rebuild.sh
set -euo pipefail
cd "$(dirname "$0")"
python3 assets.py        # 共通の CSS / JS を《三略》の頁から取り出す
python3 parse.py         # 底本 → base.json（三卷・五十問對・百九十七段）
python3 witness.py       # 四庫全書本 → wyg.json / skqs.json。二つの録文を
                         #   突き合わせ、今本の卷立てに切り直す
python3 canon.py         # 十二處を改める → canon.json
python3 align.py canon.json  # 逐字對校 → variants.json
python3 text.py          # canon.json → text.json（段も問答の界も底本のもの）
python3 notes_merge.py   # notes/zh1..zh3 ＋ colophon → notes.json
python3 en_merge.py      # notes/en1..en3 ＋ en_colophon → en.json
python3 ja_merge.py      # notes/ja1..ja3 ＋ ja_colophon → ja.json
node to_hans.js          # 繁 → 簡（notes.json を読むので notes_merge.py の後）
python3 build.py         # 四つの頁を書き出す
python3 meta.py          # 書架カードの題と抄
python3 shelf.py         # /p/dianji.html に一冊足す（冪等）
python3 verify.py        # 四つの頁の機械検査
node residue.js          # 繁／簡の取り違えを數える（要 opencc-js）
