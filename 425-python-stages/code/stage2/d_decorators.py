"""阶段二 2.4 —— 装饰器 ≈ Java 注解 + 动态代理，但纯 Python 就能写完。"""
import functools, random, time
from collections.abc import Callable
from typing import Any, TypeVar, ParamSpec

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

# ── 5. 类装饰器 & 堆叠顺序（自下而上）────────────────────────────────
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

# ── 6. 你在 AI Infra 里会遇到的装饰器（认得即可）────────────────────
print("""
6.  @app.post("/v1/chat")     FastAPI 路由      ≈ @PostMapping
    @torch.inference_mode()   关梯度 + 省显存    （推理路径必加）
    @ray.remote               函数→集群任务      ≈ 分布式 Executor
    @pytest.fixture           测试夹具依赖注入    ≈ @BeforeEach 但更强
    @functools.lru_cache      纯函数缓存         ≈ Guava Cache
    @dataclass                值对象             ≈ record / @Data
    @contextlib.contextmanager 造一个 with 资源   ≈ AutoCloseable""".rstrip())
