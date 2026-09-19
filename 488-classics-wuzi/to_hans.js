// 繁 → 簡。text.json / notes.json → text-hans.json / notes-hans.json
//
// 二種類の釘を打つ。
//
// 一、本文（text.json）——書物そのものなので、釘は打たない。この一本には
//    OpenCC が別の字に寄せてしまう字が無い（「蒐」「旂」の類が出ない）。
//    確かめは residue.js が毎回する。
//
// 二、註釋と凡例（notes.json）——そこで「談論されている」字形は轉換しない。
//    簡体にすると校語が自分で自分を取り消してしまうからで、問題になるのは
//    簡体で同じ形に落ちる對だけ。この一本では三對——
//      「閒」／「閑」  （どちらも簡体では「闲」）
//      「冑」／「胄」  （「冑」は簡体で「胄」になる）
//      「玆」／「茲」／「兹」（「茲」は簡体で「兹」）
//    釘は鉤括弧ごと一字に掛ける。前後の地の文は簡体に直るので、校語の対比
//    だけが繁體のまま殘る。「介冑」は語として談論されるので、これも釘。
//    「陳」／「陣」は簡体でも別の形に落ちるので釘は要らないが、「竈」は
//    簡体で「灶」になり、凡例の「『天灶』二處を『天竈』に改めた」が自分を
//    取り消してしまうので、これも釘。本文の「天竈」は釘を打たないから、
//    簡体の本文はちゃんと「天灶」になる。
const fs = require('fs');
const O = require(process.env.OPENCC || 'opencc-js');
const conv = O.Converter({ from: 't', to: 'cn' });

const TEXT_PIN = [];
const NOTE_PIN = ['「閒」', '「閑」', '「冑」', '「胄」', '「介冑」',
                  '「玆」', '「茲」', '「兹」', '「天竈」', '「竈」'];

// OpenCC の字表は「著」に觸れない。本頁の「著」を一つ一つ見ると、
// 《勵士》の「著不忘於心」は「あらわす」の義で簡体でも「著」、
// 凡例の「著錄」も同じ。殘りは助詞で簡体は「着」。
const ZHU_KEEP = ['著錄', '著不忘於心', '「著」', '顯著'];

const OPEN = '\u0001', CLOSE = '\u0002', ZHU = '\u0003';
let PIN = [];

function one(s) {
  if (typeof s !== 'string') return s;
  // 1) 「著」を殘す語は、その「著」だけ目印に替えておく
  for (const w of ZHU_KEEP) s = s.split(w).join(w.replace('著', ZHU));
  // 2) 談論されている字形に釘を打つ
  const marks = [];
  for (const p of PIN) {
    let i = 0;
    while ((i = s.indexOf(p, i)) >= 0) {
      const k = OPEN + marks.length + CLOSE;
      marks.push(p);
      s = s.slice(0, i) + k + s.slice(i + p.length);
      i += k.length;
    }
  }
  let out = conv(s);
  out = out.split('著').join('着');
  out = out.split(ZHU).join('著');
  return out.replace(new RegExp(OPEN + '(\\d+)' + CLOSE, 'g'), (m, i) => marks[+i]);
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

const D = __dirname;
for (const [src, dst, pin] of [['text.json', 'text-hans.json', TEXT_PIN],
                               ['notes.json', 'notes-hans.json', NOTE_PIN]]) {
  PIN = pin;
  const d = JSON.parse(fs.readFileSync(D + '/' + src, 'utf8'));
  fs.writeFileSync(D + '/' + dst, JSON.stringify(walk(d), null, 1));
  console.log(dst, 'written');
}
