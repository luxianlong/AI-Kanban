"""共享夹具。conftest.py 里的 fixture 自动对同目录及子目录的测试可见 —— 不用 import。"""

import pytest

from svclog.models import Entry


@pytest.fixture
def entries() -> list[Entry]:
    """一份小而确定的数据集。故意让 /chat 有一条 500。"""
    return [
        Entry(ts=100.0, endpoint="/chat", status=200, ms=800.0),
        Entry(ts=100.5, endpoint="/chat", status=200, ms=1200.0),
        Entry(ts=101.0, endpoint="/chat", status=500, ms=15.0),
        Entry(ts=101.2, endpoint="/healthz", status=200, ms=1.0),
        Entry(ts=102.0, endpoint="/healthz", status=200, ms=2.0),
    ]


@pytest.fixture
def csv_file(tmp_path, entries):
    """tmp_path 是 pytest 内置夹具：每个测试一个干净的临时目录，自动清理。"""
    p = tmp_path / "access.csv"
    lines = ["ts,endpoint,status,ms"]
    lines += [f"{e.ts},{e.endpoint},{e.status},{e.ms}" for e in entries]
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p
