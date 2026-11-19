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

# ── 怎么选（这张表就是全部答案）────────────────────────────────────
print("""
  想让第三方类型也能满足契约、不想让实现方 import 你     → Protocol
  需要共享实现 / 模板方法 / 强制子类实现                 → ABC
  一次性脚本、内部胶水                                   → 鸭子类型
  Java 的 interface 默认对应的是 →  Protocol（不是 ABC）
""".rstrip())
