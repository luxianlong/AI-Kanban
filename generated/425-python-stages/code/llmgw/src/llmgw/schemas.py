"""OpenAI 兼容的请求/响应契约。

pydantic 是 Python 生态里**唯一**会按类型注解做运行时校验的东西
（≈ Bean Validation，但校验规则就写在类型上）。v2 的内核是 Rust，很快。
"""

from __future__ import annotations

import time
import uuid
from typing import Literal

from pydantic import BaseModel, Field


class Message(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=32_000)


class ChatRequest(BaseModel):
    model: str = Field(min_length=1)
    messages: list[Message] = Field(min_length=1)
    max_tokens: int = Field(default=128, ge=1, le=4096)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    stream: bool = False

    @property
    def prompt(self) -> str:
        return "\n".join(f"{m.role}: {m.content}" for m in self.messages)

    @property
    def prompt_tokens(self) -> int:
        """真实网关要用 tokenizer；这里按字符估算，够做容量与计费的量纲。"""
        return max(1, len(self.prompt) // 4)


class Usage(BaseModel):
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


class Choice(BaseModel):
    index: int = 0
    message: Message
    finish_reason: Literal["stop", "length", "cancelled"] = "stop"


class ChatResponse(BaseModel):
    id: str = Field(default_factory=lambda: f"chatcmpl-{uuid.uuid4().hex[:12]}")
    object: Literal["chat.completion"] = "chat.completion"
    created: int = Field(default_factory=lambda: int(time.time()))
    model: str
    choices: list[Choice]
    usage: Usage


class ErrorBody(BaseModel):
    """错误也要和 OpenAI 对齐，否则客户端 SDK 不认。"""

    message: str
    type: str
    code: str | None = None


class ErrorResponse(BaseModel):
    error: ErrorBody
