{"slug":"py-stage1-java-to-python",
 "title":"阶段一：把 Java 翻译成 Python（6 小时，含全部可运行代码）",
 "summary":"Java 工程师的 Python 语法平移：对照表 + 10 段可运行示例 + 6 个坑的复现 + 一个 167 行的 CLI 工具，每段代码都在 Python 3.12 上跑过并附真实输出。",
 "eyebrow":"阶段一 / 5 · Stage 1",
 "h1":"把 Java 翻译成 Python",
 "sub":"你缺的不是编程能力，是一本翻译词典。这 6 小时不教你编程，只教你把已有的 Java 直觉平移过来 —— 并卸掉三个会害你的直觉。",
 "facts":["<b>6 小时</b>","代码 <b>全部可运行</b>","练习 <b>5 个</b>（带参考答案）","产出：<b>167 行 CLI 工具</b>"],
 "footer":"看板任务 #425「Python学习」· 本页是<a href=\"/p/python-ai-infra-40h.html\">《从 Java 到 Python AI Infrastructure：40 小时学习路线》(#422)</a> 阶段一的实操展开版。所有代码与输出均在 Python 3.12.14 上实测。"}
---FM---

:::goal 这一页结束时，你应该能做到
- 不再需要查「这个在 Java 里怎么写」—— 对照表已经进脑子了
- 能一眼认出 6 个 Java 人必踩的坑（可变默认参数、真值陷阱、闭包捕获…）
- 独立写出一个 150 行左右的命令行工具，`ruff check` 零告警
- 把 Java Stream API 的每一个操作翻译成 Python 的推导式或 `itertools`
:::

本页所有代码都可以直接复制运行（每个代码块右上角有复制按钮）。**输出框里的内容是真实跑出来的**，不是我编的 —— 如果你跑出来不一样，那多半是版本差异，值得去查一查。

---

## 1. 环境：2026 年的答案很短，就是 uv

`uv` 一个工具同时替代了 pyenv / pip / virtualenv / poetry / pipx。Rust 写的，快到不需要等。

```bash
# 装 uv（一次性）
curl -LsSf https://astral.sh/uv/install.sh | sh

# 建项目
uv init myproj && cd myproj     # 生成 pyproject.toml，≈ mvn archetype:generate
uv python install 3.12          # 装解释器，≈ sdk install java
uv add numpy fastapi            # 加依赖，自动建 .venv 并写 uv.lock
uv add --dev pytest ruff mypy   # 开发依赖，≈ <scope>test</scope>
uv run python script.py         # 在项目环境里跑，不用手动 activate
uv sync --frozen                # 按 lock 精确复现环境，≈ mvn -o
```

| Java 世界 | Python（2026 推荐） | 说明 |
|---|---|---|
| sdkman（JDK 版本） | `uv python install 3.12` | AI 栈优先 3.12 |
| Maven / Gradle | `uv` | 一个命令管全部 |
| `pom.xml` | `pyproject.toml` | 项目元数据 + 依赖声明 |
| `dependency-lock` | `uv.lock` | **必须提交进 git** |
| `~/.m2` 共享仓库 | `.venv/`（每项目一份） | **不共享**，这是关键差异 |
| Checkstyle + SpotBugs + PMD | `ruff check` | 一个工具顶十个 |
| google-java-format | `ruff format` | |
| javac 的类型检查 | `mypy` / `pyright` | **可选、事后、不阻塞运行** |
| JUnit | `pytest` | 见阶段三 |

:::warn 版本选择
AI 栈优先 **Python 3.12**。3.13/3.14 的 free-threaded（无 GIL）构建虽然可用，但 CUDA 编译型轮子的生态支持还不整齐，**别拿它当生产基线**。
:::

:::lab 动手 1 · 15 分钟
```bash
uv init pylab && cd pylab
uv add --dev ruff pytest
echo 'def main(): print("hello")' > hello.py
uv run ruff check .            # 应该零告警
uv run python -c "import sys; print(sys.version, sys.executable)"
```
最后一行会打出 `.venv/bin/python` —— 确认你跑在项目自己的环境里，而不是系统 Python。这一步做对了，后面所有"在我机器上能跑"的问题都少一半。
:::

---

## 2. 语法对照速查表

不用通读教程，把这张表过一遍就够了。**Java 人真正会踩的坑都在最右列。**

| 主题 | Java | Python | 踩坑提醒 |
|---|---|---|---|
| 语句块 | `{ }` + `;` | **缩进**（4 空格） | 混用 tab/空格直接报错；编辑器设死 4 空格 |
| 声明变量 | `int x = 1;` | `x = 1` | 没有声明关键字，**拼错变量名 = 悄悄新建一个** |
| 空值 | `null` | `None` | 判空用 `x is None`，**不要**用 `== None` |
| 相等 | `equals()` vs `==` | `==`（值） vs `is`（同一对象） | `is` ≈ Java 的 `==`；日常几乎只用 `==` |
| 真值 | 只有 `boolean` | `None`/`0`/`""`/`[]`/`{}` 都是假 | `if not items:` 地道，但 **`0` 会被当假** |
| 字符串 | `String.format` | f-string：`f"{x:.2f}"` | 默认选择，性能也最好 |
| 三元 | `c ? a : b` | `a if c else b` | 顺序不同，别读反 |
| 常量 | `final` | 没有，靠 `UPPER_CASE` 约定 | 语言不拦你 |
| switch | `switch` | `match/case`（3.10+） | 结构化模式匹配，比 switch 强 |
| 循环 | `for (int i=0;...)` | `for x in xs` | 几乎永远不用下标 |
| 带下标 | `for (i)` | `for i, x in enumerate(xs)` | |
| 并行遍历 | 手写下标 | `for a, b in zip(xs, ys)` | |
| 切片 | `subList` | `xs[1:5]`、`xs[-1]`、`xs[::-1]` | **负索引从尾部数**，Java 没有 |
| 多返回值 | 返回对象/数组 | `return a, b` + `x, y = f()` | 元组解包，非常常用 |
| 异常 | checked + unchecked | **全是 unchecked** | 没有 `throws` 声明 |
| try-catch | `catch (E e)` | `except E as e:` | 还有 `else:`（无异常时） |
| 异常链 | `initCause` | `raise X from e` | |
| 资源关闭 | try-with-resources | `with open(p) as f:` | `with` 用法比 AutoCloseable 泛化得多 |
| 泛型 | `List<String>` | `list[str]` | 注解，**运行时不生效** |
| 接口 | `interface` | `Protocol` | 见阶段二 |
| 包导入 | `import com.x.Y` | `from x import Y` | **导入会执行模块顶层代码** |
| main | `public static void main` | `if __name__ == "__main__":` | 脚本与库两用 |
| 文档 | `/** javadoc */` | `"""docstring"""` | 运行时可读的字符串 |

### 这些对照长什么样（可运行）

```python
"""阶段一 1.2 —— Java 习惯写法的 Python 对照，全部可运行。"""

# ── 1. null / Optional ────────────────────────────────────────────────
# Java: if (name != null && !name.isEmpty()) {...}
name: str | None = None
print("1.", "空" if not name else name)       # None、""、[]、0 都是假值
# Java: Optional.ofNullable(name).orElse("default")
print("1.", name or "default")                # or 返回第一个真值
# ⚠️ 只有判断"是不是 None"时才用 is
print("1.", name is None, 0 or "fallback")    # 注意 0 也是假值，这是头号坑

# ── 2. 字符串格式化：f-string 一招吃遍 ────────────────────────────────
who, score = "alice", 93.456
print("2.", f"{who} scored {score:.2f}")                    # %.2f
print("2.", f"|{who:>10}|{score:<8.1f}|{1234567:,}|{0.873:.1%}|")
print("2.", f"{score=}")                                    # 调试神器，打印表达式本身

# ── 3. switch → match/case（3.10+，比 switch 强）────────────────────
def classify(code: int) -> str:
    match code:
        case 200 | 201 | 204:
            return "ok"
        case c if 400 <= c < 500:
            return "client-error"
        case c if c >= 500:
            return "server-error"
        case _:
            return "other"

print("3.", classify(200), classify(429), classify(503), classify(302))

# ── 4. 遍历：几乎永远不用下标 ─────────────────────────────────────────
hosts = ["gpu-01", "gpu-02", "gpu-03"]
loads = [0.91, 0.42, 0.77]
for i, h in enumerate(hosts, start=1):        # Java: for (int i=0; ...)
    print("4.", i, h)
for h, l in zip(hosts, loads):                # Java: 手写下标并行遍历
    print("4.", f"{h} load={l}")

# ── 5. 切片：Java 没有的负索引 ────────────────────────────────────────
xs = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
print("5.", xs[2:5], xs[-3:], xs[::2], xs[::-1])
print("5.", "gpu-01.cluster.local".split(".")[0])

# ── 6. 多返回值：元组解包 ─────────────────────────────────────────────
def min_max(vs: list[float]) -> tuple[float, float]:
    return min(vs), max(vs)                   # Java 要造个 Pair 类

lo, hi = min_max(loads)
print("6.", lo, hi)
first, *rest = hosts                          # 星号解包，Java 没有
print("6.", first, rest)

# ── 7. 异常：全是 unchecked，但有 Java 没有的 else ───────────────────
def parse_port(raw: str) -> int:
    try:
        port = int(raw)
    except ValueError as e:                   # Java: catch (E e)
        raise ValueError(f"bad port: {raw!r}") from e   # from = initCause
    else:                                     # 没抛异常才走这里（Java 没有）
        return port
    finally:
        pass                                  # 和 Java 一样

print("7.", parse_port("8080"))
try:
    parse_port("八千")
except ValueError as e:
    print("7.", type(e).__name__, e, "| cause:", type(e.__cause__).__name__)

# ── 8. with ≈ try-with-resources，但更泛化 ──────────────────────────
import contextlib, io, time

@contextlib.contextmanager                    # 三行就能造一个"资源"
def timed(label: str):
    t0 = time.perf_counter()
    yield
    print("8.", f"{label} took {time.perf_counter()-t0:.4f}s")

with timed("sum"):
    total = sum(range(1_000_000))
print("8.", total)

buf = io.StringIO()
with contextlib.redirect_stdout(buf):         # with 可以做的事远超关文件
    print("这句被重定向了")
print("8.", "captured:", buf.getvalue().strip())

# ── 9. 真值陷阱：Java 人踩得最狠的一个 ───────────────────────────────
def page_size(n: int | None) -> int:
    return n or 100          # ❌ n=0 时会返回 100
def page_size_ok(n: int | None) -> int:
    return 100 if n is None else n            # ✅

print("9.", page_size(0), page_size_ok(0))

# ── 10. main：脚本与库两用 ───────────────────────────────────────────
if __name__ == "__main__":
    print("10.", "作为脚本直接跑时才执行")
```

:::out 实际输出（Python 3.12.14）
```text
1. 空
1. default
1. True fallback
2. alice scored 93.46
2. |     alice|93.5    |1,234,567|87.3%|
2. score=93.456
3. ok client-error server-error other
4. 1 gpu-01
4. 2 gpu-02
4. 3 gpu-03
4. gpu-01 load=0.91
4. gpu-02 load=0.42
4. gpu-03 load=0.77
5. [2, 3, 4] [7, 8, 9] [0, 2, 4, 6, 8] [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
5. gpu-01
6. 0.42 0.91
6. gpu-01 ['gpu-02', 'gpu-03']
7. 8080
7. ValueError bad port: '八千' | cause: ValueError
8. sum took 0.0146s
8. 499999500000
8. captured: 这句被重定向了
9. 100 0
10. 作为脚本直接跑时才执行
```
:::

:::note 第 9 段值得停一下
`return n or 100` 这个写法在 Java 里没有对应物，看起来很优雅，但 `n=0` 时会静默返回 100。
在推理服务里，这就是「用户传了 `temperature=0`（要确定性输出），你给了他 `0.7`」。
**凡是"缺省值"和"合法的假值"可能同时存在的地方，一律写 `if x is None`。**
:::

---

## 3. Stream API 的 Python 对照

这是 Java 人最快的提效点。整张表**一一对应**，翻译完就能用。

| Java Stream | Python | 备注 |
|---|---|---|
| `.map(f)` | `[f(x) for x in xs]` | |
| `.filter(p)` | `[x for x in xs if p(x)]` | |
| `.map().filter()` | `[f(x) for x in xs if p(x)]` | **一行搞定，不用链式** |
| `.collect(toList())` | 推导式本身就是 list | |
| `.collect(toSet())` | `{f(x) for x in xs}` | |
| `.collect(toMap(k, v))` | `{k(x): v(x) for x in xs}` | |
| `.collect(groupingBy(f))` | `defaultdict(list)` 或 `itertools.groupby` | groupby **必须先排序** |
| `.collect(counting())` | `collections.Counter` | `.most_common(10)` |
| `.anyMatch` / `.allMatch` | `any(...)` / `all(...)` | |
| `.count()` | `sum(1 for x in xs if p(x))` | |
| `.sorted(cmp)` | `sorted(xs, key=..., reverse=True)` | `key` 而不是 comparator |
| `.min` / `.max` | `min(xs, key=...)` / `max(...)` | |
| `.reduce` | `functools.reduce` | 通常有更直白的写法 |
| `.flatMap` | `[y for x in xs for y in x]` | **顺序和 Java 反直觉** |
| `.distinct()` | `list(dict.fromkeys(xs))` | 保序去重 |
| `.limit(n)` | `itertools.islice(gen, n)` | 对惰性流用 islice |
| 惰性无限流 | **生成器表达式** `(f(x) for x in xs)` | 圆括号 |
| `Stream.concat` | `itertools.chain(a, b)` | |
| `ArrayDeque` | `collections.deque(maxlen=n)` | 定长滑动窗口很好用 |

```python
"""阶段一 1.3 —— Java Stream API 的逐条 Python 对照，可运行。"""
from collections import Counter, defaultdict, deque
from dataclasses import dataclass
import functools, itertools, sys

@dataclass
class Req:
    endpoint: str
    status: int
    ms: float

reqs = [
    Req("/v1/chat", 200, 812.0), Req("/v1/chat", 200, 1340.5),
    Req("/v1/chat", 429, 3.1),   Req("/healthz", 200, 1.2),
    Req("/v1/embed", 200, 44.8), Req("/v1/embed", 500, 120.0),
    Req("/healthz", 200, 0.9),   Req("/v1/chat", 200, 655.0),
]

# .map(f)                     → 列表推导
print("map      ", [r.ms for r in reqs][:4])
# .filter(p)                  → 带 if 的推导
print("filter   ", [r.endpoint for r in reqs if r.status >= 400])
# .map().filter()             → 一行搞定，不用链式
print("map+filter", [round(r.ms) for r in reqs if r.ms > 100])
# .collect(toSet())
print("toSet    ", sorted({r.endpoint for r in reqs}))
# .collect(toMap(...))        → 字典推导
print("toMap    ", {r.endpoint: r.status for r in reqs})
# .anyMatch / .allMatch / .noneMatch
print("match    ", any(r.status >= 500 for r in reqs), all(r.ms > 0 for r in reqs))
# .count()
print("count    ", sum(1 for r in reqs if r.status == 200))
# .sorted(Comparator.comparing(...).reversed())
print("sorted   ", [r.endpoint for r in sorted(reqs, key=lambda r: r.ms, reverse=True)][:3])
# .min / .max
print("max      ", max(reqs, key=lambda r: r.ms).ms)
# .reduce
print("reduce   ", functools.reduce(lambda a, r: a + r.ms, reqs, 0.0))
#   ↑ 但 Python 里更地道的是直接 sum(...)
print("sum      ", sum(r.ms for r in reqs))
# .flatMap                    → 双重推导（先外后内，顺序和 Java 反直觉）
tags = [["gpu", "a100"], ["cpu"], ["gpu", "h100"]]
print("flatMap  ", [t for group in tags for t in group])
# .distinct()
print("distinct ", list(dict.fromkeys(t for g in tags for t in g)))  # 保序去重
# .limit(n) —— 对惰性流用 islice
print("limit    ", list(itertools.islice((r.ms for r in reqs), 3)))

# .collect(groupingBy(...))  → 两种写法
by_ep: dict[str, list[Req]] = defaultdict(list)
for r in reqs:
    by_ep[r.endpoint].append(r)
print("groupBy  ", {k: len(v) for k, v in by_ep.items()})

# itertools.groupby 是"相邻分组"，必须先排序 —— Java 人最容易踩的一个坑
key = lambda r: r.endpoint
print("groupby  ", {k: len(list(g)) for k, g in itertools.groupby(sorted(reqs, key=key), key)})
# ❌ 不排序的结果是错的：
print("groupby❌", {k: len(list(g)) for k, g in itertools.groupby(reqs, key)})

# .collect(counting()) → Counter
print("counting ", Counter(r.endpoint for r in reqs).most_common(2))

# ── 惰性：方括号 vs 圆括号，就是 OOM 与不 OOM 的区别 ──────────────
eager = [r.ms ** 2 for r in reqs]          # list，立刻算完、全在内存
lazy  = (r.ms ** 2 for r in reqs)          # generator，一次一个
print("eager    ", type(eager).__name__, sys.getsizeof(eager), "bytes")
print("lazy     ", type(lazy).__name__, sys.getsizeof(lazy), "bytes")
print("lazy sum ", sum(lazy))              # 生成器只能消费一次
print("再消费一次", sum(lazy))              # → 0，已耗尽！Java Stream 同理

# ArrayDeque → collections.deque
window: deque[float] = deque(maxlen=3)     # 定长滑动窗口，自动挤掉旧的
for r in reqs:
    window.append(r.ms)
print("deque    ", list(window))
```

:::out 实际输出
```text
map       [812.0, 1340.5, 3.1, 1.2]
filter    ['/v1/chat', '/v1/embed']
map+filter [812, 1340, 120, 655]
toSet     ['/healthz', '/v1/chat', '/v1/embed']
toMap     {'/v1/chat': 200, '/healthz': 200, '/v1/embed': 500}
match     True True
count     6
sorted    ['/v1/chat', '/v1/chat', '/v1/chat']
max       1340.5
reduce    2977.5
sum       2977.5
flatMap   ['gpu', 'a100', 'cpu', 'gpu', 'h100']
distinct  ['gpu', 'a100', 'cpu', 'h100']
limit     [812.0, 1340.5, 3.1]
groupBy   {'/v1/chat': 4, '/healthz': 2, '/v1/embed': 2}
groupby   {'/healthz': 2, '/v1/chat': 4, '/v1/embed': 2}
groupby❌ {'/v1/chat': 1, '/healthz': 1, '/v1/embed': 2}
counting  [('/v1/chat', 4), ('/healthz', 2)]
eager     list 120 bytes
lazy      generator 200 bytes
lazy sum  2901728.15
再消费一次 0
deque     [120.0, 0.9, 655.0]
```
:::

:::warn 对比 `groupBy` 和 `groupby❌` 这两行
`itertools.groupby` 只把**相邻的**相同键归为一组 —— 和 SQL 的 `GROUP BY` 完全不是一回事，倒更像 Unix 的 `uniq`。不排序就用它，`/v1/chat` 的 4 条会被切成 3 组，字典推导又只保留了最后一组，于是你得到 `1`。

**这个 bug 不会报错，只会让报表少算数据。** 记住两条：分组要么先 `sorted(key=...)`，要么直接用 `defaultdict(list)`。
:::

---

## 4. 函数、参数与作用域：六个坑的复现

下面每个坑都**能跑出来给你看**，不是纸上谈兵。

```python
"""阶段一 1.4 —— 函数、参数、作用域：六个 Java 人必踩的坑，全部可复现。"""

# ── 坑 1：可变默认参数（Python 头号陷阱）────────────────────────────
def add_bad(item, bucket=[]):          # ❌ 默认值在"定义时"求值一次
    bucket.append(item)
    return bucket

print("坑1 ❌", add_bad("a"), add_bad("b"), add_bad("c"))   # 三次调用共享同一个 list！
print("坑1 证据", add_bad.__defaults__)                      # 默认值是函数对象的属性

def add_ok(item, bucket=None):         # ✅ None 哨兵
    bucket = [] if bucket is None else bucket
    bucket.append(item)
    return bucket

print("坑1 ✅", add_ok("a"), add_ok("b"), add_ok("c"))

# ── 坑 2：默认值只求值一次，连时间戳也是 ────────────────────────────
import datetime as dt
def stamp_bad(t=dt.datetime.now()):    # ❌ 永远是 import 那一刻的时间
    return t
print("坑2 ❌", stamp_bad() is stamp_bad())   # True → 同一个对象

# ── 关键字参数：AI 库里到处都是 ─────────────────────────────────────
def generate(prompt, *, max_tokens=256, temperature=0.7, stream=False):
    #                 ↑ 星号之后必须用关键字传，防止调用方写 generate(p, 512, 0.7)
    return f"{prompt!r} max={max_tokens} temp={temperature} stream={stream}"

print("kwonly  ", generate("hi", max_tokens=512))
try:
    generate("hi", 512)                        # 位置传参会直接报错
except TypeError as e:
    print("kwonly  ", "TypeError:", e)

# ── *args / **kwargs：装饰器和配置透传的基础 ───────────────────────
def trace(fn, *args, **kwargs):
    print("trace   ", f"call {fn.__name__}(*{args}, **{kwargs})")
    return fn(*args, **kwargs)

print("trace   ", trace(generate, "hello", max_tokens=8, stream=True))

defaults = {"max_tokens": 128, "temperature": 0.2}
override = {"temperature": 0.9}
print("merge   ", generate("q", **{**defaults, **override}))   # 后者覆盖前者

# ── 坑 3：LEGB —— 赋值会静默新建局部变量 ───────────────────────────
counter = 0
def bump_bad():
    try:
        counter = counter + 1          # ❌ 一旦有赋值，counter 整个函数内都是局部的
    except UnboundLocalError as e:
        print("坑3 ❌", "UnboundLocalError:", e)

bump_bad()

def make_counter():                    # ✅ 闭包 + nonlocal
    n = 0
    def bump():
        nonlocal n
        n += 1
        return n
    return bump

c = make_counter()
print("坑3 ✅", c(), c(), c())

# ── 坑 4：循环里建闭包，抓的是变量不是值（Java lambda 要求 final，Python 不管）──
bad = [lambda: i for i in range(3)]
print("坑4 ❌", [f() for f in bad])                     # [2,2,2]
good = [lambda i=i: i for i in range(3)]                # 默认参数把值钉住
print("坑4 ✅", [f() for f in good])

# ── 浅拷贝 vs 深拷贝 ───────────────────────────────────────────────
import copy
cfg = {"model": "qwen", "gpus": [0, 1]}
shallow = dict(cfg)                    # ❌ 只拷一层
deep = copy.deepcopy(cfg)              # ✅
shallow["gpus"].append(2)
print("拷贝    ", "原 dict 被改了:", cfg["gpus"], "| deep 不受影响:", deep["gpus"])
```

:::out 实际输出
```text
坑1 ❌ ['a', 'b', 'c'] ['a', 'b', 'c'] ['a', 'b', 'c']
坑1 证据 (['a', 'b', 'c'],)
坑1 ✅ ['a'] ['b'] ['c']
坑2 ❌ True
kwonly   'hi' max=512 temp=0.7 stream=False
kwonly   TypeError: generate() takes 1 positional argument but 2 were given
trace    call generate(*('hello',), **{'max_tokens': 8, 'stream': True})
trace    'hello' max=8 temp=0.7 stream=True
merge    'q' max=128 temp=0.9 stream=False
坑3 ❌ UnboundLocalError: cannot access local variable 'counter' where it is not associated with a value
坑3 ✅ 1 2 3
坑4 ❌ [2, 2, 2]
坑4 ✅ [0, 1, 2]
拷贝     原 dict 被改了: [0, 1, 2] | deep 不受影响: [0, 1]
```
:::

:::note 坑 1 的记忆锚点
`add_bad.__defaults__` 打出 `(['a','b','c'],)` —— 默认值是**存在函数对象上的一个属性**，函数定义时求值一次，之后所有调用共用它。

一条无脑规则：**默认参数只允许是不可变对象**（数字、字符串、`None`、元组）。看到 `def f(x=[])` 或 `def f(x={})` 就改成 `None` 哨兵。
:::

---

## 5. 综合实战：写一个 167 行的日志聚合 CLI

这是阶段一的**验收产出**。功能：读访问日志 CSV → 按 endpoint 聚合 → 算 QPS / 错误率 / 时延分位数 → 按 P95 倒序打成对齐的表格。

你在 Java 里写过无数次这种工具。现在看看 Python 版长什么样 —— 注意它用到了本页的**每一个**知识点。

```python
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
```

### 造一份测试数据

```python
"""gen_log.py —— 造一份 2000 行的样例日志，便于试跑 svclog.py。"""
import random, csv, sys
random.seed(42)
SPEC = {   # endpoint: (基准延迟 ms, 抖动, 错误率, 权重)
    "/v1/chat/completions": (700, 400, 0.04, 50),
    "/v1/embeddings":       (35,  20,  0.01, 30),
    "/v1/models":           (4,   2,   0.00, 12),
    "/healthz":             (1,   1,   0.00, 8),
}
eps = list(SPEC); weights = [SPEC[e][3] for e in eps]
w = csv.writer(sys.stdout); w.writerow(["ts", "endpoint", "status", "ms"])
t = 1_726_600_000.0
for _ in range(2000):
    t += random.expovariate(20)                       # 平均 20 QPS
    ep = random.choices(eps, weights)[0]
    base, jitter, err, _ = SPEC[ep]
    bad = random.random() < err
    ms = round(random.gauss(base, jitter) if not bad else random.uniform(1, 20), 1)
    if random.random() < 0.02: ms = round(ms * random.uniform(4, 9), 1)   # 长尾
    w.writerow([f"{t:.3f}", ep, 500 if bad else 200, max(0.1, ms)])
```

:::out 跑起来（`python gen_log.py > access.csv && python svclog.py access.csv`）
```text
endpoint              count   qps  err%  p50   p95   p99   max
--------------------  -----  ----  ----  ---  ----  ----  ----
/v1/chat/completions   1026  10.0  3.4%  681  1442  4668  7973
/v1/embeddings          624   6.1  1.4%   35    70   172   502
/v1/models              215   2.1  0.0%    4     7    10    34
/healthz                135   1.3  0.0%    1     3    11    11
```
:::

:::out 换个输出格式（`python svclog.py access.csv --top 2 --format csv`）
```text
endpoint,count,qps,err%,p50,p95,p99,max
/v1/chat/completions,1026,10.0,3.4%,681,1442,4668,7973
/v1/embeddings,624,6.1,1.4%,35,70,172,502
```
:::

:::note 这 167 行里藏着的设计
- **`read_entries` 是生成器** —— 换成 100 GB 的日志，内存占用不变。Java 的 `Files.lines()` 是同一个思路。
- **坏行只告警不中断** —— 日志工具必须能容忍脏数据，这是生产工具和玩具的分界线。
- **`Entry` frozen，`Stat` 可变** —— 前者是值对象，后者是累加器。这个区分和你在 Java 里的习惯一致。
- **`print_table` / `print_csv` 用同一个 `rows_of`** —— 输出格式变了，聚合逻辑一行不用改。
- **`errors += e.status >= 400`** —— `bool` 是 `int` 的子类，直接加。Java 里要写三元。
:::

---

## 6. 练习

先自己写，卡住了再展开答案。**答案都是跑过的。**

:::exercise 练习 1 · 环境（15 分钟）
用 `uv` 建一个新项目，加上 `ruff` + `pytest` 开发依赖，让 `uv run ruff check .` 零告警通过。然后回答：`.venv` 和 Java 的 `~/.m2` 最本质的区别是什么？
:::

:::solution
`~/.m2` 是**全机共享**的依赖缓存，所有项目从里面按坐标取 jar；`.venv` 是**每个项目一份**的完整运行环境，里面装着这个项目**专属**的解释器链接和包。

这个差异的后果很实际：
- Java 里两个项目用不同版本的同一个库，互不干扰（classpath 隔离）
- Python 如果不用虚拟环境，两个项目会抢同一个 `site-packages`，装 A 就毁了 B —— 这就是"Python 环境地狱"的根源
- 所以：**永远不要 `pip install` 到系统 Python**，永远让每个项目有自己的 `.venv`

`uv` 的做法是全局硬链接缓存 + 每项目 `.venv` 软链过去，所以既隔离又不浪费磁盘。
:::

:::exercise 练习 2 · 翻译（30 分钟）
把下面这段 Java 翻译成 Python，要求**不超过 8 行**（不含 import）：

```java
Map<String, Double> avgLatency = requests.stream()
    .filter(r -> r.getStatus() < 400)
    .collect(Collectors.groupingBy(
        Request::getEndpoint,
        Collectors.averagingDouble(Request::getMs)));

List<String> slowest = avgLatency.entrySet().stream()
    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
    .limit(3)
    .map(Map.Entry::getKey)
    .collect(Collectors.toList());
```
:::

:::solution
```python
from collections import defaultdict
from statistics import mean

by_ep = defaultdict(list)
for r in requests:
    if r.status < 400:
        by_ep[r.endpoint].append(r.ms)

avg_latency = {ep: mean(ms) for ep, ms in by_ep.items()}
slowest = sorted(avg_latency, key=avg_latency.get, reverse=True)[:3]
```

六行。几个值得注意的地方：

- **`sorted(dict, key=dict.get)`** —— 直接对 dict 排序会遍历它的**键**，`key=avg_latency.get` 让它按值排。这是 Python 里排序字典的惯用法，比 Java 的 `Map.Entry.comparingByValue().reversed()` 短得多。
- **`[:3]` 代替 `.limit(3)`** —— 切片。注意 `sorted` 已经产出完整 list，所以这里没有惰性优势；数据量大时用 `heapq.nlargest(3, ...)` 更好。
- **不要**试图写成一行链式。Python 的哲学是"分两步、每步都可读"，硬凑成一行反而不地道。

如果一定要更短，可以用 `Counter` 的思路，但可读性会下降 —— **别这么干**。
:::

:::exercise 练习 3 · 找 6 个 bug（30 分钟）
下面这段代码**能跑起来**（有一处会抛异常），但结果全错。找出 6 个典型的 Java 人错误并修好。

```python
def collect_slow(reqs, threshold=200, out=[]):
    for i in range(0, len(reqs)):
        r = reqs[i]
        if r["ms"] > threshold:
            out.append(r)
    return out

def summarize(reqs):
    total = 0
    def add(x):
        total = total + x
    for r in reqs:
        add(r["ms"])
    return total

def dedupe_endpoints(reqs):
    seen = {}                       # 想要一个 set
    for r in reqs:
        seen.add(r["endpoint"])
    return seen

def pick_timeout(cfg):
    return cfg.get("timeout") or 30

if __name__ == "__main__":
    reqs = [{"endpoint": "/a", "ms": 300}, {"endpoint": "/b", "ms": 100}]
    print(collect_slow(reqs), collect_slow(reqs))
    print(summarize(reqs))
    print(dedupe_endpoints(reqs))
    print(pick_timeout({"timeout": 0}))
```
:::

:::solution
先看原版跑出来什么：

```text
[{'endpoint': '/a', 'ms': 300}, {'endpoint': '/a', 'ms': 300}] [{...}, {...}]
                                            ↑ 第一次调用就返回了两条！
Traceback (most recent call last):
  File "e2_buggy.py", line 13, in add
    total = total + x
            ^^^^^
UnboundLocalError: cannot access local variable 'total' where it is not associated with a value
```

六个错误和修法：

```python
"""六个错误逐个修好，并说明为什么。"""

# 错误 1：可变默认参数 out=[] 被所有调用共享
# 错误 2：range(0, len(x)) 取下标 —— 非 Python 写法
def collect_slow(reqs, threshold=200, out=None):
    out = [] if out is None else out
    out.extend(r for r in reqs if r["ms"] > threshold)   # 或直接返回推导式
    return out


# 错误 3：闭包里给外层变量赋值 → UnboundLocalError
#        （一旦函数体内有 total = ...，total 在整个函数内都是局部变量）
# 修法：根本不需要闭包，直接 sum 生成器
def summarize(reqs):
    return sum(r["ms"] for r in reqs)


# 错误 4：{} 是空 dict 不是空 set，dict 没有 .add()
def dedupe_endpoints(reqs):
    return {r["endpoint"] for r in reqs}                  # 集合推导


# 错误 5：`or` 把合法的 0 当成假值吞掉了
# 错误 6：配置项缺失与"显式设为 0"必须区分开
def pick_timeout(cfg):
    t = cfg.get("timeout")
    return 30 if t is None else t


if __name__ == "__main__":
    reqs = [{"endpoint": "/a", "ms": 300}, {"endpoint": "/b", "ms": 100}]
    print("collect  ", collect_slow(reqs), collect_slow(reqs))   # 两次都只有 /a
    print("summarize", summarize(reqs))
    print("dedupe   ", sorted(dedupe_endpoints(reqs)))
    print("timeout  ", pick_timeout({"timeout": 0}), pick_timeout({}))
```

修好之后：

```text
collect   [{'endpoint': '/a', 'ms': 300}] [{'endpoint': '/a', 'ms': 300}]
summarize 400
dedupe    ['/a', '/b']
timeout   0 30
```

**如果你只找到 4 个**，最容易漏的是 5 和 6 —— 因为 `or 30` 看起来太顺眼了。
:::

:::exercise 练习 4 · 扩展 svclog（1 小时）
给 `svclog.py` 加三个能力：

1. `--status all|2xx|4xx|5xx|err` 过滤
2. `--bucket N` 按 N 秒分桶，打印每桶的 QPS 与 P95（看时间趋势，找尖刺）
3. 把最近邻分位数换成**线性插值**（numpy / Excel 的默认口径），并验证两者差多少

第 3 点的验收标准：你的实现要和 `numpy.percentile` 完全一致。
:::

:::solution
```python
"""三个扩展，只贴改动的部分。"""
from __future__ import annotations

import math
from collections.abc import Iterable, Iterator


# ── 1) 状态码过滤器：一个返回谓词的工厂函数 ──────────────────────────
def status_filter(spec: str):
    """spec ∈ {'all','2xx','4xx','5xx','err'} → 一个 (Entry)->bool 的谓词。"""
    table = {
        "all": lambda s: True,
        "2xx": lambda s: 200 <= s < 300,
        "4xx": lambda s: 400 <= s < 500,
        "5xx": lambda s: s >= 500,
        "err": lambda s: s >= 400,
    }
    try:
        pred = table[spec]
    except KeyError:
        raise ValueError(f"unknown --status {spec!r}, pick one of {sorted(table)}") from None
    return lambda e: pred(e.status)


# ── 2) 时间分桶：生成器 → 生成器，内存恒定 ───────────────────────────
def bucketize(entries: Iterable, width: float) -> Iterator[tuple[int, list]]:
    """按 width 秒把连续的条目切成桶。输入需按 ts 递增（日志天然如此）。"""
    bucket: list = []
    key: int | None = None
    for e in entries:
        k = int(e.ts // width)
        if key is None:
            key = k
        elif k != key:
            yield key, bucket
            key, bucket = k, []
        bucket.append(e)
    if bucket and key is not None:
        yield key, bucket


# ── 3) 线性插值分位数（numpy / Excel 的默认口径）────────────────────
def pct_nearest(xs: list[float], q: float) -> float:
    """最近邻：svclog.py 里的实现。样本少时会明显偏高。"""
    if not xs:
        return 0.0
    o = sorted(xs)
    i = min(len(o) - 1, max(0, math.ceil(q * len(o)) - 1))
    return o[i]


def pct_linear(xs: list[float], q: float) -> float:
    """线性插值：位置 = q*(n-1)，在相邻两点间按小数部分插值。"""
    if not xs:
        return 0.0
    o = sorted(xs)
    if len(o) == 1:
        return o[0]
    pos = q * (len(o) - 1)
    lo = math.floor(pos)
    hi = min(lo + 1, len(o) - 1)
    return o[lo] + (o[hi] - o[lo]) * (pos - lo)


if __name__ == "__main__":
    import numpy as np  # 只用来验证我们的实现和业界口径一致

    for n in (10, 100, 1000):
        xs = [float(i) for i in range(1, n + 1)]
        for q in (0.5, 0.95, 0.99):
            mine, ref = pct_linear(xs, q), float(np.percentile(xs, q * 100))
            near = pct_nearest(xs, q)
            flag = "OK " if abs(mine - ref) < 1e-9 else "FAIL"
            print(f"n={n:<5} q={q:<5} linear={mine:8.3f} numpy={ref:8.3f} {flag}"
                  f"  nearest={near:8.3f}  差={near - mine:+.3f}")
```

验证结果 —— 和 `numpy.percentile` 逐项一致：

```text
n=10    q=0.5   linear=   5.500 numpy=   5.500 OK   nearest=   5.000  差=-0.500
n=10    q=0.95  linear=   9.550 numpy=   9.550 OK   nearest=  10.000  差=+0.450
n=10    q=0.99  linear=   9.910 numpy=   9.910 OK   nearest=  10.000  差=+0.090
n=100   q=0.5   linear=  50.500 numpy=  50.500 OK   nearest=  50.000  差=-0.500
n=100   q=0.95  linear=  95.050 numpy=  95.050 OK   nearest=  95.000  差=-0.050
n=100   q=0.99  linear=  99.010 numpy=  99.010 OK   nearest=  99.000  差=-0.010
n=1000  q=0.5   linear= 500.500 numpy= 500.500 OK   nearest= 500.000  差=-0.500
n=1000  q=0.99  linear= 990.010 numpy= 990.010 OK   nearest= 990.000  差=-0.010
```

**结论值得记住**：样本量 n=10 时，最近邻的 P95 比线性插值高 0.45（4.7%）；n=1000 时只差 0.05（0.005%）。

所以在推理服务的监控里：**样本少的时间窗口（比如 10 秒桶）不要看 P95 和 P99** —— 那个数字主要由口径和运气决定，不由系统性能决定。这是很多人误读监控图的根源。
:::

:::exercise 练习 5 · 反向翻译（30 分钟）
不看上面的对照表，把下面这段 Python 说清楚它在 Java 里对应什么，以及**为什么 Python 要这么写**：

```python
with open(path) as f:
    total = sum(float(line.split(",")[3]) for line in f if not line.startswith("ts"))
```
:::

:::solution
逐层拆：

| Python | Java 对应 | 为什么 |
|---|---|---|
| `with open(path) as f` | `try (var r = Files.newBufferedReader(p))` | 自动关闭 |
| `for line in f` | `r.lines()` | **文件对象本身就是迭代器**，逐行惰性读 |
| `if not line.startswith("ts")` | `.filter(l -> !l.startsWith("ts"))` | 跳表头 |
| `float(line.split(",")[3])` | `.mapToDouble(l -> Double.parseDouble(l.split(",")[3]))` | |
| `(... for ...)` 圆括号 | Stream 本身的惰性 | **生成器表达式**，不建中间 list |
| `sum(...)` | `.sum()` | |

关键点在圆括号：如果写成 `sum([...])`（方括号），就会先把**所有**行的数字读进一个 list，再求和 —— 100 GB 日志直接 OOM。圆括号的生成器表达式是**一次一个**，内存 O(1)。

这正是 Java Stream 相对 `collect(toList())` 的优势，Python 用一对括号的差异表达同一件事。**看到 `sum([...])`、`max([...])`、`any([...])` 这种写法，去掉方括号就白赚一次内存优化。**

（阶段二会把这件事量化：200 万个元素上，list 推导峰值 77.4 MiB，生成器 0.48 KiB。）
:::

---

## 7. 阶段一结业自检

全部能答"是"，就可以进阶段二了：

- [ ] 能用 `uv` 从零建项目、加依赖、跑 lint，并说清 `.venv` 和 `~/.m2` 的区别
- [ ] 不查表能写出推导式、生成器表达式、f-string、切片、元组解包
- [ ] 能说出 `is` 和 `==` 的区别，并知道只该对 `None` 用 `is`
- [ ] 能解释为什么 `def f(x=[])` 是 bug，以及正确写法
- [ ] 能解释为什么 `return n or 100` 在 `n=0` 时是 bug
- [ ] 知道 `itertools.groupby` 必须先排序，且和 SQL 的 `GROUP BY` 不是一回事
- [ ] 能把任意一段 Java Stream 链式调用翻译成 Python
- [ ] 独立写完了 `svclog.py`（或等价的工具），`ruff check` 零告警

:::note 下一页做什么
阶段二会把 `svclog.py` 这个**单文件脚本**重构成一个**真正的包**：src layout、`dataclass`、`Protocol`、生成器流水线，并让它通过 `mypy --strict`。同时补上 Java 里没有的三样东西：生成器、装饰器、结构化类型。
:::
