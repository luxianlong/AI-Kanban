"""阶段四 4.1 —— 张量思维。五件必须形成肌肉记忆的事。

张量 = 多维数组 + dtype + shape + 内存布局。
你不需要会推导反向传播，但必须能一眼看出 shape 对不对、显存够不够。
"""
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

# 一句话把显存直觉建起来
seq = 8192
print(f"\n   若 seq={seq}, heads={H}, batch={B}, fp16：注意力矩阵本身就要 "
      f"{B*H*seq*seq*2/2**30:.1f} GiB —— FlashAttention 解决的就是这块。")
