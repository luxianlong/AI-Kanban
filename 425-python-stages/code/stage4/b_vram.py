#!/usr/bin/env python3
"""阶段四 4.2 —— 显存口算器。不需要 GPU 就能跑，但能救你很多次 OOM。

三块显存，缺一不可：
    权重   = 参数量 × 每参数字节数                    （静态，开机就占）
    KV 缓存 = 2 × layers × kv_heads × head_dim × 字节 × 序列长度 × 并发数
                                                      （★ 随并发线性增长）
    其它   = 激活 + 框架开销 + 碎片                   （经验值：留 10~15%）

面试高频题「7B 模型 bf16 要多少显存」的完整答案是：
    权重 14 GiB，但**能服务多少并发**由 KV 缓存决定 —— 这才是重点。
"""
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
