# 484-dianji-shoulo — 典籍收罗（候选书目调研）

任务 #484：「有什么中国古代典籍是欧美人或日本人喜欢的，但还没有免费译文网页点吗」。

结论与全部证据见 `report.md`；网页版在 `frontend/public/stats/dianji-candidates.html`
（站点口令之后，与 #421 的统计页同处 `/stats/`，不进 `p/`，故不被 `build-docs-site.mjs` 收录）。

## 文件

| 文件 | 内容 |
|---|---|
| `report.md` | 正文：结论、方法、候选榜、劝退清单、方法的洞 |
| `demand.tsv` | 100 部书的 en/ja 十二个月 Wikipedia 浏览量（含已上架 12 部作标尺） |
| `candidates.tsv` | 候选榜机器可读版：字数、浏览量、免费译文判定、标准译本、等级 |
| `pageview-input.tsv` | 取数用的书名映射（中文 / en 条目名 / ja 条目名 / 分类） |
| `collect-pageviews.py` | 解析条目名并取十二个月浏览量（wikimedia REST API，`agent=user`） |
| `count-chars.py` | 从 zh.wikisource 取原文 wikitext，剥模板与注释后数 CJK 字数 |
| `gapcheck.py` | 对每部候选批量查 en.wikisource / Internet Archive / Project Gutenberg |

## 重跑

```bash
cd 484-dianji-shoulo
python3 collect-pageviews.py      # 需要 pageview-input.tsv（脚本里叫 cands.tsv，按需改名）
python3 count-chars.py
python3 gapcheck.py               # 约 3 分钟，archive.org 会限流
```

窗口写死在 `collect-pageviews.py` 里（`20250901`–`20260831`）。换窗口就改那两个日期。
zh.wikisource 的 API 会在密集请求下回 429，`count-chars.py` 里已加节流。

## 没做的

- ctext.org 挂着 Cloudflare Turnstile 并明确要求研究者走订阅，没有绕；它的英译覆盖只用 Wayback 做了间接对照。
- 只判英文 / 日文免费译文。中文白话译文到处都有，不是有效筛选条件。
- 没有做浏览量趋势，只有一个固定十二个月窗口的总量。
