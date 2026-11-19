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
