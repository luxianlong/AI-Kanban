#!/usr/bin/env node
// Turn every Markdown document in docs/ into a browsable webpage.
//
//   docs/<name>.md          ->  frontend/public/docs/<name>.html
//   (index of all of them)  ->  frontend/public/docs.html
//
// Rationale (task #255 "Result format"): when a task produces a complicated
// result, we'd rather hand the user a real web page than a wall of Markdown.
// Anything committed as Markdown under docs/ should therefore be reachable by
// the end web user. Vite serves frontend/public/ at the web root, so a doc
// lands at /docs/<name>.html and the index at /docs.html (linked from the
// board header). This runs as a frontend `prebuild` step, so the pages can
// never drift from the Markdown — every deploy regenerates them.
//
// The flagship Singapore guide keeps its own bespoke page
// (scripts/build-singapore-page.mjs, served at /singapore.html); the index
// links to that richer page instead of generating a plainer duplicate.
//
// Dependency-free: shares the Markdown->HTML converter in ./lib/md-to-html.mjs.

import fs from 'node:fs'
import path from 'node:path'
import { renderMarkdown, esc, slugify } from './lib/md-to-html.mjs'

const ROOT = path.resolve(import.meta.dirname, '..')
const DOCS_DIR = path.join(ROOT, 'docs')
const RESEARCH_DIR = path.join(ROOT, 'research')
const OUT_DIR = path.join(ROOT, 'frontend', 'public', 'docs')
const INDEX_OUT = path.join(ROOT, 'frontend', 'public', 'docs.html')
// A flat manifest of every doc card, read by the board's frontend so it can show
// a "new docs" badge on the Docs link (a doc the user hasn't opened yet).
const MANIFEST_OUT = path.join(ROOT, 'frontend', 'public', 'docs-manifest.json')
// Per-doc visibility (task #268): documents are PUBLIC by default, but the owner
// can mark one PRIVATE so only they can open it. The set of private doc ids lives
// in this gitignored sidecar — written by the backend's visibility endpoint, read
// here (to stamp the manifest/index) and by the Vite auth gate (to enforce it).
// Absent/unreadable => nothing is private.
const VISIBILITY_FILE = path.join(ROOT, 'frontend', '.doc-visibility.json')
// Static pages published through the unauthenticated upload API (task #470):
// POST /api/pages writes frontend/public/p/<slug>.html plus a <slug>.meta.json
// sidecar (title/summary/createdAt). Vite serves them straight from the web root
// at /p/<slug>.html — public, no password (see docsGate.mjs) — and they get a
// card here so an uploaded page is listed on /docs.html like any other document.
const UPLOADS_DIR = path.join(ROOT, 'frontend', 'public', 'p')
// Manifest ids for uploaded pages are namespaced so they can never collide with
// a docs/*.md slug. The backend derives the same id (PageUploadService).
const UPLOAD_ID_PREFIX = 'upload-'

// esc() is enough for text between tags; an attribute value also has to survive
// a double quote, and the classics shelf bakes strings that carry line breaks
// (an imprint is a list of lines, read back out by the language switch).
const attr = (s) => esc(String(s)).replace(/"/g, '&quot;').replace(/\n/g, '&#10;')

// /docs.html holds two kinds of thing (task #377), so it holds them in two tabs
// instead of one interleaved timeline:
//
//   文章收藏 — the reports, guides and notes written here, newest first
//   典籍    — books that were already old when this board was built, shown AS
//             books: a library grid of cover images, several volumes to a row,
//             wrapping to the next shelf as more are added
//
// Membership is DECLARED below, never inferred from a title: a page whose id is
// not named here is an article. That is the safe default — a new upload can
// never silently land on the classics shelf.
//
// Each volume lists its editions by manifest id, and the shelf is resolved
// against the cards that actually exist at build time: an edition whose page has
// been deleted simply drops off, and a volume with no readable edition left is
// not shelved at all (no cover that opens nothing).
//
// Editions are listed READING ORDER FIRST: editions[0] is what the cover and the
// title open, and the pill that gets inked. It is 简体 to match the standalone
// shelf at /p/dianji.html (task #378) — the same two books stand on both
// shelves, and they must not hand a reader a different edition depending on
// which shelf they came in by.
// The three readings every volume comes in. Declared ONCE here because the
// shelf no longer offers them per book: task #393 moved the choice to a single
// switch in the corner of the page, so the same vocabulary has to serve the
// switch's buttons and each volume's edition list. `key` is what the markup and
// localStorage speak; `label` is what both the buttons and the CLASSICS entries
// below are written in.
//
// A reading also carries the WORDS that go with it (task #397). Pressing 繁體
// used to re-point the covers while the shelf went on naming the books in 简体,
// so a reader who had asked for 繁體 was handed 孙子兵法 and opened 孫子兵法.
// The name a volume goes by belongs to the reading, not to the volume, so each
// edition below carries its own `title`/`imprint`, and the label a screen reader
// hears is written here once per reading rather than assembled in the page's
// script — where it could only ever be assembled in Chinese.
// `bcp47` tags the plate for the browser: 简体/繁體/English are three languages
// as far as font selection and a screen reader's voice are concerned.
const LANGS = [
  {
    key: 'hans',
    label: '简体',
    bcp47: 'zh-Hans',
    open: (t) => `打开《${t}》简体读本`,
    missing: (t) => `打开《${t}》（暂无简体读本）`,
    note: (t, def) => `《${t}》暂无简体读本，打开的是${def}本`,
  },
  {
    key: 'hant',
    label: '繁體',
    bcp47: 'zh-Hant',
    open: (t) => `打開《${t}》繁體讀本`,
    missing: (t) => `打開《${t}》（暫無繁體讀本）`,
    note: (t, def) => `《${t}》暫無繁體讀本，打開的是${def}本`,
  },
  {
    key: 'en',
    label: 'English',
    bcp47: 'en',
    open: (t) => `Open ${t} (English reading)`,
    missing: (t) => `Open ${t} (no English reading)`,
    note: (t, def) => `No English reading of ${t} — this opens the ${def} one`,
  },
  {
    key: 'ja',
    label: '日本語',
    bcp47: 'ja',
    open: (t) => `『${t}』の日本語訳を開く`,
    missing: (t) => `『${t}』を開く（日本語訳はまだありません）`,
    note: (t, def) => `『${t}』の日本語訳はまだありません。開くのは${def}版です`,
  },
]
const LANG_KEY_BY_LABEL = new Map(LANGS.map((l) => [l.label, l.key]))
const LANG_BY_KEY = new Map(LANGS.map((l) => [l.key, l]))
const DEFAULT_LANG = LANGS[0]

const CLASSICS_SHELF_ID = 'upload-dianji'
const CLASSICS_SHELF_HREF = '/p/dianji.html'
const CLASSICS = [
  {
    key: 'sunzi',
    cover: '/img/classics/sunzi-cover.jpg',
    // The crop was measured off the scan itself (column/row luminance), not
    // eyeballed: a 1141x1024 two-page opening whose left leaf is x 20..570,
    // y 20..1003 — the blank right-hand page is cut away.
    crop: 'background-size:207.5% 104.2%;background-position:3.4% 48.8%',
    ar: '.5595', // cover width/height AFTER the crop, so the book has the proportions of the object
    coverAlt: '宋刻《十一家注孙子》卷首「孙子本传」书影',
    editions: [
      { label: '简体', id: 'upload-sunzi-hans', title: '孙子兵法', imprint: ['春秋 · 十三篇'] },
      { label: '繁體', id: 'upload-sunzi', title: '孫子兵法', imprint: ['春秋 · 十三篇'] },
      {
        label: 'English',
        id: 'upload-sunzi-en',
        title: 'The Art of War',
        imprint: ['Spring and Autumn period · thirteen chapters'],
      },
      { label: '日本語', id: 'upload-sunzi-ja', title: '孫子兵法', imprint: ['春秋 · 全十三篇'] },
    ],
  },
  {
    key: 'guiguzi',
    cover: '/img/classics/guiguzi-cover.jpg',
    // 655x1019 front cover -> x 0..644, dropping the black photographic surround.
    crop: 'background-size:101.7% 100%;background-position:0% 0%',
    ar: '.632',
    coverAlt: '清嘉庆秦恩复刻本《鬼谷子》原书封面，题「鬼谷子三卷 陶贞白注本 通介叟校」',
    editions: [
      { label: '简体', id: 'upload-guiguzi-hans', title: '鬼谷子', imprint: ['战国 · 三卷二十一篇'] },
      { label: '繁體', id: 'upload-guiguzi', title: '鬼谷子', imprint: ['戰國 · 三卷二十一篇'] },
      {
        label: 'English',
        id: 'upload-guiguzi-en',
        title: 'Guiguzi',
        imprint: ['Warring States · three scrolls, twenty-one chapters'],
      },
      { label: '日本語', id: 'upload-guiguzi-ja', title: '鬼谷子', imprint: ['戦国 · 三巻二十一篇'] },
    ],
  },
  {
    key: 'sanshiliuji',
    cover: '/img/classics/sanshiliuji-cover.jpg',
    // 民国三十年(1941)瑞琴楼刊本的原书封面 — the printing the modern text descends
    // from. The JPEG is already cropped to the cover itself (the 500x747 page
    // render less the mounting board on the right and the library label), the
    // crop measured off column/row luminance, so it simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.5855',
    coverAlt: '民国三十年瑞琴楼刊本《三十六计》原书封面，题「三十六计（秘本兵法）」',
    editions: [
      { label: '简体', id: 'upload-sanshiliuji-hans', title: '三十六计', imprint: ['明清 · 六套三十六计'] },
      { label: '繁體', id: 'upload-sanshiliuji', title: '三十六計', imprint: ['明清 · 六套三十六計'] },
      {
        label: 'English',
        id: 'upload-sanshiliuji-en',
        title: 'The Thirty-Six Stratagems',
        imprint: ['Ming–Qing · six sets of six'],
      },
      { label: '日本語', id: 'upload-sanshiliuji-ja', title: '三十六計', imprint: ['明清 · 六套三十六計'] },
    ],
  },
  {
    key: 'bingjian',
    cover: '/img/classics/bingjian-cover.jpg',
    // 民国十四年(1925)上海昌文书局《神骨冰鉴・白鹤仙数》合一册的原书封面 —
    // the printing the text on these pages was collated against. The JPEG is
    // already cropped to the cover sheet (the 500x746 page render less the
    // black binding clip down the right edge, x>=432, measured off column
    // luminance), so it simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.5764',
    coverAlt: '民国十四年上海昌文书局本《神骨冰鉴》原书封面，题「神骨冰鉴 白鹤仙数 合一册」',
    editions: [
      { label: '简体', id: 'upload-bingjian-hans', title: '冰鉴', imprint: ['清 · 七篇'] },
      { label: '繁體', id: 'upload-bingjian', title: '冰鑑', imprint: ['清 · 七篇'] },
      {
        label: 'English',
        id: 'upload-bingjian-en',
        title: 'The Ice Mirror',
        imprint: ['Qing · seven chapters'],
      },
      { label: '日本語', id: 'upload-bingjian-ja', title: '氷鑑', imprint: ['清 · 全七章'] },
    ],
  },
  {
    key: 'daodejing',
    cover: '/img/classics/daodejing-cover.jpg',
    // 民国上海文瑞楼石印《老子道德经》(华亭张氏原本、晋王弼注) 的原书封面 —
    // the printing the text on these pages was collated against. The printed
    // frame sits at x 22..1437, y 262..2395 of the 1500x2549 scan (measured off
    // column/row luminance); the JPEG is already cut to x 14..1474, y 200..2418
    // and scaled to 620 wide, so it simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.6582',
    coverAlt: '民国上海文瑞楼石印本《老子道德经》原书封面，题「老子道德经　晋王弼注　王儒舲点句　陆德明释文坿　上海文瑞楼印行」',
    editions: [
      { label: '简体', id: 'upload-daodejing-hans', title: '道德经', imprint: ['春秋 · 八十一章'] },
      { label: '繁體', id: 'upload-daodejing', title: '道德經', imprint: ['春秋 · 八十一章'] },
      {
        label: 'English',
        id: 'upload-daodejing-en',
        title: 'The Daodejing',
        imprint: ['Spring and Autumn period · eighty-one chapters'],
      },
      { label: '日本語', id: 'upload-daodejing-ja', title: '道徳経', imprint: ['春秋 · 全八十一章'] },
    ],
  },
  {
    key: 'liaofansixun',
    cover: '/img/classics/liaofansixun-cover.png',
    // 民国二十三年(1934)上海佛学书局《了凡四训》(印光法师鉴定、尤惜阴圈点注释,
    // 中国国家图书馆藏) 的原书内封 — the printing the text on these pages was
    // collated against, leaf by leaf. The scan is CCITT G4 at 1535x2132; row and
    // column ink counts put the title and its rule at y 397..518, the seal and the
    // 上海佛学书局印行 imprint at y 1342..1680, and a modern barcode and library
    // label at y 1734..2076 — the label is cut away. The image is already cut to
    // x 310..1265, y 200..1728 and scaled to 560 wide, so it simply fills the box.
    // PNG, not JPEG: the scan is bilevel, and 560x896 of it costs 7 KB.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.6250',
    coverAlt: '民国二十三年上海佛学书局本《了凡四训》原书内封，题「了凡四训」，下署「上海佛学书局印行」，钤北海公园图书室藏书印',
    editions: [
      { label: '简体', id: 'upload-liaofansixun-hans', title: '了凡四训', imprint: ['明 · 四篇'] },
      { label: '繁體', id: 'upload-liaofansixun', title: '了凡四訓', imprint: ['明 · 四篇'] },
      {
        label: 'English',
        id: 'upload-liaofansixun-en',
        title: 'Liaofan’s Four Lessons',
        imprint: ['Ming · four lessons'],
      },
      { label: '日本語', id: 'upload-liaofansixun-ja', title: '了凡四訓', imprint: ['明 · 全四篇'] },
    ],
  },
  {
    key: 'sushu',
    cover: '/img/classics/sushu-cover.jpg',
    // 明万历吴兴慎懋赏解本《黄石公素书》(中国国家图书馆藏) 卷前的手书题耑 — the
    // very leaf that fronts the copy these pages were collated against, leaf by
    // leaf. Big brush characters 「黄石公素书」 down the left, a running-script
    // colophon dated 道光丁亥 (1827) beside them, four collector's seals below.
    // The scan is 1920 wide; the paper sits at x 985..1875, y 45..1635, and the
    // scanner bed all round it is cut away, leaving 560x1000 — so the image
    // simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.5600',
    coverAlt: '明万历吴兴慎懋赏解本《黄石公素书》卷前手书题耑，大字题「黄石公素书」，旁有道光丁亥（1827）行书题记，钤「小桐」等藏印',
    editions: [
      { label: '简体', id: 'upload-sushu-hans', title: '素书', imprint: ['秦 · 六章'] },
      { label: '繁體', id: 'upload-sushu', title: '素書', imprint: ['秦 · 六章'] },
      { label: 'English', id: 'upload-sushu-en', title: 'Su Shu', imprint: ['Qin · six chapters'] },
      { label: '日本語', id: 'upload-sushu-ja', title: '素書', imprint: ['秦 · 全六章'] },
    ],
  },
  {
    key: 'shangjunshu',
    cover: '/img/classics/shangjunshu-cover.jpg',
    // 《四部叢刊初編》景印上海涵芬樓藏天一閣范氏明刊本《商子》五卷 — the copy
    // the text on these pages was checked against. The 涵芬樓 leaf that carries
    // the printer's note gives the original block as 六寸五分 high by 四寸九分
    // wide, and the first leaf of the text is stamped 「四明范氏天一閣藏書」.
    // Page one of that facsimile IS the cover: a plain wrapper with the title
    // slip 「商子」 ruled down the left, and no scanner bed around it. The
    // render is 1280x2254 and the JPEG is simply that, scaled to 560 wide —
    // so it fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.5680',
    coverAlt: '四部丛刊景印天一阁藏明刊本《商子》原书封面，左上题签墨书「商子」',
    editions: [
      { label: '简体', id: 'upload-shangjunshu-hans', title: '商君书', imprint: ['战国 · 五卷二十六篇'] },
      { label: '繁體', id: 'upload-shangjunshu', title: '商君書', imprint: ['戰國 · 五卷二十六篇'] },
      {
        label: 'English',
        id: 'upload-shangjunshu-en',
        title: 'The Book of Lord Shang',
        imprint: ['Warring States · five scrolls, twenty-six chapters'],
      },
      { label: '日本語', id: 'upload-shangjunshu-ja', title: '商君書', imprint: ['戦国 · 五巻二十六篇'] },
    ],
  },
  {
    key: 'luozhijing',
    cover: '/img/classics/luozhijing-cover.jpg',
    // This is the one book on the shelf with no edition of its own. The title
    // is recorded — 《旧唐书·酷吏传上·来俊臣》 and 《资治通鉴》卷二〇三 both name
    // it — but the scroll itself was lost, no later bibliography lists it, and
    // the twelve-scroll text read here was written under Lai Junchen's name in
    // modern times (the reading pages say so on the first chapter and again in
    // the colophon). So the cover is not this book's cover: it is the opening
    // leaf of 《旧唐书》卷一百八十六上《酷吏上》 in the 1538 吴郡 printing by
    // 闻人诠 — the earliest surviving printing of that history, and the chapter
    // that carries the only old record of the title. National Central Library,
    // Taipei; 14 columns to the half-leaf, 26 characters to the column, block
    // 21.2 x 14.7 cm. The 1920px render of the opened spread is cut to the left
    // half-leaf (x 134..800, y 56..1198, the fold measured off column luminance)
    // and scaled to 560 wide, so the JPEG simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.5833',
    coverAlt: '明嘉靖十七年闻人诠刻本《旧唐书》卷一百八十六上《酷吏上》卷端叶，首列来俊臣，下接「古今御天下者，其政有四」序文',
    editions: [
      { label: '简体', id: 'upload-luozhijing-hans', title: '罗织经', imprint: ['旧题唐 · 十二卷'] },
      { label: '繁體', id: 'upload-luozhijing', title: '羅織經', imprint: ['舊題唐 · 十二卷'] },
      {
        label: 'English',
        id: 'upload-luozhijing-en',
        title: 'The Classic of Netting',
        imprint: ['Ascribed to the Tang · twelve scrolls'],
      },
      { label: '日本語', id: 'upload-luozhijing-ja', title: '羅織経', imprint: ['旧題唐 · 全十二巻'] },
    ],
  },
  {
    key: 'xiaorenjing',
    cover: '/img/classics/xiaorenjing-cover.jpg',
    // The second book on this shelf with no edition of its own, and the case is
    // thinner than 罗织经's: there the TITLE is at least recorded in two histories,
    // here not even that. 冯道 is real (欧阳修 wrote him up in 《五代史记》卷五十四,
    // and his own 《长乐老自叙》 survives in 《旧五代史》卷一百二十六), but 《荣枯鉴》
    // is in no bibliography — 《宋史·艺文志》 lists three collections under his name
    // and nothing like this — and no old copy of any kind survives. So the cover is
    // not this book's cover: it is the opening leaf of 《五代史记》卷第五十四
    // 《杂传第四十二》 in the earliest surviving printing (南宋初刊宋修本; National
    // Central Library, Taipei, 201.243 01561; 12 columns to the half-leaf, 21-23
    // characters to the column, block 16.7 x 11.6 cm) — the leaf on which the man
    // whose name is on this book enters the standard histories, 首列馮道, and whose
    // first paragraph is 欧阳修 reading 《长乐老叙》 and calling him shameless. The
    // 3000px render of the opened spread is cut to the left half-leaf (x 48..1340,
    // y 0..2345, the fold measured off column luminance at x≈1490) and scaled to
    // 560 wide, so the JPEG simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.5512',
    coverAlt:
      '南宋初刊宋修本《五代史记》卷第五十四《杂传第四十二》卷端叶，首列冯道，下接「传曰：礼义廉耻，国之四维」',
    editions: [
      { label: '简体', id: 'upload-xiaorenjing-hans', title: '小人经', imprint: ['旧题五代 · 十卷'] },
      { label: '繁體', id: 'upload-xiaorenjing', title: '小人經', imprint: ['舊題五代 · 十卷'] },
      {
        label: 'English',
        id: 'upload-xiaorenjing-en',
        title: 'The Classic of the Petty Man',
        imprint: ['Ascribed to the Five Dynasties · ten scrolls'],
      },
      { label: '日本語', id: 'upload-xiaorenjing-ja', title: '小人経', imprint: ['旧題五代 · 全十巻'] },
    ],
  },
  {
    key: 'caigentan',
    cover: '/img/classics/caigentan-cover.jpg',
    // The one volume on this shelf whose cover is its OWN cover. 洪应明 wrote the
    // book and 于孔兼 dated a foreword in front of it, so unlike 素书, 冰鉴,
    // 罗织经 and 小人经 there is a real book here with a real printing behind it:
    // the Harvard-Yenching Library's copy of the 万历四十五年 (1617) edition,
    // which is also the base text of the reading pages. The picture is that
    // copy's blue paper wrapper — the title brushed down the left in red as
    // 「菜根談」 (the title slip writes 談 where the text inside writes 譚), the
    // old shelfmark T 1681/3806 across the top, and the library's accession
    // stamp of 6 January 1964 in the middle. The 1200x1854 scan is cut to the
    // paper itself (x 21..1194, y 15..1845, measured off row and column
    // luminance) and scaled to 560 wide, so the JPEG simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.6407',
    coverAlt:
      '哈佛燕京图书馆藏明万历四十五年刻本《菜根谭》原书蓝纸书衣，朱笔行书题「菜根談」，上端旧架号 T 1681/3806，中钤该馆 1964 年入藏戳',
    editions: [
      { label: '简体', id: 'upload-caigentan-hans', title: '菜根谭', imprint: ['明 · 前后集三五六条'] },
      { label: '繁體', id: 'upload-caigentan', title: '菜根譚', imprint: ['明 · 前後集三五六條'] },
      {
        label: 'English',
        id: 'upload-caigentan-en',
        title: 'The Vegetable Root Discourses',
        imprint: ['Ming · 356 entries in two parts'],
      },
      { label: '日本語', id: 'upload-caigentan-ja', title: '菜根譚', imprint: ['明 · 前後集三五六条'] },
    ],
  },
  {
    key: 'fanjing',
    cover: '/img/classics/fanjing-cover.jpg',
    // Like 菜根谭 and unlike 素书 / 冰鉴 / 罗织经 / 小人经, this book's cover is its
    // OWN: the National Diet Library's copy of the 讀畫齋叢書 printing of the
    // 長短經 (清嘉慶間桐鄉顧修輯刊; shelfmark 082.1-D95K), 己集第四十一冊, the
    // opening leaf of scroll one. The leaf carries the scroll title 「長短經卷第一」,
    // the general heading 「文上」, the eight chapter names of the scroll, the
    // chapter title 「大體第一」 and the first sentence of the book — and it settles
    // a point the 四庫 editors could only guess at: they reasoned from the pattern
    // that 卷二 must have lost the words 「文中」, and this printing has them.
    // The 3000px render of the opened spread is cut to the left half-leaf
    // (x 252..1400, y 149..2178, the fold measured off column luminance) and
    // scaled to 560 wide, so the JPEG simply fills the box.
    crop: 'background-size:100% 100%;background-position:0% 0%',
    ar: '.5657',
    coverAlt:
      '日本国立国会图书馆藏清嘉庆读画斋丛书本《长短经》卷第一卷端叶，题「長短經卷第一　文上」，下列大体一至政体八八篇篇目，正文起「臣聞老子曰：以正理國」',
    editions: [
      { label: '简体', id: 'upload-fanjing-hans', title: '反经', imprint: ['唐 · 九卷六十四篇'] },
      { label: '繁體', id: 'upload-fanjing', title: '反經', imprint: ['唐 · 九卷六十四篇'] },
      {
        label: 'English',
        id: 'upload-fanjing-en',
        title: 'The Classic of Reversal',
        imprint: ['Tang · nine scrolls, 64 chapters'],
      },
      { label: '日本語', id: 'upload-fanjing-ja', title: '反経', imprint: ['唐 · 全九巻六十四篇'] },
    ],
  },
]

// A label that isn't one of LANGS would drop that edition off the switch without
// a word — the volume would simply refuse to follow the corner control. Catch it
// at build time instead: a typo in an edition label fails the deploy gate. The
// same goes for a reading with no name of its own (task #397): the shelf would
// silently go on calling that volume by the default reading's name, which is
// exactly the bug this registry now exists to prevent.
for (const book of CLASSICS) {
  for (const ed of book.editions) {
    if (!LANG_KEY_BY_LABEL.has(ed.label)) {
      throw new Error(
        `CLASSICS: ${book.key} lists edition label "${ed.label}", which is not one of ` +
          LANGS.map((l) => l.label).join(' / ')
      )
    }
    if (!ed.title || !ed.imprint || !ed.imprint.length) {
      throw new Error(
        `CLASSICS: ${book.key}'s ${ed.label} edition must carry its own title and imprint — ` +
          'the shelf names a book in the reading it is offering'
      )
    }
  }
}


// --- what the archive index tells a search engine (task #419) ------------
// The index is the site's second door into the classics: /p/dianji.html is the
// shelf, but /docs.html carries the same eleven books on its 典籍 tab and is
// what robots.txt names first. It used to introduce itself as 「文章存档」 with
// a one-line description that mentioned notes and nothing else, so a search for
// any of the books it holds had nothing here to match.
//
// The book list is READ OFF `CLASSICS`, never typed out again: the hand-written
// version in p/dianji.meta.json already went stale once — it still listed ten
// books after 菜根谭 was shelved.
const INDEX_TITLE = '文章存档 · 典籍：十二部中文经典全文，免费在线阅读'
function indexDescription() {
  const names = CLASSICS.map((b) => `《${b.editions[0].title}》`).join('')
  return (
    `典籍${names}——${CLASSICS.length} 部全文读本，每部备简体、繁體、English、` +
    '日本語 四种，附注释，全文免费在线阅读，无需注册。另有随手写下的报告、攻略和笔记，' +
    '按更新时间排列。'
  )
}

// Every id the classics zone owns: the volumes' editions plus the standalone
// shelf page. These are the cards kept OUT of 文章收藏.
function classicsIds() {
  const ids = new Set([CLASSICS_SHELF_ID])
  for (const book of CLASSICS) for (const ed of book.editions) ids.add(ed.id)
  return ids
}

// The volumes to actually shelve, resolved against this build's cards. A private
// edition is left off the shelf entirely rather than listed and gated: the shelf
// is a public display. Since task #382 there are no owner controls in this zone
// at all — a classic is managed by editing the repo, not by a button on a
// reading page.
function resolveShelf(cards) {
  const byId = new Map(cards.map((c) => [c.id, c]))
  const books = []
  for (const book of CLASSICS) {
    const editions = book.editions
      .map((ed) => ({ ...ed, card: byId.get(ed.id) }))
      .filter((ed) => ed.card && !ed.card.private)
    if (!editions.length) continue
    // The volume's default IS its first surviving edition — page, name and all.
    // If the 简体 reading ever drops off the shelf, the bay falls back to what it
    // actually opens rather than going on advertising a page that isn't there.
    books.push({
      ...book,
      editions,
      href: editions[0].card.href,
      title: editions[0].title,
      imprint: editions[0].imprint,
    })
  }
  return books
}

function readPrivateIds() {
  try {
    const raw = JSON.parse(fs.readFileSync(VISIBILITY_FILE, 'utf8'))
    const ids = Array.isArray(raw) ? raw : raw && raw.private
    return new Set(Array.isArray(ids) ? ids.filter((x) => typeof x === 'string') : [])
  } catch {
    return new Set()
  }
}

// A doc counts as "recently created/updated" if its source Markdown was last
// modified within this many days of the build. Such docs get a red "NEW" badge
// on the top-right of their card and sort to the front of the index (task #319).
// We key off the SOURCE .md mtime, never the generated HTML — the HTML is
// regenerated on every deploy, so its mtime would flag everything as new.
const NEW_WINDOW_DAYS = 7
const NEW_CUTOFF = Date.now() - NEW_WINDOW_DAYS * 24 * 60 * 60 * 1000

// Most-recent modification time (ms) across the given source files, ignoring any
// that can't be stat'd. Returns 0 when none resolve.
function newestMtime(absPaths) {
  let newest = 0
  for (const p of absPaths) {
    try {
      const m = fs.statSync(p).mtimeMs
      if (m > newest) newest = m
    } catch {
      /* missing/unreadable — skip */
    }
  }
  return newest
}

// Docs that already have a hand-tuned bespoke page — link to that instead of
// generating a generic one. Keyed by markdown filename ->
//   href:       the served URL (index links here instead of a generic page)
//   extraPaths: artifacts beyond the source .md that back this page and must be
//               removed when the doc is deleted from the web UI (the generated
//               HTML lives at the web root, plus any asset dir it owns).
const BESPOKE = {
  'singapore-travel-guide.md': {
    href: '/singapore.html',
    extraPaths: ['frontend/public/singapore.html', 'frontend/public/img/singapore'],
  },
}

// Multi-part research reports: a story's deliverable often lands as several
// numbered Markdown files in research/<dir>/ (one per step) rather than a single
// docs/*.md. Each listed here is stitched into ONE browsable page at
// /docs/<dir>.html — its parts concatenated in filename order, every heading
// demoted a level so each part title sits as an H2 section under the report
// title — and gets a card in the docs index. This is how e.g. the six-part
// 美伊战争 analysis becomes reachable from the board (task #267).
const REPORTS = [
  {
    dir: 'us-iran-war',
    title: '美伊战争 · 分析总览 (2026)',
    summary:
      '六部分深度研究：背景与结构性根源、当前局势快照、军力对比与升级情景、地缘影响、市场与能源冲击、情景推演与前景研判。',
    featured: true,
  },
]

// Demote every ATX heading by one level (# -> ##, ## -> ### …, capped at ######)
// so a report part's H1 becomes an H2 section under the page's report title (and
// so lands in the h2/h3 TOC). Safe here because these report files contain no
// fenced code blocks whose "#" lines could be mistaken for headings.
function demoteHeadings(md) {
  return md.replace(/^(#{1,5})(\s+)/gm, '#$1$2')
}

// Turn a filename like "ai-supply-chain" into "Ai supply chain" for a fallback
// title when a doc has no H1.
function humanize(slug) {
  return slug.replace(/[-_]+/g, ' ').replace(/^\w/, (c) => c.toUpperCase())
}

// The date an entry wears in the archive list: its source mtime as YYYY.MM.DD,
// stable regardless of the reader's locale (the list is set in mono type).
function formatDate(iso) {
  return String(iso).slice(0, 10).replace(/-/g, '.')
}

// A short plain-text summary for the index cards: the first real paragraph of
// the doc (skipping headings, blockquotes, lists, tables, rules and blanks).
function excerpt(md) {
  const lines = md.replace(/\r\n/g, '\n').split('\n')
  const buf = []
  for (const raw of lines) {
    const l = raw.trim()
    if (!l) {
      if (buf.length) break
      continue
    }
    if (/^(#|>|\||-|\*|\+|\d+[.)]|=)/.test(l) || /^-{3,}$/.test(l)) {
      if (buf.length) break
      continue
    }
    buf.push(l)
  }
  let text = buf.join(' ')
  // strip inline markdown so the card reads cleanly
  text = text
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
  if (text.length > 180) text = text.slice(0, 177).replace(/\s+\S*$/, '') + '…'
  return text
}

// --- shared page shell -------------------------------------------------------
// Visual language: a personal blog (task #519), replacing the Xiaohongshu / RED
// note look that came before it (task #462). The reference is someone's own
// writing site — a masthead with a voice, an archive of dated entries in one
// column, and an article page set for long reading. Warm paper, ink-blue links,
// Fraunces for display, Newsreader + Noto Serif SC for prose, IBM Plex Mono for
// the small utility type (dates, labels, tags). Nothing is a "card" any more,
// and the one loud element is the red rubber-stamp NEW marker on a fresh entry.
// Only CSS, copy and the new entry-date line change — the generated DOM
// (classes, data attributes, TOC, delete/privacy controls, PRIVATE-CARD
// markers) is untouched so every existing behaviour still works.
const THEME = `
:root {
  --bg: #f7f5f0;             /* warm laid paper */
  --sheet: #fffdf9;
  --card: #fffdf9;           /* legacy alias — panels that still need a surface */
  --ink: #211d1a;
  --muted: #6f6860;
  --line: #e4ddd0;
  --accent: #2c4a7c;         /* fountain-pen ink blue: links and marks */
  --accent-soft: #e8eaf1;
  --stamp: #b3402f;          /* rubber-stamp red: NEW, destructive actions */
  --link: #2c4a7c;
  --source-bg: #f1eee6;
  --source-line: #c2b193;
  --shadow: 0 2px 10px rgba(33,29,26,0.06);
  --display: "Fraunces", "Noto Serif SC", Georgia, serif;
  --body: "Newsreader", "Noto Serif SC", Georgia, "Songti SC", serif;
  --mono: "IBM Plex Mono", ui-monospace, "SFMono-Regular", Menlo, monospace;
  --maxw: 940px;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: var(--body);
  font-size: 17px;
  line-height: 1.8;
  -webkit-font-smoothing: antialiased;
}
a { color: var(--link); text-decoration: none; }
a:hover { text-decoration: underline; text-underline-offset: 3px; }
::selection { background: #dde3ee; }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
/* Masthead — the quiet top of a personal site: a label, the name, one line of
   voice, a way back. No banner, no gradient; a single hairline closes it. */
.hero {
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 56px 24px 24px;
  border-bottom: 1px solid var(--line);
}
.hero .eyebrow {
  font-family: var(--mono);
  font-size: 11px; font-weight: 500; letter-spacing: 0.18em;
  text-transform: uppercase; color: var(--muted);
  margin: 0 0 14px;
}
.hero h1 {
  font-family: var(--display);
  font-weight: 700;
  font-size: clamp(30px, 5vw, 46px);
  line-height: 1.16;
  letter-spacing: -0.01em;
  margin: 0 0 12px;
}
.hero p.sub { margin: 0; max-width: 62ch; color: var(--muted); font-size: 16px; text-wrap: pretty; }
.hero .back {
  display: inline-block; margin-top: 20px;
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.05em;
  color: var(--muted);
}
.hero .back:hover { color: var(--accent); }
/* Visit tally (task #523). Filled in once /api/docs/views answers, and left
   hidden until then — an article nobody has opened yet says nothing rather than
   announcing a zero. Quiet mono figures, the same register as the dates. */
.views {
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.06em; color: var(--muted);
}
.hero .views { display: inline-block; margin-top: 20px; margin-left: 14px; }
@media (max-width: 700px) {
  .hero { padding: 34px 20px 20px; }
}
`

const CONTENT_CSS = `
.layout {
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 34px 24px 96px;
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 46px;
  align-items: start;
}
/* Marginalia, not a widget: the contents list sits unboxed in the left margin,
   the way a printed book sets its running heads. */
.toc {
  position: sticky;
  top: 28px;
  max-height: calc(100vh - 56px);
  overflow-y: auto;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.55;
  padding-right: 6px;
}
/* The contents list folds away (task #224). The heading itself is the control —
   a long catalog otherwise fills the whole margin and there is no way to put it
   back — so no extra chrome is added; only the caret moves. */
.toc-title {
  display: flex; align-items: center; gap: 7px;
  width: 100%; text-align: left; cursor: pointer;
  background: none; border: none; border-bottom: 1px solid var(--line);
  padding: 0 0 9px; margin-bottom: 12px;
  font-family: var(--mono);
  font-size: 11px; letter-spacing: 0.18em; text-transform: uppercase;
  color: var(--muted);
}
.toc-title:hover { color: var(--accent); }
.toc-caret { font-size: 9px; line-height: 1; transition: transform 0.18s; }
.toc.collapsed .toc-caret { transform: rotate(-90deg); }
.toc.collapsed ul { display: none; }
/* Folded, the caret is the entire control (task #457). The word 目录 set in
   11px/0.18em tracking was still holding ~45px of margin open — several
   character widths of chrome for a list nobody is reading — so the label goes
   with the list and only the arrow stays. The padding that makes the arrow a
   real click target stays INSIDE the button rather than being pulled back with
   a negative margin: the nav is a scroll box, and anything hanging outside it
   is clipped out of the hit region as well as out of the paint. */
.toc.collapsed .toc-label { display: none; }
.toc.collapsed .toc-caret { font-size: 13px; }
/* Nothing below it to separate any more, and no text to keep off the rule. */
.toc.collapsed .toc-title { border-bottom: none; padding: 5px; margin: 0; width: auto; gap: 0; }
/* One short line of content: the scroll box has nothing left to scroll, and
   keeping it would only clip the control's own padding. */
.toc.collapsed { padding-right: 0; overflow: visible; max-height: none; }
.toc ul { list-style: none; margin: 0; padding: 0; }
.toc li { margin: 0; }
.toc li a { display: block; padding: 4px 0; color: var(--muted); overflow-wrap: anywhere; }
.toc li a:hover { color: var(--accent); text-decoration: none; }
.toc li.lv2 a { color: var(--ink); margin-top: 9px; }
.toc li.lv3 a { padding-left: 14px; font-size: 12px; }
.content { min-width: 0; max-width: 70ch; padding: 0; }
.content h1 { font-family: var(--display); font-weight: 700; font-size: 32px; margin: 6px 0 14px; }
.content h2, .content h3, .content h4, .content h5, .content h6 {
  font-family: var(--display); font-weight: 600;
  line-height: 1.3; scroll-margin-top: 20px; position: relative;
}
.content h2 { font-size: 27px; margin: 54px 0 14px; padding-top: 22px; border-top: 1px solid var(--line); }
/* A section rule the author wrote (---) already separates the sections; don't
   double it with the heading's own rule. */
.content hr + h2 { border-top: none; padding-top: 0; margin-top: 18px; }
.content h3 { font-size: 21px; margin: 34px 0 10px; }
.content h4 {
  font-family: var(--mono); font-weight: 500; font-size: 13px; letter-spacing: 0.12em;
  text-transform: uppercase; color: var(--muted); margin: 26px 0 8px;
}
.content p { margin: 16px 0; }
.content ul, .content ol { margin: 16px 0; padding-left: 22px; }
.content li { margin: 7px 0; }
.content ul li::marker { color: var(--source-line); }
.content li > ul, .content li > ol { margin: 6px 0; }
.content strong { font-weight: 600; color: var(--ink); }
.content a { text-decoration: underline; text-decoration-color: #b7c2d6; text-underline-offset: 3px; }
.content a:hover { text-decoration-color: var(--accent); }
.anchor { opacity: 0; margin-left: 8px; color: var(--muted); font-weight: 400; text-decoration: none; }
h2:hover .anchor, h3:hover .anchor, h4:hover .anchor { opacity: 0.5; }
code { background: #efece4; color: #4a423a; border-radius: 3px; padding: 1px 5px; font-family: var(--mono); font-size: 0.85em; }
blockquote.quote { margin: 24px 0; padding: 2px 0 2px 22px; border-left: 2px solid var(--source-line); color: var(--muted); font-style: italic; }
blockquote.source { font-style: normal; border-left-color: var(--accent); }
blockquote.source::before {
  content: "来源与时效"; display: block; font-family: var(--mono); font-weight: 500;
  font-size: 11px; letter-spacing: 0.14em; color: var(--accent); margin-bottom: 4px;
}
blockquote p { margin: 4px 0; font-size: 15.5px; }
.figure { margin: 30px 0; }
.figure img { max-width: 100%; height: auto; border: 1px solid var(--line); }
.figure figcaption { margin-top: 9px; font-family: var(--mono); font-size: 12px; color: var(--muted); }
.content p img { max-width: 100%; height: auto; }
.table-wrap { overflow-x: auto; margin: 26px 0; }
table { border-collapse: collapse; width: 100%; font-size: 15px; min-width: 420px; }
th, td { border-bottom: 1px solid var(--line); padding: 9px 16px 9px 0; text-align: left; vertical-align: top; }
thead th {
  font-family: var(--mono); font-size: 11px; font-weight: 500; letter-spacing: 0.12em;
  text-transform: uppercase; color: var(--muted); border-bottom: 1px solid var(--ink);
}
tbody tr:last-child td { border-bottom: none; }
hr { border: none; border-top: 1px solid var(--line); margin: 40px 0; }
pre.code { background: #241f1c; color: #ede5d7; border-radius: 4px; padding: 16px 18px; overflow-x: auto; font-family: var(--mono); font-size: 13px; line-height: 1.6; }
pre.code code { background: none; padding: 0; color: inherit; font-size: inherit; }
/* A diagram sits on its own sheet of paper. The selector is deliberately more
   specific than the reset /vendor/mermaid-init.js appends to <head> at runtime
   (that one strips the <pre> framing, which suits an uploaded page but not the
   archive's paper look), so the card survives here. */
.content pre.mermaid { background: var(--sheet); border: 1px solid var(--line); border-radius: 4px; padding: 18px; text-align: center; overflow-x: auto; white-space: normal; }
.backtop {
  position: fixed; right: 24px; bottom: 24px; width: 40px; height: 40px;
  border-radius: 50%; background: var(--sheet); color: var(--ink);
  border: 1px solid var(--line); font-size: 16px; cursor: pointer;
  box-shadow: var(--shadow);
  opacity: 0; pointer-events: none; transition: opacity 0.2s;
}
.backtop.show { opacity: 1; pointer-events: auto; }
.backtop:hover { color: var(--accent); border-color: var(--accent); }
.mobile-toc-toggle { display: none; }
/* Only wide layouts have a margin column to reclaim; narrow ones already stack
   (and their own rule below would lose to this more specific selector). */
@media (min-width: 901px) {
  .layout.toc-collapsed { grid-template-columns: max-content minmax(0, 1fr); gap: 26px; }
  /* The prose is capped at 70ch, so it cannot spend the reclaimed column — left
     alone it just slides left and leaves the whole gain as an empty right-hand
     gutter, and the shift got worse once the margin shrank to a caret (#457).
     Auto margins re-centre it; on a page whose prose really does fill the track
     they resolve to zero. Same fix the /p/ fold applies in toc-fold.js. */
  .layout.toc-collapsed .content { margin-inline: auto; }
}
@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; gap: 12px; padding: 22px 20px 70px; }
  .toc { position: static; max-height: none; }
  .mobile-toc-toggle {
    display: block; width: 100%; background: none; border: none;
    border-bottom: 1px solid var(--line); padding: 10px 0; cursor: pointer;
    font-family: var(--mono); font-size: 12px; letter-spacing: 0.12em;
    text-transform: uppercase; color: var(--muted); text-align: left;
  }
  .toc.collapsed { display: none; }
  /* The ☰ bar above is the fold control here; a second one inside the panel
     would be two buttons for one state. */
  .toc-title { display: none; }
  .content { max-width: none; }
}
`

const FONTS = `<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,600;9..144,700&family=IBM+Plex+Mono:wght@400;500&family=Newsreader:opsz,wght@6..72,400;6..72,600&family=Noto+Serif+SC:wght@400;600;700&display=swap" rel="stylesheet" />`

// --- Indexing metadata (Search Console) ---------------------------------
// The public origin these pages are served from. Every canonical URL must be
// absolute, and it must name ONE host: www and the apex both resolve here, so
// without this Google sees two competing copies of every document.
const SITE_ORIGIN = process.env.SITE_ORIGIN || 'https://youraikanban.com'

// esc() is a TEXT escaper (& < > only). Interpolating its output into a quoted
// attribute leaves any ASCII double quote in the source intact, which ends the
// attribute early and corrupts the rest of the tag — two of these documents
// really do quote a phrase that way. Attributes need the quotes escaped too.
function escAttr(s) {
  return esc(String(s ?? '')).replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

// These pages are mostly Chinese but the template hardcoded lang="en", which
// tells Google to consider them for English queries and mis-selects the fonts
// and hyphenation a browser applies. Decide per document from the text itself
// rather than assuming, since a few (public-api, staff) really are English.
function detectLang(text) {
  const stripped = String(text || '').replace(/<[^>]*>/g, '')
  const cjk = (stripped.match(/[\u4e00-\u9fff]/g) || []).length
  const letters = (stripped.match(/[A-Za-z]/g) || []).length
  // CJK is information-dense: a page reads as Chinese well before it has as
  // many han characters as it has latin letters.
  return cjk > 0 && cjk * 4 > letters ? 'zh-CN' : 'en'
}

// A search result shows ~155 chars of description. Without one Google invents a
// snippet from whatever text it finds first, which on these pages is the table
// of contents. Take the first real paragraph instead.
function metaDescription(bodyHtml) {
  const firstPara = String(bodyHtml || '').match(/<p[^>]*>([\s\S]*?)<\/p>/i)
  const text = (firstPara ? firstPara[1] : String(bodyHtml || ''))
    .replace(/<[^>]*>/g, ' ')
    .replace(/&[a-z]+;|&#\d+;/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  if (!text) return ''
  return text.length > 155 ? `${text.slice(0, 154).trimEnd()}…` : text
}

function renderDocPage({ id, title, body, toc, hasMermaid }) {
  const sidebar = toc.length
    ? `<nav class="toc" aria-label="目录">
<button class="toc-title" type="button" aria-expanded="true" aria-controls="toc-list" aria-label="目录"><span class="toc-caret" aria-hidden="true">▾</span><span class="toc-label">目录</span></button>
<ul id="toc-list">
${toc.map((h) => `<li class="lv${h.level}"><a href="#${h.id}">${esc(h.text)}</a></li>`).join('\n')}
</ul>
</nav>`
    : '<div></div>'
  const desc = metaDescription(body)
  return `<!doctype html>
<html lang="${detectLang(`${title} ${body}`)}">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${esc(title)} · 文章存档</title>${
    desc ? `\n<meta name="description" content="${escAttr(desc)}" />` : ''
  }${id ? `\n<link rel="canonical" href="${SITE_ORIGIN}/docs/${escAttr(id)}.html" />` : ''}
${FONTS}
<style>${THEME}${CONTENT_CSS}</style>
</head>
<body>
<header class="hero">
<p class="eyebrow">文章</p>
<h1>${esc(title)}</h1>
<a class="back" href="/docs.html">← 回到文章列表</a>${id ? '\n<span class="views" id="view-count" hidden></span>' : ''}
</header>
<div class="layout">
${toc.length ? `<button class="mobile-toc-toggle" type="button" aria-expanded="true" aria-controls="toc-list">☰ 目录</button>` : ''}
${sidebar}
<main class="content">
${body}
</main>
</div>
<button class="backtop" aria-label="Back to top" onclick="window.scrollTo({top:0,behavior:'smooth'})">↑</button>
<script>
  var bt = document.querySelector('.backtop');
  window.addEventListener('scroll', function () {
    if (window.scrollY > 400) bt.classList.add('show'); else bt.classList.remove('show');
  });
  // Folding the contents list (task #224). Both controls — the heading in the
  // margin and the ☰ bar on a narrow screen — drive one state, and the choice
  // is remembered so it doesn't have to be made again on the next document.
  // Narrow screens still open folded regardless: there the list would push the
  // article off the first screen.
  var toc = document.querySelector('.toc');
  if (toc) {
    var layout = document.querySelector('.layout');
    var tocTitle = document.querySelector('.toc-title');
    var tocBar = document.querySelector('.mobile-toc-toggle');
    var TOC_KEY = 'docs:toc-collapsed';
    var isNarrow = function () { return window.matchMedia('(max-width: 900px)').matches; };
    var setToc = function (collapsed, remember) {
      toc.classList.toggle('collapsed', collapsed);
      if (layout) layout.classList.toggle('toc-collapsed', collapsed);
      if (tocTitle) tocTitle.setAttribute('aria-expanded', String(!collapsed));
      if (tocBar) tocBar.setAttribute('aria-expanded', String(!collapsed));
      if (remember) { try { localStorage.setItem(TOC_KEY, collapsed ? '1' : '0'); } catch (e) {} }
    };
    var stored = null;
    try { stored = localStorage.getItem(TOC_KEY); } catch (e) {}
    setToc(isNarrow() || stored === '1', false);
    var onToggle = function () { setToc(!toc.classList.contains('collapsed'), !isNarrow()); };
    if (tocTitle) tocTitle.addEventListener('click', onToggle);
    if (tocBar) tocBar.addEventListener('click', onToggle);
  }
</script>
${hasMermaid ? `<!-- A doc that draws diagrams loads the same vendored renderer as an
     uploaded /p/ page (task #82): same-origin static files, so the diagram
     still shows up when jsdelivr is slow, blocked or the box is offline.
     'neutral' pins the theme — the archive is a fixed light paper design, so
     mermaid-init's follow-the-OS dark default would clash. -->
<script src="/vendor/mermaid.min.js" defer></script>
<script src="/vendor/mermaid-init.js" data-theme="neutral" defer></script>` : ''}${id ? `
<script>
  // Opening this document marks it "read" (task #357): the index's red "NEW" dot
  // and the board's Docs badge mean "recently updated AND not yet opened by you",
  // so once you've actually opened a doc it stops being flagged as new. Fire-and-
  // forget against the board's /api proxy; failures are harmless (badge just stays).
  try {
    fetch('/api/docs/reads', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ids: [${JSON.stringify(id)}] }),
      keepalive: true,
    }).catch(function () {});
  } catch (e) {}

  // Show this article's visit tally (task #523). The visit itself was already
  // counted server-side by the gate when it served this page, so there is
  // nothing to POST here — we only read the number back.
  fetch('/api/docs/views')
    .then(function (r) { return r.ok ? r.json() : {}; })
    .then(function (counts) {
      var n = counts && counts[${JSON.stringify(id)}];
      var el = document.getElementById('view-count');
      if (!el || !n) return;
      el.textContent = n + ' 次阅读';
      el.hidden = false;
    })
    .catch(function () {});
</script>` : ''}
</body>
</html>
`
}

const INDEX_CSS = `
.wrap { max-width: var(--maxw); margin: 0 auto; padding: 6px 24px 96px; }

/* ---------- the two tabs (task #377) ----------------------------------------
   文章收藏 and 典籍 are different kinds of thing and are looked for differently,
   so they get a switch rather than one interleaved list. The switch is drawn as
   a row of section headings sitting ON the masthead's hairline — the active one
   inks it — so the page gains a control without gaining a widget. */
.tabs {
  max-width: var(--maxw); margin: -1px auto 0; padding: 0 24px;
  display: flex; gap: 26px;
  border-bottom: 1px solid var(--line);
}
body.has-tabs .hero { border-bottom: none; }
.tab {
  appearance: none; -webkit-appearance: none; background: none; border: 0;
  padding: 13px 0 11px; margin-bottom: -1px; cursor: pointer;
  font-family: var(--display); font-size: 17px; font-weight: 600;
  color: var(--muted); border-bottom: 2px solid transparent;
  transition: color 0.14s ease, border-color 0.14s ease;
}
.tab:hover { color: var(--ink); }
.tab[aria-selected="true"] { color: var(--ink); border-bottom-color: var(--accent); }
/* The count rides with the label in the same mono grey as the dates. */
.tab .n {
  margin-left: 7px; vertical-align: 2px;
  font-family: var(--mono); font-size: 11px; font-weight: 500;
  letter-spacing: 0.1em; color: var(--muted);
}

/* ---------- 典籍: a library grid, several books to a row --------------------
   Kindle-shaped, but the objects are stitched Chinese books, so they are drawn
   as those: a cover standing on a board, the block of pages showing at the
   fore-edge. The board colours are local to this zone — they are the only
   non-paper surface on the page. */
.classics {
  --board-hi: #dcc9a3; --board-mid: #b99f74; --board-lo: #8e7549;
  --leaf-hi: #f2ead6; --leaf-lo: #ddd0ae;
  /* No zone note above the shelf any more (task #380); since task #393 the
     language switch sits in this gap, in the corner, so the margin opens the
     zone rather than padding an empty top. */
  margin-top: 22px;
}
.shelf {
  display: grid;
  /* auto-fit with a FIXED track, not auto-fill with 1fr: a row takes as many
     volumes as the width allows, but the tracks nobody stands in collapse. With
     two books on a wide screen that keeps the board under the books instead of
     running it out into empty space, and the shelf still fills left-to-right as
     volumes are added. */
  grid-template-columns: repeat(auto-fit, 178px);
  justify-content: center;
  column-gap: 40px; row-gap: 56px;
  margin: 0; padding: 0; list-style: none;
}
.bay { min-width: 0; }
/* Every cover's bottom edge meets the board's top, so volumes of different
   proportions still share one shelf line. Each bay draws its own board and
   overhangs the 40px gutter by 20px a side, so neighbouring boards meet in the
   middle and read as one shelf — for whatever number of columns the grid fits. */
.stand {
  --bh: 228px;
  position: relative; display: flex; align-items: flex-end; justify-content: center;
  height: calc(var(--bh) + 26px); padding-bottom: 11px;
}
.stand::after {
  content: ""; position: absolute; left: -20px; right: -20px; bottom: 0; height: 11px;
  background: linear-gradient(180deg, var(--board-hi) 0 2px, var(--board-mid) 2px 62%, var(--board-lo));
  box-shadow: 0 4px 9px rgba(60,45,20,0.32), inset 0 1px 0 rgba(255,255,255,0.22);
  border-radius: 1px;
}
.vol { display: block; text-decoration: none; color: inherit; }
.book {
  position: relative;
  height: var(--bh); width: calc(var(--bh) * var(--ar));
  transition: transform 0.28s cubic-bezier(0.2,0.7,0.3,1);
}
/* A Chinese stitched book is sewn on the RIGHT (both covers carry the thread),
   so the loose leaf edges show on the LEFT. That's the block of pages. */
.book::before {
  content: ""; position: absolute; left: -7px; top: 4px; bottom: 2px; width: 8px;
  border-radius: 3px 0 0 3px;
  background: repeating-linear-gradient(90deg, var(--leaf-hi) 0 1.5px, var(--leaf-lo) 1.5px 3px);
  box-shadow: inset -3px 0 4px rgba(60,45,20,0.22), 0 12px 16px -12px rgba(60,45,20,0.7);
}
.cover {
  position: absolute; inset: 0; border-radius: 2px; overflow: hidden;
  box-shadow: 0 1px 1px rgba(60,45,20,0.3), 0 22px 24px -16px rgba(60,45,20,0.65);
}
/* The scan is a real <img>, so image search can find it and the page doesn't
   carry the bytes inline. Each book's crop rides on the element's own style —
   see coverImgStyle() for how the background-* measurement carries over. */
.cover img {
  position: absolute; display: block; max-width: none;
  width: 100%; height: 100%; left: 0; top: 0;
}
/* light down the fore-edge, shadow into the sewn edge */
.cover::after {
  content: ""; position: absolute; inset: 0; border-radius: 2px; pointer-events: none;
  background: linear-gradient(90deg, rgba(255,255,255,0.18) 0 2%, rgba(255,255,255,0) 12%,
    rgba(0,0,0,0) 91%, rgba(0,0,0,0.15) 100%);
}
/* No scan on disk yet — set the volume's name on plain boards rather than
   leaving a hole where the book should be. */
.cover.blank {
  background: linear-gradient(160deg, #efe6d2, #ddd0b4);
  display: flex; align-items: center; justify-content: center;
  font-family: var(--display); font-size: 19px; font-weight: 600;
  writing-mode: vertical-rl; letter-spacing: 0.2em; color: #6b5a3d;
  border: 1px solid #c9b894;
}
.vol:hover .book, .vol:focus-visible .book { transform: translateY(-11px); }
.vol:focus-visible { outline: none; }
.vol:focus-visible .cover { outline: 2px solid var(--accent); outline-offset: 3px; }
/* ---------- the label under each book ---------- */
.plate { padding: 18px 2px 0; text-align: center; }
.plate h3 {
  margin: 0; font-family: var(--display); font-size: 19px; font-weight: 600;
  line-height: 1.45; letter-spacing: 0.08em; text-indent: 0.08em;
}
.plate h3 a { color: var(--ink); text-decoration: none; }
.plate h3 a:hover { color: var(--accent); }
.plate .imprint {
  margin: 7px 0 0; font-size: 12.5px; line-height: 1.7;
  letter-spacing: 0.02em; color: var(--muted);
}
/* The plate carries the reading it names (task #397), so the type can follow it.
   The wide tracking above is set for 简体/繁體 titles, where it opens the
   characters up; English words only come apart under it — "Liaofan's" reads as
   two words. Trim it back for the English reading only. */
.plate h3:lang(en) { letter-spacing: 0.01em; text-indent: 0; }
.plate .imprint:lang(en) { letter-spacing: 0; }
/* The four readings, spelled out under each plate (task #419). Deliberately
   quiet — one hairline of small type below the imprint — because the corner
   switch is still the main way to choose a language. This row exists for the
   reader who wants ONE book in a particular reading, and for the crawler, which
   reads hrefs and not the switch's script. */
.plate .editions {
  margin: 9px 0 0; font-size: 11.5px; line-height: 1.6;
  letter-spacing: 0.02em; color: var(--line);
}
.plate .editions a {
  color: var(--muted); text-decoration: none;
  border-bottom: 1px solid transparent; padding-bottom: 1px;
  /* A narrow plate has to wrap this row, and CJK breaks between any two
     characters — 日本語 came apart as 日本 / 語. Break between the readings,
     never inside one. */
  white-space: nowrap;
}
.plate .editions a:hover { color: var(--accent); border-bottom-color: var(--accent); }
.plate .editions .sep { padding: 0 0.35em; opacity: 0.8; }
/* ---------- one switch for the whole shelf (task #393) ----------------------
   The three readings used to hang under every book, which asked the same
   question once per volume. They are now a single control in the top-right
   corner of the zone, level with the first shelf, and it re-points every cover
   at once. Right-aligned rather than centred so it reads as the page's corner
   control and not as a caption belonging to the books. */
.langbar {
  display: flex; justify-content: flex-end; gap: 4px;
  /* The shelf row reserves ~15px of air above the covers (the books stand on
     the board at the row's foot), so the switch only needs a few px of its own
     to clear the first cover — measured at 19px in a browser. */
  margin: 0 0 4px; padding: 0 2px;
}
.lang {
  appearance: none; -webkit-appearance: none; background: none; cursor: pointer;
  font-family: var(--mono); font-size: 11px; letter-spacing: 0.02em;
  color: var(--muted);
  border: 1px solid var(--line); border-radius: 999px; padding: 3px 9px;
  transition: color 0.14s ease, border-color 0.14s ease;
}
.lang:hover { color: var(--accent); border-color: var(--accent); }
.lang[aria-pressed="true"] { color: var(--accent); border-color: #c2cdde; }
.shelf-more {
  margin: 44px 0 0; text-align: center;
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.08em;
}
@media (max-width: 700px) {
  .tabs { padding: 0 20px; gap: 20px; }
  .tab { font-size: 15.5px; }
  .shelf { grid-template-columns: repeat(auto-fit, 156px); column-gap: 18px; row-gap: 44px; }
  .stand { --bh: 186px; }
  .stand::after { left: -11px; right: -11px; }
  .plate h3 { font-size: 17px; }
}
@media (prefers-reduced-motion: reduce) {
  .book { transition: none; }
}
/* The archive: one column of dated entries, not a wall of cards. Each entry is
   a date, a title, a paragraph and a link — the shape of a blog index. */
.grid { display: block; }
.card-wrap { position: relative; display: block; padding: 30px 0 28px; border-bottom: 1px solid var(--line); }
/* Hovering an entry inks a rule in the left margin instead of lifting a card. */
.card-wrap::before {
  content: ""; position: absolute; left: -20px; top: 30px; bottom: 28px; width: 2px;
  background: var(--accent); transform: scaleY(0); transform-origin: top;
  transition: transform 0.22s ease;
}
.card-wrap:hover::before { transform: scaleY(1); }
.doc-card { display: block; color: var(--ink); }
.doc-card:hover { text-decoration: none; }
.entry-date {
  font-family: var(--mono); font-size: 11.5px; letter-spacing: 0.14em;
  color: var(--muted); margin-bottom: 9px;
}
/* The visit tally sits with the date. The two are the same mono grey, so
   without a mark between them "2026.08.29" and "12 次阅读" read as one run of
   figures (task #83) — hence an explicit middot, drawn only when a date really
   precedes the tally. The date needs its own element for that: a :first-child
   rule ignores text nodes, so a bare date never suppressed the mark itself. */
.entry-date .views { font-size: 11.5px; letter-spacing: 0.14em; }
.entry-date .date + .views::before { content: "·"; margin: 0 8px; }
.doc-card h2 {
  font-family: var(--display); font-weight: 600; font-size: 25px; line-height: 1.32;
  letter-spacing: -0.005em; margin: 0 0 9px;
}
.doc-card:hover h2 { color: var(--accent); }
.doc-card p { margin: 0; max-width: 68ch; color: var(--muted); font-size: 16px; line-height: 1.72; text-wrap: pretty; }
.doc-card .meta {
  display: inline-block; margin-top: 13px;
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.08em; color: var(--accent);
}
.doc-card:hover .meta { text-decoration: underline; text-underline-offset: 3px; }
.doc-card .badge {
  display: inline-block; margin-left: 10px; padding: 2px 8px; vertical-align: 4px;
  font-family: var(--mono); font-size: 10px; font-weight: 500; letter-spacing: 0.12em;
  text-transform: uppercase; color: var(--muted);
  border: 1px solid var(--line); border-radius: 2px;
}
.doc-card.featured .badge { color: var(--accent); border-color: #c2cdde; }
.empty { color: var(--muted); text-align: center; padding: 70px 0; font-style: italic; }
/* "Recently created/updated" marker (task #319): a red rubber stamp in the
   entry's right margin. Fresh docs already sort to the front, so the stamp is
   the only thing that has to shout — everything else on the page stays quiet. */
.new-dot {
  position: absolute; top: 22px; right: 0; z-index: 3;
  font-family: var(--mono); font-size: 10px; font-weight: 500; letter-spacing: 0.2em;
  line-height: 1; padding: 5px 7px 5px 9px;
  color: var(--stamp); background: transparent;
  border: 1.5px solid var(--stamp); border-radius: 2px;
  transform: rotate(-6deg); opacity: 0.9;
  pointer-events: none;
}
/* Owner-only controls sit in the entry's bottom-right corner, out of the way of
   the stamp, and only surface on hover/focus. */
.doc-del, .doc-priv {
  position: absolute; bottom: 24px; width: 28px; height: 28px;
  border-radius: 3px; border: 1px solid var(--line); background: var(--sheet);
  color: var(--muted); font-size: 13px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease, border-color 0.12s ease;
}
.doc-del { right: 0; }
.doc-priv { right: 34px; }
.card-wrap:hover .doc-del, .card-wrap:hover .doc-priv { opacity: 1; }
.card-wrap:focus-within .doc-del, .card-wrap:focus-within .doc-priv { opacity: 1; }
.doc-del:hover { background: #f6e9e6; color: var(--stamp); border-color: #ddbdb6; }
.doc-priv:hover { background: var(--accent-soft); color: var(--accent); border-color: #c2cdde; }
.doc-del:focus-visible, .doc-priv:focus-visible { opacity: 1; }
.doc-del:disabled, .doc-priv:disabled { cursor: default; opacity: 0.5; }
@media (hover: none) { .doc-del, .doc-priv { opacity: 1; } }
/* Privacy state (task #268): a lock tag on the title, in stamp red. */
.doc-card .badge.lock { color: var(--stamp); border-color: #ddbdb6; }
/* Owner-only controls stay hidden until the /__auth/whoami probe confirms the
   viewer is the authenticated owner. Anonymous public visitors never see the
   delete or privacy switch — and their private cards were stripped by the gate. */
body:not(.is-owner) .doc-del,
body:not(.is-owner) .doc-priv { display: none !important; }
/* Entry removal animation: collapse smoothly instead of vanishing abruptly. */
.card-wrap { transition: opacity 0.25s ease, transform 0.25s ease; }
.card-wrap.removing { opacity: 0; transform: scale(0.98); pointer-events: none; }
@media (max-width: 700px) {
  .wrap { padding: 6px 20px 70px; }
  .card-wrap::before { display: none; }
  .doc-card h2 { font-size: 22px; }
}

/* Confirmation modal — replaces the native window.confirm() with a styled,
   accessible dialog (backdrop, focus trap, Esc to cancel). */
.modal-backdrop {
  position: fixed; inset: 0; background: rgba(33,29,26,0.42);
  display: flex; align-items: center; justify-content: center; padding: 20px;
  opacity: 0; pointer-events: none; transition: opacity 0.18s ease; z-index: 50;
}
.modal-backdrop.open { opacity: 1; pointer-events: auto; }
.modal {
  background: var(--sheet); border: 1px solid var(--line); border-radius: 6px;
  max-width: 420px; width: 100%; padding: 26px 26px 22px;
  box-shadow: 0 26px 60px rgba(33,29,26,0.24);
  transform: translateY(12px) scale(0.98); transition: transform 0.18s ease;
}
.modal-backdrop.open .modal { transform: translateY(0) scale(1); }
.modal .modal-icon {
  width: 40px; height: 40px; border-radius: 50%; background: #f6e9e6; color: var(--stamp);
  display: flex; align-items: center; justify-content: center; font-size: 20px; margin-bottom: 14px;
}
.modal h3 { font-family: var(--display); font-weight: 600; font-size: 21px; margin: 0 0 8px; }
.modal p { margin: 0 0 6px; color: var(--muted); font-size: 15px; }
.modal .doc-name { color: var(--ink); font-weight: 600; word-break: break-word; }
.modal .note { font-size: 13.5px; color: var(--muted); margin-top: 10px; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 22px; }
.btn {
  font-family: var(--mono); font-size: 12.5px; letter-spacing: 0.06em;
  padding: 9px 16px; border-radius: 3px; cursor: pointer;
  border: 1px solid var(--line); background: var(--sheet); color: var(--ink);
  transition: background 0.12s ease, border-color 0.12s ease, opacity 0.12s ease;
}
.btn:hover { background: #f0ece3; }
.btn-danger { background: var(--stamp); border-color: var(--stamp); color: #fffdf9; }
.btn-danger:hover { background: #9a3527; border-color: #9a3527; }
.btn-danger:disabled { opacity: 0.6; cursor: default; }
.btn .spinner {
  display: inline-block; width: 12px; height: 12px; margin-right: 7px; vertical-align: -1px;
  border: 2px solid rgba(255,255,255,0.5); border-top-color: #fff; border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* "Write an article" (task #223) — the archive's one authoring affordance. It
   is owner-only like the delete/privacy controls, so it stays hidden until the
   /__auth/whoami probe confirms the viewer; a public reader sees a masthead
   with nothing to press. Quiet mono, the same register as the dates. */
.hero .write {
  display: none; margin-top: 20px;
  font-family: var(--mono); font-size: 12px; letter-spacing: 0.06em;
  padding: 8px 14px; border-radius: 3px; cursor: pointer;
  border: 1px solid var(--line); background: var(--sheet); color: var(--ink);
  transition: color 0.12s ease, border-color 0.12s ease;
}
body.is-owner .hero .write { display: inline-block; }
.hero .write:hover { color: var(--accent); border-color: var(--accent); }
/* "Mark everything read" (task #412) — the companion to the red NEW stamps.
   It sits next to 写文章 and stays out of sight until the read-state probe finds
   something actually unread, so the masthead only grows a second button when
   there is something for it to do. The [hidden] rule has to be spelled out:
   the owner rule above sets an explicit display, which would otherwise beat the
   browser's own [hidden] { display: none }. */
.hero .write.mark-read { margin-left: 10px; }
.hero .write[hidden] { display: none !important; }

/* The editor pop-up (task #223): a wider sheet than the delete confirmation,
   holding a title line and the Markdown itself. The paper/ink palette carries
   over, and the textarea is set in mono because what goes in it is source. */
.modal.editor { max-width: 720px; }
.modal.editor .field { margin-top: 16px; }
.modal.editor label {
  display: block; margin-bottom: 6px;
  font-family: var(--mono); font-size: 11px; letter-spacing: 0.14em;
  text-transform: uppercase; color: var(--muted);
}
.modal.editor input[type="text"], .modal.editor textarea {
  display: block; width: 100%; padding: 10px 12px;
  background: var(--bg); color: var(--ink);
  border: 1px solid var(--line); border-radius: 3px;
  font-family: var(--body); font-size: 15px; line-height: 1.7;
}
.modal.editor textarea {
  font-family: var(--mono); font-size: 13px; line-height: 1.65;
  min-height: 320px; resize: vertical;
}
.modal.editor input[type="text"]:focus, .modal.editor textarea:focus {
  outline: none; border-color: var(--accent);
}
.modal.editor .hint { font-family: var(--mono); font-size: 11.5px; margin: 10px 0 0; }
.btn-primary { background: var(--accent); border-color: var(--accent); color: #fffdf9; }
.btn-primary:hover { background: #233c66; border-color: #233c66; }
.btn-primary:disabled { opacity: 0.6; cursor: default; }
@media (max-width: 700px) {
  .modal.editor textarea { min-height: 220px; }
}

/* Toast notifications — replace native alert() for success/error feedback. */
.toast-host {
  position: fixed; left: 50%; bottom: 26px; transform: translateX(-50%);
  display: flex; flex-direction: column; gap: 10px; z-index: 60; width: max-content; max-width: 92vw;
}
.toast {
  display: flex; align-items: center; gap: 10px; padding: 11px 16px; border-radius: 4px;
  background: var(--ink); color: #f7f5f0; font-family: var(--mono); font-size: 12.5px;
  box-shadow: 0 12px 30px rgba(33,29,26,0.28);
  opacity: 0; transform: translateY(10px); transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast.show { opacity: 1; transform: translateY(0); }
.toast.error { background: var(--stamp); }
.toast .toast-icon { font-size: 15px; line-height: 1; }
@media (prefers-reduced-motion: reduce) {
  .card-wrap, .card-wrap::before, .modal-backdrop, .modal, .toast { transition: none; }
  .btn .spinner { animation: none; }
}
`

function renderCard(c) {
  const card = `<div class="card-wrap${c.isNew ? ' is-new' : ''}${c.private ? ' is-private' : ''}" data-id="${esc(c.id)}" data-private="${c.private ? 'true' : 'false'}">
  <a class="doc-card${c.featured ? ' featured' : ''}" href="${c.href}">
  <div class="entry-date">${c.updatedAt ? `<span class="date">${formatDate(c.updatedAt)}</span>` : ''}<span class="views" hidden></span></div>
  <h2>${esc(c.title)}${c.featured ? '<span class="badge">Featured</span>' : ''}${c.private ? '<span class="badge lock">🔒 Private</span>' : ''}</h2>
  <p>${esc(c.excerpt || '点开读全文。')}</p>
  <div class="meta">阅读全文 →</div>
</a>${
        c.isNew
          ? `\n  <span class="new-dot"${c.updatedAt ? ` title="Updated ${esc(c.updatedAt.slice(0, 10))}"` : ''} aria-label="Recently updated">NEW</span>`
          : ''
      }
  <button class="doc-priv" type="button" title="${c.private ? 'Make public' : 'Make private'}" aria-label="${c.private ? 'Make public' : 'Make private'}: ${esc(c.title)}" data-id="${esc(c.id)}" data-private="${c.private ? 'true' : 'false'}">${c.private ? '🔒' : '🌐'}</button>${
        c.deletable
          ? `\n  <button class="doc-del" type="button" title="Delete this document" aria-label="Delete ${esc(c.title)}" data-id="${esc(c.id)}" data-title="${esc(c.title)}">✕</button>`
          : ''
      }
</div>`
  // Wrap private cards in markers the auth gate strips for anonymous visitors
  // (task #268) so a private doc is never even listed to others.
  return c.private ? `<!--PRIVATE-CARD:${esc(c.id)}-->\n${card}\n<!--/PRIVATE-CARD-->` : card
}

// The 典籍 shelf: one <li> per volume, cover standing on the board, editions
// underneath. A volume whose cover scan isn't on disk gets its name set on plain
// boards instead of a hole where the book should be.
// The one language control for the whole shelf (task #393). It used to be three
// pills under every single book, which repeated the same choice once per volume
// and grew with the shelf; it is now a single switch in the top-right corner,
// and pressing it re-points every cover at that reading. Buttons, not links: the
// switch doesn't navigate, it changes what the covers open.
function renderLangbar() {
  const btns = LANGS.map(
    (l, i) =>
      `  <button type="button" class="lang" data-lang="${l.key}" data-bcp47="${l.bcp47}" aria-pressed="${i === 0 ? 'true' : 'false'}">${esc(l.label)}</button>`
  ).join('\n')
  return `<div class="langbar" role="group" aria-label="读本语言" data-default="${LANGS[0].key}">
${btns}
</div>`
}

// Natural pixel size of a JPEG or PNG, read straight off the file header. The
// <img> carries it as width/height so a crawler knows the asset's proportions
// without fetching it; the stylesheet overrides both for layout.
function imageSize(file) {
  const b = fs.readFileSync(file)
  if (b[0] === 0x89 && b.toString('latin1', 1, 4) === 'PNG') {
    return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) }
  }
  if (b[0] === 0xff && b[1] === 0xd8) {
    let i = 2
    while (i + 9 < b.length) {
      if (b[i] !== 0xff) {
        i += 1
        continue
      }
      const mk = b[i + 1]
      if (mk >= 0xc0 && mk <= 0xcf && mk !== 0xc4 && mk !== 0xc8 && mk !== 0xcc) {
        return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) }
      }
      if (mk === 0xd8 || mk === 0xd9 || (mk >= 0xd0 && mk <= 0xd7)) {
        i += 2
        continue
      }
      i += 2 + b.readUInt16BE(i + 2)
    }
  }
  return null
}

// Each `crop` above is written in background-size/background-position terms,
// because that is how the scans were measured. The cover is an <img> now, so
// restate the same crop as image geometry: background-position P% places the
// image at (container - image) * P/100, which is a plain left/top offset.
function coverImgStyle(crop) {
  const size = /background-size:\s*([\d.]+)%\s+([\d.]+)%/.exec(crop || '')
  const pos = /background-position:\s*(-?[\d.]+)%\s+(-?[\d.]+)%/.exec(crop || '')
  const w = size ? Number(size[1]) : 100
  const h = size ? Number(size[2]) : 100
  const px = pos ? Number(pos[1]) : 0
  const py = pos ? Number(pos[2]) : 0
  const n = (v) => String(Number(v.toFixed(4)))
  return (
    `width:${n(w)}%;height:${n(h)}%;` +
    `left:${n(((100 - w) * px) / 100)}%;top:${n(((100 - h) * py) / 100)}%`
  )
}

function renderShelf(books) {
  const bays = books
    .map((b) => {
      const coverFile = path.join(ROOT, 'frontend', 'public', b.cover.replace(/^\//, ''))
      const hasCover = fs.existsSync(coverFile)
      const dim = hasCover ? imageSize(coverFile) : null
      // The shelf is never the first thing painted on this page (it lives in a
      // tab), so every cover can wait for the reader to reach it.
      const face = hasCover
        ? `<div class="cover"><img src="${esc(b.cover)}" alt="${esc(b.coverAlt)}"` +
          (dim ? ` width="${dim.w}" height="${dim.h}"` : '') +
          ` loading="lazy" decoding="async" style="${esc(coverImgStyle(b.crop))}"></div>`
        : `<div class="cover blank" role="img" aria-label="${esc(b.title)}">${esc(b.title)}</div>`
      // Every edition this volume actually has, hung on the bay for the corner
      // switch to read. No pills under the book any more (task #393) — the shelf
      // shows covers, and the one control that picks a reading sits in the
      // corner of the page. `data-href` is the volume's default (editions[0],
      // 简体), which is where a bay lands with JS off or when the chosen
      // reading doesn't exist for this book.
      //
      // A reading brings its own NAME with it (task #397): pressing 繁體 has to
      // turn 孙子兵法 into 孫子兵法 and 战国 · 三卷二十一篇 into 戰國 · 三卷二十一篇,
      // or the shelf is labelling every book in a script the reader just said
      // they don't read. Every word the switch can put on a bay is baked here,
      // finished — including the link's aria-label, which a script in the page
      // could only ever have assembled in Chinese. What the page's JS does is
      // copy strings across; it writes none of them.
      const eds = LANGS.map((l) => {
        const ed = b.editions.find((e) => LANG_KEY_BY_LABEL.get(e.label) === l.key)
        if (!ed) {
          // No such reading of this book: the bay stays on its default, and says
          // so on the link instead of handing over another language in silence.
          return (
            ` data-label-${l.key}="${attr(l.missing(b.title))}"` +
            ` data-note-${l.key}="${attr(l.note(b.title, DEFAULT_LANG.label))}"`
          )
        }
        return (
          ` data-href-${l.key}="${attr(ed.card.href)}"` +
          ` data-title-${l.key}="${attr(ed.title)}"` +
          ` data-imprint-${l.key}="${attr(ed.imprint.join('\n'))}"` +
          ` data-label-${l.key}="${attr(l.open(ed.title))}"`
        )
      }).join('')
      const defLang = LANG_BY_KEY.get(LANG_KEY_BY_LABEL.get(b.editions[0].label)) || DEFAULT_LANG
      // The readings, written out under the plate (task #419). The corner switch
      // is the quick way to read the whole shelf in one language; this is the
      // other direction — one book, straight to the reading you want. It is also
      // the only way the 繁體 / English / 日本語 pages are ever LINKED from here:
      // the switch moves an href with a script, and a search engine follows the
      // href that is written down. Without this, eleven of the shelf's
      // forty-four pages carried every internal link and the other thirty-three
      // carried none.
      const editionLinks = LANGS
        .map((l) => {
          const ed = b.editions.find((e) => LANG_KEY_BY_LABEL.get(e.label) === l.key)
          if (!ed) return ''
          return (
            `<a href="${attr(ed.card.href)}" hreflang="${l.bcp47}" lang="${l.bcp47}"` +
            ` aria-label="${attr(l.open(ed.title))}">${esc(l.label)}</a>`
          )
        })
        .filter(Boolean)
        .join('<span class="sep">·</span>')
      return `    <li class="bay" data-title="${attr(b.title)}" data-imprint="${attr(
        b.imprint.join('\n')
      )}" data-href="${attr(b.href)}"${eds}>
      <div class="stand">
        <a class="vol" href="${b.href}" aria-label="${attr(defLang.open(b.title))}">
          <div class="book" style="--ar:${b.ar}">
            ${face}
          </div>
        </a>
      </div>
      <div class="plate">
        <h3 lang="${defLang.bcp47}"><a href="${b.href}">${esc(b.title)}</a></h3>
        <p class="imprint" lang="${defLang.bcp47}">${b.imprint.map(esc).join('<br />')}</p>
        <p class="editions">${editionLinks}</p>
      </div>
    </li>`
    })
    .join('\n')
  return `<ul class="shelf">\n${bays}\n  </ul>`
}

function renderIndex(cards) {
  const shelf = resolveShelf(cards)
  const owned = classicsIds()
  const articles = cards.filter((c) => !owned.has(c.id))
  // Two tabs only once something is actually on the shelf. A repo with no
  // classics renders the archive exactly as it did before task #377 — one
  // column, no switch to press.
  const tabbed = shelf.length > 0
  const articleGrid = articles.length
    ? `<div class="grid">\n${articles.map(renderCard).join('\n')}\n</div>`
    : '<p class="empty">还没有文章。</p>'
  const hasShelfPage = cards.some((c) => c.id === CLASSICS_SHELF_ID)
  const body = tabbed
    ? `<nav class="tabs" role="tablist" aria-label="存档分区">
  <button type="button" class="tab" role="tab" id="tab-articles" aria-controls="panel-articles" aria-selected="true">文章收藏<span class="n" data-count-of="panel-articles">${articles.length}</span></button>
  <button type="button" class="tab" role="tab" id="tab-classics" aria-controls="panel-classics" aria-selected="false" tabindex="-1">典籍<span class="n">${shelf.length}</span></button>
</nav>
<div class="wrap">
<section id="panel-articles" role="tabpanel" aria-labelledby="tab-articles" tabindex="0">
${articleGrid}
</section>
<section id="panel-classics" role="tabpanel" aria-labelledby="tab-classics" tabindex="0" hidden>
<div class="classics">
${renderLangbar()}
${renderShelf(shelf)}${
        hasShelfPage
          ? `\n<p class="shelf-more"><a href="${CLASSICS_SHELF_HREF}">整页书架 →</a></p>`
          : ''
      }
</div>
</section>
</div>`
    : `<div class="wrap">\n${articleGrid}\n</div>`
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${esc(INDEX_TITLE)}</title>
<meta name="description" content="${escAttr(indexDescription())}" />
<link rel="canonical" href="${SITE_ORIGIN}/docs.html" />
${FONTS}
<style>${THEME}
${INDEX_CSS}</style>
</head>
<body${tabbed ? ' class="has-tabs"' : ''}>
<header class="hero">
<h1>文章存档</h1>
<button class="write" type="button" id="write-open">✎ 写文章</button>
<button class="write mark-read" type="button" id="mark-all-read" hidden>✓ 全部标为已读</button>
</header>
${body}

<div class="modal-backdrop" id="del-modal" role="dialog" aria-modal="true" aria-labelledby="del-modal-title" aria-describedby="del-modal-desc" hidden>
  <div class="modal">
    <div class="modal-icon" aria-hidden="true"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6"/><path d="M10 11v6M14 11v6"/></svg></div>
    <h3 id="del-modal-title">Delete this document?</h3>
    <p id="del-modal-desc">You're about to remove <span class="doc-name"></span>.</p>
    <p class="note">This deletes the document and its published page. It can't be undone from here.</p>
    <div class="modal-actions">
      <button type="button" class="btn" data-act="cancel">Cancel</button>
      <button type="button" class="btn btn-danger" data-act="confirm">Delete</button>
    </div>
  </div>
</div>
<div class="modal-backdrop" id="write-modal" role="dialog" aria-modal="true" aria-labelledby="write-modal-title" hidden>
  <div class="modal editor">
    <h3 id="write-modal-title">写一篇文章</h3>
    <p>把 Markdown 粘进来，发布后会生成一篇和这个存档同样排版的文章。</p>
    <div class="field">
      <label for="write-title">标题</label>
      <input type="text" id="write-title" placeholder="留空就取正文里第一个 # 标题" autocomplete="off" />
    </div>
    <div class="field">
      <label for="write-md">正文 · Markdown</label>
      <textarea id="write-md" placeholder="# 标题&#10;&#10;正文……" spellcheck="false"></textarea>
    </div>
    <p class="hint">支持标题、列表、表格、引用、代码块和 mermaid 图。⌘/Ctrl + Enter 发布。</p>
    <div class="modal-actions">
      <button type="button" class="btn" data-act="write-cancel">取消</button>
      <button type="button" class="btn btn-primary" data-act="write-submit">发布</button>
    </div>
  </div>
</div>
<div class="toast-host" id="toast-host" aria-live="polite" aria-atomic="true"></div>

<script>
  // The 文章收藏 / 典籍 switch (task #377). A real tablist: roving tabindex,
  // arrow/Home/End keys, aria-selected on the buttons and hidden on the panels.
  // The chosen tab is written to the URL fragment so a tab is linkable and
  // survives a reload — replaceState, not a push, so the back button still means
  // "the page before this one" rather than "the other tab".
  (function () {
    var tablist = document.querySelector('.tabs');
    if (!tablist) return; // no classics on this build: one zone, no switch
    var tabs = [].slice.call(tablist.querySelectorAll('[role="tab"]'));
    function show(tab, moveFocus) {
      tabs.forEach(function (t) {
        var on = t === tab;
        t.setAttribute('aria-selected', on ? 'true' : 'false');
        t.tabIndex = on ? 0 : -1;
        var panel = document.getElementById(t.getAttribute('aria-controls'));
        if (panel) panel.hidden = !on;
      });
      if (moveFocus) tab.focus();
      try { history.replaceState(null, '', '#' + tab.id.replace(/^tab-/, '')); } catch (e) {}
    }
    tabs.forEach(function (t) {
      t.addEventListener('click', function () { show(t, false); });
    });
    tablist.addEventListener('keydown', function (e) {
      var i = tabs.indexOf(document.activeElement);
      if (i < 0) return;
      var to = null;
      if (e.key === 'ArrowRight') to = (i + 1) % tabs.length;
      else if (e.key === 'ArrowLeft') to = (i - 1 + tabs.length) % tabs.length;
      else if (e.key === 'Home') to = 0;
      else if (e.key === 'End') to = tabs.length - 1;
      if (to === null) return;
      e.preventDefault();
      show(tabs[to], true);
    });
    // #classics / #articles opens straight onto that tab — on load, and again
    // whenever the fragment changes under us (a link to the other tab from a
    // page that is already open changes the hash without reloading, so without
    // this the URL would say one tab and the page would show the other).
    function fromHash() {
      var want = (location.hash || '').replace(/^#/, '');
      for (var i = 0; i < tabs.length; i++) {
        if (tabs[i].id === 'tab-' + want) { show(tabs[i], false); return; }
      }
    }
    window.addEventListener('hashchange', fromHash);
    fromHash();
    // The count on a tab is baked at build time, when every card still exists.
    // An anonymous visitor is served the page with the private cards stripped
    // out (docsGate), and the owner deletes cards without a rebuild — either way
    // the label would then promise entries that aren't in the panel, and the
    // overcount tells a stranger exactly how many private docs are being kept
    // from them. So count what the panel actually holds, right now.
    window.recountTabs = function () {
      [].forEach.call(document.querySelectorAll('.tab .n[data-count-of]'), function (n) {
        var panel = document.getElementById(n.getAttribute('data-count-of'));
        if (panel) n.textContent = panel.querySelectorAll('.card-wrap').length;
      });
    };
    window.recountTabs();
  })();

  // The 简体 / 繁體 / English switch (task #393). One control for the whole
  // shelf: it does not navigate, it re-points every cover and title at the
  // chosen reading. The choice is remembered, so a reader who reads in 繁體
  // does not have to say so again on the next visit or on the full-page shelf.
  //
  // Since task #397 it also RENAMES the books: the plate under each cover shows
  // the title and imprint of the reading being offered — 孫子兵法 in 繁體, The
  // Art of War in English — because a shelf that answers 繁體 by keeping every
  // name in 简体 has only half heard the question. Every string is baked into
  // the bay at build time, so this loop copies text, never composes it.
  //
  // A volume that has no page in the chosen reading keeps its default reading —
  // name and all, so the plate always describes what the link opens — and SAYS
  // SO on the link rather than handing over a different language without a word.
  (function () {
    var bar = document.querySelector('.langbar');
    if (!bar) return;
    var KEY = 'classics:lang';
    var btns = [].slice.call(bar.querySelectorAll('.lang'));
    var bays = [].slice.call(document.querySelectorAll('.bay'));
    var tags = {};
    btns.forEach(function (b) { tags[b.getAttribute('data-lang')] = b.getAttribute('data-bcp47'); });

    function apply(lang, remember) {
      btns.forEach(function (b) {
        b.setAttribute('aria-pressed', b.getAttribute('data-lang') === lang ? 'true' : 'false');
      });
      bays.forEach(function (bay) {
        // The reading this bay can actually give: the one asked for, or its own
        // default when it hasn't got that one.
        var use = bay.getAttribute('data-href-' + lang) ? lang : bar.getAttribute('data-default');
        var href = bay.getAttribute('data-href-' + use) || bay.getAttribute('data-href');
        if (!href) return;
        var note = bay.getAttribute('data-note-' + lang);
        var label = bay.getAttribute('data-label-' + lang);
        var vol = bay.querySelector('a.vol');
        var head = bay.querySelector('.plate h3 a');
        [vol, head].forEach(function (a) {
          if (!a) return;
          a.setAttribute('href', href);
          if (note) a.setAttribute('title', note);
          else a.removeAttribute('title');
        });
        if (vol && label) vol.setAttribute('aria-label', label);
        // The plate speaks the reading it opens: title, imprint and the lang tag
        // that tells the browser and a screen reader which of the three it is.
        var name = bay.getAttribute('data-title-' + use) || bay.getAttribute('data-title');
        var h3 = bay.querySelector('.plate h3');
        if (head && name) head.textContent = name;
        var imp = bay.querySelector('.plate .imprint');
        var lines = (bay.getAttribute('data-imprint-' + use) || bay.getAttribute('data-imprint') || '').split('\\n');
        if (imp) {
          imp.textContent = '';
          lines.forEach(function (line, i) {
            if (i) imp.appendChild(document.createElement('br'));
            imp.appendChild(document.createTextNode(line));
          });
        }
        if (tags[use]) {
          if (h3) h3.setAttribute('lang', tags[use]);
          if (imp) imp.setAttribute('lang', tags[use]);
        }
      });
      if (remember) { try { localStorage.setItem(KEY, lang); } catch (e) {} }
    }

    var stored = null;
    try { stored = localStorage.getItem(KEY); } catch (e) {}
    var known = btns.map(function (b) { return b.getAttribute('data-lang'); });
    apply(known.indexOf(stored) >= 0 ? stored : bar.getAttribute('data-default'), false);
    btns.forEach(function (b) {
      b.addEventListener('click', function () { apply(b.getAttribute('data-lang'), true); });
    });
  })();
</script>

<script>
  // Delete a document: ask the backend to remove its source Markdown + generated
  // page, then drop the card. The board proxies /api to the Spring backend, so a
  // same-origin fetch works whether we're on :80 (dev/tunnel) or a build preview.
  //
  // UX (task #278): the raw window.confirm()/alert() are replaced with an
  // accessible styled modal (focus trap, Esc to cancel, restores focus), a
  // smooth card-collapse animation, and toast feedback for success and errors.
  // Scoped to the articles panel (task #377): blanking .wrap on the last delete
  // would take the tab bar and the shelf with it. Since task #382 the classics
  // tab holds no cards at all, so there is nothing over there to delete either.
  var grid = document.querySelector('#panel-articles .grid') || document.querySelector('.grid');
  var wrapEl = document.getElementById('panel-articles') || document.querySelector('.wrap');
  var modal = document.getElementById('del-modal');
  var modalName = modal.querySelector('.doc-name');
  var confirmBtn = modal.querySelector('[data-act="confirm"]');
  var cancelBtn = modal.querySelector('[data-act="cancel"]');
  var toastHost = document.getElementById('toast-host');
  var prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var pending = null; // the .doc-del button whose card we're about to delete
  var lastFocus = null;

  function toast(message, isError) {
    var el = document.createElement('div');
    el.className = 'toast' + (isError ? ' error' : '');
    el.setAttribute('role', isError ? 'alert' : 'status');
    var icon = document.createElement('span');
    icon.className = 'toast-icon';
    icon.textContent = isError ? '!' : '✓';
    el.appendChild(icon);
    el.appendChild(document.createTextNode(message));
    toastHost.appendChild(el);
    requestAnimationFrame(function () { el.classList.add('show'); });
    setTimeout(function () {
      el.classList.remove('show');
      setTimeout(function () { el.remove(); }, 250);
    }, isError ? 5000 : 3200);
  }

  function openModal(btn) {
    pending = btn;
    lastFocus = btn;
    modalName.textContent = '“' + (btn.getAttribute('data-title') || 'this document') + '”';
    modal.hidden = false;
    requestAnimationFrame(function () { modal.classList.add('open'); });
    confirmBtn.disabled = false;
    confirmBtn.textContent = 'Delete';
    setTimeout(function () { cancelBtn.focus(); }, 0);
  }

  function closeModal() {
    modal.classList.remove('open');
    var t = prefersReduced ? 0 : 180;
    setTimeout(function () { modal.hidden = true; }, t);
    pending = null;
    if (lastFocus && document.body.contains(lastFocus)) lastFocus.focus();
  }

  function removeCard(wrap) {
    wrap.classList.add('removing');
    var finish = function () {
      wrap.remove();
      if (grid && !grid.querySelector('.card-wrap') && wrapEl) {
        wrapEl.innerHTML = '<p class="empty">还没有文章。</p>';
      }
      // The tab label counts the panel, so it has to be told the panel shrank.
      if (window.recountTabs) window.recountTabs();
    };
    if (prefersReduced) finish();
    else setTimeout(finish, 260);
  }

  function doDelete() {
    if (!pending) return;
    var btn = pending;
    var id = btn.getAttribute('data-id');
    var title = btn.getAttribute('data-title') || 'this document';
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<span class="spinner" aria-hidden="true"></span>Deleting…';
    fetch('/api/docs/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function (res) {
        if (!res.ok && res.status !== 404) throw new Error('HTTP ' + res.status);
        var wrap = btn.closest('.card-wrap');
        // Don't restore focus to a button we're about to remove.
        lastFocus = null;
        closeModal();
        if (wrap) removeCard(wrap);
        toast('Deleted “' + title + '”', false);
      })
      .catch(function (err) {
        confirmBtn.disabled = false;
        confirmBtn.textContent = 'Delete';
        closeModal();
        toast('Couldn’t delete “' + title + '”: ' + err.message, true);
      });
  }

  // Open the modal from any delete button (delegated so it works after re-render).
  document.addEventListener('click', function (e) {
    var btn = e.target.closest && e.target.closest('.doc-del');
    if (!btn) return;
    e.preventDefault();
    openModal(btn);
  });

  confirmBtn.addEventListener('click', doDelete);
  cancelBtn.addEventListener('click', closeModal);
  modal.addEventListener('click', function (e) {
    if (e.target === modal) closeModal(); // click on backdrop
  });
  document.addEventListener('keydown', function (e) {
    if (modal.hidden) return;
    if (e.key === 'Escape') { closeModal(); return; }
    if (e.key === 'Tab') {
      // Simple focus trap between the two modal buttons.
      var focusables = [cancelBtn, confirmBtn].filter(function (b) { return !b.disabled; });
      if (!focusables.length) return;
      var first = focusables[0], last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  });
</script>
<script>
  // Make the red "NEW" badge mean "recently updated AND not yet opened by you"
  // (task #357). Two halves: (1) on load, strip NEW from any card whose doc the
  // user has already opened — read state lives server-side at /api/docs/reads so
  // it's the same across devices; (2) when the user clicks into a doc, record it
  // as read (keepalive so the POST survives the navigation) and clear its badge
  // immediately. Together: opening a doc stops it being flagged new here and on
  // the board's Docs badge. Bespoke pages (e.g. /singapore.html) that lack their
  // own mark-on-open script are covered by the click handler below.
  (function () {
    function markRead(id) {
      if (!id) return;
      try {
        fetch('/api/docs/reads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ ids: [id] }),
          keepalive: true,
        }).catch(function () {});
      } catch (e) {}
    }
    function clearNew(wrap) {
      if (!wrap) return;
      wrap.classList.remove('is-new');
      var dot = wrap.querySelector('.new-dot');
      if (dot) dot.remove();
    }
    // "Mark everything read" (task #412). Both the NEW stamps here and the
    // board's Docs badge mean "in the manifest, not yet in /api/docs/reads", so
    // one button that POSTs every unread id clears both at once — including the
    // 典籍, which stand on the shelf rather than in a card and so can never be
    // clicked off one by one. The button is owner-only like the other masthead
    // controls (an anonymous POST is answered by the gate with a no-op) and
    // stays hidden while there is nothing unread.
    var allBtn = document.getElementById('mark-all-read');
    var seen = Object.create(null); // doc ids the user has already opened
    var unread = [];                // manifest ids still unread — what the button sends

    function refreshAllBtn() {
      if (!allBtn) return;
      allBtn.hidden = unread.length === 0;
      allBtn.title = unread.length
        ? '把 ' + unread.length + ' 篇未读（含书架上的典籍）标记为已读'
        : '';
    }
    function forget(id) { // this one just became read
      if (!id) return;
      seen[id] = true;
      var i = unread.indexOf(id);
      if (i >= 0) unread.splice(i, 1);
      refreshAllBtn();
    }
    fetch('/api/docs/reads')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (ids) {
        if (Array.isArray(ids)) ids.forEach(function (id) { seen[id] = true; });
        var wraps = document.querySelectorAll('.card-wrap[data-id]');
        for (var i = 0; i < wraps.length; i++) {
          if (seen[wraps[i].getAttribute('data-id')]) clearNew(wraps[i]);
        }
        // Count what the board counts: the whole manifest minus what's been
        // opened — not just the cards on this page.
        return fetch('/docs-manifest.json', { cache: 'no-store' })
          .then(function (r) { return r.ok ? r.json() : []; })
          .then(function (docs) {
            if (!Array.isArray(docs)) return;
            unread = docs
              .map(function (d) { return d && d.id; })
              .filter(function (id) { return id && !seen[id]; });
            refreshAllBtn();
          });
      })
      .catch(function () {});
    if (allBtn) {
      allBtn.addEventListener('click', function () {
        if (!unread.length) return;
        var ids = unread.slice();
        allBtn.disabled = true;
        fetch('/api/docs/reads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ ids: ids }),
        })
          .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            ids.forEach(function (id) { seen[id] = true; });
            unread = [];
            refreshAllBtn();
            var wraps = document.querySelectorAll('.card-wrap');
            for (var i = 0; i < wraps.length; i++) clearNew(wraps[i]);
            if (typeof toast === 'function') toast('已把 ' + ids.length + ' 篇标为已读', false);
          })
          .catch(function (err) {
            if (typeof toast === 'function') toast('标记已读失败：' + err.message, true);
          })
          .then(function () { allBtn.disabled = false; });
      });
    }
    // Per-article visit tallies (task #523). Counting happens server-side in the
    // gate as each article is served, so the index only reads the numbers back
    // and paints them next to the date; an article nobody has opened stays bare.
    fetch('/api/docs/views')
      .then(function (r) { return r.ok ? r.json() : {}; })
      .then(function (counts) {
        if (!counts) return;
        var wraps = document.querySelectorAll('.card-wrap[data-id]');
        for (var i = 0; i < wraps.length; i++) {
          var n = counts[wraps[i].getAttribute('data-id')];
          var el = wraps[i].querySelector('.views');
          if (!el || !n) continue;
          el.textContent = n + ' 次阅读';
          el.hidden = false;
        }
      })
      .catch(function () {});
    document.addEventListener('click', function (e) {
      var link = e.target.closest && e.target.closest('.doc-card');
      if (!link) return; // delete buttons and empty space are handled elsewhere
      var wrap = link.closest('.card-wrap');
      if (!wrap) return;
      markRead(wrap.getAttribute('data-id'));
      clearNew(wrap);
      forget(wrap.getAttribute('data-id'));
    });
  })();
</script>
<script>
  // Per-doc privacy (task #268). Documents are public by default; the owner can
  // flip one PRIVATE (only they can open it) or back to PUBLIC. The delete and
  // privacy controls only make sense for the authenticated owner, so we probe the
  // gate at /__auth/whoami and reveal them via body.is-owner — anonymous visitors
  // never see them, and their private cards were already stripped by the gate.
  (function () {
    fetch('/__auth/whoami', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : { authed: false }; })
      .then(function (s) { if (s && s.authed) document.body.classList.add('is-owner'); })
      .catch(function () {});

    document.addEventListener('click', function (e) {
      var btn = e.target.closest && e.target.closest('.doc-priv');
      if (!btn) return;
      e.preventDefault();
      var id = btn.getAttribute('data-id');
      var makePrivate = btn.getAttribute('data-private') !== 'true';
      btn.disabled = true;
      fetch('/api/docs/' + encodeURIComponent(id) + '/visibility', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ private: makePrivate }),
      })
        .then(function (res) {
          if (!res.ok) throw new Error('HTTP ' + res.status);
          // The backend regenerated the index; reload to reflect the new state.
          window.location.reload();
        })
        .catch(function (err) {
          btn.disabled = false;
          if (typeof toast === 'function') {
            toast('Couldn’t update privacy: ' + err.message, true);
          }
        });
    });
  })();
</script>
<script>
  // Write an article from the archive itself (task #223): the pop-up takes
  // Markdown, POST /api/docs stores it as docs/<slug>.md, and the backend
  // regenerates the site — so the new article is an ordinary document (same
  // page style, card, view counter, delete path), not a second kind of page.
  // The button is owner-only (body.is-owner, set by the whoami probe above) and
  // the endpoint sits behind the site password, so a public reader can't write.
  (function () {
    var openBtn = document.getElementById('write-open');
    var box = document.getElementById('write-modal');
    if (!openBtn || !box) return;
    var titleEl = document.getElementById('write-title');
    var mdEl = document.getElementById('write-md');
    var submitBtn = box.querySelector('[data-act="write-submit"]');
    var cancelBtn = box.querySelector('[data-act="write-cancel"]');
    var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var opener = null;

    function say(message, isError) {
      if (typeof toast === 'function') toast(message, isError);
    }

    function open() {
      opener = openBtn;
      box.hidden = false;
      requestAnimationFrame(function () { box.classList.add('open'); });
      reset();
      setTimeout(function () { titleEl.focus(); }, 0);
    }

    function close() {
      box.classList.remove('open');
      setTimeout(function () { box.hidden = true; }, reduced ? 0 : 180);
      // Keep what was typed: a mis-click on the backdrop must not destroy a draft.
      if (opener && document.body.contains(opener)) opener.focus();
    }

    function reset() {
      submitBtn.disabled = false;
      submitBtn.textContent = '发布';
    }

    function publish() {
      var markdown = mdEl.value;
      if (!markdown.trim()) {
        mdEl.focus();
        say('正文还是空的', true);
        return;
      }
      submitBtn.disabled = true;
      submitBtn.innerHTML = '<span class="spinner" aria-hidden="true"></span>发布中…';
      fetch('/api/docs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: titleEl.value, markdown: markdown }),
      })
        .then(function (res) {
          if (!res.ok) throw new Error('HTTP ' + res.status);
          return res.json();
        })
        .then(function (doc) {
          // Straight to the new article. The index was regenerated server-side,
          // so coming back shows its card without another round trip here.
          window.location.href = doc && doc.href ? doc.href : '/docs.html';
        })
        .catch(function (err) {
          reset();
          say('发布失败：' + err.message, true);
        });
    }

    openBtn.addEventListener('click', open);
    cancelBtn.addEventListener('click', close);
    submitBtn.addEventListener('click', publish);
    box.addEventListener('click', function (e) {
      if (e.target === box) close(); // click on backdrop
    });
    document.addEventListener('keydown', function (e) {
      if (box.hidden) return;
      if (e.key === 'Escape') { close(); return; }
      // ⌘/Ctrl + Enter publishes from inside the textarea, where a bare Enter is
      // a newline and can't submit.
      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && !submitBtn.disabled) {
        e.preventDefault();
        publish();
        return;
      }
      if (e.key === 'Tab') {
        // Focus trap across the pop-up's own fields and buttons.
        var stops = [titleEl, mdEl, cancelBtn, submitBtn].filter(function (el) { return !el.disabled; });
        if (!stops.length) return;
        var first = stops[0], last = stops[stops.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    });
  })();
</script>
</body>
</html>
`
}

// --- build -------------------------------------------------------------------
function build() {
  const files = fs
    .readdirSync(DOCS_DIR)
    .filter((f) => f.endsWith('.md'))
    .sort()

  fs.mkdirSync(OUT_DIR, { recursive: true })

  // Map every publishable doc to its served URL so cross-document links written
  // as sibling ".md" paths (e.g. [x](./architecture.md)) get repointed at the
  // real page (/docs/architecture.html) instead of dead-ending on markdown the
  // web user can't open — the exact failure this docs site exists to prevent.
  const docUrlByFile = {}
  for (const f of files) {
    docUrlByFile[f] = BESPOKE[f]?.href || `/docs/${slugify(f.replace(/\.md$/, ''))}.html`
  }
  const resolveLink = (url) => {
    const m = /^(?:\.\/)?([^/#?]+\.md)(#.*)?$/.exec(url)
    if (!m) return url
    const target = docUrlByFile[m[1]]
    return target ? target + (m[2] || '') : url
  }

  const cards = []
  let generated = 0

  for (const file of files) {
    const srcPath = path.join(DOCS_DIR, file)
    const md = fs.readFileSync(srcPath, 'utf8')
    const slug = slugify(file.replace(/\.md$/, ''))
    const { title: parsed, body, toc, hasMermaid } = renderMarkdown(md, { resolveLink })
    const title = parsed || humanize(file.replace(/\.md$/, ''))
    const ex = excerpt(md)
    const mtime = newestMtime([srcPath])

    if (BESPOKE[file]) {
      // Don't regenerate — link the index to the hand-tuned page. Deletable from
      // the web UI: `paths` lists the source Markdown plus every artifact the
      // bespoke page owns (its generated HTML at the web root and any asset dir),
      // so the backend can remove them all in one go.
      cards.push({
        id: slug, title, href: BESPOKE[file].href, excerpt: ex, featured: true,
        file: `docs/${file}`, deletable: true, mtime,
        paths: [`docs/${file}`, ...(BESPOKE[file].extraPaths || [])],
      })
      continue
    }

    const outPath = path.join(OUT_DIR, `${slug}.html`)
    fs.writeFileSync(outPath, renderDocPage({ id: slug, title, body, toc, hasMermaid }), 'utf8')
    // Plain single-file docs are the ones the web user may delete: removing
    // docs/<file> and the generated page is a clean, reversible-by-git operation.
    cards.push({
      id: slug, title, href: `/docs/${slug}.html`, excerpt: ex, featured: false,
      file: `docs/${file}`, deletable: true, mtime,
      paths: [`docs/${file}`, `frontend/public/docs/${slug}.html`],
    })
    generated++
  }

  // Multi-part research reports -> one stitched page + card each.
  for (const report of REPORTS) {
    const dir = path.join(RESEARCH_DIR, report.dir)
    if (!fs.existsSync(dir)) continue
    const parts = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith('.md'))
      .sort()
    if (!parts.length) continue

    const mtime = newestMtime(parts.map((p) => path.join(dir, p)))
    const md = parts
      .map((p) => demoteHeadings(fs.readFileSync(path.join(dir, p), 'utf8')))
      .join('\n\n---\n\n')
    // stripH1: false — after demotion there is no H1 anyway; the page title comes
    // from the report config, not from the Markdown.
    const { body, toc, hasMermaid } = renderMarkdown(md, { stripH1: false, resolveLink })
    const slug = slugify(report.dir)

    const outPath = path.join(OUT_DIR, `${slug}.html`)
    fs.writeFileSync(
      outPath,
      renderDocPage({ id: slug, title: report.title, body, toc, hasMermaid }),
      'utf8'
    )
    cards.push({
      id: slug,
      title: report.title,
      href: `/docs/${slug}.html`,
      excerpt: report.summary || excerpt(md),
      featured: !!report.featured,
      // Stitched from several files under research/<dir>/. Deletable from the web
      // UI: `paths` removes the whole source directory plus the generated page.
      file: null,
      deletable: true,
      mtime,
      paths: [`research/${report.dir}`, `frontend/public/docs/${slug}.html`],
    })
    generated++
  }

  // Uploaded static pages -> one card each (the HTML is already the page).
  if (fs.existsSync(UPLOADS_DIR)) {
    const uploaded = fs
      .readdirSync(UPLOADS_DIR)
      .filter((f) => f.endsWith('.html'))
      .sort()
    for (const file of uploaded) {
      const slug = file.replace(/\.html$/, '')
      let meta = {}
      try {
        meta = JSON.parse(fs.readFileSync(path.join(UPLOADS_DIR, `${slug}.meta.json`), 'utf8')) || {}
      } catch {
        /* no/unreadable sidecar — fall back to the slug */
      }
      cards.push({
        id: `${UPLOAD_ID_PREFIX}${slug}`,
        title: meta.title || humanize(slug),
        href: `/p/${file}`,
        excerpt: meta.summary || '通过 POST /api/pages 上传的静态网页。',
        featured: false,
        file: null,
        // Removable from the web UI (owner only): drop the page and its sidecar.
        deletable: true,
        mtime: newestMtime([path.join(UPLOADS_DIR, file)]),
        paths: [`frontend/public/p/${file}`, `frontend/public/p/${slug}.meta.json`],
      })
    }
  }

  // Flag docs whose source Markdown changed within the freshness window, and
  // record when (for a hover tooltip + the manifest). Also stamp per-doc privacy
  // (task #268) so the index can badge/hide private cards and the auth gate knows
  // which pages to keep behind the password.
  const privateIds = readPrivateIds()
  for (const c of cards) {
    c.isNew = c.mtime > 0 && c.mtime >= NEW_CUTOFF
    c.updatedAt = c.mtime > 0 ? new Date(c.mtime).toISOString() : null
    c.private = privateIds.has(c.id)
  }

  // Recently created/updated docs go to the very front (newest first); the rest
  // keep the established order — featured (bespoke) cards, then alphabetical by
  // title (task #319).
  cards.sort((a, b) => {
    if (a.isNew !== b.isNew) return a.isNew ? -1 : 1
    if (a.isNew && b.isNew) return b.mtime - a.mtime
    return (b.featured - a.featured) || a.title.localeCompare(b.title)
  })

  fs.writeFileSync(INDEX_OUT, renderIndex(cards), 'utf8')
  // The manifest mirrors the index (same order). The board reads it to flag
  // unseen docs (id/title/href); the backend's DELETE /api/docs/{id} reads the
  // extra `deletable`/`paths` fields to know which docs the web user may remove
  // and exactly which files/dirs back each one.
  const inClassics = classicsIds()
  const manifest = cards.map((c) => ({
    id: c.id,
    title: c.title,
    href: c.href,
    // Which tab of /docs.html lists this card (task #377). Declared, not
    // guessed — see CLASSICS. Anything unlisted is an article.
    zone: inClassics.has(c.id) ? 'classic' : 'article',
    deletable: !!c.deletable,
    file: c.file || null,
    // When the source Markdown was last modified, and whether that's recent
    // enough to flag as newly created/updated (task #319). The index already
    // sorts new docs first and paints their red badge; exposing it here keeps the
    // manifest a faithful mirror for any other consumer.
    updatedAt: c.updatedAt || null,
    isNew: !!c.isNew,
    // Whether the owner has marked this doc private (task #268). The Vite auth
    // gate reads this to keep private pages behind the password and to hide them
    // from anonymous listings; the board reads it to badge/skip private docs.
    private: !!c.private,
    // Every repo-relative file/dir backing this doc (sources + generated output).
    // DELETE /api/docs/{id} removes each of these, then regenerates the site.
    paths: c.paths || [],
  }))
  fs.writeFileSync(MANIFEST_OUT, JSON.stringify(manifest, null, 2) + '\n', 'utf8')
  console.log(
    `✔ docs site generated: ${generated} page(s) -> ${path.relative(ROOT, OUT_DIR)}/, index (${cards.length} card(s)) -> ${path.relative(ROOT, INDEX_OUT)}, manifest -> ${path.relative(ROOT, MANIFEST_OUT)}`
  )
}

build()
