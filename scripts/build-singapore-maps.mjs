#!/usr/bin/env node
// Draws the Singapore guide's maps as standalone SVGs (task #269).
//
// Everything here is generated from coordinates + the guide's own itinerary
// text, so the maps carry no third-party image licence and stay in sync with
// docs/singapore-travel-guide.md by hand-edit rather than by scraping.
//
// The SVGs are referenced from the Markdown as plain <img>, which means they
// cannot pull the page's webfont — hence the explicit CJK font stack and the
// light panel background that survives the dark-theme brightness filter.
//
//   node scripts/build-singapore-maps.mjs

import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const OUT_DIR = resolve(ROOT, 'frontend/public/img/singapore')

const FONT = "'Noto Sans SC','Noto Sans CJK SC','PingFang SC','Microsoft YaHei','Hiragino Sans GB','Source Han Sans SC',sans-serif"

const C = {
  panel: '#fbfbfc',
  panelLine: '#dfe3ea',
  land: '#eceee8',
  landLine: '#d3d8cd',
  sea: '#d9e7f1',
  ink: '#1f2430',
  muted: '#5b6472',
  faint: '#8c95a4',
  accent: '#d1495b',
}

// Official MRT line colours.
const LINE = {
  NS: { color: '#d42e12', name: '南北线 NS' },
  EW: { color: '#009645', name: '东西线 EW' },
  NE: { color: '#9900aa', name: '东北线 NE' },
  CC: { color: '#fa9e0d', name: '环线 CC' },
  DT: { color: '#005ec4', name: '滨海市区线 DT' },
  TE: { color: '#9d5b25', name: '汤申-东海岸线 TE' },
}

const DAY = ['#d1495b', '#1f6feb', '#3f9e78', '#c77d0a', '#7a4fbf']

// ---------------------------------------------------------------- primitives

const esc = (s) =>
  String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

// CJK glyphs are ~1em, Latin ~0.55em. Good enough to size pills and panels.
function textWidth(s, size) {
  let w = 0
  for (const ch of String(s)) {
    const c = ch.codePointAt(0)
    const wide =
      (c >= 0x1100 && c <= 0x115f) ||
      (c >= 0x2e80 && c <= 0xa4cf) ||
      (c >= 0xac00 && c <= 0xd7a3) ||
      (c >= 0xf900 && c <= 0xfaff) ||
      (c >= 0xfe30 && c <= 0xfe6f) ||
      (c >= 0xff00 && c <= 0xff60) ||
      (c >= 0xffe0 && c <= 0xffe6)
    w += wide ? size : size * 0.55
  }
  return w
}

function text(x, y, s, o = {}) {
  const a = []
  a.push(`x="${r(x)}" y="${r(y)}"`)
  a.push(`font-size="${o.size ?? 13}"`)
  if (o.weight) a.push(`font-weight="${o.weight}"`)
  a.push(`fill="${o.fill ?? C.ink}"`)
  if (o.anchor) a.push(`text-anchor="${o.anchor}"`)
  if (o.opacity) a.push(`opacity="${o.opacity}"`)
  if (o.halo) a.push(`stroke="${C.panel}" stroke-width="3.2" paint-order="stroke" stroke-linejoin="round"`)
  return `<text ${a.join(' ')}>${esc(s)}</text>`
}

const r = (n) => Math.round(n * 100) / 100

function svgDoc({ width, height, title, desc, body }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" role="img" aria-labelledby="t d" font-family="${FONT}">
<title id="t">${esc(title)}</title>
<desc id="d">${esc(desc)}</desc>
<rect width="${width}" height="${height}" rx="14" fill="${C.panel}" stroke="${C.panelLine}" />
${body}
</svg>
`
}

function heading(x, y, title, sub) {
  return (
    text(x, y, title, { size: 19, weight: 700 }) +
    (sub ? text(x, y + 20, sub, { size: 12.5, fill: C.muted }) : '')
  )
}

function footnote(x, y, s) {
  return text(x, y, s, { size: 11, fill: C.faint })
}

// Equirectangular projection. At ~1.3°N cos(lat) ≈ 0.9997, so an equal
// degree-scale on both axes is accurate to well under a pixel here.
function projector({ lonMin, lonMax, latMin, latMax, width, height, pad }) {
  const w = width - pad.l - pad.r
  const h = height - pad.t - pad.b
  const dLon = lonMax - lonMin
  const dLat = latMax - latMin
  const s = Math.min(w / dLon, h / dLat)
  const ox = pad.l + (w - dLon * s) / 2
  const oy = pad.t + (h - dLat * s) / 2
  const x = (lon) => ox + (lon - lonMin) * s
  const y = (lat) => oy + (latMax - lat) * s
  return { x, y, s, pt: ([lon, lat]) => [x(lon), y(lat)], pxPerKm: s / 111.29 }
}

function polyline(pts, o = {}) {
  const d = pts.map((p, i) => `${i ? 'L' : 'M'}${r(p[0])} ${r(p[1])}`).join(' ')
  return `<path d="${d}" fill="none" stroke="${o.stroke}" stroke-width="${o.width ?? 2}" stroke-linecap="round" stroke-linejoin="round"${o.dash ? ` stroke-dasharray="${o.dash}"` : ''}${o.opacity ? ` opacity="${o.opacity}"` : ''}${o.marker ? ` marker-end="url(#${o.marker})"` : ''} />`
}

function polygon(pts, o = {}) {
  const d = pts.map((p, i) => `${i ? 'L' : 'M'}${r(p[0])} ${r(p[1])}`).join(' ') + ' Z'
  return `<path d="${d}" fill="${o.fill ?? 'none'}" stroke="${o.stroke ?? 'none'}" stroke-width="${o.width ?? 1}"${o.opacity ? ` opacity="${o.opacity}"` : ''} />`
}

// Shift a polyline sideways so parallel MRT lines sharing a corridor stay
// readable instead of painting over each other.
function offsetPath(pts, d) {
  if (!d) return pts
  const norm = (a, b) => {
    const L = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1
    return [-(b[1] - a[1]) / L, (b[0] - a[0]) / L]
  }
  return pts.map((cur, i) => {
    const prev = pts[i - 1]
    const next = pts[i + 1]
    let nx = 0
    let ny = 0
    if (prev) {
      const n = norm(prev, cur)
      nx += n[0]
      ny += n[1]
    }
    if (next) {
      const n = norm(cur, next)
      nx += n[0]
      ny += n[1]
    }
    const m = Math.hypot(nx, ny) || 1
    return [cur[0] + (d * nx) / m, cur[1] + (d * ny) / m]
  })
}

function scaleBar(x, y, km, pxPerKm) {
  const w = km * pxPerKm
  return [
    `<path d="M${r(x)} ${r(y)} L${r(x)} ${r(y - 5)} M${r(x)} ${r(y)} L${r(x + w)} ${r(y)} M${r(x + w)} ${r(y)} L${r(x + w)} ${r(y - 5)}" stroke="${C.muted}" stroke-width="1.4" fill="none" />`,
    text(x + w / 2, y + 13, `${km} km`, { size: 10.5, fill: C.muted, anchor: 'middle' }),
  ].join('')
}

function northArrow(x, y) {
  return [
    `<path d="M${x} ${y + 18} L${x} ${y - 12}" stroke="${C.muted}" stroke-width="1.6" />`,
    `<path d="M${x} ${y - 16} L${x - 4.5} ${y - 6} L${x + 4.5} ${y - 6} Z" fill="${C.muted}" />`,
    text(x, y + 30, 'N', { size: 11, weight: 700, fill: C.muted, anchor: 'middle' }),
  ].join('')
}

function legendBox(x, y, w, rows, opts = {}) {
  const lh = opts.lh ?? 19
  const h = 12 + rows.length * lh
  const items = rows
    .map((row, i) => {
      const cy = y + 12 + i * lh + 4
      let mark = ''
      if (row.type === 'line') mark = `<line x1="${x + 11}" y1="${cy}" x2="${x + 29}" y2="${cy}" stroke="${row.color}" stroke-width="4" stroke-linecap="round" />`
      else if (row.type === 'dot') mark = `<circle cx="${x + 20}" cy="${cy}" r="5" fill="${row.color}" stroke="#fff" stroke-width="1.5" />`
      else if (row.type === 'swatch') mark = `<rect x="${x + 12}" y="${cy - 6}" width="17" height="12" rx="3" fill="${row.color}" opacity="0.55" stroke="${row.color}" />`
      return mark + text(x + 36, cy + 4, row.label, { size: 11.5, fill: C.ink })
    })
    .join('')
  return (
    `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="8" fill="#ffffff" stroke="${C.panelLine}" />` + items
  )
}

// -------------------------------------------------------------------- geodata

// Simplified coastline of the main island (lon, lat), clockwise from the west tip.
const ISLAND = [
  [103.615, 1.325], [103.63, 1.345], [103.65, 1.36], [103.665, 1.395], [103.7, 1.425],
  [103.72, 1.445], [103.755, 1.45], [103.79, 1.455], [103.82, 1.462], [103.855, 1.467],
  [103.885, 1.455], [103.915, 1.425], [103.955, 1.4], [103.99, 1.39], [104.02, 1.375],
  [104.035, 1.35], [104.02, 1.325], [103.99, 1.315], [103.96, 1.305], [103.93, 1.3],
  [103.9, 1.295], [103.87, 1.29], [103.855, 1.275], [103.84, 1.265], [103.82, 1.262],
  [103.79, 1.27], [103.76, 1.275], [103.73, 1.285], [103.7, 1.295], [103.66, 1.305],
  [103.63, 1.315],
]

const SENTOSA = [
  [103.805, 1.254], [103.82, 1.257], [103.835, 1.256], [103.848, 1.251],
  [103.84, 1.245], [103.825, 1.243], [103.81, 1.247],
]

// Johor (Malaysia) landmass across the strait — context only, very rough.
const JOHOR = [
  [103.6, 1.478], [103.6, 1.56], [104.06, 1.56], [104.06, 1.45], [103.99, 1.44],
  [103.93, 1.46], [103.87, 1.475], [103.8, 1.478], [103.74, 1.47], [103.68, 1.472],
]

const S = {
  marina_bay: [103.8546, 1.2761],
  raffles_place: [103.8515, 1.2841],
  city_hall: [103.8523, 1.293],
  dhoby_ghaut: [103.8455, 1.2988],
  somerset: [103.8388, 1.3005],
  orchard: [103.8318, 1.3043],
  newton: [103.838, 1.3138],
  outram_park: [103.8395, 1.2804],
  tanjong_pagar: [103.8457, 1.2765],
  bugis: [103.8559, 1.3005],
  lavender: [103.8629, 1.3072],
  harbourfront: [103.822, 1.2653],
  chinatown: [103.8443, 1.2847],
  clarke_quay: [103.8465, 1.2884],
  little_india: [103.8494, 1.3067],
  farrer_park: [103.8544, 1.3125],
  bras_basah: [103.8508, 1.2967],
  esplanade_stn: [103.8556, 1.2933],
  promenade: [103.8611, 1.2932],
  bayfront: [103.8593, 1.282],
  downtown: [103.8529, 1.2794],
  telok_ayer: [103.8484, 1.2822],
  fort_canning: [103.8442, 1.2921],
  rochor: [103.8526, 1.3038],
  great_world: [103.8318, 1.2937],
  havelock: [103.8352, 1.2882],
  maxwell: [103.8447, 1.2803],
  shenton_way: [103.85, 1.2779],
  gardens_stn: [103.8674, 1.279],
}

// Tourist-relevant subset of each line, in running order.
const MRT_LINES = [
  { key: 'NS', stops: ['marina_bay', 'raffles_place', 'city_hall', 'dhoby_ghaut', 'somerset', 'orchard', 'newton'], off: -3.2 },
  { key: 'EW', stops: ['outram_park', 'tanjong_pagar', 'raffles_place', 'city_hall', 'bugis', 'lavender'], off: 3.2 },
  { key: 'NE', stops: ['harbourfront', 'outram_park', 'chinatown', 'clarke_quay', 'dhoby_ghaut', 'little_india', 'farrer_park'], off: -3.2 },
  { key: 'CC', stops: ['dhoby_ghaut', 'bras_basah', 'esplanade_stn', 'promenade', 'bayfront', 'marina_bay'], off: 3.2 },
  { key: 'DT', stops: ['newton', 'little_india', 'rochor', 'bugis', 'promenade', 'bayfront', 'downtown', 'telok_ayer', 'chinatown', 'fort_canning'], off: -3.2 },
  { key: 'TE', stops: ['orchard', 'great_world', 'havelock', 'outram_park', 'maxwell', 'shenton_way', 'marina_bay', 'gardens_stn'], off: 3.2 },
]

// label: [text, anchor, dx, dy]
const STATION_LABELS = {
  marina_bay: ['滨海湾 Marina Bay', 'end', -10, 4],
  raffles_place: ['莱佛士坊', 'end', -10, 4],
  city_hall: ['政府大厦 City Hall', 'end', -10, 0],
  dhoby_ghaut: ['多美歌 Dhoby Ghaut', 'end', -10, -8],
  somerset: ['索美塞', 'middle', 0, -13],
  orchard: ['乌节 Orchard', 'end', -10, 4],
  newton: ['纽顿 Newton', 'middle', 0, -13],
  outram_park: ['欧南园 Outram Park', 'end', -11, 4],
  tanjong_pagar: ['丹戎巴葛', 'end', -10, -6],
  bugis: ['武吉士 Bugis', 'start', 11, 4],
  lavender: ['劳明达', 'start', 10, 4],
  harbourfront: ['港湾 HarbourFront', 'start', 11, 4],
  chinatown: ['牛车水 Chinatown', 'end', -11, 4],
  clarke_quay: ['克拉码头', 'end', -11, 4],
  little_india: ['小印度 Little India', 'end', -11, 3],
  farrer_park: ['花拉公园', 'end', -10, 4],
  bras_basah: ['百胜', 'start', 10, -4],
  esplanade_stn: ['滨海中心', 'start', 10, -4],
  promenade: ['宝门廊', 'start', 11, 4],
  bayfront: ['海湾舫 Bayfront', 'end', -11, -4],
  downtown: ['市中心', 'middle', 0, 18],
  fort_canning: ['福康宁', 'end', -10, 4],
  rochor: ['梧槽', 'start', 10, -5],
  great_world: ['大世界', 'end', -10, 4],
  havelock: ['合乐', 'end', -10, 4],
  maxwell: ['麦士威 Maxwell', 'start', 11, 14],
  gardens_stn: ['滨海湾花园', 'start', 11, 4],
  // telok_ayer / shenton_way are drawn but left unlabelled — the CBD corner is
  // already the densest part of the map and neither is a tourist stop.
}

const INTERCHANGE = new Set([
  'marina_bay', 'raffles_place', 'city_hall', 'dhoby_ghaut', 'outram_park',
  'chinatown', 'bugis', 'promenade', 'bayfront', 'newton', 'little_india',
  'harbourfront', 'orchard',
])

// Attractions overlaid on the city map: [lon, lat, label, anchor, dx, dy]
const SIGHTS = [
  [103.8607, 1.2834, '滨海湾金沙 · 观景台', 'start', 10, -3],
  [103.8636, 1.2816, '滨海湾花园 · 擎天树丛', 'start', 10, 11],
  [103.8545, 1.2868, '鱼尾狮公园', 'end', -9, 3],
  [103.8593, 1.2863, '艺术科学博物馆', 'start', 9, 3],
  [103.8586, 1.3021, '苏丹回教堂 · 哈芝巷', 'start', 9, 3],
  [103.8445, 1.2815, '佛牙寺', 'end', -12, -2],
  [103.8504, 1.2807, '老巴刹', 'start', 10, -8],
  [103.8503, 1.3061, '竹脚市场 Tekka', 'end', -9, 14],
  [103.8555, 1.3103, '慕达发 Mustafa（24h）', 'start', 9, 3],
  [103.8386, 1.3122, 'Newton 熟食中心', 'end', -9, 3],
]

// ------------------------------------------------------- map 1: island overview

function mapOverview() {
  const W = 1000
  const H = 640
  const p = projector({
    lonMin: 103.6, lonMax: 104.05, latMin: 1.225, latMax: 1.5,
    width: W, height: H, pad: { l: 26, r: 26, t: 74, b: 54 },
  })

  const zones = [
    { at: [103.989, 1.359], label: '樟宜机场 Changi', sub: '星耀樟宜 Jewel 雨漩涡', anchor: 'end', dx: -14, dy: -2, big: true },
    { at: [103.79, 1.404], label: 'Mandai 动物园群', sub: '动物园 / 夜间动物园 / 河川生态园 / 飞禽公园', anchor: 'end', dx: -14, dy: -2, big: true },
    { at: [103.8546, 1.287], label: '市区核心', sub: '滨海湾 · 牛车水 · 小印度 · 乌节路', anchor: 'end', dx: -16, dy: -4, big: true },
    { at: [103.826, 1.25], label: '圣淘沙 Sentosa', sub: '环球影城 · 新加坡海洋馆', anchor: 'middle', dx: 18, dy: 34, big: true },
    { at: [103.8159, 1.3138], label: '新加坡植物园', sub: 'UNESCO · 免费', anchor: 'end', dx: -12, dy: 4 },
    { at: [103.8329, 1.4173], label: 'Khatib 站', sub: '换 M2 接驳车去 Mandai', anchor: 'start', dx: 12, dy: 4 },
    { at: [103.7745, 1.4382], label: '兀兰关卡', sub: '陆路往马来西亚新山', anchor: 'middle', dx: -6, dy: -14 },
  ]

  const body = []
  body.push(`<rect x="1" y="1" width="${W - 2}" height="${H - 2}" rx="14" fill="${C.sea}" />`)
  body.push(polygon(JOHOR.map(p.pt), { fill: '#e4e6e0', stroke: '#d0d4c9', opacity: 0.85 }))
  body.push(text(p.x(103.83), p.y(1.492), '马来西亚 · 柔佛新山 Johor Bahru', { size: 12, fill: C.faint, anchor: 'middle' }))
  body.push(polygon(ISLAND.map(p.pt), { fill: C.land, stroke: C.landLine, width: 1.5 }))
  body.push(polygon(SENTOSA.map(p.pt), { fill: C.land, stroke: C.landLine, width: 1.5 }))
  body.push(text(p.x(103.75), p.y(1.247), '新加坡海峡', { size: 12, fill: '#7fa3bd', anchor: 'middle' }))
  body.push(text(p.x(103.9), p.y(1.462), '柔佛海峡', { size: 11, fill: '#7fa3bd', anchor: 'middle' }))

  // The city core gets its own detail map, so flag the zoom window.
  const [bx1, by1] = p.pt([103.826, 1.318])
  const [bx2, by2] = p.pt([103.872, 1.262])
  body.push(
    `<rect x="${r(bx1)}" y="${r(by1)}" width="${r(bx2 - bx1)}" height="${r(by2 - by1)}" rx="4" fill="${C.accent}" fill-opacity="0.07" stroke="${C.accent}" stroke-width="1.4" stroke-dasharray="5 4" />`
  )
  body.push(text(bx2 + 6, by2 + 14, '↑ 此范围详见下方「市区地铁与景点分布图」', { size: 11, fill: C.accent }))

  for (const z of zones) {
    const [x, y] = p.pt(z.at)
    const rad = z.big ? 7 : 5
    body.push(`<circle cx="${r(x)}" cy="${r(y)}" r="${rad}" fill="${C.accent}" stroke="#fff" stroke-width="2" />`)
    body.push(text(x + z.dx, y + z.dy, z.label, { size: z.big ? 14 : 12.5, weight: 700, anchor: z.anchor, halo: true }))
    if (z.sub) body.push(text(x + z.dx, y + z.dy + 15, z.sub, { size: 11, fill: C.muted, anchor: z.anchor, halo: true }))
  }

  body.push(
    legendBox(28, H - 118, 268, [
      { type: 'dot', color: C.accent, label: '机场 → 市区：地铁约 45 分 / Grab 约 20 分' },
      { type: 'dot', color: C.accent, label: '市区 → 圣淘沙：地铁 + 捷运约 30 分' },
      { type: 'dot', color: C.accent, label: '市区 → Mandai：地铁 + 接驳车约 60 分' },
    ])
  )
  body.push(scaleBar(W - 150, H - 34, 5, p.pxPerKm))
  body.push(northArrow(W - 46, 108))
  body.push(heading(26, 36, '新加坡全岛方位图', '主要游玩片区、机场与跨境关卡的相对位置；全岛东西约 42 km、南北约 23 km'))
  body.push(footnote(26, H - 16, '示意图：按经纬度等距投影绘制，海岸线为简化轮廓，比例尺仅供估距参考。'))

  return svgDoc({
    width: W, height: H,
    title: '新加坡全岛方位图',
    desc: '标示樟宜机场、市区核心、圣淘沙、Mandai 动物园群与兀兰关卡在新加坡岛上的相对位置，并给出彼此间的大致交通时间。',
    body: body.join('\n'),
  })
}

// -------------------------------------------------- map 2: city MRT + sights

function mapMrtCity() {
  const W = 1000
  const H = 1010
  const p = projector({
    lonMin: 103.818, lonMax: 103.872, latMin: 1.26, latMax: 1.318,
    width: W, height: H, pad: { l: 96, r: 96, t: 84, b: 74 },
  })

  const body = []
  body.push(`<rect x="1" y="1" width="${W - 2}" height="${H - 2}" rx="14" fill="${C.land}" fill-opacity="0.5" />`)

  // Marina Bay water, so the waterfront landmarks read as waterfront.
  const BAY = [
    [103.8535, 1.2895], [103.8565, 1.2905], [103.862, 1.2905], [103.864, 1.286],
    [103.862, 1.281], [103.859, 1.2795], [103.856, 1.28], [103.853, 1.283], [103.852, 1.287],
  ]
  body.push(polygon(BAY.map(p.pt), { fill: C.sea, stroke: '#c4dbe9' }))
  body.push(text(p.x(103.8575), p.y(1.2855), '滨海湾', { size: 11.5, fill: '#6f97b3', anchor: 'middle' }))

  for (const L of MRT_LINES) {
    const pts = offsetPath(L.stops.map((k) => p.pt(S[k])), L.off)
    body.push(polyline(pts, { stroke: LINE[L.key].color, width: 4.6, opacity: 0.95 }))
  }

  for (const [key, at] of Object.entries(S)) {
    const [x, y] = p.pt(at)
    const isX = INTERCHANGE.has(key)
    body.push(
      isX
        ? `<circle cx="${r(x)}" cy="${r(y)}" r="6.4" fill="#fff" stroke="${C.ink}" stroke-width="2.4" />`
        : `<circle cx="${r(x)}" cy="${r(y)}" r="4" fill="#fff" stroke="${C.muted}" stroke-width="1.8" />`
    )
    const lb = STATION_LABELS[key]
    if (lb) body.push(text(x + lb[2], y + lb[3], lb[0], { size: isX ? 12.5 : 11.5, weight: isX ? 700 : 400, anchor: lb[1], halo: true }))
  }

  for (const [lon, lat, label, anchor, dx, dy] of SIGHTS) {
    const [x, y] = p.pt([lon, lat])
    body.push(
      `<path d="M${r(x)} ${r(y - 6.5)} L${r(x + 6)} ${r(y + 4)} L${r(x - 6)} ${r(y + 4)} Z" fill="${C.accent}" stroke="#fff" stroke-width="1.4" />`
    )
    body.push(text(x + dx, y + dy, label, { size: 11.5, weight: 500, fill: C.accent, anchor, halo: true }))
  }

  // Bottom-right: the bottom-left corner belongs to HarbourFront.
  const rows = Object.values(LINE).map((l) => ({ type: 'line', color: l.color, label: l.name }))
  body.push(legendBox(752, H - 304, 210, rows))
  body.push(
    legendBox(752, H - 166, 210, [
      { type: 'dot', color: '#ffffff', label: '换乘站（粗圈）' },
      { type: 'dot', color: C.accent, label: '主要景点 / 熟食中心' },
    ])
  )
  body.push(scaleBar(W - 130, H - 40, 1, p.pxPerKm))
  body.push(northArrow(W - 46, 118))
  body.push(heading(26, 36, '市区地铁与景点分布图', '游客常用的 6 条地铁线与主要地标；市区各站间多为 1–3 站、步行 10–20 分钟可达'))
  body.push(footnote(26, H - 16, '简图：仅绘制游客常用线路与车站，非完整线网；线路走向按站点坐标连线简化，平行线段已做偏移以便阅读。'))

  return svgDoc({
    width: W, height: H,
    title: '新加坡市区地铁与景点分布图',
    desc: '以地理坐标绘制的市区简图，包含南北线、东西线、东北线、环线、滨海市区线、汤申-东海岸线的游客常用车站，以及滨海湾金沙、滨海湾花园、鱼尾狮、牛车水、小印度、甘榜格南等主要景点位置。',
    body: body.join('\n'),
  })
}

// ------------------------------------------- map 3: Marina Bay walking loop

function mapMarinaWalk() {
  const W = 1000
  const H = 700
  const p = projector({
    lonMin: 103.8525, lonMax: 103.8685, latMin: 1.2785, latMax: 1.2915,
    width: W, height: H, pad: { l: 150, r: 150, t: 82, b: 76 },
  })

  // The bay proper: Merlion sits on its west bank, the Sands on its east one,
  // so the water must stay between them rather than swallow both.
  const BAY = [
    [103.8548, 1.2876], [103.8558, 1.2894], [103.858, 1.2897], [103.8596, 1.288],
    [103.8592, 1.2856], [103.8586, 1.2834], [103.8574, 1.2816], [103.8558, 1.2812],
    [103.8544, 1.2826], [103.8541, 1.2854],
  ]

  // The loop, in walking order.
  const stops = [
    { at: [103.8545, 1.2868], n: 1, label: '鱼尾狮公园', sub: '免费 · 24h · 30 分钟', anchor: 'end', dx: -14, dy: 0 },
    { at: [103.8556, 1.2897], n: 2, label: '滨海艺术中心', sub: '「榴莲壳」· 路过打卡', anchor: 'end', dx: -14, dy: 0 },
    { at: [103.8608, 1.2872], n: 3, label: '螺旋桥 Helix Bridge', sub: '拍金沙全景最佳点', anchor: 'start', dx: 14, dy: 0 },
    { at: [103.8593, 1.2863], n: 4, label: '艺术科学博物馆', sub: '莲花造型 · 约 1.5h', anchor: 'start', dx: 14, dy: 0 },
    { at: [103.8607, 1.2834], n: 5, label: '滨海湾金沙 · 观景台', sub: '日落时段最佳 · 约 1h', anchor: 'start', dx: 14, dy: -12 },
    { at: [103.8636, 1.2816], n: 6, label: '擎天树丛 Supertree Grove', sub: '19:45 / 20:45 灯光秀 · 免费', anchor: 'start', dx: 14, dy: 0 },
    { at: [103.8642, 1.2795], n: 7, label: '花穹 + 云雾林双馆', sub: '双馆约 2h · S$46', anchor: 'middle', dx: 0, dy: 34 },
  ]

  const legs = [
    { from: 0, to: 1, label: '步行 6 分' },
    { from: 1, to: 2, label: '步行 9 分' },
    { from: 2, to: 3, label: '步行 5 分' },
    { from: 3, to: 4, label: '步行 5 分' },
    { from: 4, to: 5, label: '步行 10 分' },
    { from: 5, to: 6, label: '步行 6 分' },
  ]

  const body = []
  body.push(`<rect x="1" y="1" width="${W - 2}" height="${H - 2}" rx="14" fill="${C.land}" fill-opacity="0.45" />`)
  body.push(polygon(BAY.map(p.pt), { fill: C.sea, stroke: '#c4dbe9', width: 1.5 }))
  body.push(text(p.x(103.8567), p.y(1.2852), '滨海湾水域', { size: 12, fill: '#6f97b3', anchor: 'middle' }))

  body.push(
    `<defs><marker id="arw" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M0 1 L9 5 L0 9 Z" fill="${C.accent}" /></marker></defs>`
  )

  for (const leg of legs) {
    const a = p.pt(stops[leg.from].at)
    const b = p.pt(stops[leg.to].at)
    body.push(polyline([a, b], { stroke: C.accent, width: 2.6, dash: '7 5', marker: 'arw' }))
    const mx = (a[0] + b[0]) / 2
    const my = (a[1] + b[1]) / 2
    body.push(text(mx, my - 7, leg.label, { size: 10.5, fill: C.accent, anchor: 'middle', halo: true }))
  }

  for (const s of stops) {
    const [x, y] = p.pt(s.at)
    body.push(`<circle cx="${r(x)}" cy="${r(y)}" r="11" fill="${C.accent}" stroke="#fff" stroke-width="2.4" />`)
    body.push(text(x, y + 4, String(s.n), { size: 12.5, weight: 700, fill: '#fff', anchor: 'middle' }))
    body.push(text(x + s.dx, y + s.dy, s.label, { size: 13, weight: 700, anchor: s.anchor, halo: true }))
    body.push(text(x + s.dx, y + s.dy + 15, s.sub, { size: 11, fill: C.muted, anchor: s.anchor, halo: true }))
  }

  body.push(scaleBar(W - 150, H - 34, 0.5, p.pxPerKm))
  body.push(northArrow(W - 46, 112))
  body.push(heading(26, 36, '滨海湾步行环线（Day 1 半日）', '全程约 2.5 km、纯步行 40 分钟；含逛馆约需 6–7 小时，建议下午出发、以灯光秀收尾'))
  body.push(footnote(26, H - 16, '示意图：按经纬度投影绘制，步行时长为常速估算，实际受天气与馆内动线影响。'))

  return svgDoc({
    width: W, height: H,
    title: '滨海湾步行环线图',
    desc: '滨海湾景点步行路线：鱼尾狮公园经滨海艺术中心、螺旋桥、艺术科学博物馆、滨海湾金沙观景台至擎天树丛与花穹云雾林双馆，标注各段步行时间。',
    body: body.join('\n'),
  })
}

// ---------------------------------------- maps 4 & 5: itinerary route chains

// A day is a chain of stops joined by transport legs, or a set of alternatives.
const ITIN_3 = [
  {
    day: 'Day 1', theme: '滨海湾地标 + 灯光秀', color: DAY[0],
    stops: [
      { t: '鱼尾狮公园', s: '上午 · 免费' },
      { t: '滨海湾花园 · 擎天树丛', s: '免费户外' },
      { t: '花穹 + 云雾林双馆', s: '下午 · 约 2h · S$46' },
      { t: '金沙观景台', s: '傍晚看日落 · S$35–39' },
      { t: 'Garden Rhapsody 灯光秀', s: '19:45 / 20:45 · 免费' },
    ],
    legs: ['步行 15 分', '园内步行', '步行 10 分', '步行 10 分'],
  },
  {
    day: 'Day 2', theme: '圣淘沙一整天', color: DAY[1],
    stops: [
      { t: '怡丰城 VivoCity', s: '地铁到 HarbourFront' },
      { t: '环球影城', s: '全天 · S$76–89' },
      { t: '新加坡海洋馆', s: '傍晚 · 联票 S$55 起' },
      { t: '岛上沙滩 / 天际线餐厅', s: '收尾' },
    ],
    legs: ['圣淘沙捷运 4 分', '步行 10 分', '步行 / 岛内接驳'],
  },
  {
    day: 'Day 3', theme: '文化街区 + 购物', color: DAY[2],
    stops: [
      { t: '牛车水 · 佛牙寺', s: '上午' },
      { t: '麦士威熟食中心', s: '午餐 · 人均 S$5–10' },
      { t: '小印度 · Tekka', s: '下午 · 彩色别墅' },
      { t: '甘榜格南 · 哈芝巷', s: '苏丹回教堂' },
      { t: '乌节路', s: '傍晚购物' },
      { t: 'Newton 熟食中心', s: '夜宵（可选）' },
    ],
    legs: ['步行 5 分', '地铁 NE 线 10 分', '步行 / 地铁 10 分', '地铁 12 分', '地铁 6 分'],
  },
]

const ITIN_5 = [
  ITIN_3[0],
  ITIN_3[1],
  {
    day: 'Day 3', theme: 'Mandai + 夜间动物园', color: DAY[2],
    stops: [
      { t: '市区出发', s: '地铁南北线' },
      { t: 'Khatib 站', s: '换 M2 接驳车' },
      { t: '动物园 / 河川生态园', s: '下午 · 大熊猫' },
      { t: '夜间动物园', s: '18:00 转场 · 2 园联票 S$88' },
    ],
    legs: ['地铁约 30 分', '接驳车 15 分', '园区步行'],
  },
  {
    day: 'Day 4', theme: '文化街区深度', color: DAY[3],
    stops: [
      { t: '牛车水 · 佛牙寺', s: '上午' },
      { t: '麦士威熟食中心', s: '午餐' },
      { t: '小印度', s: '下午' },
      { t: '甘榜格南 · 哈芝巷', s: '傍晚' },
      { t: '乌节路', s: '购物收尾' },
    ],
    legs: ['步行 5 分', '地铁 10 分', '步行 / 地铁 10 分', '地铁 12 分'],
  },
  {
    day: 'Day 5', theme: '机动 / 主题日（四选一）', color: DAY[4],
    options: [
      { t: '飞禽公园 Bird Paradise', s: 'Mandai' },
      { t: '滨海湾金沙购物', s: 'The Shoppes' },
      { t: '新加坡植物园', s: '免费 · UNESCO' },
      { t: '星耀樟宜 Jewel', s: '返程前 · 免费瀑布' },
    ],
  },
]

// The guide's content column is ~750 px. A chart much wider than that gets
// scaled down by the page and its labels turn to mush, so the width is fixed
// here and long days wrap onto extra lines instead of running off to the right.
const CHART_W = 900

function itineraryChart(days, { title, sub, desc, note }) {
  const PAD = 26
  const GAP = 14 // pill <-> connector gap
  const PILL_H = 46
  const LINE_H = 76 // one wrapped line of pills
  const TOP = 92

  const measure = (items) =>
    items.map((it) => ({ ...it, w: Math.max(textWidth(it.t, 13.5), textWidth(it.s, 10.5)) + 24 }))

  const rows = days.map((d) => ({ ...d, items: measure(d.stops ?? d.options) }))

  // The day column has to clear its own longest theme, or the first pill sits on it.
  const BADGE_W =
    Math.max(...rows.map((d) => Math.max(textWidth(d.day, 15), textWidth(d.theme, 10.5)))) + 28

  const LEFT = PAD + BADGE_W + GAP
  const RIGHT = CHART_W - PAD - 12
  const AVAIL = RIGHT - LEFT

  // Break each day into lines that fit AVAIL. Each entry keeps the index of the
  // connector that precedes it so the renderer knows what to draw.
  for (const row of rows) {
    row.conns = row.legs ?? row.items.slice(1).map(() => '或')
    row.connW = row.conns.map((c) => Math.max(textWidth(c, 10) + 14, 44))
    const lines = [[]]
    let x = 0
    row.items.forEach((it, i) => {
      const lead = i > 0 ? GAP + row.connW[i - 1] + GAP : 0
      if (i > 0 && x + lead + it.w > AVAIL) {
        lines.push([{ it, i, wrapped: true, x: 0 }])
        x = it.w
      } else {
        lines[lines.length - 1].push({ it, i, wrapped: false, x: x + lead })
        x += lead + it.w
      }
    })
    row.lines = lines
    row.h = 16 + lines.length * LINE_H - (LINE_H - PILL_H) + 16
  }

  const H = TOP + rows.reduce((a, d) => a + d.h + 16, 0) + 26

  const body = []
  body.push(
    `<defs><marker id="ia" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5.5" markerHeight="5.5" orient="auto-start-reverse"><path d="M0 1.5 L9 5 L0 8.5 Z" fill="${C.faint}" /></marker></defs>`
  )

  let top = TOP
  for (const row of rows) {
    const chained = Boolean(row.legs)
    body.push(`<rect x="${PAD}" y="${r(top)}" width="${CHART_W - PAD * 2}" height="${r(row.h)}" rx="12" fill="${row.color}" fill-opacity="0.05" />`)
    body.push(`<rect x="${PAD}" y="${r(top)}" width="4" height="${r(row.h)}" rx="2" fill="${row.color}" />`)
    body.push(text(PAD + 14, top + 28, row.day, { size: 15, weight: 700, fill: row.color }))
    body.push(text(PAD + 14, top + 45, row.theme, { size: 10.5, fill: C.muted }))

    const lineY = (li) => top + 16 + li * LINE_H + PILL_H / 2

    row.lines.forEach((line, li) => {
      const cy = lineY(li)
      for (const cell of line) {
        const x = LEFT + cell.x
        const { it, i } = cell
        if (i > 0) {
          const cw = row.connW[i - 1]
          if (cell.wrapped) {
            // U-turn: run out to the right margin, drop down, come back to the
            // left edge of the next line.
            const prevLine = row.lines[li - 1]
            const last = prevLine[prevLine.length - 1]
            const px = LEFT + last.x + last.it.w
            const midY = cy - LINE_H / 2
            body.push(
              polyline(
                [[px + 8, cy - LINE_H], [RIGHT + 6, cy - LINE_H], [RIGHT + 6, midY], [LEFT - 8, midY], [LEFT - 8, cy]],
                { stroke: C.faint, width: 1.6, marker: chained ? 'ia' : undefined, ...(chained ? {} : { dash: '4 4' }) }
              )
            )
            // On the connector's own run, clear of the pills above and below.
            body.push(text(RIGHT - 2, midY - 6, row.conns[i - 1], { size: 10, fill: C.muted, anchor: 'end', halo: true }))
          } else {
            const cx = x - GAP - cw
            body.push(polyline([[cx, cy], [cx + cw, cy]], { stroke: C.faint, width: 1.6, ...(chained ? { marker: 'ia' } : { dash: '4 4' }) }))
            body.push(text(cx + cw / 2, cy - 8, row.conns[i - 1], { size: 10, fill: C.muted, anchor: 'middle' }))
          }
        }
        body.push(
          `<rect x="${r(x)}" y="${r(cy - PILL_H / 2)}" width="${r(it.w)}" height="${PILL_H}" rx="9" fill="#fff" stroke="${row.color}" stroke-width="1.6" />`
        )
        body.push(text(x + it.w / 2, cy - 3, it.t, { size: 13.5, weight: 700, anchor: 'middle' }))
        body.push(text(x + it.w / 2, cy + 14, it.s, { size: 10.5, fill: C.muted, anchor: 'middle' }))
      }
    })

    top += row.h + 16
  }

  body.push(heading(PAD, 36, title, sub))
  body.push(footnote(PAD, H - 12, note))

  return svgDoc({ width: CHART_W, height: Math.ceil(H), title, desc, body: body.join('\n') })
}

// ------------------------------------------------ map 6: accommodation zones

function mapHotelZones() {
  const W = 940
  const H = 820
  const p = projector({
    lonMin: 103.818, lonMax: 103.872, latMin: 1.262, latMax: 1.318,
    width: W, height: H, pad: { l: 100, r: 100, t: 84, b: 74 },
  })

  // Cheapest -> priciest, mirroring the 5.1 table.
  const TIER = { budget: '#3f9e78', mid: '#c77d0a', high: '#d1495b' }
  // side decides which way the label runs; the offset itself follows the radius
  // so a label can never end up sitting inside its own circle.
  const zones = [
    { at: [103.858, 1.2828], rKm: 0.55, tier: 'high', label: '滨海湾 Marina Bay', price: 'S$250–500+', note: '看夜景 · 最贵', side: 'right', dy: 6 },
    { at: [103.8325, 1.3045], rKm: 0.6, tier: 'high', label: '乌节路 Orchard', price: 'S$180–400', note: '购物 · 多线交汇', side: 'left', dy: 0 },
    { at: [103.8443, 1.2828], rKm: 0.45, tier: 'mid', label: '牛车水 Chinatown', price: 'S$120–250', note: '★ 均衡首选', side: 'left', dy: 10 },
    { at: [103.8465, 1.2892], rKm: 0.35, tier: 'high', label: '克拉码头 Clarke Quay', price: 'S$200–400', note: '夜生活 · 怕吵慎选', side: 'left', dy: -18 },
    { at: [103.8515, 1.3085], rKm: 0.5, tier: 'budget', label: '小印度 / 惹兰勿刹', price: 'S$80–180', note: '最省钱 · 背包客', side: 'left', dy: 0 },
    { at: [103.858, 1.3015], rKm: 0.4, tier: 'mid', label: '武吉士 / 甘榜格南', price: 'S$120–260', note: '文青 · 性价比好', side: 'right', dy: 4 },
  ]

  const body = []
  body.push(`<rect x="1" y="1" width="${W - 2}" height="${H - 2}" rx="14" fill="${C.land}" fill-opacity="0.45" />`)

  const BAY = [
    [103.8535, 1.2895], [103.8565, 1.2905], [103.862, 1.2905], [103.864, 1.286],
    [103.862, 1.281], [103.859, 1.2795], [103.856, 1.28], [103.853, 1.283], [103.852, 1.287],
  ]
  body.push(polygon(BAY.map(p.pt), { fill: C.sea, stroke: '#c4dbe9' }))

  // Faint MRT lines for orientation — the whole point of the zones is transit access.
  for (const L of MRT_LINES) {
    const pts = offsetPath(L.stops.map((k) => p.pt(S[k])), L.off)
    body.push(polyline(pts, { stroke: LINE[L.key].color, width: 3, opacity: 0.28 }))
  }
  for (const key of INTERCHANGE) {
    const [x, y] = p.pt(S[key])
    body.push(`<circle cx="${r(x)}" cy="${r(y)}" r="3.6" fill="#fff" stroke="${C.faint}" stroke-width="1.4" />`)
  }

  for (const z of zones) {
    const [x, y] = p.pt(z.at)
    const rad = z.rKm * p.pxPerKm
    const col = TIER[z.tier]
    const right = z.side === 'right'
    const dx = (right ? 1 : -1) * (rad + 12)
    const anchor = right ? 'start' : 'end'
    body.push(`<circle cx="${r(x)}" cy="${r(y)}" r="${r(rad)}" fill="${col}" fill-opacity="0.2" stroke="${col}" stroke-width="1.8" />`)
    body.push(text(x + dx, y + z.dy, z.label, { size: 13, weight: 700, anchor, halo: true }))
    body.push(text(x + dx, y + z.dy + 15, `${z.price} · ${z.note}`, { size: 10.5, fill: C.muted, anchor, halo: true }))
  }

  // Sentosa is a separate island well south of this frame; an off-map pointer
  // beats stretching the bbox over 1.5 km of empty water.
  const [hx, hy] = p.pt(S.harbourfront)
  body.push(text(hx + 10, hy - 6, '港湾 HarbourFront', { size: 11.5, fill: C.muted, halo: true }))
  body.push(polyline([[hx, hy + 12], [hx, hy + 44]], { stroke: C.accent, width: 1.8, dash: '5 4' }))
  body.push(`<path d="M${r(hx)} ${r(hy + 52)} L${r(hx - 5)} ${r(hy + 42)} L${r(hx + 5)} ${r(hy + 42)} Z" fill="${C.accent}" />`)
  body.push(text(hx + 12, hy + 40, '圣淘沙 Sentosa（岛上，图外南面）', { size: 12.5, weight: 700, fill: C.accent, halo: true }))
  body.push(text(hx + 12, hy + 55, 'S$300–600+ · 亲子 / 玩乐园 · 需捷运或缆车进出', { size: 10.5, fill: C.muted, halo: true }))

  // Right-hand side: HarbourFront and the Sentosa pointer own the bottom-left.
  body.push(
    legendBox(W - 258, H - 176, 232, [
      { type: 'swatch', color: TIER.budget, label: '经济：约 S$80–180 / 晚' },
      { type: 'swatch', color: TIER.mid, label: '中档：约 S$120–260 / 晚' },
      { type: 'swatch', color: TIER.high, label: '中高 / 高档：S$180–600+ / 晚' },
      { type: 'line', color: C.faint, label: '浅色线条为地铁线（见上方地铁图）' },
    ])
  )
  body.push(scaleBar(W - 150, H - 30, 1, p.pxPerKm))
  body.push(northArrow(W - 46, 118))
  body.push(heading(26, 36, '住宿分区与价位图', '圈的大小≈该区酒店集中范围，颜色≈价位档次；住在地铁站步行范围内，全岛景点基本 30–45 分钟可达'))
  body.push(footnote(26, H - 16, '示意图：价位为 2026‑07‑15 采集的双人间淡季量级区间，仅作分区相对高低参考，请按实际日期查当期价。'))

  return svgDoc({
    width: W, height: H,
    title: '新加坡住宿分区与价位图',
    desc: '在市区地图上标出滨海湾、乌节路、牛车水、克拉码头、小印度、武吉士与圣淘沙七个住宿分区的位置、每晚参考价位区间与适合人群。',
    body: body.join('\n'),
  })
}

// ---------------------------------------------------------------------- main

const MAPS = [
  ['map-overview.svg', mapOverview()],
  ['map-mrt-city.svg', mapMrtCity()],
  ['map-marina-bay-walk.svg', mapMarinaWalk()],
  [
    'map-itinerary-3day.svg',
    itineraryChart(ITIN_3, {
      title: '三天精华行程路线图',
      sub: '滨海湾 + 圣淘沙 + 文化街区；同一天只在 1–2 个相邻区域活动，减少往返',
      desc: '三天精华行程的逐日路线：Day 1 滨海湾地标与灯光秀，Day 2 圣淘沙环球影城与海洋馆，Day 3 牛车水、小印度、甘榜格南与乌节路，含各段交通方式与时长。',
      note: '时长为地铁/步行常速估算；票价采集于 2026‑07‑15，请以官网当期为准。热门票务建议提前网购。',
    }),
  ],
  [
    'map-itinerary-5day.svg',
    itineraryChart(ITIN_5, {
      title: '五天深度行程路线图',
      sub: '在三天基础上加入 Mandai 动物园群与更从容的节奏，适合亲子或深度游',
      desc: '五天深度行程的逐日路线：Day 1 滨海湾，Day 2 圣淘沙，Day 3 Mandai 动物园与夜间动物园，Day 4 文化街区深度，Day 5 机动主题日四选一，含各段交通方式与时长。',
      note: '时长为地铁/步行常速估算；票价采集于 2026‑07‑15，请以官网当期为准。Day 5 为四选一，非依次串联。',
    }),
  ],
  ['map-hotel-zones.svg', mapHotelZones()],
]

mkdirSync(OUT_DIR, { recursive: true })
for (const [name, svg] of MAPS) {
  writeFileSync(resolve(OUT_DIR, name), svg, 'utf8')
  console.log(`  ${name}  ${(Buffer.byteLength(svg) / 1024).toFixed(1)} kB`)
}
console.log(`${MAPS.length} maps -> ${OUT_DIR}`)
