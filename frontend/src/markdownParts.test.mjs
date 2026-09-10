import assert from 'node:assert'
import {
  COLOR_SPAN_RE,
  INLINE_HTML_RE,
  classifyDiffLine,
  colorBucket,
  colorSpanParts,
  inlineHtmlParts,
  parseTableBlock,
} from './markdownParts.js'

// --- diff lines --------------------------------------------------------------
assert.equal(classifyDiffLine('+ 新增的一句'), 'add')
assert.equal(classifyDiffLine('- 删掉的一句'), 'del')
assert.equal(classifyDiffLine('-'), 'del', 'a bare - is a removed blank line')
assert.equal(classifyDiffLine('## 一、标题'), 'ctx')
assert.equal(classifyDiffLine(''), 'ctx')
// File headers start with - / + but are not removals; they must be tested first.
assert.equal(classifyDiffLine('--- a/draft.md'), 'meta')
assert.equal(classifyDiffLine('+++ b/draft.md'), 'meta')
assert.equal(classifyDiffLine('@@ -1,4 +1,6 @@'), 'meta')
assert.equal(classifyDiffLine('\\ No newline at end of file'), 'meta')
// ...but a run of dashes as prose is still a deletion, not a header.
assert.equal(classifyDiffLine('----'), 'del')

// --- colour spans ------------------------------------------------------------
const red = COLOR_SPAN_RE.exec('图例：<span style="color:red">`-` 红行</span> 表示删除')
assert.ok(red)
assert.deepEqual(colorSpanParts(red), { color: 'red', inner: '`-` 红行' })
assert.equal(red.index, 3, 'match starts at the tag, so text before it survives')

const green = COLOR_SPAN_RE.exec('<span style="color: green; font-weight:bold">加了</span>')
assert.deepEqual(colorSpanParts(green), { color: 'green', inner: '加了' })

const font = COLOR_SPAN_RE.exec('<font color="red">删了</font>')
assert.deepEqual(colorSpanParts(font), { color: 'red', inner: '删了' })

// The case from the board (workflow run 116, step 添加网络梗): a step told to
// highlight the jokes it added picked its own shade, and every one of the 15 it
// marked arrived with the tags showing.
const hex = COLOR_SPAN_RE.exec('相遇了。<span style="color:#d95f02">**这叫"双币流动性池"**</span>所以')
assert.ok(hex)
assert.deepEqual(colorSpanParts(hex), { color: 'orange', inner: '**这叫"双币流动性池"**' })
assert.equal(hex.index, 4, 'match starts at the tag, so text before it survives')
// ...and the sibling run, which reached for a red of its own.
assert.deepEqual(
  colorSpanParts(COLOR_SPAN_RE.exec('<span style="color:#e03131">这瓜不该用嘴吃</span>')),
  { color: 'red', inner: '这瓜不该用嘴吃' },
)

// Whatever the colour is written as, it lands in one of our buckets — the
// class is ours, so no value the model chose reaches a style attribute.
assert.equal(colorBucket('red'), 'red')
assert.equal(colorBucket('  GREEN '), 'green', 'case and spacing are the model\'s to get wrong')
assert.equal(colorBucket('#c00'), 'red', 'three-digit hex')
assert.equal(colorBucket('#e0313180'), 'red', 'an alpha channel is ignored, not fatal')
assert.equal(colorBucket('rgb(217, 95, 2)'), 'orange')
assert.equal(colorBucket('rgba(217 95 2 / 0.8)'), 'orange')
assert.equal(colorBucket('#d95f02'), 'orange')
assert.equal(colorBucket('gold'), 'yellow')
assert.equal(colorBucket('#2f9e44'), 'green')
assert.equal(colorBucket('#1971c2'), 'blue')
assert.equal(colorBucket('#7c3aed'), 'purple')
assert.equal(colorBucket('#e64980'), 'red', 'pink is the red end of the wheel, not purple')
// Colour we can read but have no use for: the text keeps its tags off but is
// left the colour of the page.
assert.equal(colorBucket('#888888'), 'grey', 'no hue left to bucket')
assert.equal(colorBucket('#ffffff'), 'grey', 'white would be invisible; grey at least reads')
assert.equal(colorBucket('black'), null, 'already the body colour')
assert.equal(colorBucket('#050505'), null)
assert.equal(colorBucket('chartreuse'), null, 'a name we do not know')
assert.equal(colorBucket('var(--brand)'), null)
assert.equal(colorBucket(''), null)
assert.equal(colorBucket(undefined), null)
// An unknown colour still gets its tags stripped: plain prose beats visible HTML.
assert.deepEqual(
  colorSpanParts(COLOR_SPAN_RE.exec('<span style="color:chartreuse">x</span>')),
  { color: null, inner: 'x' },
)

// The declaration may sit after others, and may be followed by others.
assert.deepEqual(
  colorSpanParts(COLOR_SPAN_RE.exec('<span style="font-weight:bold;color:#d95f02">x</span>')),
  { color: 'orange', inner: 'x' },
)
assert.deepEqual(
  colorSpanParts(COLOR_SPAN_RE.exec("<span style='color:red;background:#fff'>x</span>")),
  { color: 'red', inner: 'x' },
)
assert.deepEqual(colorSpanParts(COLOR_SPAN_RE.exec('<font color=#e03131>x</font>')), {
  color: 'red',
  inner: 'x',
})

// Only a `color` declaration is honoured, and only on `style` — every other
// attribute is still left alone rather than half-understood.
assert.equal(COLOR_SPAN_RE.exec('<span onclick="alert(1)">x</span>'), null)
assert.equal(COLOR_SPAN_RE.exec('<span style="background:red">x</span>'), null)
assert.equal(
  COLOR_SPAN_RE.exec('<span style="background-color:red">x</span>'),
  null,
  'the property is background-color, not color',
)
assert.equal(COLOR_SPAN_RE.exec('<span style="margin:0;background-color:red">x</span>'), null)
assert.equal(COLOR_SPAN_RE.exec('<span class="color:red">x</span>'), null)

// --- inline HTML -------------------------------------------------------------
// The case from the board: a step told to underline every joke it added answers
// with <u>…</u>, and the tags used to show up as literal text in the result.
const u = INLINE_HTML_RE.exec('以下是加梗后的成稿，<u>下划线</u>处为新增的梗')
assert.ok(u)
assert.deepEqual(inlineHtmlParts(u), { tag: 'u', inner: '下划线' })
assert.equal(u.index, 10, 'match starts at the tag, so text before it survives')

// Tags Markdown can say another way still get honoured, mapped to one element
// each so <b> and <strong> cannot render differently.
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<b>粗</b>')), { tag: 'strong', inner: '粗' })
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<strong>粗</strong>')), { tag: 'strong', inner: '粗' })
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<i>斜</i>')), { tag: 'em', inner: '斜' })
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<em>斜</em>')), { tag: 'em', inner: '斜' })
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<s>删</s>')), { tag: 'del', inner: '删' })
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<del>删</del>')), { tag: 'del', inner: '删' })
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<strike>删</strike>')), { tag: 'del', inner: '删' })
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<MARK>亮</MARK>')), { tag: 'mark', inner: '亮' })

// The inner text is handed back untouched for the renderer to parse again, so
// **bold** inside an underline is still bold.
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<u>**粗**</u>')), { tag: 'u', inner: '**粗**' })

// A tag spanning the newlines inside one paragraph still closes.
assert.deepEqual(inlineHtmlParts(INLINE_HTML_RE.exec('<u>第一行\n第二行</u>')), {
  tag: 'u',
  inner: '第一行\n第二行',
})

// Anything outside the allow-list is left as literal text: an attribute means
// the model asked for something we are not honouring, and an unclosed or
// mismatched tag must not swallow the rest of the paragraph.
assert.equal(INLINE_HTML_RE.exec('<u class="x">下划线</u>'), null, 'no attributes')
assert.equal(INLINE_HTML_RE.exec('<script>alert(1)</script>'), null)
assert.equal(INLINE_HTML_RE.exec('<div>块</div>'), null, 'block tags are not inline formatting')
assert.equal(INLINE_HTML_RE.exec('<u>未闭合'), null)
assert.equal(INLINE_HTML_RE.exec('<b>错配</i>'), null, 'the backreference requires the same tag')
assert.equal(INLINE_HTML_RE.exec('值 <u 3'), null)
// The self-closing shape carries nothing to underline; it is not a match, so it
// stays visible rather than vanishing silently.
assert.equal(INLINE_HTML_RE.exec('<u/>'), null)

// --- tables ------------------------------------------------------------------
const t = parseTableBlock(['| 标记 | 含义 |', '|---|---|', '| `-` 红行 | 删除 |', '| `+` 绿行 | 新增 |', '', 'after'], 0)
assert.deepEqual(t.header, ['标记', '含义'])
assert.deepEqual(t.rows, [['`-` 红行', '删除'], ['`+` 绿行', '新增']])
assert.equal(t.next, 4, 'stops at the first non-row line')

const aligned = parseTableBlock(['| a | b | c |', '| :-- | :-: | --: |', '| 1 | 2 | 3 |'], 0)
assert.deepEqual(aligned.align, ['left', 'center', 'right'])

// A ragged row loses or gains a cell; it must not shift the columns after it.
const ragged = parseTableBlock(['| a | b | c |', '|---|---|---|', '| 1 | 2 |', '| 1 | 2 | 3 | 4 |'], 0)
assert.deepEqual(ragged.rows, [['1', '2', ''], ['1', '2', '3']])

// An escaped pipe is content, not a column break.
assert.deepEqual(parseTableBlock(['| a | b |', '|---|---|', '| x \\| y | z |'], 0).rows, [['x | y', 'z']])

// Not tables: prose with pipes, a missing delimiter row, a mismatched one.
assert.equal(parseTableBlock(['grep foo | wc -l', 'next'], 0), null)
assert.equal(parseTableBlock(['| a | b |', '| 1 | 2 |'], 0), null)
assert.equal(parseTableBlock(['| a | b |', '|---|'], 0), null)
assert.equal(parseTableBlock(['| a | b |'], 0), null, 'a lone row is not a table')

console.log('ok')
