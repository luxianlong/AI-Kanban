#!/usr/bin/env bash
# 材料を取り直す：底本（維基文庫標點本）、通校二本（Kanripo KR3b0004 の
# SBCK／WYG 枝）、Kanripo の外字表、それに異體字表。どれも入庫しない。
# raw/ と var/ は本腳本で再建できる。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw raw/sbck raw/wyg var
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'

# 底本：維基文庫《吳子》標點本。六篇が一頁に入っており、段落も切ってある。
curl -s -A "$UA" -G 'https://zh.wikisource.org/w/api.php' \
  --data-urlencode 'titles=吳子' \
  --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
| python3 -c '
import json,sys
c=list(json.load(sys.stdin)["query"]["pages"].values())[0]["revisions"][0]["slots"]["main"]["*"]
open("raw/main.wiki","w",encoding="utf-8").write(c)'
sleep 2

# 通校一：四部叢刊本（上海涵芬樓借常熟瞿氏鐵琴銅劍樓藏影宋鈔本影印）。
# 通校二：四庫全書文淵閣本。どちらも Kanripo KR3b0004 の枝。
curl -s -A "$UA" -o raw/sbck/KR3b0004_000.txt \
  'https://raw.githubusercontent.com/kanripo/KR3b0004/SBCK/KR3b0004_000.txt'
curl -s -A "$UA" -o raw/wyg/KR3b0004_000.txt \
  'https://raw.githubusercontent.com/kanripo/KR3b0004/WYG/KR3b0004_000.txt'
echo "raw/ ready"

# Mandoku の外字實體（&KR0002; の類）→ Unicode 正字の對照表。
curl -s -A "$UA" -o var/kr-charlist.org.txt \
  'https://raw.githubusercontent.com/kanripo/KR-Gaiji/master/charlist.org.txt'

# 異體字表（CJKVI）：「字形の差」と「文字の異文」を分ける。align.py を見よ。
for f in cjkvi-variants.txt hydzd-variants.txt twedu-variants.txt jp-old-style.txt; do
  curl -s -A "$UA" -o "var/$f" \
    "https://raw.githubusercontent.com/cjkvi/cjkvi-variants/master/$f"
done
echo "var/ ready"
