#!/usr/bin/env bash
# 封面：この頁の第三の證人、その卷端の葉。
#   國立國會圖書館藏『百川學海』壬集 明刊本『茶經』三卷（請求記號 特1-2229）
#   Wikimedia Commons: File:NDL2536555 茶經3卷.茶録1卷.試茶録1卷.酒譜1卷.pdf
#   PD-scan。見開き第三圖の左半葉が卷上の卷端。
# 葉には右から「茶經卷上」「一之源」「唐　竟陵陸羽」、それから全書の第一句
# 「茶者南方之嘉木也一尺二尺迺至數十尺」。下に朱文の藏書印三方。
#
# 撰者行が「唐　竟陵陸羽」——四庫本は「唐　陸羽　撰」に作る。
# 篇題も「一之源」で、四庫本の「一茶之源」ではない。本頁が底本に從って
# 「一之源」を立てる根據がこの葉の上に見えている。
#
# Commons の PDF 縮小圖は頭打ちになるので、pdfjpg.py で頁に貼られている
# JPEG（五八四七×五〇四七、見開き）をそのまま取り出す。inkscan.js で
# 列・行の墨を測ると、左半葉の墨は x 317..2967、y 614..3990（原寸）。
# 紙の緣を少し殘して x 300..2990、y 590..4020 で裁ち、560 幅に縮める。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
N='NDL2536555_%E8%8C%B6%E7%B6%933%E5%8D%B7.%E8%8C%B6%E9%8C%B21%E5%8D%B7.%E8%A9%A6%E8%8C%B6%E9%8C%B21%E5%8D%B7.%E9%85%92%E8%AD%9C1%E5%8D%B7.pdf'
mkdir -p raw
[ -f raw/ndl-baichuan.pdf ] || curl -s -A "$UA" -L -o raw/ndl-baichuan.pdf \
  "https://upload.wikimedia.org/wikipedia/commons/8/83/$N"
python3 pdfjpg.py raw/ndl-baichuan.pdf 3 raw/ndl-p3.jpg
SHARP=${SHARP:-sharp} node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/ndl-p3.jpg')
  .extract({left:300,top:590,width:2690,height:3430})
  .resize(560).jpeg({quality:88,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/chajing-cover.jpg')
  .then(i=>console.log('chajing-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"
