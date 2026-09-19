{"slug":"py-stage3-engineering",
 "title":"阶段三：把工程素养接回来（6 小时，含全部可运行代码）",
 "summary":"pytest 夹具与参数化、uv 锁依赖与 CUDA 镜像、GIL 的自校准基准测试、asyncio 的取消与限流、cProfile/py-spy 剖析。含 28 个真实通过的测试用例。",
 "eyebrow":"阶段三 / 5 · Stage 3",
 "h1":"把工程素养接回来",
 "sub":"你的 Java 并发直觉在这里会失效三次。这 6 小时用可复现的基准测试把它校准过来 —— 顺便把测试、打包、剖析这套你本来就熟的东西换成 Python 的做法。",
 "facts":["<b>6 小时</b>","代码 <b>全部可运行</b>","练习 <b>5 个</b>","产出：<b>28 个测试 + 可打镜像的包</b>"],
 "footer":"看板任务 #425「Python学习」· 本页是<a href=\"/p/python-ai-infra-40h.html\">《40 小时学习路线》(#422)</a> 阶段三的实操展开版。所有代码与输出均在 Python 3.12.14 上实测；基准测试跑在一台 cgroup 限流到约 1 个有效核的 2 vCPU 容器上（脚本会自己量出这一点）。"}
---FM---

:::goal 这一页结束时，你应该能做到
- 用 pytest 的 fixture / parametrize / monkeypatch 写出比 JUnit 更短的测试
- 用 `pyproject.toml` + `uv.lock` + 分层 Dockerfile 打出一个能复现的 CUDA 镜像
- **说清 GIL 的边界**：什么时候用 `asyncio`、什么时候用多进程、什么时候什么都不用（交给 NumPy）
- 知道在容器里 `os.cpu_count()` 会骗你，以及怎么实测真实并行度
- 用 `cProfile` / `timeit` / `tracemalloc` / `py-spy` 定位瓶颈，而不是凭感觉优化
:::

---

## 1. pytest：比 JUnit 短一半，且更强

| JUnit 5 | pytest | 差别 |
|---|---|---|
| `@Test` | 函数名以 `test_` 开头 | 不需要注解 |
| `assertEquals(a, b)` | **裸 `assert a == b`** | 失败时自动展开差异 |
| `assertThrows(E.class, ...)` | `with pytest.raises(E):` | |
| `@BeforeEach` | `@pytest.fixture` | **依赖注入**，不是钩子 |
| `@ParameterizedTest` + `@CsvSource` | `@pytest.mark.parametrize` | 一行 |
| Mockito | `monkeypatch` fixture | 内置，不用装 |
| `@TempDir` | `tmp_path` fixture | 内置 |
| 捕获 stdout 要自己重定向 | `capsys` fixture | 内置 |
| `@Disabled` | `@pytest.mark.skipif` | |

### 裸 assert 是什么体验

```python
def test_shows_what_pytest_gives_you_for_free():
    stats = aggregate([Entry(1.0, "/chat", 200, 800.0), Entry(2.0, "/chat", 500, 15.0)])
    # 裸 assert —— 失败时 pytest 会自动把两边展开给你看
    assert {k: v.count for k, v in stats.items()} == {"/chat": 3}
```

:::out 失败时的输出（pytest 会重写 assert 的字节码来做这件事）
```text
=================================== FAILURES ===================================
__________________ test_shows_what_pytest_gives_you_for_free ___________________

    def test_shows_what_pytest_gives_you_for_free():
        stats = aggregate([Entry(1.0, "/chat", 200, 800.0), Entry(2.0, "/chat", 500, 15.0)])
        # 裸 assert —— 失败时 pytest 会自动把两边展开给你看
>       assert {k: v.count for k, v in stats.items()} == {"/chat": 3}
E       AssertionError: assert {'/chat': 2} == {'/chat': 3}
E
E         Differing items:
E         {'/chat': 2} != {'/chat': 3}
E         Use -v to get more diff

/tmp/demo_fail.py:7: AssertionError
```
:::

你不需要 `assertEquals`、不需要 Hamcrest、不需要 AssertJ —— **pytest 重写了 assert 的字节码**，所以它能拿到两边的值并打印差异。

### fixture 比 `@BeforeEach` 强在哪

`@BeforeEach` 是"每个测试前跑一遍"的钩子。fixture 是**依赖注入**：测试函数声明参数名，pytest 按名字注入，还能控制生命周期。

```python
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
    """tmp_path 是 pytest 内置夹具：每个测试一个干净的临时目录，自动清理。

    注意 csv_file 依赖 entries —— fixture 可以依赖 fixture，pytest 自动按图求值。
    JUnit 的 @BeforeEach 做不到这件事。
    """
    p = tmp_path / "access.csv"
    lines = ["ts,endpoint,status,ms"]
    lines += [f"{e.ts},{e.endpoint},{e.status},{e.ms}" for e in entries]
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p
```

:::note fixture 的三个 JUnit 没有的能力
1. **fixture 可以依赖 fixture** —— `csv_file(tmp_path, entries)`，pytest 自动解析依赖图。JUnit 里你得手写调用链。
2. **`scope="session"` 让重资源只建一次** —— 加载一次模型给整个测试会话用：`@pytest.fixture(scope="session")`。这在 AI 项目里能把测试时间从 20 分钟压到 2 分钟。
3. **`yield` 型 fixture 自带清理** —— `yield` 之前是 setup，之后是 teardown，写在一个函数里：
   ```python
   @pytest.fixture
   def gpu_session():
       sess = allocate_gpu()
       yield sess              # 测试在这里跑
       sess.release()          # 不管测试成功失败都会执行
   ```
:::

### 领域对象的测试

```python
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
```

### 流水线的集成测试

这一组里有三个测试值得单独看，它们测的是**性质**而不是返回值。

```python
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
    """★ 测性质：生成器必须是惰性的 —— 拿到对象时不该读任何数据。"""
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
    """★ monkeypatch ≈ Mockito：证明我们走的是惰性路径，没有 read() 整个文件。"""
    import pathlib

    original = pathlib.Path.read_text
    calls = []

    def spy(self, *a, **k):
        calls.append(self)
        return original(self, *a, **k)

    monkeypatch.setattr(pathlib.Path, "read_text", spy)
    main([str(csv_file)])
    assert calls == []
```

:::out `uv run pytest`
```text
............................                                             [100%]
28 passed in 0.06s
```
:::

:::note 那三个"测性质"的用例
- **`test_reader_is_lazy`** —— 用 `inspect.getgeneratorstate` 断言生成器还没开始跑。有人哪天把 `read_csv` 改成返回 list，这个测试立刻红。**性能契约是可以写成测试的。**
- **`test_cli_does_not_load_whole_file`** —— 用 `monkeypatch` 装一个 spy 到 `Path.read_text` 上，断言它一次都没被调用。这是"我们不会把 100 GB 日志读进内存"这个承诺的**可执行版本**。
- **`test_from_row_rejects_bad_input`** —— 参数化把两个用例压成一个函数。JUnit 要写 `@ParameterizedTest` + `@MethodSource` + 一个静态工厂方法。

Java 人最容易带过来的坏习惯：**为每个 getter 写一个测试**。别这么干 —— dataclass 生成的东西不需要你测，测**你自己写的逻辑**和**你承诺的性质**。
:::

---

## 2. 打包：pyproject + lock + 分层镜像

```toml
[project]
name = "svclog"
version = "0.2.0"
requires-python = ">=3.12"
dependencies = ["httpx>=0.27"]        # 运行时依赖

[dependency-groups]                    # 开发依赖，不会被下游装到
dev = ["pytest>=8", "ruff>=0.7", "mypy>=1.13"]

[project.scripts]
svclog = "svclog.cli:main"             # 装完包直接敲 `svclog`

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"
```

| Maven 概念 | Python 对应 | 注意 |
|---|---|---|
| `<dependencies>` | `[project] dependencies` | 声明**范围**，不是精确版本 |
| `<scope>test</scope>` | `[dependency-groups] dev` | |
| `dependency-lock` | `uv.lock` | **必须提交进 git** |
| `mvn -o` 离线复现 | `uv sync --frozen` | |
| `jar` | `wheel`（`.whl`） | 预编译产物 |
| 源码包 | `sdist`（`.tar.gz`） | |
| `mvn install` | `uv pip install -e .` | |

:::warn wheel 和 CUDA：AI 依赖为什么"换台机器就装不上"
普通 Java jar 是平台无关的字节码。AI 的 wheel **不是** —— `torch` 的 wheel 里打包了编译好的 CUDA 二进制，所以它同时绑死了：

**Python 版本**（cp312）× **操作系统/glibc**（manylinux_2_28）× **CPU 架构**（x86_64 / aarch64）× **CUDA 版本**（cu124 / cu126）

四个维度任何一个对不上，就是那句经典的 `No matching distribution found`。

三条应对：
1. **锁死 `uv.lock` 并提交** —— 这是唯一能复现的东西
2. **镜像基础层固定 tag**，不要用 `:latest`
3. **PyTorch 装的时候指定 index**：`uv add torch --index https://download.pytorch.org/whl/cu124`
:::

### Dockerfile：为什么从 CUDA 镜像起

```dockerfile
# ── 为什么从 CUDA 镜像起，而不是 python:slim ──────────────────────────
# 接真 vLLM 时需要 CUDA 运行时和驱动兼容的用户态库。自己往 python:slim 里装
# 是一条不归路。只跑 CPU 的话可以换成 python:3.12-slim，其余照抄。
FROM nvidia/cuda:12.4.1-runtime-ubuntu22.04 AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    HF_HOME=/models          # ← 权重放挂载卷，**绝不**打进镜像

COPY --from=ghcr.io/astral-sh/uv:0.5 /uv /usr/local/bin/uv
RUN apt-get update && apt-get install -y --no-install-recommends python3.12 curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ★ 依赖层和代码层分开 COPY：改一行代码不会让依赖重新装一遍。
#   这和你在 Java 里先 COPY pom.xml 再 mvn dependency:go-offline 是同一个技巧。
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src/ src/
RUN uv sync --frozen --no-dev

EXPOSE 8000

# liveness 用 /healthz（只证明进程活着）。
# ⚠️ 不要在这里用 /readyz：模型加载要几分钟，会被判定失败然后无限重启。
HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
    CMD curl -fsS http://localhost:8000/healthz || exit 1

# 单进程 + 多副本，不要用 --workers：
# 每个 worker 是独立进程，会各自去抢显存，也不共享准入控制的计数器。
# GPU 服务的正确扩展方式是"一卡一进程，前面放负载均衡"。
CMD ["uv", "run", "uvicorn", "llmgw.app:app", "--host", "0.0.0.0", "--port", "8000"]
```

:::warn 最后那条注释是 Java 人最容易踩的
在 Spring Boot 里你会很自然地想"开 4 个 worker 吃满 CPU"。`uvicorn --workers 4` 看起来是同一件事 —— **但它是四个独立进程**：

- 四个进程会各自加载一份模型 → 显存 ×4 → 直接 OOM
- 进程内的限流计数器、缓存、连接池**互不可见** → 你的"最大并发 8"实际变成了 32
- Prometheus 指标每个进程一份，`/metrics` 每次刮到的是随机一个进程的数据

GPU 服务的正确形态是：**一张卡一个进程，横向靠副本数扩展**。
:::

---

## 3. GIL：最需要重装大脑的一节 ★★

**GIL（全局解释器锁）**：CPython 同一时刻只允许一个线程执行 Python 字节码。

后果只有一句话，但它推翻了你全部的 Java 并发直觉：**多线程无法加速纯 Python 的 CPU 计算。**

不要背这句话，把它量出来。下面这个脚本会先**探测你这台机器真实可用的并行度**，再跑三组对比 —— 所以它在容器里给出的数字也是诚实的。

```python
"""阶段三 3.3 —— 用一个可复现的实验，把 GIL 给你的直觉校准过来。

结论先行（这三条推翻了你全部的 Java 并发直觉）：
    1. CPU 密集的**纯 Python** 代码：多线程不会加速（GIL），多进程才会。
    2. IO 密集的代码：多线程/asyncio 大幅加速（等待时 GIL 是释放的）。
    3. 数值计算：交给 NumPy/PyTorch —— 它们在算的时候**释放 GIL**，
       这才是 AI Infra 的正解，而不是纠结开几个线程。

脚本会先探测这台机器**实际可用**的并行度再跑，所以在容器里跑出来的数字也是诚实的。
"""
from __future__ import annotations

import os
import sys
import time
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor

N = 10_000_000
WORKERS = 4


def cpu_task(n: int = N) -> int:
    """纯 Python 的 CPU 密集活儿：一个热循环。"""
    total = 0
    for i in range(n):
        total += i * i % 7
    return total


def io_task(seconds: float = 0.25) -> float:
    """IO 密集的活儿：等待。sleep 期间 GIL 被释放。"""
    time.sleep(seconds)
    return seconds


def timeit(fn) -> float:
    t0 = time.perf_counter()
    fn()
    return time.perf_counter() - t0


def row(label: str, dt: float, baseline: float | None = None) -> None:
    tail = "" if baseline is None else f"   加速比 {baseline / dt:5.2f}×"
    print(f"  {label:<36} {dt:6.2f}s{tail}")


def probe_parallelism() -> float:
    """先量一把：这台机器真的能同时跑几个进程？

    os.cpu_count() 在容器里会撒谎 —— 它报的是宿主机核数，
    而 cgroup 的 cpu.max 配额才是你真正拿到的。所以直接实测。
    """
    one = timeit(lambda: list(ProcessPoolExecutor(1).map(cpu_task, [N // 4])))
    two = timeit(lambda: list(ProcessPoolExecutor(2).map(cpu_task, [N // 4] * 2)))
    return 2 * one / two          # 理想 2.0（真两核），1.0 表示只有一核可用


def main() -> None:
    print(f"机器：os.cpu_count()={os.cpu_count()} · Python {sys.version.split()[0]}")
    eff = probe_parallelism()
    print(f"实测有效并行度：{eff:.2f}（2.0=真有两核可并行，1.0=被限流到单核）\n")

    print(f"① CPU 密集：{WORKERS} 份 {N:,} 次的纯 Python 循环")
    serial = timeit(lambda: [cpu_task() for _ in range(WORKERS)])
    row("串行 for 循环", serial)
    row(f"ThreadPool({WORKERS}) ← 你的 Java 直觉",
        timeit(lambda: list(ThreadPoolExecutor(WORKERS).map(cpu_task, [N] * WORKERS))), serial)
    row(f"ProcessPool({WORKERS}) ← 正确答案",
        timeit(lambda: list(ProcessPoolExecutor(WORKERS).map(cpu_task, [N] * WORKERS))), serial)
    print("  → 线程池的加速比 ≈ 1.00（甚至 <1）：GIL 让它们排队，还多付了切换成本。")
    print(f"  → 进程池的加速比上限 = 实测并行度 {eff:.2f}，再减去进程启动与序列化开销。\n")

    print(f"② IO 密集：{WORKERS} 次 0.25s 的等待")
    serial_io = timeit(lambda: [io_task() for _ in range(WORKERS)])
    row("串行", serial_io)
    row(f"ThreadPool({WORKERS})",
        timeit(lambda: list(ThreadPoolExecutor(WORKERS).map(io_task, [0.25] * WORKERS))), serial_io)
    print("  → 同样是线程，这里就有效：等待时 GIL 被释放了。\n")

    print("③ 同一笔计算交给 NumPy（底层 C，算的时候释放 GIL）")
    try:
        import numpy as np
    except ImportError:
        print("  （未装 numpy，跳过）")
        return
    dt = timeit(lambda: int(((np.arange(N, dtype=np.int64) ** 2) % 7).sum()))
    checksum = int(((np.arange(N, dtype=np.int64) ** 2) % 7).sum())
    row("NumPy 向量化（一份，不是四份）", dt)
    print(f"  → 比一份纯 Python 循环快 {serial / WORKERS / dt:.1f}×，"
          f"结果一致（{checksum} == {cpu_task(N)}）。")
    print("  → 记住这个比例：优化 Python 循环最多快几倍，换成向量化是一个数量级。")


if __name__ == "__main__":
    main()
```

:::out 在一台 2 vCPU 但被 cgroup 限流的容器上跑出来
```text
机器：os.cpu_count()=2 · Python 3.12.14
实测有效并行度：1.10（2.0=真有两核可并行，1.0=被限流到单核）

① CPU 密集：4 份 10,000,000 次的纯 Python 循环
  串行 for 循环                              2.65s
  ThreadPool(4) ← 你的 Java 直觉             2.57s   加速比  1.03×
  ProcessPool(4) ← 正确答案                  2.33s   加速比  1.14×
  → 线程池的加速比 ≈ 1.00（甚至 <1）：GIL 让它们排队，还多付了切换成本。
  → 进程池的加速比上限 = 实测并行度 1.10，再减去进程启动与序列化开销。

② IO 密集：4 次 0.25s 的等待
  串行                                     1.00s
  ThreadPool(4)                          0.25s   加速比  3.99×
  → 同样是线程，这里就有效：等待时 GIL 被释放了。

③ 同一笔计算交给 NumPy（底层 C，算的时候释放 GIL）
  NumPy 向量化（一份，不是四份）                     0.05s
  → 比一份纯 Python 循环快 12.3×，结果一致（19999999 == 19999999）。
  → 记住这个比例：优化 Python 循环最多快几倍，换成向量化是一个数量级。
```
:::

:::warn 这份输出里最值钱的一行是「实测有效并行度：1.10」
`os.cpu_count()` 报 2，实测只有 1.10。**因为 `os.cpu_count()` 读的是宿主机的核数，而 cgroup 的 `cpu.max` 配额才是你真正拿到的份额。**

这不是玩具环境的怪癖，是**每一个 K8s Pod 的日常**。它导致三类真实事故：

1. **进程池开太多** —— `ProcessPoolExecutor(os.cpu_count())` 在一个限了 1 核的 Pod 里开 64 个进程，全在抢同一份 CPU 配额，比串行还慢，内存还爆了
2. **PyTorch 线程数错配** —— `torch.set_num_threads()` 默认也读 `cpu_count()`，OpenMP 线程互相抢配额，CPU 推理性能反而下降
3. **容量规划全错** —— 你按 "8 核" 做的压测结论，在实际只有 2 核配额的 Pod 上完全不成立

正确做法：读 cgroup 配额，或者直接实测。
```python
def effective_cpus() -> float:
    """cgroup v2 的真实配额。K8s 里这才是你能用的核数。"""
    try:
        quota, period = open("/sys/fs/cgroup/cpu.max").read().split()
        if quota != "max":
            return int(quota) / int(period)
    except (OSError, ValueError):
        pass
    return os.cpu_count() or 1
```
:::

| 场景 | 该用什么 | Java 类比 |
|---|---|---|
| IO 密集（HTTP / DB / 文件） | **`asyncio`**（首选）或 `threading` | Netty / Reactor / WebFlux |
| CPU 密集（纯 Python 计算） | `ProcessPoolExecutor` | 多进程，靠序列化传数据 |
| CPU 密集（数值计算） | **NumPy / PyTorch** | 把热点下沉到原生代码 |
| 高吞吐 Web 服务（无 GPU） | `asyncio` + 多 worker 进程 | 多实例 + 负载均衡 |
| **GPU 推理服务** | `asyncio` 单进程 + **多副本** | 见上面的 Dockerfile 警告 |

:::note 关于 free-threaded Python（PEP 703）
3.13 起有实验性的无 GIL 构建，3.14 起正式支持。方向是对的，但到 2026 年 9 月，**AI 生态的 CUDA 编译型轮子对它的支持仍不整齐**。

态度：了解它存在、知道它会改变什么，但**不要把它作为架构前提**。你现在写的代码仍然应该假设 GIL 存在。
:::

---

## 4. asyncio：单线程事件循环

心智模型接近 `CompletableFuture` / Reactor，但**是单线程的**。这个差别会以一种很贵的方式教育你。

```python
"""阶段三 3.3b —— asyncio：心智模型 ≈ Reactor / CompletableFuture，但**单线程**。

重点演示第 2 节：在 async 函数里做阻塞调用，会把整个事件循环卡死。
这是 Java 人转 Python 写服务时最常见、也最贵的一次事故。
"""
from __future__ import annotations

import asyncio
import time


async def fetch(name: str, delay: float) -> str:
    """模拟一次网络调用。await 会把控制权交还事件循环。"""
    await asyncio.sleep(delay)
    return f"{name}({delay}s)"


# ── 1. gather：并发发起，不是并行计算 ────────────────────────────────
async def demo_gather() -> None:
    t0 = time.perf_counter()
    results = await asyncio.gather(fetch("a", 0.3), fetch("b", 0.3), fetch("c", 0.3))
    print(f"1. gather  {results}  耗时 {time.perf_counter()-t0:.2f}s（不是 0.9s）")


# ── 2. ★ 阻塞调用毒死事件循环 ────────────────────────────────────────
def blocking_call(seconds: float) -> str:
    """任何同步库都长这样：requests、psycopg2(同步)、time.sleep、model.generate()。"""
    time.sleep(seconds)                     # ← 注意：不是 await asyncio.sleep
    return "blocked"


async def demo_blocking() -> None:
    async def heartbeat(stop: asyncio.Event) -> int:
        """每 50ms 跳一次。事件循环健康的话，1 秒该跳 ~20 次。"""
        beats = 0
        while not stop.is_set():
            await asyncio.sleep(0.05)
            beats += 1
        return beats

    for label, work in (
        ("❌ 直接调用阻塞函数", lambda: blocking_call(1.0)),
        ("✅ asyncio.to_thread 包一层", None),
    ):
        stop = asyncio.Event()
        hb = asyncio.create_task(heartbeat(stop))
        await asyncio.sleep(0.05)
        t0 = time.perf_counter()
        if work is None:
            await asyncio.to_thread(blocking_call, 1.0)   # 丢到线程池，循环不受影响
        else:
            work()                                        # 循环在这 1 秒里彻底停摆
        dt = time.perf_counter() - t0
        stop.set()
        beats = await hb
        print(f"2. {label:<24} 耗时{dt:.2f}s  这期间心跳只跳了 {beats:>2} 次"
              f"（健康值 ~20）")


# ── 3. 超时与取消：客户端断开时必须真的停下游 ────────────────────────
async def demo_cancel() -> None:
    cleaned = []

    async def long_generation() -> str:
        try:
            await asyncio.sleep(10)          # 假装在 GPU 上生成 2000 个 token
            return "done"
        except asyncio.CancelledError:
            cleaned.append("释放了 KV cache")   # ← 推理网关里这行值很多钱
            raise

    try:
        async with asyncio.timeout(0.2):     # 3.11+，比 wait_for 好用
            await long_generation()
    except TimeoutError:
        print(f"3. 超时被取消，清理动作执行了：{cleaned}")


# ── 4. 限流：Semaphore ≈ Java 的舱壁 / 连接池上限 ────────────────────
async def demo_semaphore() -> None:
    sem = asyncio.Semaphore(2)               # 同时最多 2 个在跑
    running, peak = 0, 0

    async def guarded(i: int) -> None:
        nonlocal running, peak
        async with sem:
            running += 1
            peak = max(peak, running)
            await asyncio.sleep(0.1)
            running -= 1

    t0 = time.perf_counter()
    await asyncio.gather(*(guarded(i) for i in range(6)))
    print(f"4. 6 个任务 / 并发上限 2 → 实际峰值并发 {peak}，"
          f"耗时 {time.perf_counter()-t0:.2f}s（=3 批 × 0.1s）")


# ── 5. 常见错误：忘了 await，什么也不会发生 ─────────────────────────
async def demo_forgot_await() -> None:
    coro = fetch("x", 0.01)
    print(f"5. 忘了 await 的话拿到的是：{type(coro).__name__}，不是结果")
    print(f"5. await 之后才是：{await coro}")


async def main() -> None:
    await demo_gather()
    await demo_blocking()
    await demo_cancel()
    await demo_semaphore()
    await demo_forgot_await()


if __name__ == "__main__":
    asyncio.run(main())
```

:::out 实际输出
```text
1. gather  ['a(0.3s)', 'b(0.3s)', 'c(0.3s)']  耗时 0.30s（不是 0.9s）
2. ❌ 直接调用阻塞函数               耗时1.00s  这期间心跳只跳了  1 次（健康值 ~20）
2. ✅ asyncio.to_thread 包一层  耗时1.00s  这期间心跳只跳了 21 次（健康值 ~20）
3. 超时被取消，清理动作执行了：['释放了 KV cache']
4. 6 个任务 / 并发上限 2 → 实际峰值并发 2，耗时 0.30s（=3 批 × 0.1s）
5. 忘了 await 的话拿到的是：coroutine，不是结果
5. await 之后才是：x(0.01s)
```
:::

:::warn 「心跳只跳了 1 次」就是那次事故的全貌
两行代码耗时一样（1.00s），但第一行让整个事件循环在这一秒里**彻底停摆** —— 心跳从应有的 20 次掉到 1 次。

换成生产场景：你的推理网关有 200 个在途请求，某个 handler 里调了一次同步的 `requests.get()`。这 1 秒内：
- 所有 SSE 流式连接全部卡住（用户看到"打字机"停了）
- 健康检查 `/healthz` 也返回不了 → K8s 判定 liveness 失败 → **重启 Pod**
- 重启导致在途请求全丢，流量涌向其它副本 → 雪崩

**怎么避免**：
1. 用 async 客户端：`httpx.AsyncClient` 而不是 `requests`，`asyncpg` 而不是 `psycopg2`
2. 实在只有同步库：`await asyncio.to_thread(blocking_fn, ...)`
3. 开发期加护栏：`asyncio.run(main(), debug=True)` 会对超过 100ms 的同步阻塞打警告
:::

---

## 5. 剖析：先量，再改

```python
"""阶段三 3.4 —— 先剖析，再优化。四个工具，各解决一个问题。"""
from __future__ import annotations

import cProfile
import io
import pstats
import random
import timeit

random.seed(0)
DATA = [random.gauss(500, 200) for _ in range(200_000)]


# ── 一段有意写慢的代码：你能一眼看出问题吗？ ──────────────────────────
def p95_slow(values: list[float]) -> float:
    ordered = sorted(values)                      # 每次调用都重排一次
    idx = int(len(ordered) * 0.95)
    return ordered[idx]


def summarize_slow(values: list[float]) -> dict[str, float]:
    return {
        "mean": sum(values) / len(values),
        "p50": p95_slow(values) * 0,              # 装傻：又排一次
        "p95": p95_slow(values),                  # 再排一次
        "p99": sorted(values)[int(len(values) * 0.99)],   # 第三次
    }


def summarize_fast(values: list[float]) -> dict[str, float]:
    ordered = sorted(values)                      # 只排一次，复用
    n = len(ordered)
    return {
        "mean": sum(ordered) / n,
        "p50": ordered[n // 2],
        "p95": ordered[int(n * 0.95)],
        "p99": ordered[int(n * 0.99)],
    }


# ── 1. cProfile：函数级，先定位"时间花在谁身上" ──────────────────────
print("① cProfile —— 谁吃掉了时间")
buf = io.StringIO()
cProfile.run("summarize_slow(DATA)", "/tmp/prof.out")
stats = pstats.Stats("/tmp/prof.out", stream=buf).sort_stats("cumulative")
stats.print_stats(6)
print("\n".join(buf.getvalue().splitlines()[3:13]))

# ── 2. timeit：微基准，别用 time.time() 手测 ────────────────────────
print("\n② timeit —— 改完到底快了多少")
slow = timeit.timeit(lambda: summarize_slow(DATA), number=5) / 5
fast = timeit.timeit(lambda: summarize_fast(DATA), number=5) / 5
print(f"  summarize_slow  {slow*1000:7.1f} ms")
print(f"  summarize_fast  {fast*1000:7.1f} ms   快 {slow/fast:.1f}×（只是别重复排序）")

# ── 3. 再往下一层：同一件事交给 NumPy ───────────────────────────────
import numpy as np

arr = np.asarray(DATA)
npy = timeit.timeit(lambda: np.percentile(arr, [50, 95, 99]), number=5) / 5
print(f"  numpy.percentile{npy*1000:7.1f} ms   比 fast 再快 {fast/npy:.1f}×")
print("  → 这就是那条界线：算法层面能省几倍，换成向量化能省一个数量级。")

# ── 4. tracemalloc：内存去哪了 ──────────────────────────────────────
print("\n③ tracemalloc —— 内存去哪了")
import tracemalloc

tracemalloc.start()
snap_before = tracemalloc.take_snapshot()
waste = [list(DATA) for _ in range(5)]            # 故意复制 5 份
snap_after = tracemalloc.take_snapshot()
for stat in snap_after.compare_to(snap_before, "lineno")[:2]:
    print(f"  {stat}")
tracemalloc.stop()
del waste
```

:::out 实际输出
```text
① cProfile —— 谁吃掉了时间

   Ordered by: cumulative time
   List reduced from 8 to 6 due to restriction <6>

   ncalls  tottime  percall  cumtime  percall filename:lineno(function)
        1    0.000    0.000    0.099    0.099 {built-in method builtins.exec}
        1    0.000    0.000    0.099    0.099 <string>:1(<module>)
        1    0.001    0.001    0.099    0.099 profile_demo.py:25(summarize_slow)
        3    0.096    0.032    0.096    0.032 {built-in method builtins.sorted}
        2    0.000    0.000    0.065    0.033 profile_demo.py:19(p95_slow)

② timeit —— 改完到底快了多少
  summarize_slow     96.8 ms
  summarize_fast     32.9 ms   快 2.9×（只是别重复排序）
  numpy.percentile    5.7 ms   比 fast 再快 5.8×
  → 这就是那条界线：算法层面能省几倍，换成向量化能省一个数量级。

③ tracemalloc —— 内存去哪了
  profile_demo.py:77: size=7813 KiB (+7813 KiB), count=11 (+11), average=710 KiB
  .../python3.12/tracemalloc.py:560: size=312 B (+312 B), count=2 (+2), average=156 B
```
:::

:::note 怎么读 cProfile 那张表
- **`ncalls`** —— 调用次数。`sorted` 被调了 **3 次**，这就是问题：一次 `summarize` 排了三遍序。
- **`tottime`** —— 函数**自己**花的时间（不含它调用的子函数）。找热点看这一列。
- **`cumtime`** —— 累计时间（含子函数）。找"哪条调用链贵"看这一列。

排序方式决定你能看见什么：`sort_stats("tottime")` 找"最慢的那个函数"，`sort_stats("cumulative")` 找"最贵的那条路径"。**先看 cumulative 定位路径，再看 tottime 定位具体函数。**
:::

| 需求 | Java 的工具 | Python 的工具 |
|---|---|---|
| 采样剖析（**线上可用**） | async-profiler | **`py-spy`** ← 最重要的一个 |
| 函数级剖析 | JFR | `cProfile` + `snakeviz`（可视化） |
| 行级耗时 | — | `line_profiler`（`@profile` 装饰） |
| 微基准 | JMH | `timeit` / `pytest-benchmark` |
| 内存快照 | MAT / heap dump | `tracemalloc` / `memray` |
| 断点调试 | IDE debugger | `breakpoint()`（内置，直接进 pdb） |
| 压测 | JMeter / Gatling | `locust` / `k6` |

### py-spy：Python 版的 async-profiler

```bash
py-spy dump   --pid 1234              # 立刻打出所有线程的栈：卡在哪一行
py-spy top    --pid 1234              # 像 top 一样实时看函数耗时
py-spy record --pid 1234 -o f.svg -d 30   # 30 秒火焰图
py-spy dump   --pid 1234 --locals     # 连局部变量一起打
```

:::note 半夜告警时的第一条命令
推理服务时延突然飙升，你不能重启（会丢在途请求）、不能改代码（要发版）。

**`py-spy dump --pid <pid>`** —— 不需要提前埋点、不需要重启进程、不会停顿服务，直接告诉你每个线程此刻卡在哪一行。

在 K8s 里：`kubectl exec -it <pod> -- py-spy dump --pid 1`（需要镜像里预装 py-spy，且 Pod 有 `SYS_PTRACE` 能力 —— **这两件事要在部署时就准备好**，等出事再加就来不及了）。
:::

:::warn 核心心法
**Python 比 Java 慢 30–100 倍是正常的。**

解决方案不是把 Python 循环写得更漂亮，而是判断"这段热点该不该由 Python 来跑"。

判断标准极其简单：**如果你在对几百万个元素写 for 循环，那就是写错了。** 交给 NumPy / PyTorch / Polars / DuckDB，或者写个 Rust 扩展。

上面那组数字量化了这条界线：改算法（少排两次序）快 2.9×，换向量化快 17×。
:::

---

## 6. 练习

:::exercise 练习 1 · fixture 设计（30 分钟）
你在测一个需要加载 1.2 GB 模型的评分器。现在每个测试函数都自己 `load_model()`，跑一次测试套件要 18 分钟。

用 fixture 重新设计，要求：模型只加载一次，但**每个测试仍然拿到干净的评分器状态**（评分器内部有缓存，不能串）。
:::

:::solution
关键是把"昂贵且不可变的"和"廉价但有状态的"分成两个 fixture，给不同的 scope。

```python
import pytest


@pytest.fixture(scope="session")          # ★ 整个测试会话只跑一次
def model():
    """1.2 GB 的权重。只读，可以安全共享。"""
    m = load_model("/models/scorer")
    yield m
    m.release()                           # 会话结束时释放


@pytest.fixture                           # 默认 scope="function"：每个测试一份
def scorer(model):
    """廉价的包装对象，带可变缓存 —— 必须每个测试新建。"""
    return Scorer(model=model, cache={})


def test_a(scorer):
    assert scorer.score("hello") > 0

def test_b(scorer):
    assert scorer.cache == {}             # ✅ 拿到的是干净缓存
```

三个要点：

1. **`scope="session"` 的 fixture 必须是只读的。** 如果 `model` 内部有可变状态（比如 KV cache），共享就会串数据，测试变得依赖执行顺序 —— 这比慢更糟。
2. **可用的 scope 有 5 档**：`function`（默认）→ `class` → `module` → `package` → `session`。**粒度越粗越快，但共享风险越大**，按"这个对象是不是只读"来选。
3. **`yield` 之后是 teardown**，即使测试抛异常也会执行。

如果模型加载本身还想跨**多次 pytest 运行**复用，用 `pytest-xdist` + 一个文件锁，或者干脆把模型服务化 —— 但那通常说明你在写集成测试而不是单测。

**更根本的一条建议**：单测里**根本不该加载真模型**。用一个假的 `Scorer`（阶段二的 `Protocol`），把真模型留给一小撮标了 `@pytest.mark.integration` 的用例，CI 里默认跳过。18 分钟 → 3 秒。
:::

:::exercise 练习 2 · 写一个"性能契约"测试（30 分钟）
给 `bucketize`（阶段二的时间分桶生成器）写两个测试：

1. 证明它是**惰性**的：传入一个无限生成器，取前 2 个桶就能返回，不会挂死
2. 证明它的**内存是 O(桶大小)** 而不是 O(输入大小)

第 2 条比较难，想想用什么工具能证明。
:::

:::solution
```python
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
    """★ 惰性契约：无限输入 + islice 必须能返回。"""
    got = list(itertools.islice(bucketize(exact_entries(), width=1.0), 2))
    assert len(got) == 2
    assert [k for k, _ in got] == [0.0, 1.0]
    assert len(got[0][1]) == 10          # 1 秒 / 0.1 秒 = 10 条


def test_float_accumulation_shifts_bucket_boundaries():
    """★ 浮点累加会让边界处的条目落错桶 —— 真实日志处理里的经典 bug。"""
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
    """★ 内存契约：处理 50 万条，峰值内存应该只和**单个桶**成正比。"""
    entries = (Entry(ts=i * 0.001, endpoint="/chat", status=200, ms=1.0)
               for i in range(500_000))          # 500 秒的数据，每桶 1000 条
    tracemalloc.start()
    tracemalloc.reset_peak()
    n_buckets = sum(1 for _ in bucketize(entries, width=1.0))
    peak = tracemalloc.get_traced_memory()[1]
    tracemalloc.stop()
    print(f"\n  50 万条 → {n_buckets} 个桶，峰值内存 {peak/2**10:.1f} KiB")
    assert peak < 5 * 1024 * 1024, f"峰值 {peak/2**20:.1f} MiB，实现可能不是流式的"
```

跑出来：

```text
.
  0.1 累加 10 次 = 0.9999999999999999，比 1.0 小 1.11e-16
.
  50 万条 → 500 个桶，峰值内存 190.0 KiB
.
3 passed
```

**50 万条记录，峰值 190 KiB** —— 如果实现是"先 list 再分桶"，这里会是 28 MB 以上。这就是内存契约的证明。

三个测试的价值不一样：

- **惰性契约**：如果有人把 `bucketize` 改成先 `list(entries)` 再分桶，第一个测试会**永久挂起**（建议配 `@pytest.mark.timeout(5)`，需要 `pytest-timeout`）。
- **内存契约**：阈值要**故意放宽**。设得太紧（比如 200 KB）会因为 GC 时机、Python 版本差异而随机变红，最后被人加 `@skip`。设阈值的原则不是"当前值 + 10%"，而是"**能抓住我担心的那个 bug** 的最松阈值"。
- **第二个测试是意外收获** —— 我写这组测试时本来断言第一个桶有 10 条，结果跑出 11 条。查下来是浮点累加：`0.1` 加十次等于 `0.9999999999999999`，`// 1.0` 仍然落在 0 号桶。

:::warn 这个浮点坑在真实系统里长什么样
按时间分桶的代码到处都是：监控聚合、限流窗口、账单周期、训练的 step 计时。只要时间戳是**累加**出来的而不是**算**出来的，边界条目就会系统性地偏向前一个桶。

后果通常不是崩溃，而是**每个桶都少/多一条**，你的 QPS 图整体偏低 0.1%，没人发现，直到某次容量规划按这个数字做出了错误结论。

两条规则：
1. **时间戳用整数**（纳秒/毫秒计数），要显示时再转 `float`
2. **分桶用 `//` 对绝对时间戳取整**（`entry.ts // width * width`），不要累加游标

`svclog` 的 `bucketize` 用的是第 2 条，所以它对**输入里已有的**误差免疫 —— 上面那 11 条是数据源自己带进来的误差，不是分桶逻辑的错。
:::
:::

:::exercise 练习 3 · 修一个"事件循环被毒死"的服务（45 分钟）
下面这个服务看起来没问题，但并发一上来就崩。找出**三处**会阻塞事件循环的地方并修好。

```python
import time, requests, json
from fastapi import FastAPI

app = FastAPI()
CACHE = {}

@app.post("/v1/score")
async def score(req: dict):
    key = json.dumps(req, sort_keys=True)
    if key in CACHE:
        return CACHE[key]

    embedding = requests.post("http://embed:8001/embed",
                              json={"text": req["text"]}).json()

    result = heavy_similarity(embedding, load_index())   # 纯 Python，约 200ms

    with open("/var/log/score.jsonl", "a") as f:         # 写审计日志
        f.write(json.dumps({"req": req, "result": result}) + "\n")
        f.flush()
        time.sleep(0.01)                                  # "让磁盘喘口气"

    CACHE[key] = result
    return result
```
:::

:::solution
三处阻塞（外加两个 bonus 问题）：

| # | 问题 | 为什么致命 |
|---|---|---|
| 1 | `requests.post` | 同步 HTTP，整个循环停到网络返回为止（几十到几百 ms） |
| 2 | `heavy_similarity(...)` | 纯 Python 计算 200ms，GIL 期间循环完全停摆 |
| 3 | 同步文件写 + `time.sleep(0.01)` | 每个请求都阻塞，`sleep` 更是白送的停摆 |
| bonus 4 | `CACHE` 是进程内 dict | 多副本之间不共享，且无上限 → 内存泄漏 |
| bonus 5 | `req: dict` 没有校验 | 没有 pydantic，`req["text"]` 缺失就 500 |

修好的版本：

```python
import asyncio
import json
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field


class ScoreRequest(BaseModel):          # bonus 5：运行时真校验
    text: str = Field(min_length=1, max_length=8192)
    top_k: int = Field(default=5, ge=1, le=100)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 客户端复用：每请求新建 AsyncClient 会重建连接池，浪费且容易耗尽端口
    app.state.http = httpx.AsyncClient(timeout=5.0)
    app.state.index = await asyncio.to_thread(load_index)   # 启动时加载一次
    app.state.log_q: asyncio.Queue = asyncio.Queue(maxsize=10_000)
    app.state.writer = asyncio.create_task(log_writer(app.state.log_q))
    yield
    app.state.writer.cancel()
    await app.state.http.aclose()


async def log_writer(q: asyncio.Queue) -> None:
    """修法 3：审计日志改成后台批量写，请求路径上不碰磁盘。"""
    buf: list[str] = []
    while True:
        try:
            async with asyncio.timeout(0.5):        # 攒 0.5 秒或攒满 100 条
                while len(buf) < 100:
                    buf.append(await q.get())
        except TimeoutError:
            pass
        if buf:
            lines, buf = buf, []
            await asyncio.to_thread(_flush, lines)  # 真正的磁盘 IO 丢到线程


def _flush(lines: list[str]) -> None:
    with open("/var/log/score.jsonl", "a") as f:
        f.writelines(lines)


app = FastAPI(lifespan=lifespan)


@app.post("/v1/score")
async def score(req: ScoreRequest):
    # 修法 1：async HTTP 客户端，await 时把控制权还给事件循环
    resp = await app.state.http.post("http://embed:8001/embed",
                                     json={"text": req.text})
    resp.raise_for_status()
    embedding = resp.json()

    # 修法 2：CPU 密集的纯 Python 丢到线程池。
    # ⚠️ 注意：这**不能**提升吞吐（GIL 还在），但能让**事件循环继续转**，
    #    健康检查和其它请求的 IO 不会被卡住。
    #    真要提吞吐，得把 heavy_similarity 换成 NumPy，或丢进 ProcessPool。
    result = await asyncio.to_thread(heavy_similarity, embedding, app.state.index)

    # 修法 3：非阻塞入队；队列满了宁可丢审计日志，也不让它拖垮请求
    try:
        app.state.log_q.put_nowait(
            json.dumps({"text": req.text, "result": result}) + "\n")
    except asyncio.QueueFull:
        pass

    return result
```

:::warn 修法 2 的注释是本题的重点
`asyncio.to_thread` **不会**让 CPU 密集任务变快 —— GIL 还在，200ms 还是 200ms。

它买到的是：**事件循环不再停摆**。`/healthz` 能响应、其它请求的网络 IO 能继续、SSE 流不会卡住。

真要提吞吐只有三条路：把 `heavy_similarity` 向量化（NumPy，通常快 10–100×）、丢进 `ProcessPoolExecutor`（受限于真实核数）、或者横向加副本。

**别把 `to_thread` 当成"Python 版的线程池提速"** —— 它是"避免阻塞事件循环"的工具，不是"并行计算"的工具。
:::
:::

:::exercise 练习 4 · 用剖析驱动优化（45 分钟）
下面这个函数在 50 万条日志、4000 个不同 endpoint 上要跑 **51 秒**。用 `cProfile` 找出瓶颈，优化到 100 毫秒以内，并用 `timeit` 证明。

```python
def top_slow_endpoints(entries, n=10):
    endpoints = []
    for e in entries:
        if e.endpoint not in endpoints:
            endpoints.append(e.endpoint)

    result = []
    for ep in endpoints:
        latencies = [e.ms for e in entries if e.endpoint == ep]
        latencies.sort()
        p95 = latencies[int(len(latencies) * 0.95)]
        result.append((ep, p95))

    result.sort(key=lambda x: x[1], reverse=True)
    return result[:n]
```
:::

:::solution
`cProfile` 会指向 `list.__contains__` 和那个列表推导。真正的问题是**两处 O(n²)**：

1. `e.endpoint not in endpoints` —— 对 **list** 做成员检查是 O(len(endpoints))，整体 O(n·k)
2. `[e.ms for e in entries if e.endpoint == ep]` —— **对每个 endpoint 重扫一遍全表**，整体 O(n·k)

修法：**一次遍历建索引**，把两个 O(n·k) 都变成 O(n)。

```python
from collections import defaultdict


def top_slow_endpoints(entries, n=10):
    """一次遍历分组，再对每组各排一次序。O(n + Σ mᵢlog mᵢ)。"""
    by_ep: dict[str, list[float]] = defaultdict(list)
    for e in entries:                       # ← 只扫一遍
        by_ep[e.endpoint].append(e.ms)      # dict 查找是 O(1)

    result = []
    for ep, lat in by_ep.items():
        lat.sort()
        result.append((ep, lat[int(len(lat) * 0.95)]))

    result.sort(key=lambda x: x[1], reverse=True)
    return result[:n]


def top_slow_endpoints_heap(entries, n=10):
    """只要 top-n 的话，最后一步用堆，省掉全排序。"""
    import heapq

    by_ep: dict[str, list[float]] = defaultdict(list)
    for e in entries:
        by_ep[e.endpoint].append(e.ms)

    def p95(xs: list[float]) -> float:
        xs.sort()
        return xs[int(len(xs) * 0.95)]

    return heapq.nlargest(n, ((ep, p95(l)) for ep, l in by_ep.items()),
                          key=lambda x: x[1])
```

**三个可迁移的教训**：

1. **`in` 对 `list` 是 O(n)，对 `set`/`dict` 是 O(1)。** 这是 Python 里最常见的性能杀手，而且它不显眼 —— 代码读起来完全正常。看到循环里有 `x in some_list`，立刻改成 `set`。
2. **"对每个分类再扫一遍全表"是隐藏的 O(n²)。** 一次遍历建 `defaultdict` 索引，几乎总是对的。
3. **只要 top-n 时用 `heapq.nlargest` 而不是全排序。** `k` 很大时（比如几万个 endpoint）差别明显。

**实测**（50 万条记录，两种 endpoint 基数）：

```text
endpoint 基数 = 40
  原版             616 ms
  defaultdict       95 ms   快   6.5×
  + heapq           96 ms   快   6.4×      ← 堆在这里没用

endpoint 基数 = 4000
  原版           51435 ms                  ← 51 秒
  defaultdict       99 ms   快 521.0×
  + heapq           88 ms   快 581.7×      ← 堆开始有一点用了
```

两张表对照着看，能读出这个 bug 的真实性质：

- **优化版的耗时几乎不随基数变化**（95 ms → 99 ms），因为它是 O(n)
- **原版的耗时和基数成正比**（616 ms → 51435 ms，83 倍基数换来 83 倍耗时），因为它是 O(n·k)
- 所以这类 bug 在**测试数据上完全看不出来**（你的样例日志只有 4 个 endpoint），一上生产（几千个 URL 路径）就变成事故

`heapq` 只在**分组很多**时才有意义（4000 组时省 11%，40 组时反而略慢 —— 堆的常数开销没摊掉）。**别为了显得高级而用它。**

**最后，别忘了验证正确性，不只是速度**：

```python
assert slow(DATA) == fast(DATA) == fast_heap(DATA)   # → True
```
:::

:::exercise 练习 5 · 完整工程化（1.5 小时）
把阶段二的 `svclog` 包补齐成一个**可交付**的项目：

1. ≥20 个 pytest 用例，其中至少 2 个是"性质测试"（惰性 / 内存）
2. `pyproject.toml` 里配好 ruff + mypy + pytest
3. 一个分层的 Dockerfile，`docker build` 能成功
4. 一个 GitHub Actions workflow：lint → 类型检查 → 测试，任一失败就红

验收：`uv run ruff check . && uv run mypy --strict src/ && uv run pytest` 一条命令全绿。
:::

:::solution
CI 配置（`.github/workflows/ci.yml`）：

```yaml
name: ci
on: [push, pull_request]

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: astral-sh/setup-uv@v3
        with:
          enable-cache: true          # 缓存 uv 的全局包目录，CI 快很多
      - run: uv sync --frozen --all-extras --dev
      #   ↑ --frozen：lock 和 pyproject 不一致就直接失败，
      #     而不是"顺手更新一下" —— 这正是锁文件存在的意义
      - run: uv run ruff check .
      - run: uv run ruff format --check .
      - run: uv run mypy --strict src/
      - run: uv run pytest --tb=short
```

:::note `--frozen` 这个 flag 值得单独说
不加 `--frozen`，`uv sync` 发现 lock 过期会**自动重新解析依赖**。于是 CI 用的是和你本地不同的版本组合，"在我机器上能跑"的经典问题就从开发环境搬到了 CI。

`--frozen` 让它直接失败，逼你在本地跑 `uv lock` 并把结果提交上去。**等价于 `mvn -o` 强制离线。**
:::

本地一条命令跑完所有检查（加到 `Makefile` 或 `justfile`）：

```makefile
.PHONY: check
check:
	uv run ruff check .
	uv run ruff format --check .
	uv run mypy --strict src/
	uv run pytest -q
```

最终验收的真实输出：

```text
All checks passed!
6 files already formatted
Success: no issues found in 6 source files
............................                                             [100%]
28 passed in 0.06s
```

**再往前一步**：把这四条检查装成 pre-commit hook（`pre-commit` 工具），提交时自动跑 ruff。类型检查和测试留给 CI —— 它们太慢，放进 hook 会让人开始用 `--no-verify`。
:::

---

## 7. 阶段三结业自检

- [ ] 能用 fixture + parametrize + monkeypatch 写测试，且知道 fixture 的 5 档 scope
- [ ] 能写出"性质测试"（惰性、内存上界），而不只是断言返回值
- [ ] 能说清 `pyproject.toml` / `uv.lock` / wheel / sdist 各是什么，为什么 lock 必须提交
- [ ] 知道 AI 的 wheel 绑定了「Python 版本 × OS × 架构 × CUDA」四个维度
- [ ] 能写出依赖层与代码层分离的 Dockerfile，并说清为什么 GPU 服务不该用 `--workers`
- [ ] **能解释 GIL 的影响**，并在 asyncio / 多进程 / 向量化之间正确选型
- [ ] 知道容器里 `os.cpu_count()` 会骗人，能说出怎么读 cgroup 配额
- [ ] 能指出一段 async 代码里的阻塞调用，并说出 `to_thread` 买到了什么、买不到什么
- [ ] 会用 `cProfile` 读出 ncalls/tottime/cumtime，会用 `py-spy dump` 查线上进程
- [ ] 项目有 ≥20 个测试 + Dockerfile + CI，一条命令全绿

:::note 下一页做什么
前三个阶段是"让你会写 Python"。**阶段四才是这条路线真正的重头戏** —— 16 小时，四个心智模型：张量、显存、批处理、推理引擎。

那一页有两个不需要 GPU 就能跑的核心工具：一个**显存口算器**（告诉你 7B 模型在 A100 上能扛多少并发，以及为什么 int4 量化几乎不增加并发），和一个**带假后端的推理服务**（让你在笔记本上把服务化机制练完）。
:::
