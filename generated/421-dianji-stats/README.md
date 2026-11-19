# 421-dianji-stats — 典籍点击量统计

Everything task #421 produced, plus every later refresh of the same dashboard
(#497 续了第二次基线). Nothing here is application code; the only file written
outside this folder is the standing dashboard,
`frontend/public/stats/dianji.html`.

## 交付物

| | |
|---|---|
| `report.md` | #421 的统计与取证：数字、它们的来历、能回答与不能回答的问题 |
| `refresh-20260920.md` | #497 续基线：+101 次拆成「新页 71 / 旧页 30」，以及旧页里那批像读者的点击 |
| `dataset.json` | 当前基线数据集（2026-09-20 04:38:57 UTC，十九部书） |
| `dataset-20260917.json` | 上一次的基线数据集（#421，十二部书） |
| `baselines.json` | **基线链**：历次快照的 id → 累计数。页面〈四、基线历程〉就是它算出来的 |
| `collect.mjs` | 取数：doc_views × docs-manifest × 各页 meta.json |
| `page.template.html` | 仪表板模板（`/*__BASELINE__*/null` 是注入点） |
| `build-page.mjs` | 把 dataset 注进模板，写出 `frontend/public/stats/dianji.html` |
| `views-20260917.tsv` | #421 取数当时 `doc_views` 全表的原样导出（266 条，含已删文档） |
| `views-20260920.tsv` | #497 取数当时的同一张表（313 条） |

线上：<https://youraikanban.com/stats/dianji.html>（站点口令后，匿名 401）。

## 为什么仪表板放在 `public/stats/` 而不是 `public/p/`

`scripts/build-docs-site.mjs` 把 `frontend/public/p/` 下**每一个** `.html` 都收成一张
文章卡片。统计页若放进去，下一次 `gen:docs` 就会让它混进文章时间线、进 sitemap、
并且——因为点击只按 manifest 里的 href 记——它自己也会开始被计点击。放在
`public/stats/` 三样都不会发生：生成器不扫这个目录，sitemap 由 manifest 生成，
计数器不认识这个路径。代价是它落在口令门之后（门只白名单文档路径），而这对一个
内部统计页正合适。

## 重跑

后端把 H2 独占打开着，所以**读副本**，不要动 `data/kanban.mv.db` 本体：

```bash
H2=~/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar
mkdir -p /tmp/viewsdb && cp ~/Dashboard/data/kanban.mv.db /tmp/viewsdb/
java -cp $H2 org.h2.tools.Shell \
  -url "jdbc:h2:file:/tmp/viewsdb/kanban;ACCESS_MODE_DATA=r" -user sa -password "" \
  -sql "select doc_id, view_count, last_viewed_at from doc_views" \
  | sed -e 's/ *| */\t/g' > /tmp/viewsdb/views.tsv

cd ~/Dashboard/generated/421-dianji-stats
# 留档上一份，按它自己的 asof 命名（例：dataset-20260917.json）
cp dataset.json "dataset-$(node -p "require('./dataset.json').asof.slice(0,10).replace(/-/g,'')").json"
node collect.mjs /tmp/viewsdb/views.tsv > dataset.json
NOTE='#NNN 续基线（N 部书）' node build-page.mjs
```

`collect.mjs` 里的 `BOOKS` 是手写的书目表。**书上架了而它没跟上，仪表板就会少算
——#497 修的正是这个**：现在 manifest 里每一个 `zone === 'classic'` 的页面都必须被
`BOOKS` 认领，认领不了当场抛错，所以漏书不会再悄悄发生，只会让重跑失败。

`last_viewed_at` 只在库里有，HTTP 接口不给（`GET /api/docs/views` 只返回
`id -> count`）——取证那一节全靠这一列，所以必须走库。计数本身两边一致，
`collect.mjs` 取的数与 `curl localhost:8080/api/docs/views` 核对过。

重跑 `build-page.mjs` 会把「实时相对于谁」推到当下，**但不再丢掉旧基线**（#497）：
每次快照都追加进 `baselines.json`（同一个 `asof` 幂等覆盖，所以连跑两遍不长第三行），
页面〈四、基线历程〉把整条链列出来，每一段还拆成「新上架的页面带来的」与
「本来就在架的页面涨的」——**读者只可能出现在后一列**。`NOTE=` 给这一次快照留一句
话，显示在那一行的日期后面。

所以现在「自某某以来涨了多少」是永久保存的，加了新书就该续一次基线：续得越勤，
那张表的分辨率越高。
