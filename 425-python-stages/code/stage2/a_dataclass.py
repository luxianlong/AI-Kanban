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
