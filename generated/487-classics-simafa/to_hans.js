// 繁 → 簡。text.json / notes.json → text-hans.json / notes-hans.json
//
// 二種類の釘を打つ。
//
// 一、本文（text.json）——書物そのものなので原則として釘は打たないが、
//    OpenCC の字表が「別の字」に寄せてしまう二字だけは留める。
//      蒐（春蒐。狩の名であって「搜」ではない。簡体でも「蒐」）
//      旂（鈴のついた旗。本書は「旗」を別に使う——「鼓旌旗」「無變其旗」
//         「釋旗」——ので、「旂→旗」と直すと二つの字が一つに潰れる）
//
// 二、註釋と凡例（notes.json）——そこで「談論されている」字形は轉換しない。
//    簡体にすると校語が自分で自分を取り消してしまうからで、問題になるのは
//    簡体で同じ形に落ちる對だけ——「於／于」「閑／閒」「決／决」の三對。
//    釘は談論している句にだけ打ち、まわりの地の文は簡体に直す。
const fs = require('fs');
const O = require(process.env.OPENCC || 'opencc-js');
const conv = O.Converter({ from: 't', to: 'cn' });

const TEXT_PIN = ['蒐', '旂'];
const NOTE_PIN = [
  '於／于',
  '「於」',
  '「避其閑窕」作「避其閑閒」',
  '「決」',
];

// OpenCC の字表は「著」に觸れない。本頁の「著」を一つ一つ見ると、「著錄」
// 「著作權」「明著」と鉤括弧に入った「著」だけが「顯著」の義で簡体でも
// 「著」、殘りは助詞で簡体は「着」。この四語だけ殘し、他は一律「着」。
const ZHU_KEEP = ['著錄', '著作權', '明著', '「著」'];

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
