# 公开接口（Public API）总览

这块看板整体是**私有**的：站点挂在 cloudflared 隧道后面，前面有一道口令门
（`frontend/vite.config.js` 里的中间件）。但有几条路径是**故意**放开的 —— 不需要任何账号、
口令或 token，任何能访问到域名的人都能直接调用。

本文把这些「public 接口」一次讲清楚：**有哪些、怎么调、返回什么、边界在哪里**。

> 门本身的实现在 `frontend/vite.config.js`，判断哪些路径免鉴权的纯函数在
> `frontend/docsGate.mjs`（有单测 `docsGate.test.mjs`）。想确认某条路径是否公开，读那个文件就够了。

---

## 0. 一张表看完

| 方法 | 路径 | 用途 | 任务 |
| --- | --- | --- | --- |
| `POST` | `/api/pages` | 匿名发布一张静态网页，立刻得到公开网址 | #470 |
| `GET` | `/api/pages` | 列出已发布的页面（新的在前） | #470 |
| `GET` | `/api/pages/{slug}` | 单张页面的元信息 | #470 |
| `GET` | `/api/public/tasks` | 列出「代理」任务（外部 worker 的工作队列） | #471 |
| `GET` | `/api/public/tasks/{id}` | 单个代理任务详情 | #471 |
| `POST` | `/api/public/tasks/{id}/claim` | 认领一个代理任务，拿到上报用的 token | #471 |
| `POST` | `/api/public/tasks/{id}/status` | 上报代理任务的结果 | #471 |
| `GET` | `/docs.html` | 公开文档墙（私有文档已被过滤掉） | #268 |
| `GET` | `/docs/{name}.html` | 某篇公开文档 | #268 |
| `GET` | `/p/{slug}.html` | 某张上传的静态网页 | #470 |
| `GET` | `/docs-manifest.json` | 文档清单 JSON（私有条目已剔除） | #268 |
| `GET` | `/img/**` | 文档配图等静态资源 | #268 |
| `GET` | `/vendor/**` | 文档页自用的前端库（mermaid） | #81 |
| `GET`/`POST` | `/api/docs/reads` | 已读状态；**匿名访问是空操作**（见 §4） | #472 |
| `GET` | `/__auth/whoami` | 探测当前访客是不是站长 | #268 |

**除此之外的一切都在口令门后面** —— 看板本体 `/`、`/api/todos`、`/api/stories`、
`/api/attachments`、`/api/worker/**`，以及 `/api/docs` 上除 `reads` 之外的所有动词
（删除、公开/私有切换）。尤其注意：**`DELETE` 从来不在公开面上**，陌生人可以发布，但删不掉任何东西。

下文的示例都用 `http://localhost:8080`（后端直连）。对外访问时把它换成你的域名，
例如 `https://youraikanban.com/api/pages`，Vite 会把 `/api` 反代到后端。

---

## 1. 发布静态网页：`/api/pages`

把一个**自包含**的 HTML 文件 POST 上来，立刻得到一个公开网址，并自动出现在文档墙上。

```bash
# 1) 直接传文件（最常用）
curl -X POST 'http://localhost:8080/api/pages?title=我的报告' \
     -H 'Content-Type: text/html' --data-binary @index.html

# 2) JSON
curl -X POST http://localhost:8080/api/pages \
     -H 'Content-Type: application/json' \
     -d '{"title":"我的报告","summary":"一句话简介","html":"<h1>hi</h1>"}'

# 3) 表单 / multipart（网页里的 <input type=file> 可以直接提交）
curl -X POST http://localhost:8080/api/pages -F 'file=@index.html'
```

`201 Created`，返回体：

```json
{
  "slug": "page-3f9a12bc",
  "docId": "upload-page-3f9a12bc",
  "title": "我的报告",
  "summary": "一句话简介",
  "url": "/p/page-3f9a12bc.html",
  "bytes": 147582,
  "createdAt": "2026-08-29T05:00:00Z"
}
```

`url` 就是公开地址。参数、slug 规则、配额、沙箱等细节见姊妹文档
**[上传静态网页并公开展示](/docs/publish-static-page.html)**；这里只列出错误码：

| 状态码 | 含义 |
| --- | --- |
| `400` | 请求体里没有 HTML |
| `403` | 发布被关闭（`pages.max-uploads-per-hour: 0`） |
| `409` | 已达页面总数上限（默认 200 张） |
| `413` | 单页超过大小上限（默认 5 MB） |
| `429` | 同一来源一小时内上传次数超限（默认 30 次） |

### 画图：页面里可以直接写 Mermaid（#81）

上传的 HTML 里只要有 mermaid 代码块，后端在保存时会自动补上渲染器，页面打开就是图 ——
**不用**自己引 CDN，也不用管沙箱：

```html
<pre class="mermaid">
graph TD
  Board[看板 Board] -->|派发| Worker[Worker]
  Worker -->|发布| Page[/p/*.html/]
</pre>
```

三种写法都认（大小写不敏感）：`<pre class="mermaid">`、`<div class="mermaid">`，以及
markdown 转 HTML 常见的 ` ```mermaid ` → `<code class="language-mermaid">`。

细节：

- 注入的是两个同源静态脚本 `/vendor/mermaid.min.js`（vendored mermaid 11，无第三方请求）
  和 `/vendor/mermaid-init.js`；`/vendor/**` 和 `/img/**` 一样免口令，匿名读者也能看到图。
- 页面**自己**已经引了 mermaid（CDN 或自带一份）时，后端原样保存，不会重复注入。
- 渲染用 `securityLevel: 'strict'`，跟随系统深色模式；某一张图语法写错只会保留它的源码，
  不影响页面其余部分。

---

## 2. 代理任务：`/api/public/tasks`

看板上的任务默认由本机的 Claude worker 去做。被标成**代理（delegated）**的任务是个例外：
worker 会跳过它，转而把它发布在这条公开接口上，让**外部的**执行者 —— 另一个 bot、一位同事、
一个 cron 脚本 —— 发现它、在别处做完、再把结果写回看板。

三步流程：

```bash
# 1. 找活儿（默认只返回还没做完的）
curl http://localhost:8080/api/public/tasks

# 2. 认领（可选，但推荐）：任务转为 IN_PROGRESS，看板上显示是谁接的，
#    同时返回一个 token —— 请保存好，上报时要用。
curl -X POST http://localhost:8080/api/public/tasks/42/claim \
     -H 'Content-Type: application/json' -d '{"worker":"my-bot"}'

# 3. 上报结果
curl -X POST http://localhost:8080/api/public/tasks/42/status \
     -H 'Content-Type: application/json' \
     -d '{"status":"RESOLVED","result":"做完了，答案是……","token":"…"}'
```

### `GET /api/public/tasks`

按创建时间**从旧到新**返回。`?status=` 可选：

| 取值 | 含义 |
| --- | --- |
| 不传 / `open` | 默认。`PENDING` + `IN_PROGRESS`，即所有还没结束的 |
| `all` | 全部，含已完成的 |
| `PENDING` | 无人认领、可以直接拿的活儿（轮询这个最实用） |
| `IN_PROGRESS` / `RESOLVED` / `FAILED` | 精确状态 |

每个元素：

```json
{
  "id": 42,
  "title": "统计一下 X",
  "description": "详细要求……",
  "status": "PENDING",
  "result": null,
  "claimedBy": null,
  "claimedAt": null,
  "createdAt": "2026-08-29T05:00:00Z",
  "updatedAt": "2026-08-29T05:00:00Z"
}
```

这个视图**比看板自己的任务对象窄**：没有花费/耗时统计、没有 story 编排信息、没有附件地址
（那些都在口令门后面）。只有活儿本身、状态、和谁接了。

### `POST /api/public/tasks/{id}/claim`

请求体 `{"worker":"my-bot"}`（`worker` 可选，≤100 字符，只是个自报的名字，方便站长在看板上看见）。

返回 `{"task": {...}, "token": "…"}`。**token 只在这里返回一次**，之后想改这个任务的状态就必须带上它。

- 任务不是 `PENDING` → `409`
- 任务已经被别人认领 → `409`

认领是**可选**的：如果你只想顺手回答一下，可以跳过第 2 步直接上报。认领的意义在于
①避免两个 worker 抢同一个活儿，②防止第三方覆盖你的答案。

### `POST /api/public/tasks/{id}/status`

请求体：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 是 | `RESOLVED` / `FAILED` / `IN_PROGRESS` / `PENDING`（`PENDING` = 把活儿退回队列，同时清掉认领） |
| `result` | 否 | 自由文本答案，≤100000 字符，会原样显示在看板卡片上 |
| `token` | 视情况 | 任务**已被认领**时必填；未被认领时可省略 |

结果落到看板上后，和本机 worker 自己做完的任务长得一模一样（包括进入终态时的未读小红点）。

- 未知的 `status` → `400`
- 任务已被认领但 token 不对 → `403`（token 用常数时间比较，无法逐字节爆破）

### 为什么放开它是安全的

- **只有被显式标成 `delegated` 的任务在这里存在。** 其它任务一律返回 `404` —— 和一个不存在的
  id 完全一样的响应，所以没法拿它来探测私有看板上有哪些任务 id。
- **不能创建，也不能删除。** 这条接口唯一的写操作，是给站长主动交出去的那个任务写一条状态/结果。
- 认领过的任务，只有持 token 的人能上报。

站长侧怎么把一个任务交出去：建任务时带 `"delegated": true`（`POST /api/todos`，这条**在**口令门后面）。

---

## 3. 公开文档：`/docs.html`、`/docs/*.html`、`/p/*.html`

看板产出的文档**默认公开**：任何人不用口令就能读。站长可以把某一篇标成 🔒 Private，
之后就只有登录者能打开 —— 而且匿名访客**连它存在都不知道**：

- `/docs.html`（文档墙）对匿名访客返回的是**剔除了私有卡片**的版本；
- `/docs-manifest.json` 同样会滤掉私有条目；
- 私有文档的直链对匿名访客会跳登录页。

公开的路径有五类：

| 路径 | 是什么 |
| --- | --- |
| `/docs.html` | 文档索引页 |
| `/docs/<name>.html` | 由 `docs/<name>.md` 生成的文档页 |
| `/p/<slug>.html` | 通过 §1 的接口上传的静态网页 |
| `/img/**` | 文档里的配图等资源 |
| `/vendor/**` | 文档页自用的前端库（目前只有 mermaid） |

另外，清单里显式登记了 `.html` 地址的**定制页**（例如 `/singapore.html`）也算公开文档。

**上传页面会被沙箱化。** `/p/*.html` 是陌生人写的代码，却跑在看板自己的域名下，所以服务时会带上
`Content-Security-Policy: sandbox …`（**不含** `allow-same-origin`）。页面自己的脚本、表单、
下载都正常工作，但它拿不到看板的 Cookie / localStorage，也没法冒充已登录的站长去调 `/api`。

---

## 4. 两个辅助端点

**`GET|POST /api/docs/reads`** —— 文档的「已读」状态，用来给文档墙的 **NEW** 角标去色。
它本来是站长私有的，但公开文档页加载时也会去请求它；如果直接 401，浏览器会在一个「本来不需要
验证」的页面上弹出原生登录框（任务 #472）。所以门在这里对匿名访客返回一个**空操作**：

- `GET` → `200 []`（什么也没读过）
- `POST` → `204`（什么也不记录）

站长的已读状态因此仍然只属于站长自己。注意：**只有 `reads` 这一条**是这样，`/api/docs` 上的
删除、公开/私有切换全都还在门后面。

**`GET /__auth/whoami`** → `{"authed": true|false}`。文档墙用它决定要不要显示站长专属的
「删除 / 切换私有」按钮。本机开发（没有凭据文件时）恒为 `true`。

---

## 5. 通用约定与注意事项

- **CORS**：上述控制器都带 `@CrossOrigin(origins = "*")`，所以浏览器里的第三方页面可以直接
  `fetch()` 这些接口。
- **错误格式**：Spring 默认的错误 JSON（`timestamp` / `status` / `error` / `message` / `path`），
  失败原因写在 `message` 里。
- **没有 API key、没有速率鉴权**：唯一的限流是 `/api/pages` 的按来源 IP 上传配额
  （`application.yml` 的 `pages.*`）。走隧道时来源 IP 取 `X-Forwarded-For` 的第一段。
- ⚠️ **口令门在前端（Vite）那一层，不在后端。** `http://localhost:8080` 直连后端是**完全没有**
  鉴权的 —— 谁能碰到 8080 端口，谁就是站长。所以对外只能暴露 80 端口（隧道指向 Vite），
  绝不要把 8080 直接暴露出去。
- 公开面上的写操作都是**匿名**的。它们的安全性靠的是**范围**而不是身份：能发布页面但不能删除，
  能上报代理任务但不能创建或看见普通任务。加接口时请保持这个性质。

---

## 6. 想再放开一条路径？

改 `frontend/docsGate.mjs` 里的 `isPublicApiPath()`（并补上 `docsGate.test.mjs` 的用例），
不要去动 `vite.config.js` 里的鉴权逻辑本身。加之前先问三个问题：

1. 它能不能被用来**删**东西？（不行就别放开）
2. 它会不会**泄露**私有看板上的内容？（代理接口的做法：非代理任务一律 404）
3. 它是不是无限量的写入口？（那就得像 `/api/pages` 那样配额化）
