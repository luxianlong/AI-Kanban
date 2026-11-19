// 掃描の各頁について、左右の半葉の墨の量を測る。手控え。頁には出ない。
//   for p in $(seq 1 48); do python3 pdfjpg.py raw/shanghai.pdf $p raw/pages/sh$p.jpg; done
//   SHARP=... node inkscan.js raw/pages 48
// <b>墨の薄い右半葉は白紙の裏——卷の變り目の目印である。</b>
// 上海圖書館本の卷端葉（卷二＝攻權第五、卷三＝原官第十、卷四＝束伍令第十六、
// 卷五＝兵敎上第二十一）はこれで見つけ、あとは書影で確かめた。
const sharp = require(process.env.SHARP || 'sharp');
const fs = require('fs');
const dir = process.argv[2] || 'raw/pages';
const N = Number(process.argv[3] || 48);
(async () => {
  for (let p = 1; p <= N; p++) {
    const f = `${dir}/sh${p}.jpg`;
    if (!fs.existsSync(f)) continue;
    const img = sharp(f).greyscale();
    const { width, height } = await img.metadata();
    const buf = await img.raw().toBuffer();
    const half = [0, 0];
    for (let y = Math.round(height * 0.15); y < height * 0.9; y++)
      for (let x = 0; x < width; x++)
        if (buf[y * width + x] < 110) half[x < width / 2 ? 0 : 1]++;
    console.log(String(p).padStart(2), '左', String(half[0]).padStart(6),
                '右', String(half[1]).padStart(6),
                half[1] < 100000 ? '  ← 右半葉が白紙＝この頁の左から新しい卷' : '');
  }
})();
