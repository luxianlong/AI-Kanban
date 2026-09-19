#!/usr/bin/env bash
# 從 raw/ 一路跑到四個頁面。raw/ 與 var/ 由 fetch.sh 取回。
set -euo pipefail
cd "$(dirname "$0")"
python3 assets.py        # 共通の CSS / JS を《商君書》の頁から取り出す
python3 parse.py         # 底本 → base.json（六十篇，篇下仍是一整塊）
python3 restruct.py      # 篇次還原到景宋本／四庫本的舊次第 → canon.json
python3 punct.py         # 底本標點的三處硬傷
python3 norm.py          # 維基標記、混入的簡體、書中自亂的字形
python3 witness.py       # 兩個通校本 → song.json / siku.json
python3 supply.py        # 補兩處脫文、改三處形訛
python3 align.py         # 逐字對校 → variants.json
python3 seg.py           # 分段 → text.json
node to_hans.js          # 繁 → 簡（要 opencc-js）
python3 notes_merge.py   # notes/zh1..zh6 → notes.json
python3 en_merge.py      # notes/en1..en6 → en.json
python3 ja_merge.py      # notes/ja1..ja6 → ja.json
python3 build.py         # 寫出四個頁面
python3 meta.py          # 書架カードの題と抄
python3 verify.py        # 四つの頁の機械検査
node residue.js          # 繁／簡の取り違えを數える（要 opencc-js）
