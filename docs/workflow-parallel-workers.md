# Workflow 能不能多开 worker

先给结论：**能，而且改动很小 —— 但要先加的那个数字不是 workflow 的 worker 数。**

本机实测（2026-09-09）三个数字，决定了整个答案：

- 已完成的 3 次 workflow 运行，**42% 的墙上时间没有任何会话在跑**，全花在等看板任务队列上。最差的一次（run #2）是 **62%**。
- 板上唯一一条真实 workflow（`公众号文章 2`，9 个节点）**并行度天花板只有 1.48×**，而且 **N=2 就已经吃满，N≥3 一秒都不会再快**。
- 一个 `claude` 会话实测 **270 MB**（PSS），机器 `MemAvailable` 只剩 **632 MB**，没有 swap。

所以顺序是：**先把两个 poller 分到两个线程上（收益 2.6×，Todo 仍是单 worker），再考虑加 workflow 节点并发（收益 1.48×，且机器目前撑不住）。**

本页是对任务 #236 的回答，代码位置标到行号，数字全部来自本机运行中的进程与 `/api/workflows/executions`。

## 一、串起来的不是 tickLock，是那一根调度线程

`ClaudeWorkerService` 和 `WorkflowRunnerService` 各有自己的 `ReentrantLock tickLock`（:47 / :57），它们**互相不阻塞**。真正把两者串成一条队的是别的东西：

应用只写了 `@EnableScheduling`（`KanbanApplication.java:14`），没有配 `TaskScheduler`，于是吃 Spring Boot 的默认值 `spring.task.scheduling.pool.size = 1`。**全应用两个 `@Scheduled` 方法共用一根线程。**

实测印证 —— 3600 多行后端日志里，两个 service 的日志线程名只出现过一个值：

```
$ grep -o "scheduling-[0-9]*" /tmp/kanban-backend.log | sort -u
scheduling-1
```

而 workflow 的 tick 是**阻塞**的：`poll()` (:96) → `advance()` (:124) → `runNode()` (:211) → `runner.run()` → `process.waitFor(props.getTimeoutSeconds(), …)`（`ClaudeSessionRunner.java:95`）。也就是说，一个节点跑多久，这根线程就被占多久，**看板任务的 `poll()` 在这期间连一次都轮不到**。反过来也一样。

`WorkflowRunnerService` 跑完一个节点就 `return`（:111，注释写着「hand the tick back so the task queue isn't starved」）——**这个防饿死设计是对的，但它防不住反向的饿死**：一个看板任务跑 9 分钟，workflow 就停 9 分钟。

写这段代码时的注释（`application.yml:95`、`WorkflowRunnerService.java:35`）说这根单线程「guarantees only one Claude process is alive at a time」。**这句话今天已经不成立了**：`AiStoryClassifier` (:48) 自带一个 `newFixedThreadPool(2)`，在调度线程之外自己起 `claude` 进程。所以本机峰值并发会话现在是 **1 + 2 = 3**，不是 1。并发不是新东西，只是没人记账。

## 二、实测：等待时间去哪了

`/api/workflows/executions` 里每个节点都有 `startedAt` / `finishedAt`，逐个减出来：

| 运行 | 节点数 | 墙上时间 | 会话时间之和 | 空等 |
|---|---|---|---|---|
| #1 Wexin Article | 2 | 5.9 min | 5.8 min | 3% |
| #2 Wexin Article | 4 | 45.7 min | 17.3 min | **62%** |
| #3 公众号文章 2 | 8 | 46.3 min | 34.0 min | 27% |
| #4 公众号文章 2 | 9 | 进行中 | — | 卡在 3/9 |
| **合计（已完成）** | 14 | **97.9 min** | **57.0 min** | **42%** |

run #1 几乎没有空等 —— 那段时间板上没有别的任务在跑。run #2 的 62% 是这样来的：`草稿` 05:23:32 跑完之后，第二个节点 `信息密度评估` 直到 **05:54:18** 才开始，中间 **28 分钟**整整跑掉了三个看板任务（#231/#232/#233）。workflow 一直是 RUNNING，只是没有线程可用。

**写这份文档的此刻，run #4 就正卡在 3/9** —— 它的第 3 个节点 07:27:06 结束，紧接着这根线程被任务 #236（也就是本次会话）拿走了。这不是推理出来的，是现场。

## 三、DAG 能并到几路：1.48×，而且 N=2 就到顶

把 `公众号文章 2` 的 13 条边和实测的单节点耗时（`草稿` 150s、`信息密度评估` 323s、`提高信息密度` 168s、`前后对比` 303s、`隐喻比喻评估` 192s、`去掉隐喻和比喻` 196s、`隐喻去除前后对比` 562s、`全链路对比` 222s）放进去算关键路径，再用 list scheduling 模拟 N 个 worker：

```
串行总和     = 2363s (39.4 min)
关键路径     = 1591s (26.5 min)   最大加速比 1.48x

N=1  ->  39.4 min  (1.00x)
N=2  ->  26.5 min  (1.48x)
N=3  ->  26.5 min  (1.48x)   ← 一秒不省
N=9  ->  26.5 min  (1.48x)
```

**为什么这么低，值得说清楚，因为这是结构性的，不是这条 workflow 没画好。**

这类「打磨型」workflow 的主干天然是一条链：`草稿 → 信息密度评估 → 提高信息密度 → …`，每一步都在改上一步的产物，后一步没有前一步的输出根本无从下手。能并行的只有那几个**叶子节点** —— 三个 `对比` 和 `精写` 分支：它们读上游的结果生成报告，没有任何节点依赖它们。

省下来的 772 秒，全部来自 `前后对比`(303s) 和 `隐喻去除前后对比`(562s) 这两片叶子挪到旁边的车道上跑。

推论很直接：**run #2 那种 4 节点的纯链条，加多少 workflow worker都不会快一秒**（它前四个节点在图上就是一条链）。它慢的 62% 全是第一节说的那件事。

## 四、要改什么：两处真改动，五处已经是对的

`WorkflowRunnerService` 其实已经写得很接近多 worker 的形状了。已经安全的部分：

1. `LiveLogRegistry` / `RunningSessionRegistry` 都是 `ConcurrentHashMap`，且**按 `node:<id>` 一节点一个 key** —— 多个节点同时跑，日志和「停止」按钮天然各归各。
2. `WorkflowService.cancel` (:292) 已经是**遍历所有 RUNNING 节点**逐个 `sessions.stop()`（:298），不是只停一个。
3. `recoverOrphans` (:79) 已经把**所有** RUNNING 行判失败。
4. `abortAll()` 关机时杀的是**全部**会话。
5. `advance()` 里那句「有节点 RUNNING 就别收尾」的分支，注释写着 "Only reachable if a session outlived its tick, which shouldn't happen" —— 在多 worker 下它从「不该发生」变成**主路径**，而逻辑本来就是对的。

真正要动的只有两处：

**(1) tick 不能再阻塞。** `runNode()` 必须提交给一个固定大小的执行器，`poll()` 立刻返回。否则加多少线程，一根线程还是只跑一个节点。

**(2) `PENDING → RUNNING` 的认领必须原子。** 现在是先 `findFirst()` 选出候选（:143-146），再在 `runNode()` 里 `setStatus(RUNNING)` 保存。两个线程在任何一个写回之前都读到同一行，就会**把同一个节点跑两遍** —— 副作用做两次，账单付两次。两种改法：给 `NodeExecution` 加 `@Version`（两个实体现在都没有版本列），认领冲突时捕获 `OptimisticLockException` 跳过；或者用一条 `@Modifying` 的 `UPDATE … SET status='RUNNING' WHERE id=? AND status='PENDING'`，只有返回 1 行才继续。后者更直白。

配套的小改动：`tickLock` 从「一把互斥锁」换成「N 张许可证」；`return true`（:111）那条「跑完一个就交还」的规则换成「许可证用完为止」。

配置面：加 `workflow.max-concurrent-nodes`，**默认 1**（保持今天的行为），要并发才显式调大。看板任务侧 `worker.batch-size: 1`（`application.yml:88`）**一个字都不用动** —— 卡片要求的「Todo 保持单 worker」是免费成立的，因为两个 poller 本来就是两个独立的 bean。

## 五、机器撑不撑得住：这才是真正的上限

```
CPU              2 核
内存             3866 MB，无 swap
MemAvailable     632 MB          ← 现在
一个 claude 会话  270 MB (PSS)    ← 实测本次会话
后端 JVM         320 MB (PSS)
```

按这个数，**今天连第 2 个并发会话都是贴着边的**。而且内存打满的后果在这个项目里特别贵：`application.yml:47` 那段注释记着，2026-08-29 丢库就是因为 SIGKILL 落在 H2 压缩窗口里 —— OOM killer 干掉 JVM 就是同一件事。

但先别急着说「机器不行」。查了一下现在内存被谁占着：

```
$ ps -eo pid,args | grep "chrome --headless"
9 个泄漏的 headless Chrome 实例（81 个进程），合计 1685 MB (PSS)
   /tmp/wf-focus-profile   /tmp/wf-probe   /tmp/wfbase-profile   /tmp/wfdbg-profile
```

这些是本 story 第 9、12、13 步的**验证脚手架没关掉的浏览器**（存活 42 分钟到 1 小时 51 分钟不等；`~/.chrome-debug-profile` 那个是 MCP 在用的，不在此列）。**整机 44% 的内存是垃圾。** 清掉它够放 6 个并发会话。

所以容量这一栏的真实结论是：**限制并发的不是设计，是没人关的浏览器。** 任何调度参数的收益都比不上在验证脚本里补一句 kill。（本次没有动它们 —— 端口 9222 那个是 MCP 实例，误杀会打断别的东西；这属于另一张卡。）

## 六、单线程一直在替我们挡的那件事

有个副作用得先说，否则第一步就会踩坑。

所有会话的工作目录都是仓库本身：`--worker.working-directory=/home/xianllu/Dashboard`（`scripts/env.sh:94`）。**同一棵 git 工作树。**

今天那根单线程保证了一件事：一个看板任务跑 `scripts/deploy.sh`（`git checkout main` + merge + 重启后端）的时候，**绝不可能**有 workflow 节点在同一棵树里写文件。把两者分到两根线程上，这个保证当场消失 —— 而 deploy 的 `git checkout` 遇到脏工作树会中止（记忆里已经有一次：regenerated docs 弄脏工作树导致 merge 被静默跳过、却仍打印 ✔ DEPLOYED）。

好消息是仓库里已经有现成的解法：`AiStoryClassifier` 早就把自己的会话挪到了 `/tmp/kanban-classify`（:198），正是为了不加载仓库上下文。workflow 节点写的是文章和研究产物，本来就不需要待在应用仓库里。

**所以：在动任何并发参数之前，先给 workflow 节点一个独立的 `workflow.working-directory`。** 这一步比调度器配置更重要，而且它自己就是个好改动。

## 七、建议的顺序

**第 0 步（前置，必做）：workflow 节点换独立工作目录。** 见第六节。不做这一步，后面两步都会把 deploy 和节点放进同一棵 git 树。

**第 1 步（一行配置，收益最大）：`spring.task.scheduling.pool.size: 2`。**
两个 poller 各占一根线程。Todo 仍然是单 worker（`batch-size: 1` 不变），workflow 仍然一次一个节点 —— **谁的并发数都没加**，只是它们不再互相排队。run #2 那种运行 45.7 min → 约 17.6 min（**2.6×**），而且是那 42% 空等里收益最稳的部分。
代价：峰值并发会话 1 → 2（约 +270 MB）。今天的 632 MB 勉强够，清掉泄漏的 Chrome 之后很宽裕。

**第 2 步（要写代码，收益有限）：`workflow.max-concurrent-nodes: 2`。**
需要第四节的两处改动。天花板 1.48×，**N≥3 没有任何收益**，别配大。峰值会话变成 1(todo) + 2(node) + 2(classify) = 5，约 1.35 GB —— **在清掉那 1685 MB 之前不要开**。

**不建议做的：提高 `worker.batch-size`。** 卡片本来就要求 Todo 保持单 worker，而且这里有独立理由：story 步骤链是显式串行的（`isBlockedByStory` 要求前序步骤全部 RESOLVED），并发只会让更多步骤在 `isEligible` 里被判不合格空转；再加上共享工作树，两个任务同时 `git checkout -b` 就是直接的相互破坏。

**顺带该修的注释：** `application.yml:95`、`WorkflowRunnerService.java:35`、以及 `docs/task-context-isolation.html` 里「同一时刻只有一个 Claude 会话」的说法，因为 `AiStoryClassifier` (:48) 的两线程池，今天已经是错的。真正的不变式是「**看板任务和 workflow 节点各自最多一个**」，不是「全机只有一个」。

---

*本页只做分析与实测，没有改动任何应用代码、没有部署、没有重启、没有开新任务。上面所有数字都可以用 `/api/workflows/executions`、`/tmp/kanban-backend.log` 和 `ps` 复现。*
