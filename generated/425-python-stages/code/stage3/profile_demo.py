"""阶段三 3.4 —— 先剖析，再优化。四个工具，各解决一个问题。

心法：Python 比 Java 慢 30-100 倍是**正常的**。解决办法不是把 Python 循环
写得更漂亮，而是判断"这段热点该不该由 Python 来跑"。
"""
from __future__ import annotations

import cProfile
import io
import pstats
import random
import timeit

random.seed(0)
DATA = [random.gauss(500, 200) for _ in range(200_000)]


# ── 一段有意写慢的代码：三处问题，你能一眼看出几处？ ──────────────────
def p95_slow(values: list[float]) -> float:
    ordered = sorted(values)                      # 每次调用都重排一次
    idx = int(len(ordered) * 0.95)
    return ordered[idx]


def summarize_slow(values: list[float]) -> dict[str, float]:
    return {
        "mean": sum(values) / len(values),
        "p50": p95_slow(values) * 0,              # 装傻：又排一次
        "p95": p95_slow(values),                  # 再排一次
        "p99": sorted(values)[int(len(values) * 0.99)],   # 第四次
    }


def summarize_fast(values: list[float]) -> dict[str, float]:
    ordered = sorted(values)                      # 只排一次，复用
    n = len(ordered)
    return {
        "mean": sum(ordered) / n,
        "p50": ordered[n // 2],
        "p95": ordered[int(n * 0.95)],
        "p99": ordered[int(n * 0.99)],
    }


# ── 1. cProfile：函数级，先定位"时间花在谁身上" ──────────────────────
print("① cProfile —— 谁吃掉了时间")
buf = io.StringIO()
cProfile.run("summarize_slow(DATA)", "/tmp/prof.out")
stats = pstats.Stats("/tmp/prof.out", stream=buf).sort_stats("cumulative")
stats.print_stats(6)
print("\n".join(buf.getvalue().splitlines()[3:13]))

# ── 2. timeit：微基准，别用 time.time() 手测 ────────────────────────
print("\n② timeit —— 改完到底快了多少")
slow = timeit.timeit(lambda: summarize_slow(DATA), number=5) / 5
fast = timeit.timeit(lambda: summarize_fast(DATA), number=5) / 5
print(f"  summarize_slow  {slow*1000:7.1f} ms")
print(f"  summarize_fast  {fast*1000:7.1f} ms   快 {slow/fast:.1f}×（只是别重复排序）")

# ── 3. 再往下一层：同一件事交给 NumPy ───────────────────────────────
try:
    import numpy as np

    arr = np.asarray(DATA)
    npy = timeit.timeit(lambda: np.percentile(arr, [50, 95, 99]), number=5) / 5
    print(f"  numpy.percentile{npy*1000:7.1f} ms   比 fast 再快 {fast/npy:.1f}×")
    print("  → 这就是那条界线：算法层面能省几倍，换成向量化能省一个数量级。")
except ImportError:
    pass

# ── 4. tracemalloc：内存去哪了 ──────────────────────────────────────
print("\n③ tracemalloc —— 内存去哪了")
import tracemalloc

tracemalloc.start()
snap_before = tracemalloc.take_snapshot()
waste = [list(DATA) for _ in range(5)]            # 故意复制 5 份
snap_after = tracemalloc.take_snapshot()
for stat in snap_after.compare_to(snap_before, "lineno")[:2]:
    print(f"  {stat}")
tracemalloc.stop()
del waste

print("""
④ 线上进程怎么办（不能改代码、不能重启）
   py-spy dump   --pid 1234        # 立刻打出所有线程的栈：卡在哪一行
   py-spy top    --pid 1234        # 像 top 一样实时看函数耗时
   py-spy record --pid 1234 -o f.svg -d 30   # 30 秒火焰图
   → 这是 Python 版的 async-profiler，**不需要**提前埋点，也不会停进程。
     推理服务半夜时延飙升时，第一条命令就是 py-spy dump。""".rstrip())
