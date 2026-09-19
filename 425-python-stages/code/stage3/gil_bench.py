"""阶段三 3.3 —— 用一个可复现的实验，把 GIL 给你的直觉校准过来。

结论先行（这三条推翻了你全部的 Java 并发直觉）：
    1. CPU 密集的**纯 Python** 代码：多线程不会加速（GIL），多进程才会。
    2. IO 密集的代码：多线程/asyncio 大幅加速（等待时 GIL 是释放的）。
    3. 数值计算：交给 NumPy/PyTorch —— 它们在算的时候**释放 GIL**，
       这才是 AI Infra 的正解，而不是纠结开几个线程。

脚本会先探测这台机器**实际可用**的并行度再跑，所以在容器里跑出来的数字也是诚实的。
"""
from __future__ import annotations

import os
import sys
import time
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor

N = 10_000_000
WORKERS = 4


def cpu_task(n: int = N) -> int:
    """纯 Python 的 CPU 密集活儿：一个热循环。"""
    total = 0
    for i in range(n):
        total += i * i % 7
    return total


def io_task(seconds: float = 0.25) -> float:
    """IO 密集的活儿：等待。sleep 期间 GIL 被释放。"""
    time.sleep(seconds)
    return seconds


def timeit(fn) -> float:
    t0 = time.perf_counter()
    fn()
    return time.perf_counter() - t0


def row(label: str, dt: float, baseline: float | None = None) -> None:
    tail = "" if baseline is None else f"   加速比 {baseline / dt:5.2f}×"
    print(f"  {label:<36} {dt:6.2f}s{tail}")


def probe_parallelism() -> float:
    """先量一把：这台机器真的能同时跑几个进程？

    os.cpu_count() 在容器里会撒谎 —— 它报的是宿主机核数，
    而 cgroup 的 cpu.max 配额才是你真正拿到的。所以直接实测。
    """
    one = timeit(lambda: list(ProcessPoolExecutor(1).map(cpu_task, [N // 4])))
    two = timeit(lambda: list(ProcessPoolExecutor(2).map(cpu_task, [N // 4] * 2)))
    return 2 * one / two          # 理想 2.0（真两核），1.0 表示只有一核可用


def main() -> None:
    print(f"机器：os.cpu_count()={os.cpu_count()} · Python {sys.version.split()[0]}")
    eff = probe_parallelism()
    print(f"实测有效并行度：{eff:.2f}（2.0=真有两核可并行，1.0=被限流到单核）\n")

    print(f"① CPU 密集：{WORKERS} 份 {N:,} 次的纯 Python 循环")
    serial = timeit(lambda: [cpu_task() for _ in range(WORKERS)])
    row("串行 for 循环", serial)
    row(f"ThreadPool({WORKERS}) ← 你的 Java 直觉",
        timeit(lambda: list(ThreadPoolExecutor(WORKERS).map(cpu_task, [N] * WORKERS))), serial)
    row(f"ProcessPool({WORKERS}) ← 正确答案",
        timeit(lambda: list(ProcessPoolExecutor(WORKERS).map(cpu_task, [N] * WORKERS))), serial)
    print("  → 线程池的加速比 ≈ 1.00（甚至 <1）：GIL 让它们排队，还多付了切换成本。")
    print(f"  → 进程池的加速比上限 = 实测并行度 {eff:.2f}，再减去进程启动与序列化开销。\n")

    print(f"② IO 密集：{WORKERS} 次 0.25s 的等待")
    serial_io = timeit(lambda: [io_task() for _ in range(WORKERS)])
    row("串行", serial_io)
    row(f"ThreadPool({WORKERS})",
        timeit(lambda: list(ThreadPoolExecutor(WORKERS).map(io_task, [0.25] * WORKERS))), serial_io)
    print("  → 同样是线程，这里就有效：等待时 GIL 被释放了。\n")

    print("③ 同一笔计算交给 NumPy（底层 C，算的时候释放 GIL）")
    try:
        import numpy as np
    except ImportError:
        print("  （未装 numpy，跳过）")
        return
    dt = timeit(lambda: int(((np.arange(N, dtype=np.int64) ** 2) % 7).sum()))
    checksum = int(((np.arange(N, dtype=np.int64) ** 2) % 7).sum())
    row("NumPy 向量化（一份，不是四份）", dt)
    print(f"  → 比一份纯 Python 循环快 {serial / WORKERS / dt:.1f}×，"
          f"结果一致（{checksum} == {cpu_task(N)}）。")
    print("  → 记住这个比例：优化 Python 循环最多快几倍，换成向量化是一个数量级。")


if __name__ == "__main__":
    main()
