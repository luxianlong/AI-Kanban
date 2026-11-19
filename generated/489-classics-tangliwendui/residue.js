// 繁／簡の取り違えと、簡体で「自分を取り消す」校語を機械で数える。
const fs = require('fs'), D = __dirname;
const O = require(process.env.OPENCC || 'opencc-js');
const t2s = O.Converter({ from: 't', to: 'cn' });
const s2t = O.Converter({ from: 'cn', to: 't' });

const flat = (v, out = []) => {
  if (typeof v === 'string') out.push(v);
  else if (Array.isArray(v)) v.forEach(x => flat(x, out));
  else if (v && typeof v === 'object') Object.values(v).forEach(x => flat(x, out));
  return out;
};
const load = f => flat(JSON.parse(fs.readFileSync(D + '/' + f, 'utf8'))).join('\n');

// 註釋で名指ししている字形は、その頁で「わざと」残してある。除外する。
const KEEP = new Set('於于案按常嘗校較繇由闇暗蕃番鬬鬭說説筭算隸𨽻發𤼵護䕶玄𤣥旨㫖蔾藜陳陣冄再爾耳太大候堠偏徧仇讐材才蒐乾'.split(''));
// 繁體の頁に出てよい字。s2t が別の形を返すが、どれも繁體として正しい——
//   于（《仁本》の祭告の一段。影宋鈔本と同じ）・凶（不因凶）・后（夏后氏・
//   后土）・复（無し）・里（表裏の裏ではなく「千里」の類）・咸・并・占・
//   徵（徵師）・松・岳・干・板・沈・卜。
const OK_HANT = new Set('于凶后里咸并占征群松岳干板沈卜面准啟吃斗么雲却云范迹丑采蒐乾'.split(''));
// 繁／簡の判定から外す語。「文淵閣四庫全書」の四庫本が缺筆で避諱した「𤣥」や、
// 校語のなかで名指ししている字は、上の KEEP に入れてある。ここは今のところ空。
const SKIP = [];

for (const [f, want] of [['text.json', 't'], ['notes.json', 't'],
                         ['text-hans.json', 's'], ['notes-hans.json', 's']]) {
  let s = load(f);
  for (const rx of SKIP) s = s.replace(rx, '');
  let n = 0; const seen = new Map();
  for (const c of s) {
    if (!/[一-鿿]/.test(c) || KEEP.has(c)) continue;
    if (want === 't' && OK_HANT.has(c)) continue;
    const bad = want === 's' ? (t2s(c) !== c) : (s2t(c) !== c && t2s(c) === c);
    if (bad) { n++; seen.set(c, (seen.get(c) || 0) + 1); }
  }
  console.log(f.padEnd(18), n, '字', [...seen].map(([c, k]) => c + '×' + k).join(' '));
}

// 「X」…作「X」 の形（＝校語が自分を取り消したもの）
for (const f of ['notes-hans.json', 'notes.json']) {
  const s = load(f);
  const hits = [];
  const rx = /「(.{1,4}?)」[^「」]{0,12}作「(.{1,4}?)」/g;
  let m;
  while ((m = rx.exec(s))) if (m[1] === m[2]) hits.push(m[0]);
  console.log(f.padEnd(18), '自己取消の校語', hits.length, hits.join(' / '));
}
