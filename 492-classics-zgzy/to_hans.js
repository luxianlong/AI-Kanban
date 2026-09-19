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
//   采／採（どちらも「采」）、於／于（「于」）、爲／為（「为」）、
//   噐／器・煑／煮・髙／高・內／内・䆫／窗・羹／羮・㪷／斗（どれも一つの形）。
// ほかに談論している對（荼／茶、灸／炙、簞／箄、赤／尺、樌／檟、間／聞、
// 啄／喙、昍／曰、到／列、櫟／礫、悴／萃、漳／鄭、褒／襄、常／衡、恩／思、
// 戈／弋、卯／卵、勝／升、細／紐、毓／育、譚／曇）は簡體でも別の形に殘るので
// 釘づけにしない——釘づけにすると簡體の頁にわけもなく繁體が殘ってしまう。
const PIN = [
  '「采」「採」', '「於」「于」', '「爲」「為」', '「噐」「器」', '「煑」「煮」',
  '「髙」「高」', '「內」「内」', '「䆫」「窗」', '「羹」「羮」', '「㪷」「斗」',
  '「采」', '「採」', '「於」', '「于」', '「爲」', '「為」', '「噐」', '「器」',
  '「煑」', '「煮」', '「髙」', '「高」', '「內」', '「内」', '「䆫」', '「窻」',
  '「窗」', '「羹」', '「羮」', '「㪷」', '「斗」', '「𤓰」', '「瓜」', '「昇」',
  '「升」', '「麪」', '「麵」',
  '「鹾簋」', '「鹺簋」', '「鹾」', '「鹺」', '「葱」', '「蔥」', '「葱／蔥」',
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
