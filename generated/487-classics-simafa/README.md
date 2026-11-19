# 《司馬法》入庫：本文の流水線

`/p/simafa*.html` 四つの讀本と `/img/classics/simafa-cover.jpg` の製作スクリプト。
覆核と再実行のために残してある。データ（`raw/` `var/` と `*.json`）は入庫しない。

    bash fetch.sh        # 底本＋通校二本＋外字表＋異體字表を取り直す
    bash cover.sh        # 封面（四部叢刊本 卷上卷端葉）を裁つ
    bash rebuild.sh      # 全部やり直して四つの頁を書き出す

`opencc-js`（繁→簡）と `sharp`（封面）が要る。どちらもリポジトリの依存では
ないので、`npm install opencc-js sharp` した先を環境変数で渡す：

    OPENCC=/path/to/opencc-js SHARP=/path/to/sharp bash rebuild.sh

## rebuild.sh の順序

| 歩 | スクリプト | すること |
|---|---|---|
| 1 | `assets.py` | 共通の CSS / JS を《六韜》の頁から取り出す |
| 2 | `parse.py` | 底本（維基文庫標點本）→ `base.json`、五篇 |
| 3 | `witness.py` | 通校二本を篇ごとに切る → `sbck.json` / `wyg.json`。外字實體を Unicode に還元し、SBCK の卷次錯亂を直し、WYG に無い《用衆》の篇界を補って切る |
| 4 | `canon.py` | 底本の機械的な誤り六箇所だけを直す → `canon.json` |
| 5 | `align.py` | 逐字對校 → `variants.json`（異體字表で字形と異文を分ける） |
| 6 | `text.py` | `canon.json` → `text.json`。**分段は底本のもので、本頁は一段も切っていない** |
| 7 | `notes_merge.py` `en_merge.py` `ja_merge.py` | `notes/` → `notes.json` / `en.json` / `ja.json` |
| 8 | `to_hans.js` | 繁 → 簡。註釋で「談論」される字形は釘づけ（`notes.json` を読むので 7 の後）。本文にも二字だけ釘を打つ（蒐・旂） |
| 9 | `build.py` | 四つの頁を書き出す |
| 10 | `meta.py` | 書架カードの題と抄（`*.meta.json`） |
| 11 | `shelf.py` | 書架頁 `/p/dianji.html` に一冊足す（冪等） |
| 12 | `verify.py` | 錨点・id・タグの開閉・段落 id の一致・封面の寸法・影宋鈔本との字數一致を機械検査 |
| 13 | `residue.js` | 繁／簡の取り違えと「自分を取り消す校語」を數える |

`/docs.html` と `docs-manifest.json` は `scripts/build-docs-site.mjs` の
`CLASSICS` に一條足したうえで `npm run gen:docs`。その一條は
`../487-classics-simafa.patch` にも残してある（`patch -R` で戻せる）。

`pvar.py` は註釋を書くときの手控えで、異文を段ごとに割りつけて印字するだけ。
頁には出ない。

## 中身

* `notes/zh1..zh5.py` — 篇旨五・段註六十八（繁體、約一万三千四百字）
* `notes/colophon.py` — 卷末〈底本與校例〉（繁體）
* `notes/en1..en5.py`、`notes/en_colophon.py` — 英訳（約一万一千二百語）
* `notes/ja1..ja5.py`、`notes/ja_colophon.py` — 日本語訳（約二万七百字）

## 本子

* 底本：維基文庫《司馬法》標點本（仁本・天子之義・定爵・嚴位・用眾の五篇、
  本文 3,438 字。本頁は逸文十八字を削り一字を補って 3,420 字）
* 通校一：《四部叢刊》本（上海涵芬樓借常熟瞿氏鐵琴銅劍樓藏影宋鈔本印行。
  Kanripo KR3b0005 の SBCK 枝）——今存最古の一支
* 通校二：《四庫全書》文淵閣本（同 KR3b0005 の WYG 枝）。維基文庫の
  四庫本翻刻でも覆核した
* 封面：通校一の卷上卷端葉（Wikimedia Commons, Sibu Congkan 0343 第 138 葉、PD）

## この一本で分かったこと（要点だけ）

* 底本は《仁本》の中に**《太平御覽》卷二十が引く逸文十八字**を取り込んでいた
  （「春不東征、秋不西伐。月食班師、所以省戰」）。二本のいずれにも無い。削った。
* **四庫全書本には「用衆第五」の篇題が無い。** Kanripo の翻刻と維基文庫の
  四庫本翻刻という互いに独立した二つの翻刻が揃って持たないので、翻刻の誤りでは
  なくこの本自身がそうなのだろう。
* **四部叢刊本の Kanripo 翻刻は卷次が錯亂**していて、上→下→中の順に並ぶ。
  各卷の卷端の題（司馬法卷上／司馬灋卷中／司馬灋卷下）自身が次序を語っている。
* 改字のあと、本頁の本文は**各篇の字數が影宋鈔本と完全に一致**する
  （648／942／884／681／265）。`verify.py` が毎回これを檢算する。
* 改めなかったが四庫本のほうが正しいと思う箇所が三つある。とくに《仁本》の
  「是以明其義也」——四庫本の「明其仁也」でなければ下文の「六德」が五つにしか
  ならない。一本しか拠るところが無いので改めず、註と凡例に書いた。
