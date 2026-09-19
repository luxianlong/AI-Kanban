import itertools
import tracemalloc

from svclog.aggregate import bucketize
from svclog.models import Entry


def infinite_entries(step: float = 0.1):
    """⚠️ 故意用浮点累加 —— 这本身就是一个真实的坑，见下面的测试。"""
    ts = 0.0
    while True:
        yield Entry(ts=ts, endpoint="/chat", status=200, ms=10.0)
        ts += step


def exact_entries(step_ms: int = 100):
    """✅ 用整数毫秒计数，最后才转成秒。没有累积误差。"""
    i = 0
    while True:
        yield Entry(ts=i * step_ms / 1000, endpoint="/chat", status=200, ms=10.0)
        i += 1


def test_bucketize_is_lazy_on_infinite_input():
    got = list(itertools.islice(bucketize(exact_entries(), width=1.0), 2))
    assert len(got) == 2
    assert [k for k, _ in got] == [0.0, 1.0]
    assert len(got[0][1]) == 10


def test_float_accumulation_shifts_bucket_boundaries():
    """浮点累加会让边界处的条目落错桶 —— 真实日志处理里的经典 bug。"""
    naive = list(itertools.islice(bucketize(infinite_entries(), width=1.0), 2))
    exact = list(itertools.islice(bucketize(exact_entries(), width=1.0), 2))
    assert len(naive[0][1]) == 11        # ← 多了一条！0.1 累加 10 次 < 1.0
    assert len(exact[0][1]) == 10        # ← 正确
    ts = 0.0
    for _ in range(10):
        ts += 0.1
    assert ts != 1.0 and ts < 1.0
    print(f"\n  0.1 累加 10 次 = {ts!r}，比 1.0 小 {1.0 - ts:.2e}")


def test_bucketize_memory_is_bounded_by_bucket_size():
    entries = (Entry(ts=i * 0.001, endpoint="/chat", status=200, ms=1.0)
               for i in range(500_000))
    tracemalloc.start()
    tracemalloc.reset_peak()
    n_buckets = sum(1 for _ in bucketize(entries, width=1.0))
    peak = tracemalloc.get_traced_memory()[1]
    tracemalloc.stop()
    print(f"\n  50 万条 → {n_buckets} 个桶，峰值内存 {peak/2**10:.1f} KiB")
    assert peak < 5 * 1024 * 1024, f"峰值 {peak/2**20:.1f} MiB，实现可能不是流式的"
