#!/usr/bin/env bash
# 封面：この頁の第三の證人、その卷端の葉。
#   上海圖書館藏『尉繚子』白文刊本（半葉八行・行二十字、四周單邊）
#   Wikimedia Commons: File:Shanghai 尉繚子十五卷.pdf 第一頁（PD-scan）
# 葉の右から順に「尉繚子卷一」「天官第一」、それから全書の第一句
# 「梁惠王問尉繚子曰黃帝刑德可以百勝有之乎」。題の下に朱文の藏書印二方。
#
# 第三行に「黃帝刑德」とある——四庫本は「形德」に誤り、
# 本頁が改めた二處の第一がこの封面の上に見えている。
#
# Commons の PDF 縮小圖は頭打ちになるので、pdfjpg.py で頁に貼られている
# JPEG（一五〇三×一一三四、見開き）をそのまま取り出す。第一頁は右が遊紙で
# 左に卷端の半葉が一つあるだけ。meas.js／輝度走査で墨を測ると、
# 半葉の版匡は右の縱罫が x 633..644、上下の橫罫が y 180..189 と y 1062..1073。
# 紙の縁を少し殘して x 0..656、y 160..1092 で裁ち、560 幅に縮める（→560x796）。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
N='Shanghai_%E5%B0%89%E7%B9%9A%E5%AD%90%E5%8D%81%E4%BA%94%E5%8D%B7.pdf'
mkdir -p raw
[ -f raw/shanghai.pdf ] || curl -s -A "$UA" -L -o raw/shanghai.pdf \
  "https://upload.wikimedia.org/wikipedia/commons/f/f9/$N"
python3 pdfjpg.py raw/shanghai.pdf 1 raw/shanghai1.jpg
SHARP=${SHARP:-sharp} node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/shanghai1.jpg')
  .extract({left:0,top:160,width:656,height:932})
  .resize(560).jpeg({quality:88,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/weiliaozi-cover.jpg')
  .then(i=>console.log('weiliaozi-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"
