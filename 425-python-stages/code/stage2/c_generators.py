"""阶段二 2.3 —— 生成器：Python 相对 Java 的真实增益，也是数据管道的地基。"""
import itertools, sys, tracemalloc
from collections.abc import Iterator, Iterable

# ── 1. yield 让函数变成惰性流 ────────────────────────────────────────
def read_batches(lines: Iterable[str], size: int = 3) -> Iterator[list[str]]:
    batch: list[str] = []
    for line in lines:
        batch.append(line.strip())
        if len(batch) == size:
            yield batch                      # 交出一批，函数在这里"暂停"
            batch = []
    if batch:                                # 别忘了最后不足一批的尾巴
        yield batch

print("1.", list(read_batches([f"l{i}" for i in range(8)])))

# ── 2. 生成器是"暂停的函数"，可以亲眼看到它暂停 ──────────────────────
def noisy() -> Iterator[int]:
    print("   [gen] 开始")
    for i in range(3):
        print(f"   [gen] 准备产出 {i}")
        yield i
    print("   [gen] 结束")

g = noisy()
print("2. 创建了生成器，注意上面什么都没打印:", type(g).__name__)
print("2. next →", next(g))
print("2. next →", next(g))
print("2. 剩下的:", list(g))

# ── 3. 管道组合：每一级都是生成器，全流程内存 O(1) ───────────────────
def parse(lines: Iterable[str]) -> Iterator[dict]:
    for ln in lines:
        ep, ms = ln.split()
        yield {"endpoint": ep, "ms": float(ms)}

def slow_only(rows: Iterable[dict], threshold: float) -> Iterator[dict]:
    yield from (r for r in rows if r["ms"] > threshold)     # yield from = 转发

raw = ["/chat 812", "/healthz 1.2", "/chat 1340", "/embed 45", "/chat 3"]
pipeline = slow_only(parse(raw), 100)         # 到这里一行都还没读
print("3.", list(pipeline))

# ── 4. 内存实测：list 推导 vs 生成器 ─────────────────────────────────
N = 2_000_000
tracemalloc.start()
eager = [i * i for i in range(N)]
peak_eager = tracemalloc.get_traced_memory()[1]
del eager; tracemalloc.reset_peak()
lazy = (i * i for i in range(N))
total = sum(lazy)
peak_lazy = tracemalloc.get_traced_memory()[1]
tracemalloc.stop()
print(f"4. N={N:,}   list 推导峰值 = {peak_eager/2**20:7.1f} MiB"
      f"   |   生成器峰值 = {peak_lazy/2**10:.2f} KiB"
      f"   |   相差 {peak_eager/max(peak_lazy,1):,.0f} 倍")

# ── 5. 生成器只能消费一次 —— Java Stream 同理，但更容易忘 ────────────
g2 = (i for i in range(3))
print("5. 第一次", list(g2), "第二次", list(g2), "← 空了")

# ── 6. itertools：AI 数据管道天天用的六个 ────────────────────────────
stream = (i for i in itertools.count())                  # 无限流！
print("6. islice ", list(itertools.islice(stream, 5)))    # ≈ .limit(5)
print("6. batched", [list(b) for b in itertools.batched(range(7), 3)])  # 3.12+
print("6. chain  ", list(itertools.chain([1, 2], [3], [4, 5])))         # ≈ Stream.concat
print("6. tee    ", [list(t) for t in itertools.tee([1, 2, 3], 2)])     # 一分为二
print("6. pairwise", list(itertools.pairwise([1, 2, 3, 4])))            # 相邻两两
print("6. cycle  ", list(itertools.islice(itertools.cycle("AB"), 5)))   # 轮询调度

# ── 7. 生成器可以接收数据（很少用，但 asyncio 的底层就是它）──────────
def accumulator() -> Iterator[float]:
    total = 0.0
    while True:
        x = yield total                      # yield 既产出也接收
        total += x

acc = accumulator(); next(acc)               # 先"预热"到第一个 yield
print("7.", acc.send(10), acc.send(5), acc.send(0.5))

# ── 8. 生成器要清理资源？用 try/finally，close() 会触发它 ────────────
def with_cleanup() -> Iterator[int]:
    try:
        yield 1
        yield 2
    finally:
        print("   [gen] 清理执行了")

g3 = with_cleanup(); print("8. 取了", next(g3)); g3.close()

print("\n口诀：方括号 [..] 立刻算完吃内存；圆括号 (..) 惰性省内存。"
      "\n      处理几百万行日志时，这个括号就是 OOM 与不 OOM 的区别。")
