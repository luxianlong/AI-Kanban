#!/usr/bin/env bash
# 封面：四部叢刊景宋刊本『六韜』卷第一の卷端葉。
# 出典は Wikimedia Commons の
#   File:Sibu Congkan0343-姜尚、吴起、田穰苴-六韜·吳子·司馬法-1-1.djvu（PD）
# その第 10 葉が卷端（「六韜卷第一／文韜／文師」、藏書印四顆）。
# djvu の頁は原寸 960x1351 でしか出せない（それ以上の幅は 404）。
# meas.js で墨の範囲を測ると x 1..802 / y 98..1262。
# 版匡ぎりぎりでは窮屈なので左右に数ピクセル余白を残し、
#   x 0..811（幅 812）／ y 90..1271（高さ 1182）で裁ち、560 幅に縮める。
set -euo pipefail
cd "$(dirname "$0")"
UA='DianjiClassicsBot/1.0 (https://youraikanban.com)'
N='Sibu_Congkan0343-%E5%A7%9C%E5%B0%9A%E3%80%81%E5%90%B4%E8%B5%B7%E3%80%81%E7%94%B0%E7%A9%B0%E8%8B%B4-%E5%85%AD%E9%9F%9C%C2%B7%E5%90%B3%E5%AD%90%C2%B7%E5%8F%B8%E9%A6%AC%E6%B3%95-1-1.djvu'
mkdir -p raw
curl -s -A "$UA" -o raw/leaf10.jpg \
  "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b6/$N/page10-960px-$N.jpg"
node meas.js raw/leaf10.jpg
node -e "
const sharp=require(process.env.SHARP||'sharp');
sharp('raw/leaf10.jpg')
  .extract({left:0,top:90,width:812,height:1182})
  .resize(560).greyscale().jpeg({quality:80,mozjpeg:true})
  .toFile(process.env.HOME+'/Dashboard/frontend/public/img/classics/liutao-cover.jpg')
  .then(i=>console.log('liutao-cover.jpg', i.width+'x'+i.height, i.size, 'bytes'));
"
