"""阶段三 3.3b —— asyncio：心智模型 ≈ Reactor / CompletableFuture，但**单线程**。

重点演示第 3 节：在 async 函数里做阻塞调用，会把整个事件循环卡死。
这是 Java 人转 Python 写服务时最常见、也最贵的一次事故。
"""
from __future__ import annotations

import asyncio
import time


async def fetch(name: str, delay: float) -> str:
    """模拟一次网络调用。await 会把控制权交还事件循环。"""
    await asyncio.sleep(delay)
    return f"{name}({delay}s)"


# ── 1. gather：并发发起，不是并行计算 ────────────────────────────────
async def demo_gather() -> None:
    t0 = time.perf_counter()
    results = await asyncio.gather(fetch("a", 0.3), fetch("b", 0.3), fetch("c", 0.3))
    print(f"1. gather  {results}  耗时 {time.perf_counter()-t0:.2f}s（不是 0.9s）")


# ── 2. ★ 阻塞调用毒死事件循环 ────────────────────────────────────────
def blocking_call(seconds: float) -> str:
    """任何同步库都长这样：requests、psycopg2(同步)、time.sleep、model.generate()。"""
    time.sleep(seconds)                     # ← 注意：不是 await asyncio.sleep
    return "blocked"


async def demo_blocking() -> None:
    async def heartbeat(stop: asyncio.Event) -> int:
        """每 50ms 跳一次。事件循环健康的话，1 秒该跳 ~20 次。"""
        beats = 0
        while not stop.is_set():
            await asyncio.sleep(0.05)
            beats += 1
        return beats

    for label, work in (
        ("❌ 直接调用阻塞函数", lambda: blocking_call(1.0)),
        ("✅ asyncio.to_thread 包一层", None),
    ):
        stop = asyncio.Event()
        hb = asyncio.create_task(heartbeat(stop))
        await asyncio.sleep(0.05)
        t0 = time.perf_counter()
        if work is None:
            await asyncio.to_thread(blocking_call, 1.0)   # 丢到线程池，循环不受影响
        else:
            work()                                        # 循环在这 1 秒里彻底停摆
        dt = time.perf_counter() - t0
        stop.set()
        beats = await hb
        print(f"2. {label:<24} 耗时{dt:.2f}s  这期间心跳只跳了 {beats:>2} 次"
              f"（健康值 ~20）")


# ── 3. 超时与取消：客户端断开时必须真的停下游 ────────────────────────
async def demo_cancel() -> None:
    cleaned = []

    async def long_generation() -> str:
        try:
            await asyncio.sleep(10)          # 假装在 GPU 上生成 2000 个 token
            return "done"
        except asyncio.CancelledError:
            cleaned.append("释放了 KV cache")   # ← 推理网关里这行值很多钱
            raise

    try:
        async with asyncio.timeout(0.2):     # 3.11+，比 wait_for 好用
            await long_generation()
    except TimeoutError:
        print(f"3. 超时被取消，清理动作执行了：{cleaned}")


# ── 4. 限流：Semaphore ≈ Java 的舱壁 / 连接池上限 ────────────────────
async def demo_semaphore() -> None:
    sem = asyncio.Semaphore(2)               # 同时最多 2 个在跑
    running, peak = 0, 0

    async def guarded(i: int) -> None:
        nonlocal running, peak
        async with sem:
            running += 1
            peak = max(peak, running)
            await asyncio.sleep(0.1)
            running -= 1

    t0 = time.perf_counter()
    await asyncio.gather(*(guarded(i) for i in range(6)))
    print(f"4. 6 个任务 / 并发上限 2 → 实际峰值并发 {peak}，"
          f"耗时 {time.perf_counter()-t0:.2f}s（=3 批 × 0.1s）")


# ── 5. 常见错误：忘了 await，什么也不会发生 ─────────────────────────
async def demo_forgot_await() -> None:
    coro = fetch("x", 0.01)
    print(f"5. 忘了 await 的话拿到的是：{type(coro).__name__}，不是结果")
    print(f"5. await 之后才是：{await coro}")


async def main() -> None:
    await demo_gather()
    await demo_blocking()
    await demo_cancel()
    await demo_semaphore()
    await demo_forgot_await()


if __name__ == "__main__":
    asyncio.run(main())
