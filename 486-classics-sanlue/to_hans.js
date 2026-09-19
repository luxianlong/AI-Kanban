// 繁 → 簡。text.json / notes.json → text-hans.json / notes-hans.json
//
// 註釋と凡例のなかで「談論」されている字形だけは転換しない。簡体にすると
// 自分で自分を取り消してしまうからで（「「奸」を「姦」に改む」→「「奸」を
// 「奸」に改む」）、問題になるのは簡体で同じ形に落ちる対だけ——
//   姦→奸、於→于、誌→志、蔔→卜、徵→征、讐→仇、闇→暗。
// 釘は「談論されている字」そのものにだけ打ち、まわりの地の文は簡体に直す。
// 本文（text.json）は書物そのものなので釘は打たない。
const fs = require('fs');
const O = require(process.env.OPENCC || 'opencc-js');
const conv = O.Converter({ from: 't', to: 'cn' });

// 「姦」「於」「裏」は地の文にも普通に出るので、鉤括弧に入って「談論されて
// いる」形のときだけ釘を打つ。「誌」「蔔」「讐」「闇」「壹」は本頁ではその話を
// している所にしか出ないので裸で釘を打ってよい。
const PIN = [
  '「姦」', '「於」', '「裏」', '「徵」', '「征」', '「竈」',
  '誌', '蔔', '讐', '闇', '壹',
  '亡之征',
];

// OpenCC の字表は「著」に触れない。本頁の「著」を一つ一つ見ると、「著錄」三・
// 「著名」一・「著成敗」一が「著作／顯著」の義で簡体でも「著」、残りは助詞か
// 「附著」の義で簡体は「着」。この五語の「著」だけ残し、他は一律「着」にする。
const ZHU_KEEP = ['著錄', '著名', '著成敗'];

const OPEN = '\u0001', CLOSE = '\u0002', ZHU = '\u0003';
let PINNED = true;

function one(s) {
  if (typeof s !== 'string') return s;
  // 1) 「著」を残す語は、その「著」だけ目印に替えておく
  for (const w of ZHU_KEEP) s = s.split(w).join(w.replace('著', ZHU));
  // 2) 談論されている字形に釘を打つ
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
for (const [src, dst, pinned] of [['text.json', 'text-hans.json', false],
                                  ['notes.json', 'notes-hans.json', true]]) {
  PINNED = pinned;
  const d = JSON.parse(fs.readFileSync(D + '/' + src, 'utf8'));
  fs.writeFileSync(D + '/' + dst, JSON.stringify(walk(d), null, 1));
  console.log(dst, 'written');
}
