"""读取 → 聚合 → 渲染 的集成测试。"""

import pytest

from svclog.aggregate import aggregate, bucketize, rank, status_filter
from svclog.cli import main
from svclog.reader import open_log


def test_read_csv_roundtrip(csv_file, entries):
    assert list(open_log(csv_file)) == entries      # frozen dataclass 的 __eq__ 是值相等


def test_reader_skips_bad_lines(tmp_path, capsys):
    p = tmp_path / "dirty.csv"
    p.write_text("ts,endpoint,status,ms\n1,/a,200,5\nBAD,/a,200,5\n3,/b,500,9\n")
    got = list(open_log(p))
    assert [e.endpoint for e in got] == ["/a", "/b"]     # 坏行被跳过，不中断
    assert "跳过坏行" in capsys.readouterr().err          # 但要有告警


def test_reader_is_lazy(csv_file):
    """生成器必须是惰性的 —— 拿到对象时不该读任何数据。"""
    import inspect

    gen = open_log(csv_file)
    assert inspect.getgeneratorstate(gen) == "GEN_CREATED"
    next(gen)
    assert inspect.getgeneratorstate(gen) == "GEN_SUSPENDED"


def test_aggregate_groups_by_endpoint(entries):
    stats = aggregate(entries)
    assert set(stats) == {"/chat", "/healthz"}
    assert stats["/chat"].count == 3
    assert stats["/healthz"].errors == 0


@pytest.mark.parametrize(
    ("spec", "expected"), [("all", 5), ("2xx", 4), ("5xx", 1), ("err", 1), ("4xx", 0)]
)
def test_status_filter(entries, spec, expected):
    keep = status_filter(spec)
    assert sum(1 for e in entries if keep(e)) == expected


def test_status_filter_rejects_unknown():
    with pytest.raises(ValueError, match="未知的 --status"):
        status_filter("7xx")


def test_bucketize_splits_by_time(entries):
    buckets = list(bucketize(entries, width=1.0))
    assert [(k, len(v)) for k, v in buckets] == [(100.0, 2), (101.0, 2), (102.0, 1)]


def test_rank_sorts_by_p95_desc(entries):
    ordered = rank(aggregate(entries).values())
    assert [s.endpoint for s in ordered] == ["/chat", "/healthz"]


def test_rank_honours_min_count_and_top(entries):
    assert rank(aggregate(entries).values(), min_count=3) == [
        s for s in aggregate(entries).values() if s.endpoint == "/chat"
    ]
    assert len(rank(aggregate(entries).values(), top=1)) == 1


def test_cli_table_output(csv_file, capsys):
    assert main([str(csv_file)]) == 0
    out = capsys.readouterr().out
    assert "endpoint" in out and "/chat" in out


def test_cli_json_output(csv_file, capsys):
    import json

    assert main([str(csv_file), "--format", "json"]) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload[0]["endpoint"] == "/chat"


def test_cli_returns_1_when_nothing_matches(csv_file, capsys):
    assert main([str(csv_file), "--min-count", "999"]) == 1
    assert "没有满足条件的数据" in capsys.readouterr().out


def test_cli_does_not_load_whole_file(csv_file, monkeypatch):
    """monkeypatch ≈ Mockito：证明我们走的是惰性路径，没有 read() 整个文件。"""
    import pathlib

    original = pathlib.Path.read_text
    calls = []

    def spy(self, *a, **k):
        calls.append(self)
        return original(self, *a, **k)

    monkeypatch.setattr(pathlib.Path, "read_text", spy)
    main([str(csv_file)])
    assert calls == []
