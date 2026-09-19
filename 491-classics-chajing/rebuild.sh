#!/usr/bin/env bash
# raw/ から一路四つの頁まで。raw/ と var/ は fetch.sh、封面は cover.sh。
set -euo pipefail
cd "$(dirname "$0")"
python3 assets.py        # 共通の CSS / JS を《六韜》の頁から取り出す
python3 witness.py       # 四庫本の錄文二種 → wyg.json / skqs.json（食い違い零を確かめる）
python3 base.py          # 底本の wiki → base.json（十篇・百二十四段）
python3 canon.py         # 句讀の正規化・字形の統一・改字十六處 → canon.json
python3 collate.py       # 底本 × 四庫本 の逐字對校 → collate.json
python3 text.py          # canon.json → text.json（三卷・十篇・百二十四段）
node to_hans.js          # 繁 → 簡（要 opencc-js）
python3 notes_merge.py   # notes/zh1..zh3 → notes.json
python3 en_merge.py      # notes/en1..en3 → en.json
python3 ja_merge.py      # notes/ja1..ja3 → ja.json
python3 build.py         # 四つの頁を書き出す
python3 meta.py          # 書架カードの題と抄
python3 shelf.py         # 書架頁に一冊足す（冪等）
python3 verify.py        # 四つの頁の機械檢査
node residue.js          # 繁／簡の取り違え（要 opencc-js）
