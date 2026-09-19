"""LLM 推理网关。

这个文件把前面几块装配起来，并处理五件生产服务必须做对的事：
  1. 准入控制：超载返回 429，而不是把 GPU 拖垮
  2. 取消传播：客户端断开 → 真的停止下游生成（省 GPU，最常被忽略）
  3. 结构化日志：每条带 request_id，能和指标对上
  4. 流式：SSE，且首 token 之后不能再改 HTTP 状态码（见下面的注释）
  5. 健康检查：liveness 与 readiness 分开
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import time
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse, PlainTextResponse, StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from llmgw import metrics as M
from llmgw.admission import AdmissionController, Overloaded
from llmgw.backends import Backend, FakeBackend, VLLMBackend
from llmgw.schemas import (
    ChatRequest,
    ChatResponse,
    Choice,
    ErrorBody,
    ErrorResponse,
    Message,
    Usage,
)

log = logging.getLogger("llmgw")


def _configure_logging() -> None:
    """JSON 行日志。容器里唯一正确的日志格式 —— 采集器不用再写正则。"""

    class JsonFormatter(logging.Formatter):
        def format(self, record: logging.LogRecord) -> str:
            payload = {
                "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
                "level": record.levelname,
                "msg": record.getMessage(),
            }
            payload.update(getattr(record, "extra_fields", {}))
            return json.dumps(payload, ensure_ascii=False)

    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    log.handlers = [handler]
    log.setLevel(os.environ.get("LOG_LEVEL", "INFO"))
    log.propagate = False


def build_backend() -> Backend:
    """一个环境变量切换真假后端。≈ Spring 的 @Profile。"""
    if os.environ.get("LLMGW_BACKEND", "fake") == "vllm":
        return VLLMBackend(base_url=os.environ.get("VLLM_URL", "http://localhost:8001"),
                           name=os.environ.get("MODEL_NAME", "vllm"))
    return FakeBackend(name=os.environ.get("MODEL_NAME", "fake-1b"))


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """启动/关闭钩子 ≈ @PostConstruct / @PreDestroy。"""
    _configure_logging()
    app.state.backend = build_backend()
    app.state.admission = AdmissionController(
        max_concurrency=int(os.environ.get("MAX_CONCURRENCY", "8")),
        max_queue=int(os.environ.get("MAX_QUEUE", "32")),
        queue_timeout_s=float(os.environ.get("QUEUE_TIMEOUT_S", "10")),
    )
    app.state.request_timeout_s = float(os.environ.get("REQUEST_TIMEOUT_S", "120"))
    app.state.ready = await app.state.backend.ready()
    M.READY.set(int(app.state.ready))
    log.info("gateway up", extra={"extra_fields": {
        "backend": app.state.backend.name, "ready": app.state.ready,
        "max_concurrency": app.state.admission.max_concurrency}})
    yield
    log.info("gateway down")


app = FastAPI(title="llmgw", version="1.0.0", lifespan=lifespan)


def _err(status: int, msg: str, typ: str, code: str) -> JSONResponse:
    body = ErrorResponse(error=ErrorBody(message=msg, type=typ, code=code))
    return JSONResponse(status_code=status, content=body.model_dump())


@app.middleware("http")
async def request_id_middleware(request: Request, call_next: Any) -> Response:
    """给每个请求一个 id，并回写到响应头 —— 排障时客户端能直接报给你。"""
    rid = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
    request.state.request_id = rid
    response: Response = await call_next(request)
    response.headers["x-request-id"] = rid
    return response


async def _run(req: ChatRequest, backend: Backend, rid: str,
               disconnected: Callable[[], Awaitable[bool]] | None,
               timeout_s: float) -> AsyncIterator[tuple[str, Any]]:
    """真正干活的协程：产出 (事件类型, 数据)。

    抽成生成器是为了让流式与非流式共用同一条代码路径 ——
    否则两边的指标、取消、超时逻辑迟早会漂移。
    """
    t0 = time.perf_counter()
    first_at: float | None = None
    last_at = t0
    n = 0
    try:
        async with asyncio.timeout(timeout_s):
            async for tok in backend.stream(req):
                now = time.perf_counter()
                if first_at is None:
                    first_at = now
                    M.TTFT.labels(req.model).observe(now - t0)
                    yield ("ttft", now - t0)
                else:
                    M.ITL.labels(req.model).observe(now - last_at)
                last_at = now
                n += 1
                yield ("token", tok)
                # ★ 每吐一个 token 就看一眼客户端还在不在。
                #   不做这一步 = 用户关掉页面后 GPU 还在为他算，白烧钱。
                if disconnected is not None and await disconnected():
                    yield ("cancelled", n)
                    return
    except TimeoutError:
        yield ("timeout", n)
        return
    yield ("done", n)


@app.post("/v1/chat/completions")
async def chat_completions(req: ChatRequest, request: Request) -> Any:
    rid: str = request.state.request_id
    ctl: AdmissionController = app.state.admission
    backend: Backend = app.state.backend
    timeout_s: float = app.state.request_timeout_s
    started = time.perf_counter()

    if not app.state.ready:
        M.REQUESTS.labels(req.model, "unavailable").inc()
        return _err(503, "后端未就绪", "service_unavailable", "not_ready")

    # ── 非流式：先拿名额，全部生成完再一次性返回 ──────────────────
    if not req.stream:
        try:
            slot = ctl.slot()
            async with slot:
                M.QUEUE_WAIT.labels(req.model).observe(slot.queued_s)
                M.IN_FLIGHT.set(ctl.running)
                M.WAITING.set(ctl.waiting)
                text, outcome = "", "done"
                async for kind, data in _run(req, backend, rid, request.is_disconnected,
                                             timeout_s):
                    if kind == "token":
                        text += data
                    elif kind in {"timeout", "cancelled"}:
                        outcome = kind
        except Overloaded as e:
            M.REQUESTS.labels(req.model, "overloaded").inc()
            log.warning("rejected", extra={"extra_fields": {
                "request_id": rid, "waiting": e.waiting}})
            return JSONResponse(
                status_code=429,
                content=ErrorResponse(error=ErrorBody(
                    message="服务繁忙，请稍后重试", type="rate_limit_error",
                    code="queue_full")).model_dump(),
                headers={"Retry-After": "2"},   # ← 告诉客户端**多久后**再来
            )
        finally:
            M.IN_FLIGHT.set(ctl.running)
            M.WAITING.set(ctl.waiting)

        if outcome == "timeout":
            M.REQUESTS.labels(req.model, "timeout").inc()
            return _err(504, "生成超时", "timeout_error", "generation_timeout")

        M.REQUESTS.labels(req.model, "ok").inc()
        M.E2E.labels(req.model, "ok").observe(time.perf_counter() - started)
        M.TOKENS.labels(req.model, "prompt").inc(req.prompt_tokens)
        M.TOKENS.labels(req.model, "completion").inc(len(text))
        log.info("completed", extra={"extra_fields": {
            "request_id": rid, "tokens": len(text), "queued_ms": round(slot.queued_s * 1e3, 1),
            "duration_ms": round((time.perf_counter() - started) * 1e3, 1)}})
        return ChatResponse(
            model=req.model,
            choices=[Choice(message=Message(role="assistant", content=text or " "))],
            usage=Usage(prompt_tokens=req.prompt_tokens, completion_tokens=len(text),
                        total_tokens=req.prompt_tokens + len(text)),
        )

    # ── 流式 ──────────────────────────────────────────────────────
    # ⚠️ 关键约束：一旦开始流式输出，HTTP 状态码就**已经发出去了**，
    #    之后再出错只能在流里发一个 error 事件。所以准入控制必须在
    #    StreamingResponse **之前**做完 —— 429 要在第一个字节前返回。
    try:
        slot = ctl.slot()
        await slot.__aenter__()
    except Overloaded as e:
        M.REQUESTS.labels(req.model, "overloaded").inc()
        log.warning("rejected", extra={"extra_fields": {
            "request_id": rid, "waiting": e.waiting}})
        return JSONResponse(
            status_code=429,
            content=ErrorResponse(error=ErrorBody(
                message="服务繁忙，请稍后重试", type="rate_limit_error",
                code="queue_full")).model_dump(),
            headers={"Retry-After": "2"},
        )

    M.QUEUE_WAIT.labels(req.model).observe(slot.queued_s)
    cid = f"chatcmpl-{uuid.uuid4().hex[:12]}"

    def frame(delta: dict[str, Any], finish: str | None = None) -> str:
        return "data: " + json.dumps({
            "id": cid, "object": "chat.completion.chunk", "created": int(time.time()),
            "model": req.model,
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
        }, ensure_ascii=False) + "\n\n"

    async def body() -> AsyncIterator[str]:
        outcome, n, ttft = "done", 0, None
        try:
            M.IN_FLIGHT.set(ctl.running)
            yield frame({"role": "assistant", "content": ""})
            async for kind, data in _run(req, backend, rid, request.is_disconnected,
                                         timeout_s):
                if kind == "token":
                    n += 1
                    yield frame({"content": data})
                elif kind == "ttft":
                    ttft = data
                elif kind in {"timeout", "cancelled"}:
                    outcome = kind
            if outcome == "timeout":
                yield "data: " + json.dumps({"error": {
                    "message": "生成超时", "type": "timeout_error"}}) + "\n\n"
            yield frame({}, finish="length" if outcome == "timeout" else "stop")
            yield "data: [DONE]\n\n"
        except asyncio.CancelledError:
            outcome = "cancelled"          # 客户端断开，ASGI 服务器会取消这个任务
            raise
        except Exception as exc:           # noqa: BLE001 —— 流里不能抛到外面去
            outcome = "error"
            log.exception("stream failed", extra={"extra_fields": {"request_id": rid}})
            yield "data: " + json.dumps({"error": {
                "message": str(exc), "type": "internal_error"}}) + "\n\n"
        finally:
            # ★ finally 必须释放名额：客户端断开走的是 CancelledError 路径，
            #   漏掉这里 = 并发额度慢慢泄漏光，服务最后只会返回 429。
            await slot.__aexit__(None, None, None)
            M.IN_FLIGHT.set(ctl.running)
            M.WAITING.set(ctl.waiting)
            M.REQUESTS.labels(req.model, outcome).inc()
            M.E2E.labels(req.model, outcome).observe(time.perf_counter() - started)
            M.TOKENS.labels(req.model, "completion").inc(n)
            M.TOKENS.labels(req.model, "prompt").inc(req.prompt_tokens)
            log.info("stream finished", extra={"extra_fields": {
                "request_id": rid, "outcome": outcome, "tokens": n,
                "ttft_ms": round(ttft * 1e3, 1) if ttft else None,
                "queued_ms": round(slot.queued_s * 1e3, 1),
                "duration_ms": round((time.perf_counter() - started) * 1e3, 1)}})

    return StreamingResponse(body(), media_type="text/event-stream", headers={
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",      # 关掉 nginx 缓冲，否则流式会被攒成一坨
        "x-request-id": rid,
    })


@app.get("/healthz", response_class=PlainTextResponse)
async def healthz() -> str:
    """liveness：进程没死。K8s 探测失败会**重启 Pod** —— 所以这里绝不能查后端。"""
    return "ok"


@app.get("/readyz")
async def readyz() -> JSONResponse:
    """readiness：能接流量了吗。失败只是**摘流量**，不重启。

    LLM 服务加载权重要几分钟，这期间 liveness 必须过、readiness 必须不过。
    把两者写成同一个接口，是滚动更新时 502 的头号原因。
    """
    ok = await app.state.backend.ready()
    app.state.ready = ok
    M.READY.set(int(ok))
    ctl: AdmissionController = app.state.admission
    payload = {"ready": ok, "backend": app.state.backend.name, **ctl.snapshot()}
    return JSONResponse(payload, status_code=200 if ok else 503)


@app.get("/metrics")
async def prom_metrics() -> PlainTextResponse:
    ctl: AdmissionController = app.state.admission
    M.IN_FLIGHT.set(ctl.running)
    M.WAITING.set(ctl.waiting)
    return PlainTextResponse(generate_latest(M.REGISTRY).decode(),
                             media_type=CONTENT_TYPE_LATEST)


with contextlib.suppress(ImportError):   # 可选：本地开发时直接 python -m llmgw.app
    if __name__ == "__main__":
        import uvicorn

        uvicorn.run(app, host="0.0.0.0", port=8000)
