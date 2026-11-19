"""程序入口。只做参数解析和装配，逻辑一行都不放在这里。"""

from __future__ import annotations

import argparse
from pathlib import Path

from svclog.aggregate import aggregate, rank, status_filter
from svclog.reader import open_log
from svclog.report import RENDERERS


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="svclog", description="访问日志时延报表")
    p.add_argument("path", type=Path, help="CSV 或 JSONL，字段：ts,endpoint,status,ms")
    p.add_argument("--status", default="all", help="all|2xx|4xx|5xx|err")
    p.add_argument("--min-count", type=int, default=1, help="样本数低于此值的 endpoint 丢弃")
    p.add_argument("--top", type=int, default=0, help="只看最慢的 N 个")
    p.add_argument("--by", type=float, default=0.95, help="排序用的分位数")
    p.add_argument("--format", choices=sorted(RENDERERS), default="table")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    keep = status_filter(args.status)
    entries = (e for e in open_log(args.path) if keep(e))     # 惰性过滤
    stats = rank(aggregate(entries).values(), by=args.by,
                 min_count=args.min_count, top=args.top)
    if not stats:
        print("没有满足条件的数据")
        return 1
    RENDERERS[args.format].render(stats)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
