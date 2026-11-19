# SUMMARY 里那半句：数学底子与 5 项专利怎么写

> 这是 [#424 简历 PMF 诊断](/p/resume-pmf-staff-ai-infra.html) §5.6 留下的尾巴。原话是：
> "SJTU 图像处理所、IEEE VCIP 论文、5 项专利，现在躺在最后一节当装饰，**应该在 SUMMARY 里有半句话呼应**。"
> 这一页就是把那半句写出来 —— 六个候选、一版推荐、插回 SUMMARY 的完整效果，以及写完之后你必须能答的三个追问。

---

## 0. 先说结论

**推荐版（Resume-D 主简历，替换 v2 SUMMARY 最后那句 `MS from SJTU (image processing); 5 patents.`）：**

> *I came into infrastructure from the modelling side — MS in image processing at SJTU, an IEEE VCIP
> paper on online video-object classification, 5 patents — so the ML layer is new vocabulary to me,
> not a new discipline.*

**短版（字数紧张、或用在 Resume-A 控制面简历上）：**

> *MS in image processing (SJTU), IEEE VCIP paper, 5 patents — I came to systems from the modelling side.*

一句话理由：**原来那行是学历，推荐版是主张。** 学历只能被核实，主张能被相信 —— 而你要处理的那个反对意见（"他没做过 ML"）只能靠主张来处理，学历行对它一个字都没说。

---

## 1. 这半句要干的活，比看上去窄

先把要打的靶子说准。"他没做过 ML" 在招聘方脑子里其实是**三个不同的反对意见**，杀伤力和可修复性都不一样：

| # | 反对意见的真实形式 | 它在问什么 | 这半句能不能解决 |
|---|---|---|---|
| ① | "他没训练过模型 / 没跑过 GPU" | 手上有没有算力栈的肌肉记忆 | **不能**。这是 §4.1 的活，只有 #422 capstone 能补 |
| ② | "他不懂模型在数学上是怎么回事，讲不清 loss / 概率 / 线性代数" | 能不能参与技术讨论而不只是搬运需求 | **能，而且只有它能** |
| ③ | "让他学 ML 要多久？半年还是两年？招他风险多大？" | 学习曲线的斜率 | **能**（这是 §5.6 原话点名的目标） |

所以这半句的职责是：**把 ② 和 ③ 关掉，然后闭嘴。** 它不该去碰 ①。很多人写这类句子时贪心，顺手加一句 "with hands-on experience in machine learning" —— 那是在用一句无法举证的话，去碰一个简历里 0 次 `GPU / PyTorch / vLLM` 的领域，等于把一个能赢的争论拖进一个会输的争论。

**判据**：这半句写完之后，读者应该想的是「数学不是他的障碍」，而不是「他好像在暗示自己做过 ML？」。后者一旦发生，它就变成了一个负分项。

---

## 2. 为什么现在那行不算数

v2 SUMMARY 的收尾是：

```text
MS from SJTU (image processing); 5 patents.
```

八个词，三处失效：

1. **没有主张。** 它陈述了两个事实，没说这两个事实**买到了什么**。读者不会替你做推理 —— 尤其是在 6 秒扫读里。学历行的默认解读是"这人在填格式"，不是"这人在论证"。
2. **时间方向反了。** 放在 SUMMARY 末尾的分号后面，它读起来像"补充一句过去"。而你要的效果是**"我是从那边过来的"** —— 一个起点，不是一个附录。起点决定读者怎么读上面那 90 个词；附录谁都不读。
3. **和 HONORS 完全重复。** 同一页的最后，HONORS 已经写了 `IEEE VCIP paper … 5 patents（video coding, similarity network fusion, monitoring）`。一份 staff 简历里同一组事实出现两次而**两次说的是同一件事**，第二次就是纯粹的字数浪费。允许重复的唯一条件是：**两次的用途不同** —— HONORS 负责可核实，SUMMARY 负责下结论。

> 一句话：那八个词现在既不是论据，也不是结论，它只是一份没删干净的简历模板残留。

---

## 3. 六个候选，按主张强度排序

每条都标了**前提**（什么情况下才能写）和**风险**。字数以英文词计。

### C1 — 最小改动（14 词）

> *MS from SJTU in image processing — I came into systems from the modelling side, not the other way round; 5 patents.*

- **主张**：起点是建模，不是运维。
- **前提**：无。这条谁都能写。
- **风险**：最低，收益也最低。适合"我今晚就要投，不想重排版"。

### C2 — 学习曲线版（24 词）

> *My training is image processing (MS, SJTU) — transforms, linear algebra and statistical modelling were the day job before distributed systems were; 5 patents.*

- **主张**：数学是旧技能不是新技能，学习曲线短。
- **前提**：无。
- **风险**：`transforms, linear algebra, statistical modelling` 是**名词清单**，容易读成关键词堆砌。如果用它，最多留两个词，三个就过线。

### C3 — 证据版 ★ 推荐（31 词）

> *I came into infrastructure from the modelling side — MS in image processing at SJTU, an IEEE VCIP
> paper on online video-object classification, 5 patents — so the ML layer is new vocabulary to me,
> not a new discipline.*

- **主张**：不是"我学过数学"，是"我发表过一篇分类问题的论文"。**用工件代替形容词。**
- **前提**：论文你能在两分钟内讲清楚（见 §6）。讲不清就退回 C1。
- **风险**：会招来追问 —— 但这正是你要的，§11 的结论是你现在**四条 headline 是"你答得出、简历却没让人问"**，这句话主动制造一个你答得出的问题。
- **为什么是它**：`classification` 这个词由论文标题自带，不是你自己贴的标签。**由事实名称携带的关键词，比由你声明的关键词可信一个量级。**

### C4 — 脊椎对仗版（38 词）

> *I started on the modelling side — an MS in image processing and an IEEE VCIP paper on
> similarity-network fusion — which is the same graph-and-statistics shape I have been building
> infrastructure for ever since; 5 patents.*

- **主张**：把 2015 年的论文和今天的图数据库接成**一条线**，直接为 §4.6 的"叙事脊椎"服务。
- **前提**：你愿意在面试里把这条线讲圆（相似度图上的融合与传播 ↔ 你今天做的高扇出邻接布局、超级节点）。
- **风险**：38 词太长，SUMMARY 会从 95 词涨到 125 词，**超过 recruiter 的扫读预算**。这条的正确用法不是放 SUMMARY，而是**留着当面试的开场故事**，或放进 cover letter 第一段。

### C5 — Resume-A 短版（16 词）

> *MS in image processing (SJTU), IEEE VCIP paper, 5 patents — I came to systems from the modelling side.*

- **用途**：控制面那份简历（原型 A）。那边的买家要的是状态机、幂等、编排，**"我不怕数学"对他们的决策权重接近 0**。给它 16 个词封顶，或者直接砍掉、把字数还给 MQ 状态机那条。

### C6 — 反对意见直取版 ✕ 不要用

> *No, I haven't trained a foundation model — but the math isn't new to me: MS in image processing, IEEE VCIP paper, 5 patents.*

- **为什么不要**：在 SUMMARY 里把反对意见**说出口**，等于替筛选者把结论写好了。前 6 秒的扫读里，"haven't trained a foundation model" 这半句的留存率远高于后半句。**简历不是辩论场，反对意见在面试里处理，不在第一屏处理。**
- 保留它的价值：它提醒你这条线的边界在哪 —— 你能主张的上限是"数学熟"，不是"做过 ML"。

---

## 4. 插回去之后长什么样

推荐版（C3）插进 §7 的 v2 SUMMARY，完整效果：

```text
SUMMARY
I build control planes for machine-learning artifacts — the layer that knows which model,
which prompt, which dataset and which run produced a given result, and keeps that true under
multi-tenant production load. 11 years in distributed systems; the last 7 at Microsoft, where
I was tech lead of a self-built graph database, owned the provenance layer of Microsoft Purview
end to end, and most recently extended it to models, prompts, agents and evaluation runs on the
company's AI platform. I write storage internals and reconciliation loops, not just services on
top of them. I came into infrastructure from the modelling side — MS in image processing at SJTU,
an IEEE VCIP paper on online video-object classification, 5 patents — so the ML layer is new
vocabulary to me, not a new discipline.
```

**读起来的四拍**（这也是检验任何改写的方法：能不能数出四拍）：

| 拍 | 句子 | 回答的问题 |
|---|---|---|
| 1 | control planes for ML artifacts | 你是干什么的 |
| 2 | 11 years / 7 at Microsoft / 三个工件 | 凭什么信你 |
| 3 | storage internals, not services on top | 你有多深 |
| 4 | **from the modelling side → new vocabulary, not a new discipline** | 你跨得过来吗 |

第 4 拍以前是空的 —— 而它恰好是招聘方对你**唯一真正的顾虑**。这就是这半句的全部价值：它不增加新事实，它占住了一个本来没人回答的位置。

字数：95 → 118 词。**118 是上限，别再加了。**

---

## 5. 五条写法规则（下次自己改也能用）

1. **形容词换名词。** `strong mathematical foundation` / `solid background in math` —— 这类自评在 staff 简历里净效应为负（谁都这么写，且无法核实）。换成能被追问的名词：论文标题、专利领域、期刊名。**可被追问 = 可信。**
2. **让关键词由事实自己携带。** 你不用写 `machine learning`，写论文标题 "online video-object classification"，`classification` 自己就在里面，而且它有出处。
3. **时间方向必须是"我从那边来"，不是"我学过"。** "学过"是履历，"从那边来"是身份。身份决定读者怎么读你上面所有的 bullet。
4. **一处事实、两种用途才允许重复。** SUMMARY 下结论（"数学是旧技能"），HONORS 供核实（论文全名、专利领域）。两处都写结论 = 浪费；两处都写事实 = 浪费。
5. **不要指望它救 ATS。** 这半句里 0 个算力关键词，`GPU / CUDA / PyTorch / inference` 一个都不会因为它出现。§4.1 的窟窿只有 #422 的推理网关能补。**这半句是给人读的第二关（recruiter / HM）准备的，不是给第一关准备的。**

---

## 6. 写完之后你必须能答的三个追问

**硬规则：如果第 1 问你答不上来，就退回 C1，把论文留在 HONORS 里。** 把一篇自己讲不清的论文提到 SUMMARY，是这次改写唯一可能把事情变糟的方式。

### 追问 1：能用两分钟讲讲 similarity network fusion 吗？

答题骨架（不是背诵，是结构）：

1. **问题**：一个视频对象有多路特征 —— 颜色、纹理、运动 —— 每一路单独看都不够稳。
2. **做法**：每一路特征各建一张"样本之间的相似度图"；让这几张图**互相扩散、反复归一化**，直到收敛成一张融合图；分类在融合图上做（近邻 / 标签传播）。
3. **"online" 难在哪**：新样本来了不能整张图重算，要增量更新并控制住误差累积 —— 这是论文真正的贡献点。
4. **一句现代对照**（很加分）：*"Today you'd describe it as multi-view graph fusion over embeddings."* —— 这句话证明你不是在背 2015 年的记忆，你知道它在今天的坐标系里在哪。

### 追问 2：这和你现在做的事有什么关系？

这是**送分题**，答案就是你的脊椎：

> 那时候我在图上做统计，现在我给图做存储和血缘。**高扇出的超级节点在两边是同一个问题** —— 当年是它让相似度传播不收敛，现在是它让邻接布局塌掉。

答完这句，面试官会顺势问图数据库 —— 而那条你是能扛的（§11）。**这半句真正的战术价值在这里：它是一个把话题引向你最强工件的钩子。**

### 追问 3：那你为什么没继续做 ML？

诚实版，别绕：

> 2015 年那个时间点，国内的落点是工程，我选了分布式系统。这七年我一直在做模型周边的控制面 —— 血缘、catalog、评估 —— 现在是把两头接上。

**不要说**"我一直对 ML 很有热情"。热情不可核实，且和你七年里 0 个 ML 工件的事实相冲突。**"我选了另一条路，现在要把它接回来"是一个成年人的答案，而且它是真的。**

### recency 对冲

追问 3 的下一句很可能是"所以你上一次碰数学是十年前？"。对冲方式不在 SUMMARY 里，在**版面布局**上：确保 PROJECTS 的第一条（#422 的推理网关：batching 策略、p99、吞吐）和这半句**在同一屏内可见**。一个 2015 年的论文 + 一个 2026 年的负载测试报告，中间那十年的空白就被两端夹住了。

---

## 7. 三件要你核实的事

这半句的最终措辞取决于三个我不知道的事实：

| # | 要确认的 | 如果是 | 措辞怎么改 |
|---|---|---|---|
| 1 | VCIP 那篇是不是**一作**？ | 是 | 写 `a first-author IEEE VCIP paper`。加 2 个词，可信度提升一档 —— 二作在国内语境下常被默认为"导师带的"，一作值得说明 |
| | | 否 | 保持现写法，不提作者序，也不要暗示 |
| 2 | 5 项专利是**已授权**还是含申请中？是否前两位发明人？ | 全部已授权 | `5 granted patents`（HONORS 里补一句 `(CN)`） |
| | | 含申请中 | 写 `5 patent filings (N granted)`。**这一条会被背景调查查到**，不要用模糊计数省事 |
| 3 | 论文有没有可公开的 DOI / IEEE Xplore 链接？ | 有 | HONORS 那行给论文标题加超链接。零成本，且把"可核实"这件事做到底 |

---

## 8. 一行交付

把 §7 v2 简历里的这一行：

```text
MS from SJTU (image processing); 5 patents.
```

替换为：

```text
I came into infrastructure from the modelling side — MS in image processing at SJTU, an IEEE VCIP
paper on online video-object classification, 5 patents — so the ML layer is new vocabulary to me,
not a new discipline.
```

HONORS 那行保持不动（它负责可核实），除非 §7 的三条核实结果要求改。Resume-A 用 §3 的 C5 短版或直接删。
