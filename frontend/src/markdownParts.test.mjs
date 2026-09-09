import assert from 'node:assert'
import { COLOR_SPAN_RE, classifyDiffLine, colorSpanParts, parseTableBlock } from './markdownParts.js'

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
