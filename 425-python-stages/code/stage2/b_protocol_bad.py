"""故意写错：签名不匹配的"实现"。mypy 会抓住，运行时不会。"""
from b_protocol import Tokenizer, count_tokens

class BrokenTokenizer:
    def encode(self, text: str) -> str:      # ← 返回 str，应该是 list[int]
        return text
    # 还漏了 decode

print(count_tokens(["hi"], BrokenTokenizer()))   # 运行时居然能跑出结果！
