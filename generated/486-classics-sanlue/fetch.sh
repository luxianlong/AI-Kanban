#!/usr/bin/env bash
# 三つの材料を取り直す：底本（維基文庫標點本）、四庫全書本白文、
# 四庫全書本《三畧直解》所載の經文、それに異體字表。
# どれも入庫しない。raw/ と var/ は本腳本で再建できる。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw raw/wyg raw/zhijie var
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'

# 底本：維基文庫《三略》標點本。上略・中略・下略が一頁に入っている。
curl -s -A "$UA" -G 'https://zh.wikisource.org/w/api.php' \
  --data-urlencode 'titles=三略' \
  --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
| python3 -c '
import json,sys
c=list(json.load(sys.stdin)["query"]["pages"].values())[0]["revisions"][0]["slots"]["main"]["*"]
open("raw/main.wiki","w",encoding="utf-8").write(c)'
sleep 2

# 通校一：四庫全書本《黃石公三畧》白文（Kanripo KR3b0007、WYG 枝）。
# 葉と半葉の界（<pb:...>）を持つので、witness.py はそれも拾う。
for n in 000 001 002 003; do
  curl -s -A "$UA" -o "raw/wyg/KR3b0007_$n.txt" \
    "https://raw.githubusercontent.com/kanripo/KR3b0007/WYG/KR3b0007_$n.txt"
done

# 通校二：明劉寅《三畧直解》（Kanripo KR3b0008、WYG 枝）。
# 經文は行頭が字下げでない行、劉寅の註は字下げ一字で始まる。
for n in 000 001 002 003; do
  curl -s -A "$UA" -o "raw/zhijie/KR3b0008_$n.txt" \
    "https://raw.githubusercontent.com/kanripo/KR3b0008/WYG/KR3b0008_$n.txt"
done
echo "raw/ ready"

# 異體字表（CJKVI）：「字形の差」と「文字の異文」を分ける。align.py を見よ。
for f in cjkvi-variants.txt hydzd-variants.txt twedu-variants.txt jp-old-style.txt; do
  curl -s -A "$UA" -o "var/$f" \
    "https://raw.githubusercontent.com/cjkvi/cjkvi-variants/master/$f"
done
echo "var/ ready"
