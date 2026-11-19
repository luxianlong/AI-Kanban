#!/usr/bin/env bash
# 封面：この頁の第三の證人、その卷端の葉。
#   上海圖書館藏『唐太宗李衞公問對三卷』白文刊本（半葉八行・行二十字）
#   Wikimedia Commons: File:Shanghai 唐太宗李衛公問對三卷.pdf 第一頁（PD-scan）
# 葉の右から順に「唐太宗李衞公問對卷上」、それから全書の第一句
# 「太宗曰高麗數侵新羅朕遣使諭不奉詔將討之如何」。題の下に朱文の藏書印二方、
# 下の一方は「上海圖書館藏」。
#
# 第七行に「太宗曰晉馬隆討涼州」とある——底本は「計涼州」に誤り、本頁が
# 直した十二處の第一がこの封面の上に見えている。
#
# Commons の PDF 縮小圖は一二八〇幅で頭打ちなので、pdfjpg.py で頁に
# 貼られている JPEG（一六五二×一二一一）をそのまま取り出す。meas.js で
# 墨を測ると、版匡は y 262..1147、右の縦罫が x 620..636、左の縦罫が x 25。
# 紙の縁を少し殘して x 0..656、y 243..1168 で裁ち、560 幅に縮める（→560x790）。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
N='Shanghai_%E5%94%90%E5%A4%AA%E5%AE%97%E6%9D%8E%E8%A1%9B%E5%85%AC%E5%95%8F%E5%B0%8D%E4%B8%89%E5%8D%B7.pdf'
mkdir -p raw
[ -f raw/shanghai.pdf ] || curl -s -A "$UA" -L -o raw/shanghai.pdf \
  "https://upload.wikimedia.org/wikipedia/commons/d/de/$N"
python3 pdfjpg.py raw/shanghai.pdf 1 raw/shanghai1.jpg
SHARP=${SHARP:-sharp} node meas.js raw/shanghai1.jpg
SHARP=${SHARP:-sharp} node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/shanghai1.jpg')
  .extract({left:0,top:243,width:656,height:925})
  .resize(560).jpeg({quality:88,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/tangliwendui-cover.jpg')
  .then(i=>console.log('tangliwendui-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"
