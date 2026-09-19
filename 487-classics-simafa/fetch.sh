#!/usr/bin/env bash
# 材料を取り直す：底本（維基文庫標點本）、通校二本（Kanripo の SBCK／WYG 枝）、
# Kanripo の外字表、それに異體字表。どれも入庫しない。raw/ と var/ は
# 本腳本で再建できる。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw raw/sbck raw/wyg raw/ws var
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'

# 底本：維基文庫《司馬法》標點本。五篇が一頁に入っており、段落も切ってある。
curl -s -A "$UA" -G 'https://zh.wikisource.org/w/api.php' \
  --data-urlencode 'titles=司馬法' \
  --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
| python3 -c '
import json,sys
c=list(json.load(sys.stdin)["query"]["pages"].values())[0]["revisions"][0]["slots"]["main"]["*"]
open("raw/main.wiki","w",encoding="utf-8").write(c)'
sleep 2

# 通校一：四部叢刊本（上海涵芬樓借常熟瞿氏鐵琴銅劍樓藏影宋鈔本影印）。
# 通校二：四庫全書文淵閣本。どちらも Kanripo KR3b0005 の枝。
curl -s -A "$UA" -o raw/sbck/KR3b0005_000.txt \
  'https://raw.githubusercontent.com/kanripo/KR3b0005/SBCK/KR3b0005_000.txt'
curl -s -A "$UA" -o raw/wyg/KR3b0005_000.txt \
  'https://raw.githubusercontent.com/kanripo/KR3b0005/WYG/KR3b0005_000.txt'

# 四庫本の覆核用：維基文庫の四庫全書本翻刻（Kanripo とは別人の手になる
# 独立した翻刻。「用衆第五」の篇題が無いことは両者一致する——witness.py 参照）。
curl -s -A "$UA" -G 'https://zh.wikisource.org/w/api.php' \
  --data-urlencode 'titles=司馬法 (四庫全書本)' \
  --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
| python3 -c '
import json,sys
c=list(json.load(sys.stdin)["query"]["pages"].values())[0]["revisions"][0]["slots"]["main"]["*"]
open("raw/ws/siku.wiki","w",encoding="utf-8").write(c)'
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
