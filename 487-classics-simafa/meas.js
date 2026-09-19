// 封面の裁ち寸法を走査画像から測る。墨（閾値 110 未満）の画素を
// 列ごと・行ごとに数え、版匡の罫と紙の縁の位置を出す。
const sharp = require(process.env.SHARP || 'sharp')
sharp(process.argv[2]).greyscale().raw().toBuffer({ resolveWithObject: true })
  .then(({ data, info }) => {
    const { width: w, height: h } = info
    const T = Number(process.env.T || 110)
    const X0 = Number(process.env.X0 || 0), X1 = Number(process.env.X1 || w)
    const col = new Int32Array(w), row = new Int32Array(h)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      if (data[y * w + x] < T) { col[x]++; if (x >= X0 && x < X1) row[y]++ }
    }
    const peaks = (a, lim) => {
      const out = []
      for (let i = 1; i < a.length - 1; i++)
        if (a[i] >= lim && a[i] >= a[i - 1] && a[i] >= a[i + 1]) out.push(i + ':' + a[i])
      return out
    }
    console.log(`${w}x${h}`)
    console.log('縦の罫（列の墨が突出するところ）', peaks(col, w * 0.3).join(' '))
    console.log('横の罫（行の墨が突出するところ）', peaks(row, (X1 - X0) * 0.3).join(' '))
  })
