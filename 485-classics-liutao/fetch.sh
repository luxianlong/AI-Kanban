#!/usr/bin/env bash
# 取回三個本子：底本（維基文庫標點本）、景宋本（四部叢刊）、四庫全書本。
# 三個本子都不入庫，raw/ 由本腳本重建。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
API=https://zh.wikisource.org/w/api.php

# 底本：《六韜》標點本，一頁六韜全文
curl -s -A "$UA" -G "$API" \
  --data-urlencode 'titles=六韜' \
  --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
| python3 -c '
import json,sys
c=list(json.load(sys.stdin)["query"]["pages"].values())[0]["revisions"][0]["slots"]["main"]["*"]
open("raw/main.wiki","w",encoding="utf-8").write(c)'
sleep 3

# 通校一：《六韜（四部叢刊本）》＝景常熟瞿氏鐵琴銅劍樓藏景宋刊本。
# 正文在 Page: 名字空間，故取 action=parse 的轉錄結果。
for n in 一 二 三 四 五 六; do
  curl -s -A "$UA" -G "$API" \
    --data-urlencode "page=六韜 (四部叢刊本)/卷第$n" \
    --data 'action=parse&prop=text&format=json&disablelimitreport=1' \
    -o "raw/sbck_$n.json"
  sleep 3
done

# 通校二：《六韜（四庫全書本）》，無句讀，另一支傳本
for n in 1 2 3 4 5 6; do
  curl -s -A "$UA" -G "$API" \
    --data-urlencode "titles=六韜 (四庫全書本)/卷$n" \
    --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
    -o "raw/sk_$n.json"
  sleep 3
done
echo "raw/ ready"

# 異體字表（CJKVI）：判「字形差異」與「文字異文」之別，見 align.py
mkdir -p var
for f in cjkvi-variants.txt hydzd-variants.txt twedu-variants.txt jp-old-style.txt joyo-variants.txt; do
  curl -s -A "$UA" -o "var/$f" \
    "https://raw.githubusercontent.com/cjkvi/cjkvi-variants/master/$f"
done
echo "var/ ready"
