// 繁 → 簡。text.json / notes.json → text-hans.json / notes-hans.json
// 凡例と註釋のなかで「談論」されている字形は、簡體に轉ずると自分を取り消して
// しまう（「「形」作「刑」」が「「形」作「刑」」のままならよいが、
// 「「縱」作「縦」」の類は「「纵」作「纵」」になる）。一律に釘づけにする。
const fs = require('fs');
const O = require(process.env.OPENCC || 'opencc-js');
const conv = O.Converter({ from: 't', to: 'cn' });

// 釘づけ：註釋・卷末で舉げられている字形と、本書に關わる固有の對。
// 長いものを先に置く——「陣」單字が先に釘づけになると「「陣」」が合わなくなる。
// 釘づけ：簡體に轉ずると<b>自分を取り消してしまう</b>對だけを釘づけにする。
// 註釋と卷末で「AはBに作る」と字形を談論している箇所のうち、
// A と B が簡體で同じ形に落ちるのは次の三對だけである——
//   于／於（どちらも「于」）、敎／教（どちらも「教」）、縱／縦（どちらも「纵」）。
// ほかに談論している對（形／刑、心／必、陣／陳、十／什、臣／陳、揚、龜、
// 利／節、氣／後、未／無、加／如、武／伏、夫／仗）は簡體でも別の形に残るので、
// 釘づけにしない——釘づけにすると簡體の頁にわけもなく繁體が殘ってしまう。
const PIN = [
  '「于」「於」', '「敎」「教」', '「縱」「縦」',
  '「于」', '「於」', '「敎」', '「教」', '「縱」', '「縦」',
];

const OPEN = String.fromCharCode(1), CLOSE = String.fromCharCode(2);

// 釘づけは註釋にだけ掛ける。本文は書そのものなので、そのまま簡體に落とす。
let PINNED = true;

function one(s) {
  if (typeof s !== 'string') return s;
  const marks = [];
  for (const p of (PINNED ? PIN : [])) {
    let i = 0;
    while ((i = s.indexOf(p, i)) >= 0) {
      const k = OPEN + marks.length + CLOSE;
      marks.push(p);
      s = s.slice(0, i) + k + s.slice(i + p.length);
      i += k.length;
    }
  }
  let out = conv(s);
  out = out.replace(new RegExp(OPEN + '(\\d+)' + CLOSE, 'g'), (m, i) => marks[+i]);
  return out;
}

function walk(v) {
  if (typeof v === 'string') return one(v);
  if (Array.isArray(v)) return v.map(walk);
  if (v && typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) o[k] = walk(v[k]);
    return o;
  }
  return v;
}

for (const [src, dst, pinned] of [['text.json', 'text-hans.json', false],
                                  ['notes.json', 'notes-hans.json', true]]) {
  PINNED = pinned;
  const d = JSON.parse(fs.readFileSync(src, 'utf8'));
  fs.writeFileSync(dst, JSON.stringify(walk(d), null, 1));
  console.log(dst, 'written');
}
