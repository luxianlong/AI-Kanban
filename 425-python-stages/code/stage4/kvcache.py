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
