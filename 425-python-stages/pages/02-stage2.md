{"slug":"py-stage2-python-model",
 "title":"阶段二：对象模型 + Java 没有的那些机制（6 小时，含全部可运行代码）",
 "summary":"dataclass / Protocol / 生成器 / 装饰器 / 类型注解，五段可运行示例加一次真实重构：把阶段一的单文件脚本改成 6 模块的包，通过 mypy --strict。",
 "eyebrow":"阶段二 / 5 · Stage 2",
 "h1":"对象模型 + Java 没有的那些机制",
 "sub":"Java 的 interface 在 Python 里对应三样东西，选错一个就会写出别扭的代码。这 6 小时补齐生成器、装饰器、结构化类型 —— 这三样是 AI Infra 代码的地基。",
 "facts":["<b>6 小时</b>","代码 <b>全部可运行</b>","练习 <b>5 个</b>","产出：<b>6 模块的包，mypy --strict 通过</b>"],
 "footer":"看板任务 #425「Python学习」· 本页是<a href=\"/p/python-ai-infra-40h.html\">《40 小时学习路线》(#422)</a> 阶段二的实操展开版。所有代码与输出均在 Python 3.12.14 上实测。"}
---FM---

:::goal 这一页结束时，你应该能做到
- 在 `dataclass` / `NamedTuple` / `TypedDict` 之间正确选型，并知道 `frozen=True` **拦不住什么**
- 在 `Protocol` / `ABC` / 鸭子类型之间正确选型（提示：Java 的 `interface` 默认对应 `Protocol`，不是 `ABC`）
- 用生成器搭出内存 O(1) 的数据流水线，并说出 `itertools` 里最该记的六个函数
- 写出带参数的装饰器，并解释为什么 `functools.wraps` 不能漏
- 把一个单文件脚本重构成 src layout 的包，让 `mypy --strict` 通过
:::

阶段一把 Java 平移了过来。这一页处理**Java 里没有对应物**的部分 —— 它们不是语法糖，是你后面读 PyTorch、FastAPI、vLLM 源码时每一页都会撞见的东西。

---

## 1. dataclass：record + Lombok，但选项更多

```python
"""阶段二 2.1 —— dataclass 就是 Java 的 record + Lombok，但选项更多。"""
from dataclasses import dataclass, field, asdict, replace, fields
import sys

# ── 最常见的四种配方 ─────────────────────────────────────────────────

@dataclass(frozen=True, slots=True)          # ≈ Java record（不可变 + 省内存）
class ModelSpec:
    name: str
    dtype: str = "bfloat16"
    params_b: float = 7.0
    tags: list[str] = field(default_factory=list)   # 可变默认值必须 factory

    def __post_init__(self) -> None:         # ≈ record 的 compact constructor
        if self.params_b <= 0:
            raise ValueError("params_b must be > 0")

    @property                                # ≈ 派生 getter
    def weight_gib(self) -> float:
        bytes_per = {"float32": 4, "bfloat16": 2, "float16": 2, "fp8": 1, "int4": 0.5}
        return self.params_b * 1e9 * bytes_per[self.dtype] / 2**30

    @classmethod                             # ≈ 静态工厂，Python 的"构造器重载"
    def from_hf_id(cls, hf_id: str, **kw) -> "ModelSpec":
        size = hf_id.rsplit("-", 1)[-1].rstrip("Bb")
        return cls(name=hf_id, params_b=float(size), **kw)


m = ModelSpec.from_hf_id("Qwen3-8B", tags=["chat"])
print("repr    ", m)                          # __repr__ 自动生成
print("weight  ", f"{m.weight_gib:.1f} GiB")
print("eq      ", m == ModelSpec.from_hf_id("Qwen3-8B", tags=["chat"]))   # 值相等

# ⚠️ frozen 不等于"深度不可变"：字段里有 list 就既不可 hash、内容也仍可改
try:
    hash(m)
except TypeError as e:
    print("hash ❌  ", e, "← tags 是 list")
m.tags.append("偷偷改了")                     # frozen 拦不住这个！
print("frozen 漏 ", m.tags)

@dataclass(frozen=True, slots=True)
class Spec2:                                  # ✅ 不可变字段用 tuple
    name: str
    tags: tuple[str, ...] = ()
print("hash ✅  ", hash(Spec2("a", ("x",))) == hash(Spec2("a", ("x",))))

# frozen ⇒ 想"改"就复制一份（≈ record 的 withers）
m2 = replace(m, dtype="fp8")
print("replace ", m2.dtype, f"{m2.weight_gib:.1f} GiB")
try:
    m.dtype = "fp8"
except Exception as e:
    print("frozen  ", type(e).__name__, e)

# 内省：字段列表、转 dict（≈ Jackson 的序列化）
print("fields  ", [f.name for f in fields(m)])
print("asdict  ", asdict(m))

# __post_init__ 做校验
try:
    ModelSpec("bad", params_b=0)
except ValueError as e:
    print("validate", e)

# ── slots 到底省多少内存？实测 ────────────────────────────────────────
@dataclass
class Fat:
    a: int; b: int; c: int
@dataclass(slots=True)
class Slim:
    a: int; b: int; c: int

def deep_size(o) -> int:
    n = sys.getsizeof(o)
    d = getattr(o, "__dict__", None)
    return n + (sys.getsizeof(d) if d is not None else 0)

print("slots   ", f"无 slots={deep_size(Fat(1,2,3))}B  有 slots={deep_size(Slim(1,2,3))}B")

# ── 排序：order=True 自动生成 __lt__ 等（≈ Comparable）────────────────
@dataclass(order=True, frozen=True)
class Job:
    priority: int
    name: str = field(compare=False)          # 不参与比较，只参与 repr/eq

jobs = [Job(3, "eval"), Job(1, "serve"), Job(2, "train")]
print("order   ", [j.name for j in sorted(jobs)])

# ── 三种"值对象"怎么选 ───────────────────────────────────────────────
from typing import NamedTuple, TypedDict
class Point(NamedTuple):      # 不可变、可解包、可当 dict key，比 dataclass 更轻
    x: float
    y: float
class Payload(TypedDict):     # 只是给 dict 加类型，运行时仍是普通 dict
    prompt: str
    max_tokens: int

p = Point(1.0, 2.0); x, y = p
print("namedtup", p, x, y, p._asdict())
d: Payload = {"prompt": "hi", "max_tokens": 8}
print("typeddic", type(d).__name__, d)
```

:::out 实际输出
```text
repr     ModelSpec(name='Qwen3-8B', dtype='bfloat16', params_b=8.0, tags=['chat'])
weight   14.9 GiB
eq       True
hash ❌   unhashable type: 'list' ← tags 是 list
frozen 漏  ['chat', '偷偷改了']
hash ✅   True
replace  fp8 7.5 GiB
frozen   FrozenInstanceError cannot assign to field 'dtype'
fields   ['name', 'dtype', 'params_b', 'tags']
asdict   {'name': 'Qwen3-8B', 'dtype': 'bfloat16', 'params_b': 8.0, 'tags': ['chat', '偷偷改了']}
validate params_b must be > 0
slots    无 slots=344B  有 slots=56B
order    ['serve', 'train', 'eval']
namedtup Point(x=1.0, y=2.0) 1.0 2.0 {'x': 1.0, 'y': 2.0}
typeddic dict {'prompt': 'hi', 'max_tokens': 8}
```
:::

:::warn `frozen=True` 只冻结「引用」，不冻结「内容」
`m.tags.append("偷偷改了")` 成功了 —— `frozen` 只拦住 `m.tags = [...]` 这种**重新赋值**，拦不住对 list 内部的修改。这和 Java 的 `final List` 是同一个语义。

后果有两层：
1. **不可 hash** —— 不能放进 `set`、不能当 dict key，`@lru_cache` 也用不了
2. **不是真的线程/进程安全** —— 你以为传了个值对象过去，其实共享了同一个 list

规则：**`frozen=True` 的 dataclass，字段只能是不可变类型**（`str` / `int` / `float` / `tuple` / `frozenset` / 另一个 frozen dataclass）。要序列就用 `tuple[str, ...]`。
:::

:::note `slots=True` 省了 6 倍内存（344 B → 56 B）
普通对象把属性存在每实例一份的 `__dict__` 里；`slots=True` 改成固定槽位，省掉整个字典。

什么时候值得开：**你要创建几十万个这种对象的时候**。日志条目、张量元信息、请求记录都符合。代价是不能动态加属性、多继承会受限 —— 对值对象来说都不是问题。
:::

| 场景 | 选什么 | 理由 |
|---|---|---|
| 业务值对象、要方法和校验 | `@dataclass` | 最通用 |
| 不可变、要当 dict key | `@dataclass(frozen=True, slots=True)` | ≈ record |
| 极轻量、要解包、要当 key | `NamedTuple` | 比 dataclass 更省，能 `x, y = p` |
| 只是给现成的 dict 标类型 | `TypedDict` | 运行时仍是 dict，零开销 |
| 要**运行时校验**（API 边界） | **pydantic `BaseModel`** | 见阶段四 |
| 大量对象、只读 | 加 `slots=True` | 内存省 6 倍 |

---

## 2. interface 的三个对应物：Protocol / ABC / 鸭子类型

```python
"""阶段二 2.1b —— Java 的 interface 在 Python 里有三种对应物，用哪个？"""
from abc import ABC, abstractmethod
from typing import Protocol, runtime_checkable

# ── 方式 A：Protocol —— 结构化类型（"长得像就算"）。infra 代码首选 ──────
class Tokenizer(Protocol):
    """注意：实现方 **不需要** import 这个类，更不需要 implements。"""
    def encode(self, text: str) -> list[int]: ...
    def decode(self, ids: list[int]) -> str: ...


class CharTokenizer:                       # 完全不知道 Tokenizer 存在
    def encode(self, text: str) -> list[int]:
        return [ord(c) for c in text]
    def decode(self, ids: list[int]) -> str:
        return "".join(chr(i) for i in ids)


class WordTokenizer:
    def __init__(self) -> None:
        self.vocab: dict[str, int] = {}
    def encode(self, text: str) -> list[int]:
        return [self.vocab.setdefault(w, len(self.vocab)) for w in text.split()]
    def decode(self, ids: list[int]) -> str:
        rev = {v: k for k, v in self.vocab.items()}
        return " ".join(rev[i] for i in ids)


def count_tokens(texts: list[str], tok: Tokenizer) -> dict[str, int]:
    """参数标 Protocol，mypy 会在调用点检查结构是否匹配。"""
    return {t: len(tok.encode(t)) for t in texts}


texts = ["hello world", "ai infra"]
print("char    ", count_tokens(texts, CharTokenizer()))
print("word    ", count_tokens(texts, WordTokenizer()))
print("roundtrip", CharTokenizer().decode(CharTokenizer().encode("gpu")))

# @runtime_checkable 让 isinstance 也能用（只查方法名，不查签名！）
@runtime_checkable
class Closeable(Protocol):
    def close(self) -> None: ...

class Conn:
    def close(self) -> None: ...
print("isinst  ", isinstance(Conn(), Closeable), isinstance(42, Closeable))

# ── 方式 B：ABC —— 名义类型，强制继承。需要"共享实现"时才用 ──────────
class Backend(ABC):
    @abstractmethod
    def generate(self, prompt: str) -> str: ...

    def generate_batch(self, prompts: list[str]) -> list[str]:
        """模板方法：子类只补 generate，批量版免费得到（≈ 抽象类的具体方法）。"""
        return [self.generate(p) for p in prompts]


class EchoBackend(Backend):
    def generate(self, prompt: str) -> str:
        return prompt.upper()

print("abc     ", EchoBackend().generate_batch(["a", "b"]))
try:
    Backend()                               # 抽象类不能实例化，和 Java 一样
except TypeError as e:
    print("abc     ", type(e).__name__, str(e)[:60])

# ── 方式 C：纯鸭子类型 —— 什么都不声明。脚本里可以，库里别这么干 ────
def close_all(things: list) -> None:
    for t in things:
        t.close()                           # 直到运行时才知道有没有 close
```

:::out 实际输出
```text
char     {'hello world': 11, 'ai infra': 8}
word     {'hello world': 2, 'ai infra': 2}
roundtrip gpu
isinst   True False
abc      ['A', 'B']
abc      TypeError Can't instantiate abstract class Backend without an implemen
```
:::

### 为什么说 `Protocol` 才是 Java `interface` 的默认对应物

写一个**签名不匹配**的"实现"，看看谁能抓住它：

```python
"""故意写错：签名不匹配的"实现"。mypy 会抓住，运行时不会。"""
from b_protocol import Tokenizer, count_tokens

class BrokenTokenizer:
    def encode(self, text: str) -> str:      # ← 返回 str，应该是 list[int]
        return text
    # 还漏了 decode

print(count_tokens(["hi"], BrokenTokenizer()))   # 运行时居然能跑出结果！
```

:::out 运行时：错误代码照跑不误
```text
{'hi': 2}
```
:::

`len("hi")` 是 2 —— 看起来像个 token 数，实际是**字符串长度**。这种 bug 不会抛异常，只会让你的 token 计费少算十倍。

:::out `mypy --strict` 抓住了
```text
error: Argument 2 to "count_tokens" has incompatible type "BrokenTokenizer"; expected "Tokenizer"  [arg-type]
note: "BrokenTokenizer" is missing following "Tokenizer" protocol member:
note:     decode
note: Following member(s) of "BrokenTokenizer" have conflicts:
note:     Expected:
note:         def encode(self, text: str) -> list[int]
note:     Got:
note:         def encode(self, text: str) -> str
Found 1 error in 1 file (checked 1 source file)
```
:::

| 需求 | 选什么 |
|---|---|
| 想让**第三方**类型也能满足契约、不想让实现方 import 你 | **`Protocol`** |
| 需要共享实现 / 模板方法 / 强制子类实现 | `ABC` |
| 一次性脚本、内部胶水 | 鸭子类型 |
| Java 的 `interface` 默认对应 | **`Protocol`** |

:::note 这个选择在 AI Infra 里很具体
你要给推理网关写一个 `Backend` 抽象，真实现接 vLLM，测试用假后端。

用 `ABC`：假后端必须 `from yourpkg import Backend` 再继承 —— 测试代码依赖了生产代码的类型。
用 `Protocol`：假后端就是个普通类，有 `stream()` 方法就行。**换后端不需要改一行网关代码**，这正是阶段五那个网关的做法。
:::

---

## 3. 生成器：Python 相对 Java 的真实增益 ★

这是本页最重要的一节。数据管道、流式响应、DataLoader 的底层全是这套协议。

```python
"""阶段二 2.3 —— 生成器：Python 相对 Java 的真实增益，也是数据管道的地基。"""
import itertools, sys, tracemalloc
from collections.abc import Iterator, Iterable

# ── 1. yield 让函数变成惰性流 ────────────────────────────────────────
def read_batches(lines: Iterable[str], size: int = 3) -> Iterator[list[str]]:
    batch: list[str] = []
    for line in lines:
        batch.append(line.strip())
        if len(batch) == size:
            yield batch                      # 交出一批，函数在这里"暂停"
            batch = []
    if batch:                                # 别忘了最后不足一批的尾巴
        yield batch

print("1.", list(read_batches([f"l{i}" for i in range(8)])))

# ── 2. 生成器是"暂停的函数"，可以亲眼看到它暂停 ──────────────────────
def noisy() -> Iterator[int]:
    print("   [gen] 开始")
    for i in range(3):
        print(f"   [gen] 准备产出 {i}")
        yield i
    print("   [gen] 结束")

g = noisy()
print("2. 创建了生成器，注意上面什么都没打印:", type(g).__name__)
print("2. next →", next(g))
print("2. next →", next(g))
print("2. 剩下的:", list(g))

# ── 3. 管道组合：每一级都是生成器，全流程内存 O(1) ───────────────────
def parse(lines: Iterable[str]) -> Iterator[dict]:
    for ln in lines:
        ep, ms = ln.split()
        yield {"endpoint": ep, "ms": float(ms)}

def slow_only(rows: Iterable[dict], threshold: float) -> Iterator[dict]:
    yield from (r for r in rows if r["ms"] > threshold)     # yield from = 转发

raw = ["/chat 812", "/healthz 1.2", "/chat 1340", "/embed 45", "/chat 3"]
pipeline = slow_only(parse(raw), 100)         # 到这里一行都还没读
print("3.", list(pipeline))

# ── 4. 内存实测：list 推导 vs 生成器 ─────────────────────────────────
N = 2_000_000
tracemalloc.start()
eager = [i * i for i in range(N)]
peak_eager = tracemalloc.get_traced_memory()[1]
del eager; tracemalloc.reset_peak()
lazy = (i * i for i in range(N))
total = sum(lazy)
peak_lazy = tracemalloc.get_traced_memory()[1]
tracemalloc.stop()
print(f"4. N={N:,}   list 推导峰值 = {peak_eager/2**20:7.1f} MiB"
      f"   |   生成器峰值 = {peak_lazy/2**10:.2f} KiB"
      f"   |   相差 {peak_eager/max(peak_lazy,1):,.0f} 倍")

# ── 5. 生成器只能消费一次 —— Java Stream 同理，但更容易忘 ────────────
g2 = (i for i in range(3))
print("5. 第一次", list(g2), "第二次", list(g2), "← 空了")

# ── 6. itertools：AI 数据管道天天用的六个 ────────────────────────────
stream = (i for i in itertools.count())                  # 无限流！
print("6. islice ", list(itertools.islice(stream, 5)))    # ≈ .limit(5)
print("6. batched", [list(b) for b in itertools.batched(range(7), 3)])  # 3.12+
print("6. chain  ", list(itertools.chain([1, 2], [3], [4, 5])))         # ≈ Stream.concat
print("6. tee    ", [list(t) for t in itertools.tee([1, 2, 3], 2)])     # 一分为二
print("6. pairwise", list(itertools.pairwise([1, 2, 3, 4])))            # 相邻两两
print("6. cycle  ", list(itertools.islice(itertools.cycle("AB"), 5)))   # 轮询调度

# ── 7. 生成器可以接收数据（很少用，但 asyncio 的底层就是它）──────────
def accumulator() -> Iterator[float]:
    total = 0.0
    while True:
        x = yield total                      # yield 既产出也接收
        total += x

acc = accumulator(); next(acc)               # 先"预热"到第一个 yield
print("7.", acc.send(10), acc.send(5), acc.send(0.5))

# ── 8. 生成器要清理资源？用 try/finally，close() 会触发它 ────────────
def with_cleanup() -> Iterator[int]:
    try:
        yield 1
        yield 2
    finally:
        print("   [gen] 清理执行了")

g3 = with_cleanup(); print("8. 取了", next(g3)); g3.close()
```

:::out 实际输出
```text
1. [['l0', 'l1', 'l2'], ['l3', 'l4', 'l5'], ['l6', 'l7']]
2. 创建了生成器，注意上面什么都没打印: generator
   [gen] 开始
   [gen] 准备产出 0
2. next → 0
   [gen] 准备产出 1
2. next → 1
   [gen] 准备产出 2
   [gen] 结束
2. 剩下的: [2]
3. [{'endpoint': '/chat', 'ms': 812.0}, {'endpoint': '/chat', 'ms': 1340.0}]
4. N=2,000,000   list 推导峰值 =    77.4 MiB   |   生成器峰值 = 0.48 KiB   |   相差 163,564 倍
5. 第一次 [0, 1, 2] 第二次 [] ← 空了
6. islice  [0, 1, 2, 3, 4]
6. batched [[0, 1, 2], [3, 4, 5], [6]]
6. chain   [1, 2, 3, 4, 5]
6. tee     [[1, 2, 3], [1, 2, 3]]
6. pairwise [(1, 2), (2, 3), (3, 4)]
6. cycle   ['A', 'B', 'A', 'B', 'A']
7. 10.0 15.0 15.5
8. 取了 1
   [gen] 清理执行了
```
:::

:::note 第 4 行就是全部理由
**77.4 MiB vs 0.48 KiB，16 万倍。** 换成真实场景：一份 20 GB 的训练数据 JSONL，用 list 读进来直接 OOM，用生成器占用是常数。

第 2 段的输出也值得盯一眼 —— 创建生成器时**函数体一行都没执行**，`[gen] 开始` 是第一次 `next()` 才打印的。这个"暂停/恢复"的心智模型，就是 `async/await` 的前身。
:::

| itertools | 用途 | Java 类比 |
|---|---|---|
| `islice(it, n)` | 取前 n 个（对无限流也安全） | `.limit(n)` |
| `batched(it, n)` | 分批（3.12+，AI 里天天用） | 手写 |
| `chain(a, b)` | 拼接 | `Stream.concat` |
| `tee(it, 2)` | 一个流复制成两个 | 无（Stream 不能复用） |
| `pairwise(it)` | 相邻两两配对（算 ITL 用它） | 无 |
| `cycle(it)` | 无限轮询（做 round-robin 调度） | 无 |
| `groupby(it, key)` | **相邻**分组，**必须先排序** | `groupingBy`（语义不同！） |

---

## 4. 装饰器：Java 注解 + 动态代理，纯 Python 就能写完

```python
"""阶段二 2.4 —— 装饰器 ≈ Java 注解 + 动态代理，但纯 Python 就能写完。"""
import functools, random, time
from collections.abc import Callable
from typing import TypeVar, ParamSpec

P = ParamSpec("P"); R = TypeVar("R")

# ── 1. 最简装饰器：计时 ──────────────────────────────────────────────
def timed(fn: Callable[P, R]) -> Callable[P, R]:
    @functools.wraps(fn)                      # ★ 保留原函数的名字/docstring/签名
    def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
        t0 = time.perf_counter()
        try:
            return fn(*args, **kwargs)
        finally:
            print(f"   [timed] {fn.__name__} 耗时 {time.perf_counter()-t0:.4f}s")
    return wrapper

@timed
def load_model(name: str) -> str:
    """假装在加载权重。"""
    time.sleep(0.05)
    return f"{name}@loaded"

print("1.", load_model("qwen3-8b"))

# ── 2. 忘了 functools.wraps 会发生什么 ──────────────────────────────
def bad_deco(fn):
    def wrapper(*a, **k): return fn(*a, **k)
    return wrapper

@bad_deco
def documented() -> None:
    """我是文档。"""

print("2. 有 wraps:", load_model.__name__, "|", load_model.__doc__)
print("2. 无 wraps:", documented.__name__, "|", documented.__doc__, "← 元信息全丢了")

# ── 3. 带参数的装饰器 = 多一层函数（"装饰器工厂"）────────────────────
def retry(times: int = 3, backoff: float = 0.01,
          on: tuple[type[BaseException], ...] = (Exception,)):
    def deco(fn: Callable[P, R]) -> Callable[P, R]:
        @functools.wraps(fn)
        def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
            last: BaseException | None = None
            for attempt in range(1, times + 1):
                try:
                    return fn(*args, **kwargs)
                except on as e:
                    last = e
                    print(f"   [retry] 第 {attempt}/{times} 次失败: {e}")
                    if attempt < times:
                        time.sleep(backoff * 2 ** (attempt - 1))   # 指数退避
            raise RuntimeError(f"{fn.__name__} 重试 {times} 次仍失败") from last
        return wrapper
    return deco

random.seed(7)
@retry(times=4, on=(ConnectionError,))
def flaky_download(url: str) -> str:
    if random.random() < 0.6:
        raise ConnectionError("连接被重置")
    return f"{url} → 200 OK"

print("3.", flaky_download("https://hf.co/model.safetensors"))

# ── 4. 标准库自带的两个，你会天天用 ─────────────────────────────────
@functools.lru_cache(maxsize=128)             # ≈ Caffeine / Guava Cache
def tokenize_cost(text: str) -> int:
    time.sleep(0.02)                          # 假装很贵
    return len(text.split())

t0 = time.perf_counter(); tokenize_cost("a b c"); t1 = time.perf_counter()
tokenize_cost("a b c"); t2 = time.perf_counter()
print(f"4. lru_cache 首次 {t1-t0:.4f}s，命中 {t2-t1:.6f}s，{ (t1-t0)/max(t2-t1,1e-9):,.0f}× 快")
print("4. 缓存统计:", tokenize_cost.cache_info())

class Model:
    def __init__(self, path: str) -> None: self.path = path
    @functools.cached_property                # 第一次访问才算，之后存成实例属性
    def size_gib(self) -> float:
        print("   [cached_property] 真的去算了一次")
        return 14.9
m = Model("/w"); print("4.", m.size_gib, m.size_gib)   # 只算一次

# ── 5. 堆叠顺序（自下而上包裹，自上而下执行）────────────────────────
def announce(tag: str):
    def deco(fn):
        @functools.wraps(fn)
        def w(*a, **k):
            print(f"   [{tag}] enter"); r = fn(*a, **k); print(f"   [{tag}] exit"); return r
        return w
    return deco

@announce("outer")
@announce("inner")
def work() -> str:
    return "done"

print("5. 堆叠顺序 →"); print("5.", work())
```

:::out 实际输出
```text
   [timed] load_model 耗时 0.0501s
1. qwen3-8b@loaded
2. 有 wraps: load_model | 假装在加载权重。
2. 无 wraps: wrapper | None ← 元信息全丢了
   [retry] 第 1/4 次失败: 连接被重置
   [retry] 第 2/4 次失败: 连接被重置
3. https://hf.co/model.safetensors → 200 OK
4. lru_cache 首次 0.0201s，命中 0.000001s，20,474× 快
4. 缓存统计: CacheInfo(hits=1, misses=1, maxsize=128, currsize=1)
   [cached_property] 真的去算了一次
4. 14.9 14.9
5. 堆叠顺序 →
   [outer] enter
   [inner] enter
   [inner] exit
   [outer] exit
5. done
```
:::

:::warn 漏掉 `functools.wraps` 的真实代价
输出第二行：没加 `wraps` 的函数，`__name__` 变成了 `wrapper`，`__doc__` 变成了 `None`。

后果不止是"不好看"：
- **FastAPI 靠函数签名生成 OpenAPI 文档** —— 丢了签名，你的 `/docs` 页面会变成一堆 `wrapper(*args, **kwargs)`
- **pytest 靠函数名收集测试** —— 所有测试都叫 `wrapper`
- **日志和火焰图里全是 `wrapper`** —— 排障时你看不出是谁慢

一条规则：**写装饰器第一件事就是敲 `@functools.wraps(fn)`**，没有例外。
:::

你在 AI Infra 里会天天遇到的装饰器：

| 装饰器 | 作用 | Java 类比 |
|---|---|---|
| `@app.post("/v1/chat")` | FastAPI 路由 | `@PostMapping` |
| `@torch.inference_mode()` | 关梯度 + 省显存 | 无（推理路径必加） |
| `@ray.remote` | 函数变成集群任务 | 分布式 Executor |
| `@pytest.fixture` | 测试夹具依赖注入 | `@BeforeEach`，但更强 |
| `@functools.lru_cache` | 纯函数缓存 | Guava Cache |
| `@dataclass` | 值对象 | `record` / `@Data` |
| `@contextlib.contextmanager` | 造一个 `with` 资源 | `AutoCloseable` |

---

## 5. 综合实战：把脚本重构成包

阶段一的 `svclog.py` 是**一个 167 行的文件**。真实项目里它会长成这样：

```text
svclog-pkg/
├── pyproject.toml            # 元数据 + 依赖 + ruff/mypy/pytest 配置
├── src/                      # ★ src layout：避免"根目录能 import、装完包 import 不到"
│   └── svclog/
│       ├── __init__.py       # 包门面，声明 __all__
│       ├── models.py         # 领域对象      ≈ record / VO
│       ├── reader.py         # 数据入口      ≈ Repository，全是生成器
│       ├── aggregate.py      # 业务逻辑      ≈ Service
│       ├── report.py         # 输出适配      ≈ View，按 Protocol 多态
│       └── cli.py            # 程序入口      ≈ CommandLineRunner
└── tests/                    # 阶段三补上
```

:::note 为什么一定要 src layout
不用 src 目录时，在项目根目录跑 `python -c "import svclog"` 能成功 —— 因为当前目录在 `sys.path` 里。于是你的测试一直是在测**源码目录**，而不是测**装好的包**。

结果就是那个经典事故：本地全绿，装到别的机器上 `ModuleNotFoundError: No module named 'svclog.report'` —— 因为你忘了把某个文件写进打包配置。

src layout 强制你必须先装（`uv sync` / `pip install -e .`）才能 import，测试的就是真实产物。**新项目一律用 src layout。**
:::

### `pyproject.toml`

```toml
[project]
name = "svclog"
version = "0.2.0"
description = "推理服务访问日志聚合器"
requires-python = ">=3.12"
dependencies = []

[project.scripts]
svclog = "svclog.cli:main"          # 装完包后可以直接敲 `svclog access.csv`

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.ruff]
line-length = 100

[tool.ruff.lint]
select = ["E", "F", "I", "UP", "B", "SIM", "RUF"]   # 一个工具顶十个
# 中文注释必关：RUF001/2/3 会把全角标点当成"可疑的相似字符"
ignore = ["RUF001", "RUF002", "RUF003"]

[tool.mypy]
python_version = "3.12"
strict = true                       # 直接开最严，新项目没有历史包袱

[tool.pytest.ini_options]
pythonpath = ["src"]                 # 不用装包也能 import，本地迭代最省事
testpaths = ["tests"]
addopts = "-q --strict-markers"
```

### `models.py` —— 领域对象

```python
"""领域对象。frozen dataclass ≈ Java record。"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Self


@dataclass(frozen=True, slots=True)
class Entry:
    """一条访问记录。frozen 让它可以安全地跨线程/进程传递。"""

    ts: float
    endpoint: str
    status: int
    ms: float

    @property
    def failed(self) -> bool:
        return self.status >= 400

    @classmethod
    def from_row(cls, row: dict[str, str]) -> Self:
        """从 CSV 的一行构造。Self 让子类继承时返回类型也跟着变（3.11+）。"""
        return cls(
            ts=float(row["ts"]),
            endpoint=row["endpoint"].strip(),
            status=int(row["status"]),
            ms=float(row["ms"]),
        )


def percentile(values: list[float], q: float) -> float:
    """线性插值分位数，口径与 numpy.percentile 一致。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    pos = q * (len(ordered) - 1)
    lo = math.floor(pos)
    hi = min(lo + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


@dataclass(slots=True)
class Stat:
    """一个 endpoint 的聚合结果。可变，因为它是累加器。"""

    endpoint: str
    latencies: list[float] = field(default_factory=list)
    errors: int = 0
    first_ts: float = math.inf
    last_ts: float = -math.inf

    def add(self, entry: Entry) -> None:
        self.latencies.append(entry.ms)
        self.errors += int(entry.failed)
        self.first_ts = min(self.first_ts, entry.ts)
        self.last_ts = max(self.last_ts, entry.ts)

    @property
    def count(self) -> int:
        return len(self.latencies)

    @property
    def error_rate(self) -> float:
        return self.errors / self.count if self.count else 0.0

    @property
    def qps(self) -> float:
        span = self.last_ts - self.first_ts
        return self.count / span if span > 0 else float(self.count)

    @property
    def worst(self) -> float:
        return max(self.latencies, default=0.0)

    def pct(self, q: float) -> float:
        return percentile(self.latencies, q)
```

### `reader.py` —— 全是生成器

```python
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
    """同一个契约的另一种实现 —— 调用方一行都不用改。"""
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
```

### `aggregate.py` —— 业务逻辑

```python
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
```

### `report.py` —— Protocol 多态

```python
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
```

### `cli.py` —— 只做装配

```python
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
```

### 验收

```bash
uv run ruff check src/          # All checks passed!
uv run mypy --strict src/svclog # Success: no issues found in 6 source files
uv run python -m svclog.cli access.csv --top 3
```

:::out 三条命令的真实结果
```text
All checks passed!
Success: no issues found in 6 source files

endpoint              count   qps  err%  p50   p95   p99   max
--------------------  -----  ----  ----  ---  ----  ----  ----
/v1/chat/completions   1026  10.0  3.4%  682  1441  4635  7973
/v1/embeddings          624   6.1  1.4%   35    70   168   502
/v1/models              215   2.1  0.0%    4     7    10    34
```
:::

:::out 新增能力：JSONL 输入 + JSON 输出 + 脏数据容错
```text
$ python -m svclog.cli access.jsonl --format json --top 1
[
  {
    "endpoint": "/v1/chat/completions",
    "count": "198",
    "qps": "9.1",
    "err%": "3.0%",
    "p50": "603",
    "p95": "1482",
    "p99": "5929",
    "max": "7973"
  }
]

$ python -m svclog.cli dirty.csv
dirty.csv:3: 跳过坏行 (could not convert string to float: 'BAD')
dirty.csv:4: 跳过坏行 (invalid literal for int() with base 10: 'oops')
endpoint  count  qps    err%  p50  p95  p99  max
--------  -----  ---  ------  ---  ---  ---  ---
/b            1  1.0  100.0%    9    9    9    9
/a            1  1.0    0.0%    5    5    5    5
```
:::

:::note 这次重构真正买到了什么
不是"看起来更专业"，是三个具体能力：

1. **换输入格式不用改业务逻辑** —— `read_jsonl` 和 `read_csv` 产出同样的 `Iterator[Entry]`，`aggregate` 一行没动
2. **换输出格式不用改聚合逻辑** —— 三个 Renderer 满足同一个 Protocol，加第四个只需新增一个类
3. **`mypy --strict` 通过意味着**：所有函数都有完整注解、没有隐式 `Any`、没有未处理的 `None`。这是 Java 编译器免费给你的，Python 要靠这一条命令换回来

第 3 条是给 Java 人的：**别因为"Python 是动态类型"就放弃类型安全**。`mypy --strict` + `Protocol` 能拿回你 80% 的编译期保障，代价只是写注解。
:::

---

## 6. 练习

:::exercise 练习 1 · frozen 的边界（20 分钟）
下面这个类"看起来"不可变。写出**三种**能改到它内部状态的方法，然后给出正确的定义。

```python
from dataclasses import dataclass, field

@dataclass(frozen=True)
class RunConfig:
    name: str
    gpus: list[int] = field(default_factory=list)
    env: dict[str, str] = field(default_factory=dict)
```
:::

:::solution
三种破法：

```python
cfg = RunConfig("train", [0, 1], {"CUDA_VISIBLE_DEVICES": "0,1"})

cfg.gpus.append(2)                    # 破法 1：直接改 list 内容
cfg.env["HF_HOME"] = "/tmp"           # 破法 2：直接改 dict 内容
cfg.gpus.clear()                      # 破法 3：清空
# cfg.gpus = [9]                      # ← 只有这一行会被 frozen 拦住
```

`list` → `tuple` 是显然的。`dict` 麻烦一点，有两条路，**它们的取舍不一样**：

**方案 A：全部换成不可变类型**（推荐）

```python
from dataclasses import dataclass, replace

@dataclass(frozen=True, slots=True)
class RunConfig:
    name: str
    gpus: tuple[int, ...] = ()                      # tuple 而不是 list
    env: tuple[tuple[str, str], ...] = ()           # 键值对元组而不是 dict

    def with_gpus(self, *ids: int) -> "RunConfig":  # ≈ record 的 wither
        return replace(self, gpus=ids)

    def env_dict(self) -> dict[str, str]:           # 要用的时候现转
        return dict(self.env)
```

**方案 B：只读视图 + 排除出比较**

```python
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Mapping

@dataclass(frozen=True, slots=True)
class RunConfig:
    name: str
    gpus: tuple[int, ...] = ()
    env: Mapping[str, str] = field(
        default_factory=lambda: MappingProxyType({}), compare=False)
```

:::warn 一个容易写错的地方
只写 `env: Mapping[str, str] = MappingProxyType({})` 是**不够的** —— `mappingproxy` 拦得住写入，但它**仍然不可 hash**：

```text
TypeError: unhashable type: 'dict'
```

必须再加 `compare=False`，把它排除出自动生成的 `__eq__` / `__hash__`，对象才能进 `set` 或当 dict key。而这就引入了方案 B 的代价 ——
:::

两种方案实测：

```text
A hashable: True | eq: True
A env_dict: {'HF_HOME': '/models'} | with_gpus: (0, 1, 2)
A append blocked: 'tuple' object has no attribute 'append'
B hashable: True
B env write blocked: 'mappingproxy' object does not support item assignment
B 注意：env 不参与 eq/hash → True     ← 两个 env 完全不同的配置被判定为"相等"
```

最后一行就是方案 B 的代价：`compare=False` 意味着**两个环境变量完全不同的配置会被认为是同一个**。如果你拿它做缓存 key，就会拿到别的配置的缓存结果。

**所以默认选 A。** 只有当 env 很大、转换开销明显，且你确定不拿它做 key 时，才考虑 B。

**为什么这在 AI Infra 里很重要**：配置对象会被传进多进程（`ProcessPoolExecutor`、`torchrun`）。可变配置 + 多进程 = 每个进程改的是自己那份副本，你以为改了全局，其实什么也没发生 —— 这类 bug 极难查。
:::

:::exercise 练习 2 · Protocol vs ABC（30 分钟）
你要给推理网关设计一个"限流器"抽象，会有三种实现：内存令牌桶、Redis 分布式限流、测试用的假限流器（永远放行）。

用 `Protocol` 和 `ABC` 各写一版，然后回答：**这个场景该选哪个，为什么？**
:::

:::solution
Protocol 版：

```python
from typing import Protocol

class RateLimiter(Protocol):
    async def acquire(self, key: str, cost: int = 1) -> bool: ...
    async def remaining(self, key: str) -> int: ...


class InMemoryLimiter:          # 不继承任何东西
    def __init__(self, qps: float) -> None: ...
    async def acquire(self, key: str, cost: int = 1) -> bool: ...
    async def remaining(self, key: str) -> int: ...


class AlwaysAllow:              # 测试用，两行
    async def acquire(self, key: str, cost: int = 1) -> bool: return True
    async def remaining(self, key: str) -> int: return 10**9
```

ABC 版：

```python
from abc import ABC, abstractmethod

class RateLimiter(ABC):
    @abstractmethod
    async def acquire(self, key: str, cost: int = 1) -> bool: ...

    @abstractmethod
    async def remaining(self, key: str) -> int: ...

    async def acquire_or_raise(self, key: str) -> None:   # 共享实现
        if not await self.acquire(key):
            raise Overloaded(key)


class AlwaysAllow(RateLimiter):     # 必须 import 并继承
    async def acquire(self, key, cost=1): return True
    async def remaining(self, key): return 10**9
```

**这个场景选 `Protocol`**，三条理由：

1. **测试假件不该依赖生产代码。** ABC 版里 `tests/fakes.py` 必须 `from llmgw.limiter import RateLimiter` —— 一个纯粹为了满足类型检查的 import。
2. **你可能想接第三方库。** 如果某天用 `aiolimiter` 的对象，它绝不可能继承你的 ABC，但它可能恰好有 `acquire` 方法。Protocol 能直接接纳它，ABC 只能写适配器。
3. **共享实现可以用别的方式给。** `acquire_or_raise` 完全可以是个模块级函数 `async def acquire_or_raise(limiter: RateLimiter, key: str)` —— 不需要为了一个辅助方法引入继承。

**什么时候反过来选 ABC**：当共享实现很重、子类只补一两个钩子时（模板方法模式）。比如一个 `BaseScorer`，负责批处理、重试、缓存、指标，子类只实现 `score_one()` —— 这种情况 ABC 更合适，因为"共享的那部分"才是主体。
:::

:::exercise 练习 3 · 生成器流水线（45 分钟）
实现一个函数 `tail_follow(path, poll=0.5)`，行为像 `tail -f`：惰性产出文件新增的行，文件没新内容时就等待。然后用它搭一条流水线：

`tail_follow → parse → 只保留 5xx → 每 10 条打一次告警`

要求：全程内存 O(1)，`Ctrl-C` 能干净退出。
:::

:::solution
```python
"""tail -f 的生成器实现 + 一条告警流水线。"""
from __future__ import annotations

import itertools
import time
from collections.abc import Iterable, Iterator
from pathlib import Path


def tail_follow(path: Path, poll: float = 0.5, from_start: bool = False) -> Iterator[str]:
    """像 tail -f。生成器天然适合这种"无限流"。"""
    with path.open(encoding="utf-8") as f:
        if not from_start:
            f.seek(0, 2)                      # 跳到文件末尾
        while True:
            line = f.readline()
            if line:
                yield line.rstrip("\n")
            else:
                time.sleep(poll)              # 没新内容，歇一下再看
                # 处理日志轮转：文件被换掉了就重新打开
                if path.exists() and f.tell() > path.stat().st_size:
                    f.seek(0)


def parse(lines: Iterable[str]) -> Iterator[dict]:
    for ln in lines:
        parts = ln.split(",")
        if len(parts) != 4:
            continue                          # 坏行直接跳过，不中断流
        try:
            yield {"ts": float(parts[0]), "endpoint": parts[1],
                   "status": int(parts[2]), "ms": float(parts[3])}
        except ValueError:
            continue


def only_5xx(rows: Iterable[dict]) -> Iterator[dict]:
    yield from (r for r in rows if r["status"] >= 500)


def alert_every(rows: Iterable[dict], n: int = 10) -> Iterator[str]:
    """itertools.batched 让"每 N 条"变成一行。"""
    for batch in itertools.batched(rows, n):
        eps = {r["endpoint"] for r in batch}
        yield f"[ALERT] 最近 {len(batch)} 个 5xx，涉及 {sorted(eps)}"


def main(path: Path) -> None:
    # 四级流水线，每级都是生成器 —— 整条链的内存占用是 O(n)，n=10
    pipeline = alert_every(only_5xx(parse(tail_follow(path))), n=10)
    try:
        for alert in pipeline:
            print(alert, flush=True)
    except KeyboardInterrupt:
        print("\n已停止")
        # 生成器的 finally 会在这里被触发，文件句柄自动关闭


if __name__ == "__main__":
    import sys
    main(Path(sys.argv[1]))
```

几个关键点：

- **`tail_follow` 是无限生成器** —— 它永远不会 `return`。这正是生成器相对"返回 list"的根本优势：无限流只能用惰性表达。
- **`with` 写在生成器里面** —— 生成器被 GC 或 `close()` 时，`with` 的 `__exit__` 会执行，文件正常关闭。这是 `Ctrl-C` 能干净退出的原因。
- **每一级都只做一件事** —— 想加"按 endpoint 去重"只要插一级新生成器，上下游都不用动。这就是 Unix 管道的思路。
- **`itertools.batched` 让"每 N 条"只用一行**（3.12+）。3.12 之前要手写一个攒批的循环。

**扩展思考**：如果要"每 10 条**或**每 30 秒告警一次，以先到者为准"，生成器就不够了 —— 你需要 `asyncio`（超时 + 队列）。这是阶段三的内容，也是从"数据处理"跨到"服务"的分水岭。
:::

:::exercise 练习 4 · 写一个真有用的装饰器（45 分钟）
写一个 `@instrument(name)` 装饰器，同时支持**普通函数和 async 函数**，功能：

- 记录调用次数、总耗时、异常次数
- 提供 `instrument.stats()` 打出所有被装饰函数的汇总表
- 不能破坏被装饰函数的签名和 docstring

提示：用 `inspect.iscoroutinefunction` 分派。
:::

:::solution
```python
"""一个同时支持 sync/async 的埋点装饰器。"""
from __future__ import annotations

import asyncio
import functools
import inspect
import time
from collections import defaultdict
from dataclasses import dataclass, field


@dataclass
class _Counter:
    calls: int = 0
    errors: int = 0
    total_s: float = 0.0
    slowest_s: float = 0.0

    def record(self, dt: float, failed: bool) -> None:
        self.calls += 1
        self.errors += failed
        self.total_s += dt
        self.slowest_s = max(self.slowest_s, dt)

    @property
    def mean_ms(self) -> float:
        return self.total_s / self.calls * 1000 if self.calls else 0.0


_STATS: dict[str, _Counter] = defaultdict(_Counter)


def instrument(name: str | None = None):
    """装饰器工厂：@instrument() 或 @instrument("自定义名")。"""

    def deco(fn):
        label = name or fn.__qualname__
        counter = _STATS[label]

        if inspect.iscoroutinefunction(fn):          # ← 关键分派

            @functools.wraps(fn)
            async def async_wrapper(*args, **kwargs):
                t0 = time.perf_counter()
                failed = False
                try:
                    return await fn(*args, **kwargs)
                except BaseException:
                    failed = True
                    raise
                finally:
                    counter.record(time.perf_counter() - t0, failed)

            return async_wrapper

        @functools.wraps(fn)
        def sync_wrapper(*args, **kwargs):
            t0 = time.perf_counter()
            failed = False
            try:
                return fn(*args, **kwargs)
            except BaseException:
                failed = True
                raise
            finally:
                counter.record(time.perf_counter() - t0, failed)

        return sync_wrapper

    return deco


def stats() -> None:
    rows = sorted(_STATS.items(), key=lambda kv: kv[1].total_s, reverse=True)
    print(f"{'函数':<22}{'调用':>6}{'失败':>6}{'总耗时':>10}{'均值':>10}{'最慢':>10}")
    print("-" * 64)
    for label, c in rows:
        print(f"{label:<22}{c.calls:>6}{c.errors:>6}{c.total_s:>9.3f}s"
              f"{c.mean_ms:>9.1f}ms{c.slowest_s*1000:>9.1f}ms")


instrument.stats = stats        # 挂到装饰器上，方便调用


# ── 验证 ────────────────────────────────────────────────────────────
@instrument()
def tokenize(text: str) -> int:
    """算 token 数。"""
    time.sleep(0.01)
    return len(text.split())


@instrument("backend.generate")
async def generate(prompt: str) -> str:
    await asyncio.sleep(0.03)
    if "boom" in prompt:
        raise RuntimeError("后端炸了")
    return prompt.upper()


async def main() -> None:
    for _ in range(3):
        tokenize("a b c")
    for p in ("hi", "there", "boom"):
        try:
            await generate(p)
        except RuntimeError:
            pass
    # 签名和文档都没坏
    print("签名保住了:", tokenize.__name__, "|", tokenize.__doc__)
    print("是协程函数:", inspect.iscoroutinefunction(generate))
    print()
    instrument.stats()


if __name__ == "__main__":
    asyncio.run(main())
```

跑出来：

```text
签名保住了: tokenize | 算 token 数。
是协程函数: True

函数                        调用    失败       总耗时        均值        最慢
----------------------------------------------------------------
backend.generate           3     1    0.090s     30.1ms     30.1ms
tokenize                   3     0    0.030s     10.1ms     10.1ms
```

三个值得注意的地方：

1. **`inspect.iscoroutinefunction` 的分派是必须的。** 如果只写同步版本包一个 async 函数，`fn(*args)` 返回的是协程对象而不是结果，计时永远是 0.000s —— 你会得到一个"永远很快"的假指标。
2. **用 `except BaseException` 而不是 `Exception`。** `asyncio.CancelledError` 在 3.8+ 继承自 `BaseException`，而"请求被取消"恰恰是推理服务最该统计的一类结束方式。
3. **`finally` 而不是在 return 前记录。** 异常路径也必须计数，否则你的"失败率"永远是 0。

这个装饰器再往前一步就是阶段五里的 Prometheus 埋点 —— 把 `_Counter` 换成 `Histogram`，就是生产版本。
:::

:::exercise 练习 5 · 重构验收（1.5 小时）
把你自己的 `svclog.py` 重构成上面的包结构，要求：

1. `uv run ruff check src/` 零告警
2. `uv run mypy --strict src/svclog` 零错误
3. 新增一个 `read_jsonl`，让 `--format json` 和 `.jsonl` 输入都能用
4. 不改 `aggregate.py` 一行代码就能支持新的输入格式

第 4 条是真正的验收标准 —— 如果你必须改 `aggregate.py`，说明分层没做对。
:::

:::solution
第 4 条能不能做到，全看 `aggregate` 的签名怎么写。

**做不到的写法**：

```python
def aggregate(path: Path) -> dict[str, Stat]:      # ❌ 依赖了"文件"这个概念
    with path.open() as f:
        for row in csv.DictReader(f):
            ...
```

加 JSONL 就必须改它，加"从 Kafka 读"更是要重写。

**做得到的写法**：

```python
def aggregate(entries: Iterable[Entry]) -> dict[str, Stat]:   # ✅ 只依赖抽象
    stats: dict[str, Stat] = {}
    for entry in entries:
        stats.setdefault(entry.endpoint, Stat(entry.endpoint)).add(entry)
    return stats
```

它只要求"能迭代出 `Entry`"。于是：

- CSV 文件 → `read_csv(path)` ✅
- JSONL 文件 → `read_jsonl(path)` ✅
- 内存里的 list → 直接传 ✅（**这让单元测试不需要碰文件系统**）
- Kafka / S3 / 数据库 → 写个新生成器就行 ✅

`mypy --strict` 常见的三个拦路虎和修法：

| 报错 | 原因 | 修法 |
|---|---|---|
| `Function is missing a type annotation` | 有函数没标注解 | 全部标上，包括 `-> None` |
| `Need type annotation for "stats"` | 空容器推断不出类型 | `stats: dict[str, Stat] = {}` |
| `Returning Any from function declared to return X` | 第三方库没有类型信息 | 显式标注中间变量，或 `--ignore-missing-imports` |

最终验收（真实输出）：

```text
$ uv run ruff check src/
All checks passed!

$ uv run mypy --strict src/svclog
Success: no issues found in 6 source files
```
:::

---

## 7. 阶段二结业自检

- [ ] 能说出 `frozen=True` 拦得住什么、拦不住什么，以及正确的字段类型选择
- [ ] 能说出 `slots=True` 省内存的原理和适用场景（实测 344 B → 56 B）
- [ ] 能在 `Protocol` / `ABC` / 鸭子类型之间给出**有理由**的选择
- [ ] 能解释为什么 `Protocol` 比 `ABC` 更适合"测试假件"和"接第三方类型"
- [ ] 能写出一条三级以上的生成器流水线，并说清它为什么是 O(1) 内存
- [ ] 记得 `itertools` 的 `islice` / `batched` / `chain` / `pairwise` 各干什么
- [ ] 能写出带参数的装饰器，且不忘 `functools.wraps`
- [ ] 知道装饰 async 函数要用 `inspect.iscoroutinefunction` 分派
- [ ] 有一个 src layout 的包，`ruff` + `mypy --strict` 双绿

:::note 下一页做什么
阶段三把**工程素养**接回来：pytest（fixture 比 `@BeforeEach` 强在哪）、`uv` 锁依赖打镜像，以及最需要重装大脑的一节 —— **GIL**。

那一节有一个自校准的基准测试脚本，它会在**你自己的机器上**量出：线程池对 CPU 密集任务的加速比是多少（剧透：约等于 1.00），以及为什么在容器里 `os.cpu_count()` 会骗你。
:::
