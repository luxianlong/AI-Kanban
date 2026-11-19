"""领域对象的测试。注意：函数名以 test_ 开头即可，不需要 @Test。"""

import math

import pytest

from svclog.models import Entry, Stat, percentile


def test_entry_is_immutable():
    e = Entry(ts=1.0, endpoint="/a", status=200, ms=5.0)
    with pytest.raises(AttributeError):        # ≈ assertThrows
        e.status = 500                          # type: ignore[misc]


def test_from_row_strips_and_converts():
    e = Entry.from_row({"ts": "1.5", "endpoint": "  /a  ", "status": "429", "ms": "3"})
    assert (e.ts, e.endpoint, e.status, e.ms) == (1.5, "/a", 429, 3.0)
    assert e.failed is True


@pytest.mark.parametrize(                       # ≈ @ParameterizedTest + @CsvSource
    ("raw", "exc"),
    [
        ({"ts": "x", "endpoint": "/a", "status": "200", "ms": "1"}, ValueError),
        ({"ts": "1", "endpoint": "/a", "status": "200"}, KeyError),
    ],
)
def test_from_row_rejects_bad_input(raw, exc):
    with pytest.raises(exc):
        Entry.from_row(raw)


@pytest.mark.parametrize(
    ("q", "expected"),
    [(0.0, 1.0), (0.5, 5.5), (0.95, 9.55), (1.0, 10.0)],
)
def test_percentile_matches_numpy_convention(q, expected):
    """线性插值口径应与 numpy.percentile 一致。"""
    assert percentile([float(i) for i in range(1, 11)], q) == pytest.approx(expected)


def test_percentile_edge_cases():
    assert percentile([], 0.5) == 0.0
    assert percentile([42.0], 0.99) == 42.0


def test_stat_accumulates(entries):
    s = Stat("/chat")
    for e in entries[:3]:
        s.add(e)
    assert s.count == 3
    assert s.errors == 1
    assert s.error_rate == pytest.approx(1 / 3)
    assert s.worst == 1200.0
    assert s.qps == pytest.approx(3 / 1.0)      # 3 条落在 1 秒的跨度里


def test_empty_stat_does_not_divide_by_zero():
    s = Stat("/x")
    assert s.error_rate == 0.0 and s.qps == 0.0 and s.worst == 0.0
    assert math.isinf(s.first_ts)
