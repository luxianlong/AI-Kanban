// 繁 → 簡。text.json / notes.json → text-hans.json / notes-hans.json
//
// 三種類の手當てをする。
//
// 一、本文の「陳」（text.json）——この書の底本は陣の意味でも通して「陳」に
//    作る（四十八處すべて）。古字の通用であって、簡体の「陈」ではない。
//    四庫全書本はそこを四十三處まで「陣」に作り、殘る五處だけ「陳」のままに
//    しているので、その五處（卿試陳之・陳師牧野・陳兵縱橫・陳勝・陳平）を
//    除いて、簡体では「阵」に落とす。繁體の頁は底本どおり「陳」のまま。
//    ——今の標點整理本もみな「八陣圖」に作る。
//
// 二、註釋と凡例で「談論」されている字形は轉換しない。簡体にすると校語が
//    自分で自分を取り消してしまうからで、問題になるのは簡体で同じ形に落ちる
//    對だけ。この一本では——鬬／鬭（ともに斗）、說／説（ともに说）、
//    筭／算（ともに算）、𨽻／隸（ともに隶）、𤼵／發（ともに发）、
//    護／䕶、玄／𤣥、それに「冄」（冉になる）。釘は對ごと一括で打つ。
//
// 三、「著」は OpenCC の字表が觸れない。この頁の「著」を一つ一つ見ると、
//    「著錄」「著書」は簡体でも「著」、殘りは無い。
const fs = require('fs');
const O = require(process.env.OPENCC || 'opencc-js');
const conv = O.Converter({ from: 't', to: 'cn' });

// 本文で「陳」のまま殘す（＝簡体で「陈」にする）語。
const CHEN_KEEP = ['卿試陳之', '陳師牧野', '陳兵縱橫', '陳勝', '陳平'];
const NOTE_PIN = ['鬬／鬭', '說／説', '筭／算', '𨽻／隸', '𤼵／發',
                  '護／䕶', '玄／𤣥', '「冄」（冉）'];
const ZHU_KEEP = ['著錄', '著書', '「著」'];

const OPEN = '\u0001', CLOSE = '\u0002', ZHU = '\u0003', CHEN = '\u0004';
let PIN = [], chen = false;

function one(s) {
  if (typeof s !== 'string') return s;
  for (const w of ZHU_KEEP) s = s.split(w).join(w.replace('著', ZHU));
  // 「陳」の振り分け。殘す語の「陳」に目印を置き、殘りを「陣」に替える。
  if (chen && s.includes('陳')) {
    for (const w of CHEN_KEEP) s = s.split(w).join(w.replace('陳', CHEN));
    s = s.split('陳').join('陣');
  }
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
  out = out.split(ZHU).join('著');
  out = out.split(CHEN).join('陈');
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
for (const [src, dst, pin, c] of [['text.json', 'text-hans.json', [], true],
                                  ['notes.json', 'notes-hans.json', NOTE_PIN, true]]) {
  PIN = pin; chen = c;
  const d = JSON.parse(fs.readFileSync(D + '/' + src, 'utf8'));
  fs.writeFileSync(D + '/' + dst, JSON.stringify(walk(d), null, 1));
  console.log(dst, 'written');
}
