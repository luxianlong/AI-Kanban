#!/usr/bin/env bash
# 封面：通校本の一つ、その巻端の葉。しかも葉の上に、この輪で分かったことが
# そのまま書いてある。
#   『景印文淵閣四庫全書』第七二六冊（子部 兵家類）『呉子』巻端
#   Wikimedia Commons: File:文淵閣四庫全書 0726冊.djvu 第六二葉（PD-scan）
# 右から順に「欽定四庫全書」「呉子」「呉起初見文侯章句」「周　呉起　撰」、
# 続いて全書の第一句「呉起儒服以兵機見魏文侯」。左の細い一行が版心で、
# 「欽定四庫全書　呉子　一」とある。
#
# 景印本は一葉に二丁（＝四半葉）を上下に組む。3840 幅で取り、meas.js で
# 墨を測ると
#   上の丁の版匡は上 y=295、下 y=2488、左右の縦罫は x=360 と x=3427、
#   版心（中縫）は x=1837..1957。
# 右半葉だけを取り、紙の縁を少し残して x 1820..3450（幅 1630）、
# y 270..2520（高さ 2250）で裁ち、560 幅に縮める（→ 560x773）。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
N='%E6%96%87%E6%B7%B5%E9%96%A3%E5%9B%9B%E5%BA%AB%E5%85%A8%E6%9B%B8_0726%E5%86%8A.djvu'
mkdir -p raw
curl -s -A "$UA" -o raw/siku62.jpg \
  "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fa/$N/page62-3840px-$N.jpg"
SHARP=${SHARP:-sharp} node meas.js raw/siku62.jpg
SHARP=${SHARP:-sharp} node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/siku62.jpg')
  .extract({left:1820,top:270,width:1630,height:2250})
  .resize(560).jpeg({quality:88,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/wuzi-cover.jpg')
  .then(i=>console.log('wuzi-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"

# 叢刊本の巻端葉も取っておく（封面には使わないが、Kanripo 録文の「二大四尺」を
# 目驗で覆すのに使った葉。卷末の〈底本と校例〉がこれを言っている）。
S='Sibu_Congkan0343-%E5%A7%9C%E5%B0%9A%E3%80%81%E5%90%B4%E8%B5%B7%E3%80%81%E7%94%B0%E7%A9%B0%E8%8B%B4-%E5%85%AD%E9%9F%9C%C2%B7%E5%90%B3%E5%AD%90%C2%B7%E5%8F%B8%E9%A6%AC%E6%B3%95-1-1.djvu'
curl -s -A "$UA" -o raw/sbck108.jpg \
  "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b6/$S/page108-1920px-$S.jpg"
echo "raw/sbck108.jpg（叢刊本 吳子卷上 卷端葉、目驗用）"
