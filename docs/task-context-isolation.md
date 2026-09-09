# 任务间的上下文是怎么管理的，有隔离吗

先给结论：**有隔离，但隔离的是「对话」，不是「环境」。**

每个任务都是一次全新的 `claude -p` 进程，不带 `--resume` / `--continue`，所以**上一个任务的对话记忆一个字都传不过来**。但所有任务共用同一个仓库工作区、同一棵 git 工作树、同一份 `CLAUDE.md`、同一个 `~/.claude` 记忆目录、同一个浏览器登录态。

跨任务传上下文的**正规通道只有一条**：story 的步骤链 —— 前序步骤的 `result` 被拼进下一步的 prompt。除此之外任何「串味」都是通过共享文件系统发生的，不是设计出来的。

本页是对任务 #228 的回答，代码位置全部标到行号（`ClaudeWorkerService.java`,`AiStoryClassifier.java`, `scripts/env.sh`），并附本机实测数字（2026-09-09）。

## 一个任务的完整生命周期

`ClaudeWorkerService.poll()` (:160) 每 5 秒跑一次（`WORKER_POLL_MS=5000`）：

1. `tickLock.tryLock()` (:164) —— 上一 tick 没跑完就直接跳过。
2. 取 `PENDING`，过 `isEligible()` (:198)：代理任务不归本机跑、正在分类的任务先押着、story 步骤按序等前序步骤 RESOLVED (`isBlockedByStory` :208)。
3. `batch-size: 1`（application.yml:88），且 `@Scheduled` 跑在单线程调度器上 —— **同一时刻本机只会有一个 worker 会话**。
4. `buildPrompt()` (:295) 拼 prompt → `runClaude()` (:444) 起进程：

```
claude --output-format stream-json --verbose --dangerously-skip-permissions -p <整段 prompt>
```

工作目录 `WORKER_WORK_DIR`（env.sh:80）= **仓库本身** `/home/xianllu/Dashboard`。

命令行里**没有** `--resume`、`--continue`、`--session-id`。这就是隔离的根源：会话是一次性的，上下文只能来自两处 —— **prompt 文本**，和 **cwd 里那些文件**。

## 隔离住了的部分（会话层）

| 维度 | 隔离 | 机制 |
|---|---|---|
| 对话历史 | ✅ 完全 | 每任务新进程，无 resume；上一任务的推理过程不可见 |
| 实时日志 | ✅ 按任务 | `LiveLogRegistry` 按 todo id 存，`start()` (:232) 丢弃旧转录 |
| 运行中的进程 | ✅ 按任务 | `RunningSessionRegistry` 按 todo id 注册，可单独 stop / 单独判定「用户停止」 |
| 并发 | ✅ 串行 | `tickLock` + `batch-size: 1`，不存在两个任务同时写同一棵工作树 |
| 故事分类器 | ✅ 换了 cwd | `AiStoryClassifier.runClaude()` (:196-198) 故意跑在 `/tmp/kanban-classify`，好让这次纯文本提问**不加载仓库的 CLAUDE.md 和文件** |

分类器那条隔离是本仓库里唯一一处**主动**做的上下文隔离，而且它是有效的：`~/.claude/projects/` 下 `-tmp-kanban-classify` 攒了 **90** 个会话文件，与 `-home-xianllu-Dashboard` 的 **31** 个各归各。

## 没有隔离的部分（环境层）—— 这才是问题的正面

| 共享物 | 后果 |
|---|---|
| cwd = 仓库本身 | git 工作树、**当前分支**是全局状态。本机现有 **61** 个 `auto/*` 分支；一个任务被 deploy 重启打断时停在哪个分支，下一个任务就从哪个分支开始 |
| `~/.claude/projects/-home-xianllu-Dashboard/memory/` | `MEMORY.md`（当前 6 条）**每个会话自动注入** —— 这是有意的长期跨任务记忆，也是最强的一条串味通道 |
| 同目录 31 个 `.jsonl` 转录 | 任何任务都能读到别的任务的**完整转录**（含全部工具返回）。不自动加载，但一个 `grep` 就够 |
| `CLAUDE.md` / `settings.json` / skills / MCP | 同一份规则、同一个 Stop 钩子（推送）、同一个 Chrome 与其登录态 |
| `/tmp`、H2 数据库、`frontend/public/` | 一个任务写的文件，下一个任务看得见 |
| `--dangerously-skip-permissions`（env.sh:83） | 没有权限门。「不要改看板自己的代码」这类隔离**只靠 prompt 里的一段文字**约束 |
| prompt 走 argv | 整段 prompt（含前序步骤全文）出现在 `ps -ef` 里，本机任何用户可读。本次实测 **7406 字符 / 11863 字节** |

## 唯一的正规跨任务通道：story 步骤链

`appendStoryContext()` (:409)：

- **只取 `storyOrder` 更小的步骤**，按序拼进 prompt —— 单向，后面看得到前面，前面看不到后面。
- 每步的 `result` 截到 `STORY_RESULT_BUDGET = 4000` 字符 (:402, :436)，超了加 `…(truncated)`。
- 传的是**最终 result，不是转录**：前一步怎么想的、试错过什么、读了哪些文件，全部丢弃，只留它自己写的那份总结。
- 门控在 `isBlockedByStory()` (:208)：任一前序步骤不是 RESOLVED 就一直等 —— 一步失败，整条链停住，不会带着半截上下文往下跑。
- **独立任务之间是零上下文**，连标题都不给对方。

实测（本板 18 条有结果的任务）：结果长度中位 **1473**、最长 **2843** 字符 —— **4000 的截断阈值到今天一次都没触发过**。prompt 随步数线性增长：本次是第 6 步，带了前 5 步共 **5908** 字符的结果。按这个体量，story 长到 20 步以上截断才会开始咬人。

## 三个实际后果

1. **「上下文隔离」实为约定，不是机制。** 会话层隔得很干净，但两个任务共用一棵工作树、一份记忆、一个浏览器 —— 真正防止串味的是 prompt 里的措辞和 `batch-size: 1` 的串行，不是沙箱。步骤 3 在 MyMacbook 里查到的那个 harvest bug 是同一类问题的镜像：判据扫的是整个转录（含工具返回），于是别的题的工作流被采了进来。**共享的文件系统会把「另一个任务提到过这个路径」变成「这就是这个任务的东西」。**
2. **分支残留。** 61 个 `auto/*` 分支还在原地，工作树共享。任务里 `git checkout` 的效果是全局的、跨任务的。
3. **重启会重跑。** deploy 重启杀掉在跑的会话，任务留在 `IN_PROGRESS`，下次启动由 `requeueOrphans()` 收回队列；到 `maxAttempts`（默认 2）才转 Needs Attention —— 这就是记忆里那条「story 步骤在 deploy 重启后被重跑」的由来。

## 如果要真隔离（本次没做，只是记下来）

- **每任务一个 `git worktree`**：分支状态天然隔离，且能安全放开串行；改动小，是性价比最高的一步。
- **prompt 走 stdin 而不是 argv**：把 prompt 从进程表里拿掉。
- **记忆目录按任务/story 分区**：现在是一份全局 `MEMORY.md`，好处坏处都归它。
- 真正的沙箱（容器/用户隔离）代价大得多，在 `--dangerously-skip-permissions` 已经开着的前提下，才是唯一能挡住「任务改了不该改的东西」的东西。

现状下最便宜的护栏其实已经在用了：`batch-size: 1` 的串行 + 部署前的测试门 + 部署前的数据库快照。

## 一句话

**会话层强隔离（每任务全新进程、零对话记忆），环境层零隔离（同一棵工作树、同一份长期记忆、同一个浏览器），中间由 story 步骤链做唯一一条受控的单向上下文传递 —— 每步上限 4000 字符，只传最终结果，一步失败整条链停。**
