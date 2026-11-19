// 書影の一部を切り出して擴大する（目驗の手控え。頁には出ない）。
//   node crop.js <jpg> <left> <top> <width> <height> <倍率> <出力>
const sharp = require(process.env.SHARP || 'sharp');
const [f, l, t, w, h, z, out] = process.argv.slice(2);
sharp(f).extract({left:+l, top:+t, width:+w, height:+h})
  .resize(Math.round(+w * +z)).png()
  .toFile(out).then(i => console.log(out, i.width + 'x' + i.height));
