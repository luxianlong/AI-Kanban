#!/usr/bin/env python3
"""svclog —— 推理服务访问日志聚合器（阶段一综合练习）。

把一份 CSV 访问日志按 endpoint 聚合，算出 QPS、错误率、时延分位数，
按 P95 倒序打印成一张对齐的表格。这是你在 Java 里写过无数次的那类小工具。

用法：
    python svclog.py access.csv
    python svclog.py access.csv --top 3 --min-count 5
    python svclog.py access.csv --format csv > report.csv
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
from collections import defaultdict
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field

# ── 领域模型 ────────────────────────────────────────────────────────────
# Java 里这是一个 record + 一个带 Builder 的统计类；Python 里两个 dataclass。


@dataclass(frozen=True, slots=True)
class Entry:
    """一行日志。frozen=True ≈ Java record，slots=True 省内存。"""

    ts: float
    endpoint: str
    status: int
    ms: float

    @classmethod
    def from_row(cls, row: dict[str, str]) -> Entry:
        """工厂方法。Python 只有一个 __init__，重载靠 @classmethod。"""
        return cls(
            ts=float(row["ts"]),
            endpoint=row["endpoint"].strip(),
            status=int(row["status"]),
            ms=float(row["ms"]),
        )


@dataclass(slots=True)
class Stat:
    """一个 endpoint 的聚合结果。"""

    endpoint: str
    latencies: list[float] = field(default_factory=list)  # 可变默认值必须用 factory
    errors: int = 0
    first_ts: float = math.inf
    last_ts: float = -math.inf

    def add(self, e: Entry) -> None:
        self.latencies.append(e.ms)
        self.errors += e.status >= 400  # bool 是 int 的子类，可以直接加
        self.first_ts = min(self.first_ts, e.ts)
        self.last_ts = max(self.last_ts, e.ts)

    @property  # ≈ Java 的 getter，但调用方写 s.count 不写 s.getCount()
    def count(self) -> int:
        return len(self.latencies)

    @property
    def error_rate(self) -> float:
        return self.errors / self.count if self.count else 0.0

    @property
    def qps(self) -> float:
        span = self.last_ts - self.first_ts
        return self.count / span if span > 0 else float(self.count)

    def pct(self, q: float) -> float:
        """最近邻插值分位数（够用，不引 numpy）。"""
        if not self.latencies:
            return 0.0
        ordered = sorted(self.latencies)
        idx = min(len(ordered) - 1, max(0, math.ceil(q * len(ordered)) - 1))
        return ordered[idx]


# ── 读取：生成器，100 GB 日志也只占 O(1) 内存 ───────────────────────────
def read_entries(path: str) -> Iterator[Entry]:
    """惰性逐行产出 Entry —— Java 的 Files.lines() + map()。"""
    with open(path, newline="", encoding="utf-8") as f:  # with ≈ try-with-resources
        for lineno, row in enumerate(csv.DictReader(f), start=2):
            try:
                yield Entry.from_row(row)
            except (KeyError, ValueError) as e:
                print(f"skip line {lineno}: {e}", file=sys.stderr)


def aggregate(entries: Iterable[Entry]) -> dict[str, Stat]:
    """≈ stream().collect(groupingBy(Entry::endpoint, ...))。"""
    stats: dict[str, Stat] = defaultdict(lambda: Stat(""))
    for e in entries:
        s = stats[e.endpoint]
        if not s.endpoint:
            s.endpoint = e.endpoint
        s.add(e)
    return dict(stats)


# ── 输出 ────────────────────────────────────────────────────────────────
COLUMNS = ("endpoint", "count", "qps", "err%", "p50", "p95", "p99", "max")


def rows_of(stats: Iterable[Stat]) -> Iterator[tuple[str, ...]]:
    for s in stats:
        yield (
            s.endpoint,
            f"{s.count}",
            f"{s.qps:.1f}",
            f"{s.error_rate:.1%}",
            f"{s.pct(0.50):.0f}",
            f"{s.pct(0.95):.0f}",
            f"{s.pct(0.99):.0f}",
            f"{max(s.latencies):.0f}",
        )


def print_table(stats: list[Stat]) -> None:
    rows = [COLUMNS, *rows_of(stats)]
    widths = [max(len(r[i]) for r in rows) for i in range(len(COLUMNS))]
    for n, row in enumerate(rows):
        line = "  ".join(
            cell.ljust(w) if i == 0 else cell.rjust(w)
            for i, (cell, w) in enumerate(zip(row, widths))
        )
        print(line)
        if n == 0:
            print("  ".join("-" * w for w in widths))


def print_csv(stats: list[Stat]) -> None:
    w = csv.writer(sys.stdout)
    w.writerow(COLUMNS)
    w.writerows(rows_of(stats))


# ── 入口 ────────────────────────────────────────────────────────────────
def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("path", help="CSV，表头需含 ts,endpoint,status,ms")
    p.add_argument("--top", type=int, default=0, help="只看 P95 最高的 N 个")
    p.add_argument("--min-count", type=int, default=1, help="样本数低于此值的丢弃")
    p.add_argument("--format", choices=("table", "csv"), default="table")
    args = p.parse_args(argv)

    stats = aggregate(read_entries(args.path))
    picked = [s for s in stats.values() if s.count >= args.min_count]
    picked.sort(key=lambda s: s.pct(0.95), reverse=True)  # ≈ Comparator.reversed()
    if args.top:
        picked = picked[: args.top]

    if not picked:
        print("没有满足条件的数据", file=sys.stderr)
        return 1
    (print_csv if args.format == "csv" else print_table)(picked)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
