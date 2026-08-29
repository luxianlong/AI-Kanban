# 上传静态网页并公开展示（免鉴权 API）

看板提供一个**不需要任何鉴权**的接口：把一个自包含的 HTML 文件 POST 上来，立刻拿到一个
**公开可访问**的网址，并且它会自动出现在公开的文档墙 `/docs.html` 上。

任务 #470：「创建一个 api 不需校验就可以上传静态网页到这个看板，如何展示给公众」。

## 一句话上传

```bash
# 1) 直接传文件（最常用）
curl -X POST 'http://localhost:8080/api/pages?title=我的报告' \
     -H 'Content-Type: text/html' \
     --data-binary @index.html

# 2) JSON
curl -X POST http://localhost:8080/api/pages \
     -H 'Content-Type: application/json' \
     -d '{"title":"我的报告","summary":"一句话简介","html":"<h1>hi</h1>"}'

# 3) 表单 / multipart（网页里的 <input type=file> 也能直接提交）
curl -X POST http://localhost:8080/api/pages -F 'file=@index.html'
```

返回：

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

`url` 就是公开地址。本机是 `http://localhost/p/<slug>.html`；把站点通过 cloudflared 隧道
对外暴露之后就是 `https://<你的域名>/p/<slug>.html`。

## 如何展示给公众

上传后有**两条**公开入口，都不需要密码：

1. **直链** `https://<域名>/p/<slug>.html` — 把这个链接发给任何人即可。
   Vite 的鉴权中间件把 `/p/*.html` 归类为「公开文档」，和 `docs/` 下的文章一样绕过登录页。
2. **文档墙** `https://<域名>/docs.html` — 上传时后端会重跑文档站生成器
   （`scripts/build-docs-site.mjs`），把这张页面作为一张卡片插进索引，标题/简介取自上传时
   给的 `title` / `summary`。7 天内的新页面还会带红色 NEW 角标排在最前面。

看板本体（`/`）、`/api` 的其余接口仍然在密码后面 —— 公众只能看到你公开的页面，看不到任务板。

整站怎么对外暴露（cloudflared 隧道 + 域名 + Basic-Auth 门）不在本文范围内：门本身是
`frontend/vite.config.js` 里的中间件，凭据放在被 gitignore 的 `frontend/.public-auth.json`。

## 其它接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/pages` | ❌ 不需要 | 发布一张静态网页 |
| `GET` | `/api/pages` | ❌ 不需要 | 列出已发布的页面（新的在前） |
| `GET` | `/api/pages/{slug}` | ❌ 不需要 | 单张页面的元信息 |
| `DELETE` | `/api/docs/upload-{slug}` | ✅ 需要（站长） | 下架某张页面 |

**发布是匿名的，下架不是。** 删除走文档墙上原有的「✕」按钮 / `DELETE /api/docs/{docId}`，
这条路径依旧在密码门后面 —— 陌生人可以发布，但不能删掉别人的页面。

## 参数

| 字段 | 位置 | 说明 |
| --- | --- | --- |
| `html` | JSON 字段 / 请求体 / 上传文件 | 页面内容，必填 |
| `title` | JSON 字段或 query | 卡片标题；不给就取文档里的 `<title>`，再不行取第一个 `<h1>` |
| `slug` | JSON 字段或 query | 想要的网址名；会被清洗成 `[a-z0-9-]` |
| `summary` | JSON 字段或 query | 文档墙卡片上的一句话简介 |

关于 `slug`：中文标题清洗后为空，会退化成随机的 `page-<8位十六进制>`；想要好看的网址就显式传
`slug=my-report`。**已存在的 slug 永远不会被覆盖** —— 重名会自动变成 `-2`、`-3`，所以匿名调用者
无法靠猜名字替换掉别人的页面。

## 边界与安全

这是一个匿名写接口，请了解它的取舍：

- **必须是单文件。** 页面要自包含（内联 CSS/JS，图片用 data: URI 或外链）。不支持上传
  一个目录或附带资源文件。
- **配额**（`application.yml` 的 `pages.*`）：单页 ≤ 5 MB、总数 ≤ 200 张、同一来源
  每小时 ≤ 30 次上传，超出分别返回 `413` / `409` / `429`。把 `max-uploads-per-hour`
  设成 `0` 可以彻底关掉发布（GET 仍然可用）。
- **上传的 HTML 被沙箱化。** 它是陌生人写的代码，却跑在看板自己的域名下，所以服务这些页面时
  会带上 `Content-Security-Policy: sandbox …`（不含 `allow-same-origin`）。页面自己的
  脚本、表单、下载都照常工作，但拿不到看板的 Cookie / localStorage，也无法冒充已登录的站长
  去调 `/api`。
- **落盘位置** `frontend/public/p/<slug>.html`（外加 `<slug>.meta.json` 边车文件），上传后
  由后端提交进 git —— 否则下一次 `deploy.sh` 的 `git checkout main` 会把它冲掉
  （和任务 #463 里「删掉的文档又冒出来」是同一类坑）。
- 页面内容是**公开**的。上传后如果想收起来，站长可以在文档墙上把它标成 🔒 Private，
  之后只有登录者能打开。
