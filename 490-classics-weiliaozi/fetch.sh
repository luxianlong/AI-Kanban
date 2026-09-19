#!/usr/bin/env bash
# 材料を取り直す。どれも入庫しない——raw/ と var/ は本腳本で再建できる。
#   底本：四庫全書文淵閣本の録文二種（Kanripo KR3b0006 の WYG 枝と維基文庫）
#   最古の證人：《羣書治要》卷第三十七（四部叢刊景上海涵芬樓藏日本尾張刊本）
#   目驗と封面：上海圖書館藏《尉繚子》白文刊本の掃描（cover.sh が取る）
#   參考：維基文庫の《尉繚子》標點本——四篇が空で底本には使えない。
#         「使えない」ことを自分で確かめられるように取っておく。
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

# 底本（通校二）：維基文庫が四庫全書の書影から起こした無句讀の録文。五卷。
for n in 1 2 3 4 5; do
  wiki "尉繚子 (四庫全書本)/卷$n" "raw/skqs-$n.wiki"
done

# 參考：維基文庫の標點本。〈伍制令〉〈分塞令〉〈束伍令〉〈經卒令〉が空。
wiki '尉繚子/全覽' 'raw/base-quanlan.wiki'

# 底本（通校一）：四庫全書文淵閣本（Kanripo KR3b0006 の WYG 枝）。000 は提要。
for n in 000 001 002 003 004 005; do
  curl -s -A "$UA" -o "raw/wyg/KR3b0006_$n.txt" \
    "https://raw.githubusercontent.com/kanripo/KR3b0006/WYG/KR3b0006_$n.txt"
done

# 最古の證人：《羣書治要》卷第三十七。Page 名前空間からの transclusion なので
# wikitext ではなく描畫結果を取る。
curl -s -A "$UA" -o raw/qszy37.json \
  'https://zh.wikisource.org/w/api.php?action=parse&prop=text&format=json&page=%E7%BE%A3%E6%9B%B8%E6%B2%BB%E8%A6%81%20(%E5%9B%9B%E9%83%A8%E5%8F%A2%E5%88%8A%E6%9C%AC)/%E5%8D%B7%E7%AC%AC%E4%B8%89%E5%8D%81%E4%B8%83'
echo "raw/ ready"

# Mandoku の外字實體（&KR0111; の類）→ Unicode 正字の對照表。
curl -s -A "$UA" -o var/kr-charlist.org.txt \
  'https://raw.githubusercontent.com/kanripo/KR-Gaiji/master/charlist.org.txt'

# 異體字表（CJKVI）。この輪では對校の相手が無いので使わないが、
# 前の輪と揃えて取っておく。
for f in cjkvi-variants.txt hydzd-variants.txt twedu-variants.txt jp-old-style.txt; do
  curl -s -A "$UA" -o "var/$f" \
    "https://raw.githubusercontent.com/cjkvi/cjkvi-variants/master/$f"
done
echo "var/ ready"
