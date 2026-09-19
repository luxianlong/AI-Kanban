"""准入控制 —— 把你在 Java 里做的限流/舱壁/超时搬过来。

为什么 LLM 服务比普通 Web 服务更需要它：
  · 一个请求会占住 GPU 显存（KV cache）几十秒，不是几毫秒
  · 超载时不是"变慢一点"，而是 **OOM 直接把整个进程带走**
  · 所以必须在**入口**就拒绝，而不是让请求堆到后端去
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from types import TracebackType


class Overloaded(Exception):
    """队列满了。对应 HTTP 429 —— 这是一个**正常**的、可重试的答复。"""

    def __init__(self, waiting: int, limit: int) -> None:
        super().__init__(f"queue full: {waiting} waiting, limit {limit}")
        self.waiting, self.limit = waiting, limit


@dataclass
class AdmissionController:
    """信号量控并发 + 计数器控排队长度 + 超时兜底。

    三个参数的物理含义（调参时按这个思路，别瞎试）：
        max_concurrency  同时在 GPU 上跑几个请求 → 由**显存**决定，用 #422 的显存公式算
        max_queue        最多让几个人排队       → 由**可接受的排队时延**决定
        queue_timeout_s  排多久就放弃           → 应小于客户端自己的超时
    """

    max_concurrency: int = 8
    max_queue: int = 32
    queue_timeout_s: float = 10.0

    _sem: asyncio.Semaphore = field(init=False)
    waiting: int = field(default=0, init=False)
    running: int = field(default=0, init=False)
    admitted: int = field(default=0, init=False)
    rejected: int = field(default=0, init=False)

    def __post_init__(self) -> None:
        self._sem = asyncio.Semaphore(self.max_concurrency)

    def snapshot(self) -> dict[str, int]:
        return {"running": self.running, "waiting": self.waiting,
                "admitted": self.admitted, "rejected": self.rejected}

    def slot(self) -> _Slot:
        return _Slot(self)


@dataclass
class _Slot:
    """async with 拿到的"名额"。用上下文管理器保证异常路径也会释放。"""

    ctl: AdmissionController
    queued_s: float = 0.0

    async def __aenter__(self) -> _Slot:
        c = self.ctl
        if c.waiting >= c.max_queue:          # ← 快速失败，不让它进来排
            c.rejected += 1
            raise Overloaded(c.waiting, c.max_queue)
        c.waiting += 1
        loop = asyncio.get_running_loop()
        t0 = loop.time()
        try:
            async with asyncio.timeout(c.queue_timeout_s):
                await c._sem.acquire()
        except TimeoutError:
            c.rejected += 1
            raise Overloaded(c.waiting, c.max_queue) from None
        finally:
            c.waiting -= 1
        self.queued_s = loop.time() - t0      # ★ 排队时延：LLM 服务最该埋的指标
        c.running += 1
        c.admitted += 1
        return self

    async def __aexit__(self, exc_type: type[BaseException] | None,
                        exc: BaseException | None, tb: TracebackType | None) -> None:
        self.ctl.running -= 1
        self.ctl._sem.release()
