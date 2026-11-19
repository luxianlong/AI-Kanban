const fs = require('fs'), O = require('opencc-js');
const conv = O.Converter({ from: 't', to: 'cn' });

// 被談論的字形、卦名與年號：轉簡之後會自我取消或變成別的字，釘住不轉。
const PIN = [
  '爲', '裏', '衆', '羣', '竈',       // 凡例第一條舉的舊字形
  '「雲」', '「云」',                  // 凡例第二條
  '⿰口賀', '⿰目效',                  // Unicode 無字，描述的正是「賀」「效」的字形
  '脩以矛易心',                        // 底本描述字形的原話，改一個字就指不著了
];

// OpenCC 的字表不碰「著」。凡作助詞或「附著／穿著」解的，一律改「着」。
const FIXES = [
  ['接著', '接着'], ['跟著', '跟着'], ['對著', '对着'], ['連著', '连着'],
  ['順著', '顺着'], ['向著', '向着'], ['用得著', '用得着'], ['刻著', '刻着'],
  ['相持著', '相持着'], ['不著羽', '不着羽'], ['著箭頭', '着箭头'],
  ['皆著黃巾', '皆着黄巾'], ['夢著朱衣', '梦着朱衣'], ['之著面', '之着面'],
  // 乾卦與年號：簡體仍作「乾」，OpenCC 卻把它併進「干」
  ['干灵之休德', '乾灵之休德'], ['干图之休征', '乾图之休征'],
  ['干爻在四', '乾爻在四'], ['干隆', '乾隆'],
  ['幺麼', '幺么'], ['幺麽', '幺么'],
];

const OPEN = String.fromCharCode(1), CLOSE = String.fromCharCode(2);

function one(s) {
  if (typeof s !== 'string') return s;
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
  for (const [a, b] of FIXES) out = out.split(a).join(b);
  out = out.replace(new RegExp(OPEN + '(\\d+)' + CLOSE, 'g'), (m, i) => marks[+i]);
  return out;
}

function walk(x) {
  if (typeof x === 'string') return one(x);
  if (Array.isArray(x)) return x.map(walk);
  if (x && typeof x === 'object') {
    const o = {};
    for (const k of Object.keys(x)) o[k] = walk(x[k]);
    return o;
  }
  return x;
}

for (const f of process.argv.slice(2)) {
  const out = f.replace(/\.json$/, '-hans.json');
  fs.writeFileSync(out, JSON.stringify(walk(JSON.parse(fs.readFileSync(f, 'utf8')))));
  console.log(f, '->', out);
}
