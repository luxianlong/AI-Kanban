"""阶段四 4.4 —— 最小可跑的 OpenAI 兼容推理服务（不需要 GPU）。

后端是个**假的**生成器，按真实 LLM 的节奏吐 token（先有 TTFT，再按 ITL 逐个吐）。
这样你在笔记本上就能把服务化的全部机制练完：pydantic 校验、SSE 流式、
背压、取消、指标 —— 这些才是 infra 工程师的考点，换成真 vLLM 只是改一个类。

跑起来：
    uv run uvicorn d_serve_min:app --port 8000
    curl -N localhost:8000/v1/chat/completions -H 'content-type: application/json' \
         -d '{"model":"fake","messages":[{"role":"user","content":"hi"}],"stream":true}'
"""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from collections.abc import AsyncIterator

from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

# ── 1. 契约层：pydantic ≈ DTO + @Valid，但校验是**运行时真做**的 ──────


class Message(BaseModel):
    role: str = Field(pattern="^(system|user|assistant)$")
    content: str = Field(min_length=1, max_length=32_000)


class ChatRequest(BaseModel):
    model: str
    messages: list[Message] = Field(min_length=1)
    max_tokens: int = Field(default=128, ge=1, le=4096)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    stream: bool = False


# ── 2. 后端：Protocol 化，真假实现可互换 ─────────────────────────────

TTFT_S = 0.25          # 首 token 时延：prefill 阶段，算力瓶颈
ITL_S = 0.012          # token 间隔：decode 阶段，访存瓶颈


async def fake_generate(req: ChatRequest) -> AsyncIterator[str]:
    """模拟一次生成。注意这里的两段式节奏，它复刻了真实 LLM 的行为。"""
    await asyncio.sleep(TTFT_S)                       # prefill
    vocab = ["这", "是", "一", "个", "假", "的", "回", "答", "，", "用", "来", "练", "服", "务", "化", "。"]
    n = min(req.max_tokens, 64)
    for i in range(n):
        await asyncio.sleep(ITL_S)                    # decode，一次一个 token
        yield vocab[i % len(vocab)]


# ── 3. 路由 ────────────────────────────────────────────────────────

app = FastAPI(title="mini-gateway")


def _chunk(cid: str, model: str, delta: dict, finish: str | None = None) -> str:
    """一条 SSE 数据行。OpenAI 的流式格式就长这样。"""
    payload = {
        "id": cid, "object": "chat.completion.chunk",
        "created": int(time.time()), "model": model,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


@app.post("/v1/chat/completions")                     # ≈ @PostMapping
async def chat(req: ChatRequest):
    cid = f"chatcmpl-{uuid.uuid4().hex[:12]}"
    if not req.stream:
        text = "".join([t async for t in fake_generate(req)])
        return {
            "id": cid, "object": "chat.completion", "model": req.model,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
            "usage": {"completion_tokens": len(text)},
        }

    async def sse() -> AsyncIterator[str]:
        yield _chunk(cid, req.model, {"role": "assistant", "content": ""})
        try:
            async for tok in fake_generate(req):
                yield _chunk(cid, req.model, {"content": tok})
            yield _chunk(cid, req.model, {}, finish="stop")
            yield "data: [DONE]\n\n"
        except asyncio.CancelledError:
            # ★ 客户端断开时会走到这里。真后端在这里必须**取消下游生成**，
            #   否则 GPU 还在为一个没人看的请求算 token —— 白烧钱。
            print(f"[{cid}] 客户端断开，已取消生成")
            raise

    return StreamingResponse(
        sse(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.get("/healthz")     # liveness：进程还活着吗
async def healthz():
    return {"status": "ok"}


@app.get("/readyz")      # readiness：★ 和 liveness 不是一回事
async def readyz():
    # 真实服务里这里要检查"权重加载完了没"。模型要几分钟才加载完，
    # 这期间进程是活的但**不能**接流量 —— 混淆这两者会导致滚动更新时 502。
    return {"status": "ready", "model_loaded": True}
