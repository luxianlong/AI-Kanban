#!/usr/bin/env bash
# 封面：伏見版古活字本『黃石公三略』卷之上の卷端葉。
#   出典 国立国会図書館デジタルコレクション 請求記号 WA7-22（doi:10.11501/2532141）
#   Wikimedia Commons: File:NDL2532141 黄石公三略 3卷.pdf（PD）
# 慶長九年（1604）、徳川家康が伏見圓光寺で木活字により印した一本。
# その第 2 葉（PDF 第 2 頁）が卷之上の卷端で、葉の上から
#   「黃石公三略卷之上」「上略」、本文起「夫主將之法，務擥英雄之心」、
# 朱の藏書印二顆と、後人の手になる校語「講作怨／直作人」を載せる。
#
# PDF 原寸は 10150x8693。4200 幅で出し、meas.js で墨を測ると
#   版匡は x 200..1975、y 435..2840（右の縦罫 x=1970 が最も濃い）。
# 紙の縁まで少し残して x 175..1995（幅 1820）、y 345..2930（高さ 2585）
# で裁ち、560 幅に縮める（→ 560x795）。朱印があるので墨色には落とさない。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
F='NDL2532141%20%E9%BB%84%E7%9F%B3%E5%85%AC%E4%B8%89%E7%95%A5%203%E5%8D%B7.pdf'
mkdir -p raw
curl -s -A "$UA" -o raw/leaf2.jpg \
  "https://commons.wikimedia.org/w/thumb.php?f=$F&w=4200&p=2"
X0=150 X1=2130 SHARP=${SHARP:-sharp} node meas.js raw/leaf2.jpg
SHARP=${SHARP:-sharp} node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/leaf2.jpg')
  .extract({left:175,top:345,width:1820,height:2585})
  .resize(560).jpeg({quality:88,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/sanlue-cover.jpg')
  .then(i=>console.log('sanlue-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"
