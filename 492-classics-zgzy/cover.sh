#!/usr/bin/env bash
# 封面：この頁が第三の證人として目驗した本、その卷一の卷端葉。
#   國立國會圖書館藏『貞觀政要』十卷（請求記號 WA7-268）
#   慶長五年（一六〇〇）伏見版古活字本。德川家康が伏見の圓光寺に命じて
#   木活字で印させた「伏見版」の一。刊記に
#   「慶長五年星輯庚子朝節／前龍山見鹿苑承兌叟謹誌／慈眼久德刊之」。
#   Wikimedia Commons / NDL デジタルコレクション、PD-scan。
#
# この一葉に、本頁が卷末で論じることの大半が見えている——
#   ・「貞觀政要卷第一」の下、卷の目は「論君道一」「論政體二」と「論」を冠し、
#     その左の篇題は「君道第一」と冠さない。同じ葉の上で二つの書きかたが竝ぶ。
#   ・篇題の下に「凡五章」。戈直が數えた章の數で、本頁もこれに從う。
#   ・本文第一行「未有身正而影曲、上理而下亂者也」——「理」は唐人が高宗李治の
#     諱を避けて「治」に代えた字。底本はこれを「治」に復している。
#   ・全葉に朱引きと片假名の送り假名。四百年前の日本の讀者の手が入っている。
#     この書の讀者が昔から日本にいたということが、書影そのものに出ている。
#
# NDL の IIIF で見開き（7076×5890）の左半葉だけを切り出す。
# 輝度で版匡を測ると原寸で x 639..3184、y 802..4588。版心（黑口・魚尾・
# 「貞觀一」）は版匡の外、x 387 あたりまで續く。これを殘したいので左を 375 まで
# 廣げ、上下左右に紙の緣を六十ばかり殘して x 375..3245、y 740..4700 で裁ち、
# 560 幅に縮める。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
mkdir -p raw/ndl
[ -f raw/ndl/leaf.jpg ] || curl -s -A "$UA" -o raw/ndl/leaf.jpg \
  'https://www.dl.ndl.go.jp/api/iiif/2570049/R0000019/375,740,2870,3960/full/0/default.jpg'
SHARP=${SHARP:-sharp} node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/ndl/leaf.jpg')
  .resize(560).jpeg({quality:88,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/zhenguanzhengyao-cover.jpg')
  .then(i=>console.log('zhenguanzhengyao-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"
