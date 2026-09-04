# 从 /docs.html 直接写文章

这是一次端到端验证：文章是在 /docs.html 的弹窗里粘贴 Markdown 发布的。

## 它做了什么

- 把 Markdown 存成 `docs/<slug>.md`
- 重新生成静态站点，排版和存档里其他文章完全一致
- 提交到 git，下一次 deploy 的 `git checkout main` 不会把它抹掉

| 步骤 | 落到哪里 |
| --- | --- |
| 写盘 | docs/*.md |
| 生成 | /docs/*.html |

> 引用块也走同一套渲染器。
