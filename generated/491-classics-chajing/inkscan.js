// 掃描の見開きから左右の半葉の版匡を見つける（列ごとの墨の量）。
const sharp = require(process.env.SHARP || 'sharp');
(async () => {
  const f = process.argv[2];
  const { data, info } = await sharp(f).greyscale().resize(1200)
    .raw().toBuffer({ resolveWithObject: true });
  const { width: w, height: h } = info;
  const col = new Array(w).fill(0), row = new Array(h).fill(0);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const v = 255 - data[y * w + x];
    if (v > 110) { col[x]++; row[y]++; }
  }
  const runs = (a, t) => { const o = []; let s = -1;
    for (let i = 0; i < a.length; i++) { if (a[i] > t) { if (s < 0) s = i; }
      else { if (s >= 0 && i - s > 8) o.push([s, i]); s = -1; } }
    if (s >= 0) o.push([s, a.length]); return o; };
  console.log(f, w + 'x' + h);
  console.log(' cols', JSON.stringify(runs(col, h * 0.12)));
  console.log(' rows', JSON.stringify(runs(row, w * 0.10)));
})();
