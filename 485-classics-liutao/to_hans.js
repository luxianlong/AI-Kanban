// 繁 → 簡。text.json / notes.json → text-hans.json / notes-hans.json
// 凡例與註釋裡被「談論」的字形，轉簡之後會自我取消（「『爲』作『為』」變成
// 「『为』作『为』」），一律釘住不轉。
const fs = require('fs');
const O = require(process.env.OPENCC || 'opencc-js');
const conv = O.Converter({ from: 't', to: 'cn' });

// 釘住：凡例與註釋裡舉出的字形、篇題用字、以及簡體同形的異文對。
// 先長後短：'爲→為' 這樣的成對舉例必須排在單字之前，否則單字先被釘走，
// 成對的那一條就再也對不上了。
const PIN = [
  '爲→為', '旂→旗', '彊→強', '姦→奸',
  '爲', '旂', '彊', '姦', '无', '决', '摇', '厩', '栖',
  '「陳」', '「陣」', '陳其政教', '勾陳', '三陳', '三陣',
  '「烏」', '「鳥」', '鳥雲', '鳥散而雲合', '烏散而雲合',
  '「鈗」', '「銳」', '「莉」', '「藜」', '「疾」', '「賊」',
  '「餧」', '「衣」', '「卒」', '「猝」', '「於」', '「于」',
  '「上／二」', '「丈／支」', '「雲」', '「云」',
  '《三陣第三十二》', '《三陳第三十二》',
];

// OpenCC 的字表不碰「著」。本書（含註釋）裡的「著」逐處看過：除「著錄」三處
// 與「著名」一處是「著作／顯著」之義外，其餘八十餘處都是助詞或「附著」之義，
// 簡體一律作「着」。
const ZHU_KEEP = ['著錄', '就是著名的'];

const OPEN = String.fromCharCode(1), CLOSE = String.fromCharCode(2);

// 釘住只對 notes 用。正文是書本身，裡頭的「烏散而雲合」「三陣」該照常轉簡；
// 註釋與凡例裡那些被「談論」的字形才不能轉。
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
  for (const w of ZHU_KEEP) s = s.split(w).join(OPEN + 'K' + marks.push(w) + CLOSE);
  let out = conv(s);
  out = out.split('著').join('着');
  out = out.replace(new RegExp(OPEN + 'K?(\\d+)' + CLOSE, 'g'), (m, i) =>
    m[1] === 'K' ? marks[+i - 1] : marks[+i]);
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
