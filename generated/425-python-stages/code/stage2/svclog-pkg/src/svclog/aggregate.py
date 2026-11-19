"""业务逻辑。输入是任意 Entry 可迭代对象，不关心它从哪来。"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Iterator

from svclog.models import Entry, Stat

Predicate = Callable[[Entry], bool]

_STATUS_RULES: dict[str, Predicate] = {
    "all": lambda e: True,
    "2xx": lambda e: 200 <= e.status < 300,
    "4xx": lambda e: 400 <= e.status < 500,
    "5xx": lambda e: e.status >= 500,
    "err": lambda e: e.failed,
}


def status_filter(spec: str) -> Predicate:
    try:
        return _STATUS_RULES[spec]
    except KeyError:
        raise ValueError(f"未知的 --status {spec!r}，可选：{sorted(_STATUS_RULES)}") from None


def aggregate(entries: Iterable[Entry]) -> dict[str, Stat]:
    """≈ stream().collect(groupingBy(Entry::endpoint))。"""
    stats: dict[str, Stat] = {}
    for entry in entries:
        stats.setdefault(entry.endpoint, Stat(entry.endpoint)).add(entry)
    return stats


def bucketize(entries: Iterable[Entry], width: float) -> Iterator[tuple[float, list[Entry]]]:
    """按 width 秒切桶。生成器进、生成器出，内存恒定。"""
    bucket: list[Entry] = []
    key: float | None = None
    for entry in entries:
        current = entry.ts // width * width
        if key is None:
            key = current
        elif current != key:
            yield key, bucket
            key, bucket = current, []
        bucket.append(entry)
    if key is not None and bucket:
        yield key, bucket


def rank(stats: Iterable[Stat], *, by: float = 0.95, min_count: int = 1,
         top: int = 0) -> list[Stat]:
    """按某个分位数倒序，过滤样本过少的，可选只取前 N。"""
    picked = [s for s in stats if s.count >= min_count]
    picked.sort(key=lambda s: s.pct(by), reverse=True)
    return picked[:top] if top else picked
