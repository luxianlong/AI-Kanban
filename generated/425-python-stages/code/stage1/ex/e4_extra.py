"""练习 1.4 参考答案 —— 给 svclog 加三个能力，只贴改动的部分。

1) --status 4xx/5xx/all 过滤
2) --bucket N 按 N 秒分桶，打印每桶 QPS 与 P95（看时间趋势）
3) 用线性插值分位数替换最近邻，并对比两者差异
"""
from __future__ import annotations

import math
from collections.abc import Iterable, Iterator


# ── 1) 状态码过滤器：一个返回谓词的工厂函数 ──────────────────────────
def status_filter(spec: str):
    """spec ∈ {'all','2xx','4xx','5xx','err'} → 一个 (Entry)->bool 的谓词。"""
    table = {
        "all": lambda s: True,
        "2xx": lambda s: 200 <= s < 300,
        "4xx": lambda s: 400 <= s < 500,
        "5xx": lambda s: s >= 500,
        "err": lambda s: s >= 400,
    }
    try:
        pred = table[spec]
    except KeyError:
        raise ValueError(f"unknown --status {spec!r}, pick one of {sorted(table)}") from None
    return lambda e: pred(e.status)


# ── 2) 时间分桶：生成器 → 生成器，内存恒定 ───────────────────────────
def bucketize(entries: Iterable, width: float) -> Iterator[tuple[int, list]]:
    """按 width 秒把连续的条目切成桶。输入需按 ts 递增（日志天然如此）。"""
    bucket: list = []
    key: int | None = None
    for e in entries:
        k = int(e.ts // width)
        if key is None:
            key = k
        elif k != key:
            yield key, bucket
            key, bucket = k, []
        bucket.append(e)
    if bucket and key is not None:
        yield key, bucket


# ── 3) 线性插值分位数（numpy / Excel 的默认口径）────────────────────
def pct_nearest(xs: list[float], q: float) -> float:
    """最近邻：svclog.py 里的实现。样本少时会明显偏高。"""
    if not xs:
        return 0.0
    o = sorted(xs)
    i = min(len(o) - 1, max(0, math.ceil(q * len(o)) - 1))
    return o[i]


def pct_linear(xs: list[float], q: float) -> float:
    """线性插值：位置 = q*(n-1)，在相邻两点间按小数部分插值。"""
    if not xs:
        return 0.0
    o = sorted(xs)
    if len(o) == 1:
        return o[0]
    pos = q * (len(o) - 1)
    lo = math.floor(pos)
    hi = min(lo + 1, len(o) - 1)
    return o[lo] + (o[hi] - o[lo]) * (pos - lo)


if __name__ == "__main__":
    import numpy as np  # 只用来验证我们的实现和业界口径一致

    for n in (10, 100, 1000):
        xs = [float(i) for i in range(1, n + 1)]
        for q in (0.5, 0.95, 0.99):
            mine, ref = pct_linear(xs, q), float(np.percentile(xs, q * 100))
            near = pct_nearest(xs, q)
            flag = "OK " if abs(mine - ref) < 1e-9 else "FAIL"
            print(f"n={n:<5} q={q:<5} linear={mine:8.3f} numpy={ref:8.3f} {flag}"
                  f"  nearest={near:8.3f}  差={near - mine:+.3f}")
