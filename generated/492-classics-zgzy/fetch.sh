#!/usr/bin/env bash
# 材料を取り直す。どれも入庫しない——raw/ と var/ は本腳本で再建できる。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw var raw/wyg
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
API='https://zh.wikisource.org/w/api.php'

wiki () {   # wiki <頁名> <出力先>
  curl -s -A "$UA" -G "$API" \
    --data-urlencode "titles=$1" \
    --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
  | python3 -c '
import json,sys
p=list(json.load(sys.stdin)["query"]["pages"].values())[0]
sys.stdout.write(p["revisions"][0]["slots"]["main"]["*"])' > "$2"
  sleep 1
}

# 底本：維基文庫《貞觀政要》十卷四十篇
for n in 01 02 03 04 05 06 07 08 09 10; do
  wiki "貞觀政要/卷$n" "raw/base-$n.wiki"
done
wiki '貞觀政要' 'raw/base-index.wiki'
wiki '貞觀政要序' 'raw/base-xu.wiki'

# 通校一：四庫全書本の録文（維基文庫）
for n in 01 02 03 04 05 06 07 08 09 10; do
  wiki "貞觀政要 (四庫全書本)/卷$n" "raw/skqs-$n.wiki"
done
wiki '貞觀政要 (四庫全書本)' 'raw/skqs-提要.wiki'

# 異體字表（CJKVI）
for f in cjkvi-variants.txt hydzd-variants.txt twedu-variants.txt jp-old-style.txt; do
  curl -s -A "$UA" -o "var/$f" \
    "https://raw.githubusercontent.com/cjkvi/cjkvi-variants/master/$f"
done
echo "raw/ var/ ready"

# SKchar（四庫本の錄文が畫像／外字で示す字）の對照表
curl -s -A "$UA" -G "$API" --data-urlencode 'titles=Module:SKchar' \
  --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
| python3 -c '
import json,sys
p=list(json.load(sys.stdin)["query"]["pages"].values())[0]
sys.stdout.write(p["revisions"][0]["slots"]["main"]["*"])' > var/skchar.lua
curl -s -A "$UA" -G "$API" --data-urlencode 'titles=Module:SKchar2' \
  --data 'action=query&prop=revisions&rvprop=content&rvslots=main&format=json' \
| python3 -c '
import json,sys
p=list(json.load(sys.stdin)["query"]["pages"].values())[0]
sys.stdout.write(p["revisions"][0]["slots"]["main"]["*"])' > var/skchar2.lua
echo "skchar tables ready"
