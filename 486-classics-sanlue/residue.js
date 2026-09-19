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
const KEEP = new Set('姦於誌蔔徵讐闇壹裏覩畧竈灶'.split(''));
// 繁體の頁に出てよい字。s2t が別の形を返すが、どれも繁體として正しい——
//   咸（皆の意。鹹ではない）・并（「下力并」。二本とも并）・里（千里）・
//   群（羣の通用）・秘（祕の通用）・吃・凶（吉凶）・占（占卜）・征（征夷大將軍）・
//   范（范蠡。範ではない）・岳・干（相干）・于（「「于」三處改作「於」」）。
const OK_HANT = new Set('咸并里群秘吃凶占征范岳干于'.split(''));
// 繁／簡の判定から外す語。日本の機関名（国立国会図書館）は日本語の正字で、
// 簡体の混入ではない。二字目以降の判定に効くよう、丸ごと落としてから数える。
const SKIP = [/国立国会図書館/g];

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
