"""后端抽象。Protocol 让"假后端"和"真 vLLM"可以互换，**不需要继承**。

为什么要有假后端：
  · 单测里绝不能加载真模型（慢、要 GPU、不确定）
  · 你在没有 GPU 的笔记本上也要能把服务化机制练完
  · 压测网关自身的开销时，需要一个时延可控的基准后端
"""

from __future__ import annotations

import asyncio
import random
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Protocol

from llmgw.schemas import ChatRequest


class Backend(Protocol):
    """实现方不需要 import 这个类。结构对得上就是它的实现。"""

    name: str

    async def ready(self) -> bool: ...

    def stream(self, req: ChatRequest) -> AsyncIterator[str]: ...


@dataclass
class FakeBackend:
    """按真实 LLM 的两段式节奏产出 token。

    prefill（首 token）耗时 ∝ prompt 长度  —— 算力瓶颈
    decode（后续 token）每个固定间隔       —— 访存瓶颈
    这两句解释了几乎所有 LLM 服务的性能现象，所以假后端必须复刻它。
    """

    name: str = "fake-1b"
    ttft_base_s: float = 0.08
    ttft_per_1k_tokens_s: float = 0.12
    itl_s: float = 0.010
    jitter: float = 0.15
    fail_rate: float = 0.0
    _ready: bool = True

    async def ready(self) -> bool:
        return self._ready

    def _noise(self, base: float) -> float:
        return base * random.uniform(1 - self.jitter, 1 + self.jitter)

    async def stream(self, req: ChatRequest) -> AsyncIterator[str]:
        if self.fail_rate and random.random() < self.fail_rate:
            raise RuntimeError("backend exploded")
        ttft = self.ttft_base_s + self.ttft_per_1k_tokens_s * req.prompt_tokens / 1000
        await asyncio.sleep(self._noise(ttft))                 # ← prefill
        alphabet = "这是一个用来练习服务化的假回答，它按真实节奏逐个吐字。"
        for i in range(req.max_tokens):
            await asyncio.sleep(self._noise(self.itl_s))       # ← decode
            yield alphabet[i % len(alphabet)]


@dataclass
class VLLMBackend:
    """真后端：把 HTTP 流式响应翻译成同一个 AsyncIterator[str]。

    起 vLLM：
        vllm serve Qwen/Qwen3-8B --max-model-len 8192 \\
             --gpu-memory-utilization 0.90 --port 8001
    换后端时，网关其余代码**一行都不用改** —— 这就是 Protocol 的价值。
    """

    base_url: str = "http://localhost:8001"
    name: str = "vllm"
    timeout_s: float = 300.0

    async def ready(self) -> bool:
        import httpx

        try:
            async with httpx.AsyncClient(timeout=2.0) as c:
                return (await c.get(f"{self.base_url}/health")).status_code == 200
        except Exception:
            return False

    async def stream(self, req: ChatRequest) -> AsyncIterator[str]:
        import json

        import httpx

        body = {
            "model": self.name,
            "messages": [m.model_dump() for m in req.messages],
            "max_tokens": req.max_tokens,
            "temperature": req.temperature,
            "stream": True,
        }
        async with (
            httpx.AsyncClient(timeout=self.timeout_s) as client,
            client.stream("POST", f"{self.base_url}/v1/chat/completions", json=body) as resp,
        ):
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                if not line.startswith("data: "):
                    continue
                data = line[6:]
                if data == "[DONE]":
                    return
                delta = json.loads(data)["choices"][0]["delta"]
                if content := delta.get("content"):
                    yield content
