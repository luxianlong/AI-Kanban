# 《三略》入庫：本文の流水線

`/p/sanlue*.html` 四つの讀本と `/img/classics/sanlue-cover.jpg` の製作スクリプト。
覆核と再実行のために残してある。データ（`raw/` `var/` と `*.json`）は入庫しない。

    bash fetch.sh        # 底本＋通校二本＋異體字表を取り直す
    bash cover.sh        # 封面（伏見版卷之上卷端葉）を裁つ
    bash rebuild.sh      # 全部やり直して四つの頁を書き出す

`opencc-js`（繁→簡）と `sharp`（封面）が要る。どちらもリポジトリの依存では
ないので、`npm install opencc-js sharp` した先を環境変数で渡す：

    OPENCC=/path/to/opencc-js SHARP=/path/to/sharp bash rebuild.sh

## rebuild.sh の順序

| 歩 | スクリプト | すること |
|---|---|---|
| 1 | `assets.py` | 共通の CSS / JS を《六韜》の頁から取り出す |
| 2 | `parse.py` | 底本（維基文庫標點本）→ `base.json`、三卷 |
| 3 | `witness.py` | 通校二本を卷ごとに切る → `siku.json` / `zhijie.json` |
| 4 | `canon.py` | 底本の機械的な誤り二十三箇所だけを直す → `canon.json` |
| 5 | `align.py` | 逐字對校 → `variants.json`（異體字表で字形と異文を分ける） |
| 6 | `seg.py` | 分段（劉寅『三畧直解』の節界に従う） → `text.json` |
| 7 | `notes_merge.py` `en_merge.py` `ja_merge.py` | `notes/` → `notes.json` / `en.json` / `ja.json` |
| 8 | `to_hans.js` | 繁 → 簡。註釋で「談論」される字形は釘づけ（`notes.json` を読むので 7 の後） |
| 9 | `build.py` | 四つの頁を書き出す |
| 10 | `meta.py` | 書架カードの題と抄（`*.meta.json`） |
| 11 | `shelf.py` | 書架頁 `/p/dianji.html` に一冊足す（冪等） |
| 12 | `verify.py` | 錨点・id・タグの開閉・段落 id の一致を機械検査 |
| 13 | `residue.js` | 繁／簡の取り違えと「自分を取り消す校語」を數える |

`/docs.html` と `docs-manifest.json` は `scripts/build-docs-site.mjs` の
`CLASSICS` に一條足したうえで `npm run gen:docs`。その一條は
`../486-classics-sanlue.patch` にも残してある（`patch -R` で戻せる）。

`pvar.py` は註釋を書くときの手控えで、異文を段ごとに割りつけて印字するだけ。
頁には出ない。

## 中身

* `notes/zh1..zh3.py` — 卷旨三・段註一一九（繁體、約一万二千字）
* `notes/colophon.py` — 卷末〈底本與校例〉（繁體）
* `notes/en1..en3.py`、`notes/en_colophon.py` — 英訳（約八千七百語）
* `notes/ja1..ja3.py`、`notes/ja_colophon.py` — 日本語訳（約一万七千字）

## 本子

* 底本：維基文庫《三略》標點本（上・中・下三卷、本文 3,815 字）
* 通校一：《四庫全書》本《黃石公三畧》三卷白文（無句讀。Kanripo KR3b0007 の WYG 枝）
* 通校二：明劉寅《三畧直解》所載の經文（同 KR3b0008。『武經七書』系で、
  白文本とは別系統。劉寅が節ごとに註を挟むので、その節界が本頁の段界になる）
* 目驗：慶長九年（1604）伏見版古活字本《黃石公三略》三卷
  （国立国会図書館 WA7-22、Wikimedia Commons、PD）。四庫二本とは系統が
  異なる第三證。機械比較はできないので要所のみ目で確かめた。
* 封面：その伏見版 PDF 第 2 葉（卷之上卷端）

## この一本で分かったこと（要点だけ）

* 底本は**一度簡体を経て繁体に戻された**痕跡を十七箇所残していた
  （「一→壹」十二、「志→誌」二、「卜→蔔」「徵→征」「竈→灶」各一）。
  台湾教育部の異體字表がまさにその対を並べているので、繁体化の道具が
  額面どおり拾ったのだろう。
* 伏見版の卷端葉には後人の手になる校語「**講作怨／直作人**」があり、
  同じ本の卷末刊記が「板行の誤りは講直両部を以て改正する者なり」と
  自述している。本頁の機械對校が出した異文と、四百年前の書き込みが
  一致した。
* 《三略》には篇も章も無いので、**本頁は小標題を立てない**。目次は三卷と
  卷末の四條だけである。
