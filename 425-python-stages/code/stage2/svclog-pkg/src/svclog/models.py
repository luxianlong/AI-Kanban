"""领域对象。frozen dataclass ≈ Java record。"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Self


@dataclass(frozen=True, slots=True)
class Entry:
    """一条访问记录。frozen 让它可以安全地跨线程/进程传递。"""

    ts: float
    endpoint: str
    status: int
    ms: float

    @property
    def failed(self) -> bool:
        return self.status >= 400

    @classmethod
    def from_row(cls, row: dict[str, str]) -> Self:
        """从 CSV 的一行构造。Self 让子类继承时返回类型也跟着变（3.11+）。"""
        return cls(
            ts=float(row["ts"]),
            endpoint=row["endpoint"].strip(),
            status=int(row["status"]),
            ms=float(row["ms"]),
        )


def percentile(values: list[float], q: float) -> float:
    """线性插值分位数，口径与 numpy.percentile 一致。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    pos = q * (len(ordered) - 1)
    lo = math.floor(pos)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


@dataclass(slots=True)
class Stat:
    """一个 endpoint 的聚合结果。可变，因为它是累加器。"""

    endpoint: str
    latencies: list[float] = field(default_factory=list)
    errors: int = 0
    first_ts: float = math.inf
    last_ts: float = -math.inf

    def add(self, entry: Entry) -> None:
        self.latencies.append(entry.ms)
        self.errors += int(entry.failed)
        self.first_ts = min(self.first_ts, entry.ts)
        self.last_ts = max(self.last_ts, entry.ts)

    @property
    def count(self) -> int:
        return len(self.latencies)

    @property
    def error_rate(self) -> float:
        return self.errors / self.count if self.count else 0.0

    @property
    def qps(self) -> float:
        span = self.last_ts - self.first_ts
        return self.count / span if span > 0 else float(self.count)

    @property
    def worst(self) -> float:
        return max(self.latencies, default=0.0)

    def pct(self, q: float) -> float:
        return percentile(self.latencies, q)
