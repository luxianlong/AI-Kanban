# 典籍 · Google 收录（任务 #417）

目标查询形如「商君书 English Free」「Tao Te Ching free full text」。本轮把**页面这一侧**
能做的全部做完，并把**只有你能做的**列在最后。

## 一、诊断：为什么现在难被收录

| # | 事实 | 影响 |
|---|---|---|
| 1 | 33 个读本 + 书架页**一条 canonical 都没有**；三个语言读本互相之间**没有 hreflang** | 同一部书三页近似，Google 只能自己猜规范页，或判为重复择一收录 |
| 2 | 英文页 `<title>` / description **一个汉字都没有**（「The Book of Lord Shang — Shangjunshu (English)」） | 查询里的「商君书」三字在页面标题里无从匹配 |
| 3 | 全站**没有一处写着 free / 免费** | 「Free」这一词在页面上没有落点 |
| 4 | 无 Open Graph、无 schema.org 结构化数据 | Google 无法把页面挂到「商君书 / The Book of Lord Shang」这个实体上 |
| 5 | **33 个读本页没有一条链接回书架** `/p/dianji.html` | 叶子页是死胡同，站内权重不流动，抓取深度全靠 sitemap |
| 6 | 站点**没有任何外链**，首页又被 `Disallow: /`（板子是私有的，本就该挡） | 发现渠道只剩 sitemap —— 见第三节 |

robots.txt / sitemap.xml 本身是好的：`docsGate.mjs` 动态生成，57 条 URL 全部命中
（33 读本 + 书架 + docs.html + 22 篇文章），**逐条以 Googlebot UA 实测全部 200**。

## 二、这一轮改了什么（提交 `d7527fc`，34 个文件）

对 11 部书 × 3 读本 + 书架页，每页 `<head>` 注入：

- **canonical**：自指，一律 apex（`https://youraikanban.com/...`；www 已 301 到 apex，方向一致）
- **hreflang**：`zh-Hans` / `zh-Hant` / `en` + `x-default`（指英文页），
  33 页**双向互指、零悬空**，脚本已验
- **robots**：`index,follow,max-snippet:-1,max-image-preview:large`
- **Open Graph + Twitter**：og:type=book、og:image 指 `/img/classics/*-cover.jpg`
  （书架页封面是 base64 内联的，取不到 URL；这批封面文件是 `docs.html` 在用的那一份，线上 200）
- **JSON-LD**：`Book`（`isAccessibleForFree: true`、`alternateName` 兼收简／繁／罗马字，
  中文页 `workTranslation` → 英文页，英文页 `translationOfWork` → 繁體页）
  + `WebPage` + `ImageObject` + `BreadcrumbList`；书架页为 `CollectionPage` + 11 条 `ItemList`
- **标题重写**（把检索词提到前 60 字符内）

  | | 旧 | 新 |
  |---|---|---|
  | EN | The Book of Lord Shang — Shangjunshu (English) | **The Book of Lord Shang 商君书 · Free Full English Translation** |
  | 简 | 商君书 · 简体版 | **商君书 · 简体全文 · 免费在线阅读** |
  | 繁 | 商君書 · 繁體版 | **商君書 · 繁體全文 · 免費線上閱讀** |

  《道德经》英文标题用 **Tao Te Ching (Daodejing)**——英文世界的检索量在 Tao Te Ching 这一侧。
- **描述**在原句后各加一句：英文「Free to read in full — no signup and no paywall;
  the Chinese original (商君书／商君書) is one click away.」；中文「全文免费在线阅读，无需注册」。
- **可见文字两处**（不是只给爬虫看的）：工具栏首位加一颗「典籍 / Classics」按钮，
  33 页都能一键回书架（同时补上了叶子页 → hub 的站内链接）；副题末尾注明「全文免费阅读 /
  free to read in full」。

**正文、段落 id、注释、篇旨一字未动**——`git diff` 里被替换的行只有 34 条 title、
34 条 description、33 条副题，其余全是纯插入。`npm test` 五套全绿；1200px / 390px 无横向溢出；
线上 34 页与磁盘一致（vite 直读 `public/`，未部署、未重启）。

## 三、只有你能做的（这是现在真正的瓶颈）

域名**已经在 Search Console 验证过了**——DNS 里有
`google-site-verification=P2VB1-PLk5JjINih1TlxzbUdofQZ4vRfYGzplZiJWwE`（Domain 属性，
apex 与 www 通吃）。所以接下来三步都在你的 GSC 里点：

1. **提交 sitemap**：Sitemaps → 填 `sitemap.xml`。（若已提交，看「已发现网页数」是不是 57。）
2. **手动请求收录**：URL Inspection 逐个贴，先做这 4 条种子页——
   `/p/dianji.html`、`/p/shangjunshu-en.html`、`/p/daodejing-en.html`、`/p/sunzi-en.html`
   → Request Indexing。剩下的靠 sitemap 慢慢来。
3. **两周后回看**：Pages 报表里「已抓取 — 尚未编入索引」若占多数，那是站点权重问题
   （见下条），不是页面问题。

**外链是绕不开的一环**：全新域名零外链，Google 的抓取预算近乎为零。见效最快的几处
（都不花钱、也都合规）：Wikipedia「Book of Lord Shang」「Tao Te Ching」等条目的
External links、Reddit r/taoism 与 r/ChineseLanguage、Hacker News Show HN、
豆瓣／知乎相关条目下的引用。一两条真实外链通常比任何页面改动都更快把收录推起来。

## 四、我碰不了、但值得单开一张卡的三件

1. **HEAD 请求返回 401**。`curl -I https://youraikanban.com/p/dianji.html` → 401；
   同一 URL 的 GET → 200。公开文档页对 HEAD 应当与 GET 同待遇。修在
   `frontend/docsGate.mjs`（派工不许改前端源码）。
2. **`docs.html` 的 `<title>` 是「文章存档」，没有 description**。它是站内通往全部典籍的
   第二个入口，标题该带上书名。它由 `scripts/build-docs-site.mjs` 生成，得走补丁（同下）。
3. **sitemap 没有 hreflang 注解**。`buildSitemap()` 只出 `<loc>`/`<lastmod>`。
   页面里的 `<link rel="alternate">` 已足够 Google 用，但 sitemap 里再来一份更稳。同样在
   `docsGate.mjs`。

另：`scripts/build-docs-site.mjs` 的两个补丁（`414-classics-xiaorenjing.patch`、
`416-classics-caigentan.patch`）**仍未打**——与本轮无关，但不打的话下次 `gen:docs`
会把《小人经》《菜根谭》从书架抹掉。本轮改的是 `/p/*.html`，生成器只从 `*.meta.json`
取标题，所以 `gen:docs` 不会回滚本轮改动。

## 五、复现

注入脚本：`/tmp/kanban-worker/todo-417/seo.py`（幂等，见 `<!-- search metadata -->` 标记即跳过）。
