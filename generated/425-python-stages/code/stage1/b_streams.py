"""阶段一 1.3 —— Java Stream API 的逐条 Python 对照，可运行。"""
from collections import Counter, defaultdict, deque
from dataclasses import dataclass
import functools, itertools, sys

@dataclass
class Req:
    endpoint: str
    status: int
    ms: float

reqs = [
    Req("/v1/chat", 200, 812.0), Req("/v1/chat", 200, 1340.5),
    Req("/v1/chat", 429, 3.1),   Req("/healthz", 200, 1.2),
    Req("/v1/embed", 200, 44.8), Req("/v1/embed", 500, 120.0),
    Req("/healthz", 200, 0.9),   Req("/v1/chat", 200, 655.0),
]

# .map(f)                     → 列表推导
print("map      ", [r.ms for r in reqs][:4])
# .filter(p)                  → 带 if 的推导
print("filter   ", [r.endpoint for r in reqs if r.status >= 400])
# .map().filter()             → 一行搞定，不用链式
print("map+filter", [round(r.ms) for r in reqs if r.ms > 100])
# .collect(toSet())
print("toSet    ", sorted({r.endpoint for r in reqs}))
# .collect(toMap(...))        → 字典推导
print("toMap    ", {r.endpoint: r.status for r in reqs})
# .anyMatch / .allMatch / .noneMatch
print("match    ", any(r.status >= 500 for r in reqs), all(r.ms > 0 for r in reqs))
# .count()
print("count    ", sum(1 for r in reqs if r.status == 200))
# .sorted(Comparator.comparing(...).reversed())
print("sorted   ", [r.endpoint for r in sorted(reqs, key=lambda r: r.ms, reverse=True)][:3])
# .min / .max
print("max      ", max(reqs, key=lambda r: r.ms).ms)
# .reduce
print("reduce   ", functools.reduce(lambda a, r: a + r.ms, reqs, 0.0))
#   ↑ 但 Python 里更地道的是直接 sum(...)
print("sum      ", sum(r.ms for r in reqs))
# .flatMap                    → 双重推导（先外后内，顺序和 Java 反直觉）
tags = [["gpu", "a100"], ["cpu"], ["gpu", "h100"]]
print("flatMap  ", [t for group in tags for t in group])
# .distinct()
print("distinct ", list(dict.fromkeys(t for g in tags for t in g)))  # 保序去重
# .limit(n) —— 对惰性流用 islice
print("limit    ", list(itertools.islice((r.ms for r in reqs), 3)))

# .collect(groupingBy(...))  → 两种写法
by_ep: dict[str, list[Req]] = defaultdict(list)
for r in reqs:
    by_ep[r.endpoint].append(r)
print("groupBy  ", {k: len(v) for k, v in by_ep.items()})

# itertools.groupby 是"相邻分组"，必须先排序 —— Java 人最容易踩的一个坑
key = lambda r: r.endpoint
print("groupby  ", {k: len(list(g)) for k, g in itertools.groupby(sorted(reqs, key=key), key)})
# ❌ 不排序的结果是错的：
print("groupby❌", {k: len(list(g)) for k, g in itertools.groupby(reqs, key)})

# .collect(counting()) → Counter
print("counting ", Counter(r.endpoint for r in reqs).most_common(2))

# ── 惰性：方括号 vs 圆括号，就是 OOM 与不 OOM 的区别 ──────────────
eager = [r.ms ** 2 for r in reqs]          # list，立刻算完、全在内存
lazy  = (r.ms ** 2 for r in reqs)          # generator，一次一个
print("eager    ", type(eager).__name__, sys.getsizeof(eager), "bytes")
print("lazy     ", type(lazy).__name__, sys.getsizeof(lazy), "bytes")
print("lazy sum ", sum(lazy))              # 生成器只能消费一次
print("再消费一次", sum(lazy))              # → 0.0，已耗尽！Java Stream 同理

# ArrayDeque → collections.deque
window: deque[float] = deque(maxlen=3)     # 定长滑动窗口，自动挤掉旧的
for r in reqs:
    window.append(r.ms)
print("deque    ", list(window))
