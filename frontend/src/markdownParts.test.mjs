import assert from 'node:assert'
import {
  COLOR_SPAN_RE,
  INLINE_HTML_RE,
  classifyDiffLine,
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

// Anything outside the two-colour whitelist is left as literal text rather than
// smuggled into a style attribute.
assert.equal(COLOR_SPAN_RE.exec('<span style="color:#c00">x</span>'), null)
assert.equal(COLOR_SPAN_RE.exec('<span onclick="alert(1)">x</span>'), null)
assert.equal(COLOR_SPAN_RE.exec('<span style="background:red">x</span>'), null)

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
