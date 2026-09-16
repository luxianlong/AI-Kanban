# 反經（長短經）入庫：文本流水線

本目錄是 `/p/fanjing*.html` 四個讀本的製作腳本，留檔以便覆核與重跑。
資料檔（`book.json`、`sku/`、`text.json`、`ids.txt`、`*-variants.txt` 等）不入庫，
`fetch.sh` 與 `variants.py` 會重新取回。

    bash fetch.sh        # 維基文庫標點本六十五頁（序 + 六十四篇）
    python3 parse.py     # → book.json
    # 另需 sk/j1..j9.json：維基文庫《長短經（四庫全書本）》九卷
    # 以及 ids.txt（CHISE/CJKVI 表意文字結構）與 cjkvi-variants 的異體字表
    bash rebuild.sh      # 全部重跑，直接寫出四個頁面

`rebuild.sh` 的次序與各步的作用：

| 步 | 腳本 | 做什麼 |
|---|---|---|
| 1 | `norm.py` | 依 Big5 判別底本混入的簡體與舊字形，生成 `glyphfix.json` |
| 2 | `norm2.py` | 統一底本三種注記標法、還原方括號描述的缺字、歸併引號 |
| 3 | `restruct.py` | 把底本攪在一起的卷四《霸圖》與卷五《七雄略》重新切開 |
| 4 | `sku.py` | 解析四庫全書本，得六十四篇及其雙行小字注的界限 |
| 5 | `align.py` | 兩本逐字對齊：輸入四庫本的注界，輸出異文表 |
| 6 | `classify.py` | 用異體字表把「字形差異」從「文字異文」裡分出來 |
| 7 | `supply.py` | 補底本脫去的五處（《適變》後半等，共三千三百餘字） |
| 8 | `post.py` | 《是非》標目、云／雲、殘餘簡體字、注內外重複標點 |
| 9 | `to_hans.js` | 繁→簡，釘住凡例裡被談論的字形、乾卦與年號 |
| 10 | `build.py` | 生成 `fanjing.html` / `-hans` / `-en` / `-ja` |

英譯與日譯的文字在 `en*.py` / `ja*.py`，卷旨篇旨在 `notes.py`，卷末《底本與校例》在 `colophon.py`。
封面的裁切量測見 `meas.js` / `meas2.js`（國立國會圖書館藏讀畫齋叢書本，IIIF）。
生成器 `scripts/build-docs-site.mjs` 的書架登記見 `../420-classics-fanjing.patch`。
