#!/usr/bin/env bash
# 材料を取り直す：底本（維基文庫《唐李問對》標點本、三卷）、通校一（四庫全書
# 文淵閣本＝Kanripo KR3b0010 の WYG 枝）、通校二（維基文庫が別に起こした
# 四庫全書本の録文）、上海圖書館藏の白文刊本の書影、それに異體字表。
# どれも入庫しない。raw/ と var/ は本腳本で再建できる。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw raw/wyg var
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

# 底本：維基文庫《唐李問對》標點本。三卷が別の頁に入っており、句讀のうえ
# 一つの發言を一行に切ってある。
for j in 卷上 卷中 卷下; do
  wiki "唐李問對/$j" "raw/base-$j.wiki"
done

# 通校二：維基文庫が四庫全書の書影から別に起こした無句讀の録文。
# 通校一（Kanripo）とは互いに獨立した二つの録文で、食い違えば
# 「本子の異」ではなく「録文の誤」である。
for j in 卷上 卷中 卷下; do
  wiki "李衛公問對 (四庫全書本)/$j" "raw/skqs-$j.wiki"
done

# 通校一：四庫全書文淵閣本（Kanripo KR3b0010 の WYG 枝）。000 は提要。
for n in 000 001 002 003; do
  curl -s -A "$UA" -o "raw/wyg/KR3b0010_$n.txt" \
    "https://raw.githubusercontent.com/kanripo/KR3b0010/WYG/KR3b0010_$n.txt"
done
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
