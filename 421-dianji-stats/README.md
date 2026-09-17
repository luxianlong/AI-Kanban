# 421-dianji-stats — 典籍点击量统计

Everything task #421 produced. Nothing here is application code; the only file
written outside this folder is the standing dashboard,
`frontend/public/stats/dianji.html`.

## 交付物

| | |
|---|---|
| `report.md` | 统计与取证：数字、它们的来历、能回答与不能回答的问题 |
| `dataset.json` | 基线数据集（2026-09-17 02:41:52 UTC） |
| `collect.mjs` | 取数：doc_views × docs-manifest × 各页 meta.json |
| `page.template.html` | 仪表板模板（`/*__BASELINE__*/null` 是注入点） |
| `build-page.mjs` | 把 dataset 注进模板，写出 `frontend/public/stats/dianji.html` |
| `views-20260917.tsv` | 取数当时 `doc_views` 全表的原样导出（268 行，含已删文档） |

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

cd ~/Dashboard/421-dianji-stats
node collect.mjs /tmp/viewsdb/views.tsv > dataset.json
node build-page.mjs
```

`last_viewed_at` 只在库里有，HTTP 接口不给（`GET /api/docs/views` 只返回
`id -> count`）——取证那一节全靠这一列，所以必须走库。计数本身两边一致，
`collect.mjs` 取的数与 `curl localhost:8080/api/docs/views` 核对过。

重跑 `build-page.mjs` 会把基线推到当下。**一般不要这么做**：基线的用处正是
「自 2026-09-17 以来涨了多少」，重置一次就把那条线抹了。加了新书要更新页面时，
先想清楚是不是真的要换基线。
