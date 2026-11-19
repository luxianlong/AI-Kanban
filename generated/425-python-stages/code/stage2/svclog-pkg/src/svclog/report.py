"""输出适配层。三种渲染器满足同一个 Protocol —— 这就是 Java 的面向接口编程。"""

from __future__ import annotations

import csv
import json
import sys
from collections.abc import Iterable, Iterator, Sequence
from typing import Protocol

from svclog.models import Stat

COLUMNS: tuple[str, ...] = ("endpoint", "count", "qps", "err%", "p50", "p95", "p99", "max")


class Renderer(Protocol):
    """实现方不需要继承它，甚至不需要 import 它 —— 结构对得上就行。"""

    def render(self, stats: Sequence[Stat]) -> None: ...


def _rows(stats: Iterable[Stat]) -> Iterator[tuple[str, ...]]:
    for s in stats:
        yield (
            s.endpoint,
            f"{s.count}",
            f"{s.qps:.1f}",
            f"{s.error_rate:.1%}",
            f"{s.pct(0.50):.0f}",
            f"{s.pct(0.95):.0f}",
            f"{s.pct(0.99):.0f}",
            f"{s.worst:.0f}",
        )


class TableRenderer:
    def render(self, stats: Sequence[Stat]) -> None:
        rows = [COLUMNS, *_rows(stats)]
        widths = [max(len(r[i]) for r in rows) for i in range(len(COLUMNS))]
        for n, row in enumerate(rows):
            print("  ".join(
                cell.ljust(w) if i == 0 else cell.rjust(w)
                for i, (cell, w) in enumerate(zip(row, widths, strict=True))
            ))
            if n == 0:
                print("  ".join("-" * w for w in widths))


class CsvRenderer:
    def render(self, stats: Sequence[Stat]) -> None:
        writer = csv.writer(sys.stdout)
        writer.writerow(COLUMNS)
        writer.writerows(_rows(stats))


class JsonRenderer:
    def render(self, stats: Sequence[Stat]) -> None:
        payload = [dict(zip(COLUMNS, row, strict=True)) for row in _rows(stats)]
        json.dump(payload, sys.stdout, ensure_ascii=False, indent=2)
        print()


RENDERERS: dict[str, Renderer] = {
    "table": TableRenderer(),
    "csv": CsvRenderer(),
    "json": JsonRenderer(),
}
