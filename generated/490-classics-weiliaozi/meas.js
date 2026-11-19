// 封面の裁ち寸法は目分量ではなく、走査画像の列／行の輝度から測った。
// 使い方: node meas.js <jpg> [閾値]
// 出力は墨のある範囲（＝版匡）の x/y。
const sharp = require(process.env.SHARP || 'sharp');
(async () => {
  const f = process.argv[2], T = Number(process.argv[3] || 250);
  const { data, info } = await sharp(f).greyscale().raw()
    .toBuffer({ resolveWithObject: true });
  const W = info.width, H = info.height;
  const col = new Float64Array(W), row = new Float64Array(H);
  for (let y = 0; y < H; y++) {
    let s = 0;
    for (let x = 0; x < W; x++) { const v = data[y * W + x]; col[x] += v; s += v; }
    row[y] = s / W;
  }
  for (let x = 0; x < W; x++) col[x] /= H;
  let x0 = 0, x1 = W - 1, y0 = 0, y1 = H - 1;
  while (x0 < W && col[x0] > T) x0++;
  while (x1 > 0 && col[x1] > T) x1--;
  while (y0 < H && row[y0] > T) y0++;
  while (y1 > 0 && row[y1] > T) y1--;
  console.log('ink x', x0, '..', x1, ' y', y0, '..', y1,
              ' page', W + 'x' + H,
              ' ar', ((x1 - x0 + 1) / (y1 - y0 + 1)).toFixed(4));
})();
