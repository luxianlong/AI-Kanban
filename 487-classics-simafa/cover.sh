#!/usr/bin/env bash
# 封面：通校本の一つ、その巻端の葉。
#   『四部叢刊』影印 常熟瞿氏鉄琴銅剣楼蔵 影宋鈔本『司馬法』巻上 第一葉
#   Wikimedia Commons: File:Sibu Congkan0343-姜尚、吴起、田穰苴-六韜·吳子·司馬法-1-1.djvu
#   （パブリックドメイン）の第一三八頁。ウィキソースの『司馬法（四部叢刊本）』
#   卷第上も同じ djvu の 138-147 頁を用いている。
# 葉の上には順に「司馬法卷上」「仁本第一」と全書の第一句
#   「古者以仁爲本，以義治之之謂正」があり、右に朱文の蔵書印二顆。
#
# djvu の一葉は 2600x4578。upload.wikimedia.org は決まった幅しか出さないので
# 1920 幅で取り、meas.js で墨を測ると
#   版匡は上 y=300..309、下 y=2626..2638、右の縦罫 x=1630、左の縦罫は薄く x≒114。
# 紙の縁を少し残して x 70..1680（幅 1610）、y 250..2690（高さ 2440）で裁ち、
# 560 幅に縮める（→ 560x849）。朱印があるので墨色には落とさない。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
B='https://upload.wikimedia.org/wikipedia/commons/thumb/b/b6/Sibu_Congkan0343-%E5%A7%9C%E5%B0%9A%E3%80%81%E5%90%B4%E8%B5%B7%E3%80%81%E7%94%B0%E7%A9%B0%E8%8B%B4-%E5%85%AD%E9%9F%9C%C2%B7%E5%90%B3%E5%AD%90%C2%B7%E5%8F%B8%E9%A6%AC%E6%B3%95-1-1.djvu'
N='Sibu_Congkan0343-%E5%A7%9C%E5%B0%9A%E3%80%81%E5%90%B4%E8%B5%B7%E3%80%81%E7%94%B0%E7%A9%B0%E8%8B%B4-%E5%85%AD%E9%9F%9C%C2%B7%E5%90%B3%E5%AD%90%C2%B7%E5%8F%B8%E9%A6%AC%E6%B3%95-1-1.djvu.jpg'
mkdir -p raw
curl -s -A "$UA" -o raw/leaf138.jpg "$B/page138-1920px-$N"
X0=60 X1=1700 SHARP=${SHARP:-sharp} node meas.js raw/leaf138.jpg
SHARP=${SHARP:-sharp} node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/leaf138.jpg')
  .extract({left:70,top:250,width:1610,height:2440})
  .resize(560).jpeg({quality:88,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/simafa-cover.jpg')
  .then(i=>console.log('simafa-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"
