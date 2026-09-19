"""阶段一 1.4 —— 函数、参数、作用域：四个 Java 人必踩的坑，全部可复现。"""

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
