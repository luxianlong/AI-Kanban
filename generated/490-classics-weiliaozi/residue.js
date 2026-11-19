// 繁／簡の取り違えを數える。本文と註釋とを分けて出す——
// <b>本書の底本は四庫全書の寫本の録文であって、その字形をそのまま寫している</b>。
// 四庫の寫工は「于」「内」「黄」「并」「虚」「况」「横」「毁」「么」の類を用いる。
// OpenCC はこれらを「簡體の殘り」と見るが、底本の字形であって誤りではない。
// だから許容表を持ち、表に無いものが出たら赤くする。
const fs = require('fs'), O = require(process.env.OPENCC || 'opencc-js');
const t2s = O.Converter({ from: 't', to: 'cn' });
const s2t = O.Converter({ from: 'cn', to: 't' });

// 繁體の頁に出てよい「簡體に見える字」。すべて底本（四庫本）の字形か、
// 繁體でも本來その形を用いる字。
const HANT_OK = {
  // 底本（四庫寫本）の字形
  黄: '四庫本の寫工の形（黃の異體）', 内: '同上（內の異體）',
  并: '同上（並／併と別字、本書は并）', 虚: '同上（虛の異體）',
  况: '同上（況の異體）', 横: '同上（橫の異體）',
  毁: '同上（毀の異體）', 么: '「么麽」の么', 没: '同上（沒の異體）',
  别: '同上（別の異體）', 采: '同上（採の異體）', 敎: '同上（教の異體）',
  縦: '同上（縱の異體）',
  // 繁體でも本來この形
  于: '「兵勝于朝廷」の于。於と別字', 里: '百里・千里・鄉里の里',
  云: '「云」＝いう。雲ではない', 干: '「干令犯禁」＝おかす。幹ではない',
  征: '征役・征伐。徵ではない', 占: '占卜。佔ではない',
  斗: '北斗の斗。鬥ではない', 凶: '「兵者凶器」の凶', 咸: '咸池（星の名）',
  秘: '「若秘于地」の秘。祕と通用', 吃: '吃＝食う（註釋の白話）',
  准: '「不准」＝ゆるさない。準ではない', 丑: '公孫丑（人名）',
};
// 簡體の頁に殘ってよい繁體。註釋で字形そのものを談論している三對と、
// 固有名詞・語彙として簡體でもその形を用いる字。
const HANS_OK = {
  於: '「「于」「於」」——談論されている字形', 敎: '「「敎」「教」」——同上',
  縱: '「「縱」「縦」」——同上',
  徵: '魏徵（人名）。簡體でも徵に作る', 麽: '「幺麽」の麽',
};

function strip(s) {
  s = s.slice(s.indexOf('<body'));
  s = s.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<style[\s\S]*?<\/style>/g, '');
  // 言語切替のボタンには「简体」「繁體」の両方が必ず出る。頁の文章ではないので外す。
  s = s.replace(/<div class="grp langs">[\s\S]*?<\/div>/, '');
  return s.replace(/<[^>]+>/g, '');
}
const R = process.env.HOME + '/Dashboard/frontend/public/p/';
let bad = 0;
for (const [f, dir, allow] of [['weiliaozi-hans.html', 'hans', HANS_OK],
                               ['weiliaozi.html', 'hant', HANT_OK]]) {
  const s = strip(fs.readFileSync(R + f, 'utf8'));
  const hit = {}, okHit = {};
  for (const ch of s) {
    if (!/[㐀-鿿]/.test(ch)) continue;
    let flag = false;
    if (dir === 'hans') flag = t2s(ch) !== ch;
    else { const c = s2t(ch); flag = c !== ch && t2s(c) === ch; }
    if (!flag) continue;
    if (allow[ch]) okHit[ch] = (okHit[ch] || 0) + 1;
    else hit[ch] = (hit[ch] || 0) + 1;
  }
  const n = Object.values(hit).reduce((a, b) => a + b, 0);
  bad += n;
  console.log(f, n ? '未申告の残留 ' + JSON.stringify(hit) : '未申告の残留 0',
              '／申告済み', JSON.stringify(okHit));
}
if (bad) { console.error('！ 未申告の繁簡残留がある'); process.exit(1); }
console.log('繁簡の取り違え：申告外は零');
