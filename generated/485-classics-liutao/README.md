# 《六韜》入庫：本文の流水線

`/p/liutao*.html` 四つの讀本と `/img/classics/liutao-cover.jpg` の製作スクリプト。
覆核と再実行のために残してある。データ（`raw/` `var/` と `*.json`）は入庫しない。

    bash fetch.sh        # 底本＋通校二本＋異體字表を取り直す
    bash cover.sh        # 封面（景宋本卷端葉）を裁つ
    bash rebuild.sh      # 全部やり直して四つの頁を書き出す

`opencc-js`（繁→簡）と `sharp`（封面）が要る。どちらもリポジトリの依存では
ないので、`npm install opencc-js sharp` した先を環境変数で渡す：

    OPENCC=/path/to/opencc-js SHARP=/path/to/sharp bash rebuild.sh

## rebuild.sh の順序

| 歩 | スクリプト | すること |
|---|---|---|
| 1 | `parse.py` | 底本（維基文庫標點本）→ `base.json`、六十篇 |
| 2 | `restruct.py` | 《兵道》の篇次を景宋本・四庫本の舊次第に戻す → `canon.json` |
| 3 | `punct.py` | 底本の標點三箇所の硬傷（衍引號十二・重出一・空白三） |
| 4 | `norm.py` | 維基標記、混入した簡體、書中で亂れた字形（陳→陣ほか） |
| 5 | `witness.py` | 通校二本を篇ごとに切る → `song.json` / `siku.json` |
| 6 | `supply.py` | 脱文二箇所（五十四字）を補い、形訛三箇所を改める |
| 7 | `align.py` | 逐字對校 → `variants.json`（異體字表で字形と異文を分ける） |
| 8 | `seg.py` | 分段（話し手の交代＋四庫本の行界） → `text.json` |
| 9 | `to_hans.js` | 繁 → 簡。註釋で「談論」される字形は釘づけ |
| 10 | `build.py` | 四つの頁を書き出す |
| 11 | `meta.py` | 書架カードの題と抄（`*.meta.json`） |
| 12 | `verify.py` | 錨点・id・タグの開閉・段落 id の一致を機械検査 |

書架頁 `/p/dianji.html` は生成物ではなく手書きなので、`shelf.py` で一冊足す
（冪等）。`/docs.html` と `docs-manifest.json` は
`scripts/build-docs-site.mjs` の `CLASSICS` に一條足したうえで
`npm run gen:docs`。その一條は `../485-classics-liutao.patch` にも残してある。

## 中身

* `notes/zh1..zh6.py` — 卷旨六・篇旨六十・段註三二三（繁體、約四萬五千字）
* `notes/en1..en6.py`、`notes/en_colophon.py` — 英譯（約三萬語）
* `notes/ja1..ja6.py`、`notes/ja_colophon.py` — 日本語訳（約五萬三千字）
* `notes/colophon.py` — 卷末〈底本與校例〉（繁體）
* `residue.js` — 繁／簡の取り違えを本文だけで數える
* `meas.js` — 封面の裁ち寸法を走査画像の輝度から測る

## 本子

* 底本：維基文庫《六韜》標點本（六卷六十篇、本文 16,686 字）
* 通校一：《四部叢刊初編》影印 **常熟瞿氏鐵琴銅劍樓藏景宋刊本**（現存最古）
* 通校二：《四庫全書》本《六韜》六卷（無句讀）
* 封面：通校一の djvu 第十葉（卷第一卷端）。Wikimedia Commons、PD
