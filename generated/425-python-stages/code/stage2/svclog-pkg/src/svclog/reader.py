"""数据入口。全部返回生成器 —— 100 GB 日志也只占 O(1) 内存。"""

from __future__ import annotations

import csv
import json
import sys
from collections.abc import Iterator
from pathlib import Path

from svclog.models import Entry


def read_csv(path: Path) -> Iterator[Entry]:
    """逐行惰性产出。坏行只告警不中断 —— 日志工具必须能容忍脏数据。"""
    with path.open(newline="", encoding="utf-8") as handle:
        for lineno, row in enumerate(csv.DictReader(handle), start=2):
            try:
                yield Entry.from_row(row)
            except (KeyError, ValueError) as exc:
                print(f"{path}:{lineno}: 跳过坏行 ({exc})", file=sys.stderr)


def read_jsonl(path: Path) -> Iterator[Entry]:
    """同一个 Protocol 的另一种实现 —— 调用方一行都不用改。"""
    with path.open(encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                yield Entry.from_row(json.loads(line))
            except (KeyError, ValueError, TypeError) as exc:
                print(f"{path}:{lineno}: 跳过坏行 ({exc})", file=sys.stderr)


def open_log(path: Path) -> Iterator[Entry]:
    """按后缀分派。≈ 工厂方法。"""
    return read_jsonl(path) if path.suffix in {".jsonl", ".ndjson"} else read_csv(path)
