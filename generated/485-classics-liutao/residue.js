// 繁 / 簡の取り違えを本文（見える文字）だけで数える。
// <style> <script> JSON-LD とタグの中は見ない。
const fs = require('fs'), O = require(process.env.OPENCC || 'opencc-js');
const t2s = O.Converter({ from: 't', to: 'cn' }), s2t = O.Converter({ from: 'cn', to: 't' });
const R = process.env.HOME + '/Dashboard/frontend/public/p/';
function strip(s) {
  s = s.slice(s.indexOf('<body'));
  s = s.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<style[\s\S]*?<\/style>/g, '');
  return s.replace(/<[^>]+>/g, '');
}
let bad = 0;
for (const [f, dir] of [['liutao-hans.html', 'hans'], ['liutao.html', 'hant']]) {
  const s = strip(fs.readFileSync(R + f, 'utf8'));
  const hit = {};
  for (const ch of s) {
    if (!/[㐀-鿿]/.test(ch)) continue;
    if (dir === 'hans') { const c = t2s(ch); if (c !== ch) hit[ch] = (hit[ch] || 0) + 1; }
    else { const c = s2t(ch); if (c !== ch && t2s(c) === ch) hit[ch] = (hit[ch] || 0) + 1; }
  }
  const n = Object.values(hit).reduce((a, b) => a + b, 0);
  bad += n;
  console.log(f, n ? JSON.stringify(hit) : '残留 0');
}
