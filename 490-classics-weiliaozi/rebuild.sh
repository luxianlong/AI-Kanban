#!/usr/bin/env bash
# raw/ から一路四つの頁まで。raw/ と var/ は fetch.sh、封面は cover.sh。
set -euo pipefail
cd "$(dirname "$0")"
python3 assets.py        # 共通の CSS / JS を《六韜》の頁から取り出す
python3 witness.py       # 四庫全書本の録文二種 → wyg.json / skqs.json
python3 base.py          # punct/ の句讀つき本文 → base.json（四庫本と逐字照合）
python3 canon.py         # 改字二處 → canon.json
python3 qszy.py          # 《羣書治要》の抄と逐字對校 → qszy.json
python3 text.py          # canon.json → text.json
node to_hans.js          # 繁 → 簡（要 opencc-js）
python3 notes_merge.py   # notes/zh1..zh5 → notes.json
python3 en_merge.py      # notes/en1..en5 → en.json
python3 ja_merge.py      # notes/ja1..ja5 → ja.json
python3 build.py         # 四つの頁を書き出す
python3 meta.py          # 書架カードの題と抄
python3 shelf.py         # 書架頁に一冊足す（冪等）
python3 verify.py        # 四つの頁の機械検査
node residue.js          # 繁／簡の取り違え（要 opencc-js）
