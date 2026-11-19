#!/usr/bin/env bash
# 材料を取り直す。どれも入庫しない——raw/ と var/ は本腳本で再建できる。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw var
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

# 底本：維基文庫《茶經》（浙江鮑士恭家藏本）十篇
for n in 一之源 二之具 三之造 四之器 五之煮 六之飲 七之事 八之出 九之略 十之圖; do
  wiki "茶經/$n" "raw/base-$n.wiki"
done
wiki '茶經' 'raw/base-index.wiki'

# 通校一：四庫全書文淵閣本の録文（維基文庫）
for n in 卷上 卷中 卷下; do
  wiki "茶經 (四庫全書本)/$n" "raw/skqs-$n.wiki"
done
wiki '茶經 (四庫全書本)' 'raw/skqs-提要.wiki'

# 通校一（二種目の録文）：Kanripo KR3i0019 の WYG 枝
mkdir -p raw/wyg
for n in 000 001 002 003; do
  curl -s -A "$UA" -o "raw/wyg/KR3i0019_$n.txt" \
    "https://raw.githubusercontent.com/kanripo/KR3i0019/WYG/KR3i0019_$n.txt"
done

# Mandoku の外字實體（&KR1431; の類）→ Unicode 正字の對照表
curl -s -A "$UA" -o var/kr-charlist.org.txt \
  'https://raw.githubusercontent.com/kanripo/KR-Gaiji/master/charlist.org.txt'

# 異體字表（CJKVI）
for f in cjkvi-variants.txt hydzd-variants.txt twedu-variants.txt jp-old-style.txt; do
  curl -s -A "$UA" -o "var/$f" \
    "https://raw.githubusercontent.com/cjkvi/cjkvi-variants/master/$f"
done
echo "raw/ var/ ready"
