"""取消传播：客户端断开时必须真的停止生成。

这是整个网关里**最省钱、也最容易被漏掉**的一条。
没有这段逻辑，用户关掉页面后 GPU 还会为他算完剩下 2000 个 token。
"""

import asyncio

from llmgw.admission import AdmissionController
from llmgw.app import _run
from llmgw.backends import FakeBackend
from llmgw.schemas import ChatRequest


def make_req(max_tokens: int = 200) -> ChatRequest:
    return ChatRequest(model="fake-1b", max_tokens=max_tokens,
                       messages=[{"role": "user", "content": "写一篇长文"}])


async def test_stops_generating_when_client_disconnects():
    backend = FakeBackend(ttft_base_s=0.01, itl_s=0.001, jitter=0.0)
    gone = False

    async def disconnected() -> bool:
        return gone

    produced, outcome = 0, None
    async for kind, _data in _run(make_req(200), backend, "rid", disconnected, 30.0):
        if kind == "token":
            produced += 1
            if produced == 5:
                gone = True            # 模拟用户关掉了页面
        elif kind in {"cancelled", "done", "timeout"}:
            outcome = kind

    assert outcome == "cancelled"
    assert produced == 5, f"断开后又多生成了 {produced - 5} 个 token —— 这些都是白烧的 GPU"


async def test_runs_to_completion_when_client_stays():
    backend = FakeBackend(ttft_base_s=0.01, itl_s=0.001, jitter=0.0)

    async def connected() -> bool:
        return False

    produced, outcome = 0, None
    async for kind, _data in _run(make_req(20), backend, "rid", connected, 30.0):
        if kind == "token":
            produced += 1
        elif kind in {"cancelled", "done", "timeout"}:
            outcome = kind
    assert (outcome, produced) == ("done", 20)


async def test_timeout_cuts_generation_off():
    backend = FakeBackend(ttft_base_s=0.01, itl_s=0.02, jitter=0.0)
    outcome, produced = None, 0
    async for kind, _data in _run(make_req(1000), backend, "rid", None, 0.15):
        if kind == "token":
            produced += 1
        elif kind in {"cancelled", "done", "timeout"}:
            outcome = kind
    assert outcome == "timeout"
    assert 0 < produced < 1000        # 生成了一部分就被切断


async def test_ttft_is_reported_before_any_token():
    """TTFT 必须是**第一个**事件，否则指标会把排队时间算进去。"""
    backend = FakeBackend(ttft_base_s=0.05, itl_s=0.001, jitter=0.0)
    events = []
    async for kind, _data in _run(make_req(3), backend, "rid", None, 10.0):
        events.append(kind)
    assert events[0] == "ttft"
    assert events.count("token") == 3
    assert events[-1] == "done"


async def test_admission_slot_not_leaked_when_task_cancelled():
    """★ 名额泄漏是最阴险的 bug：服务跑几小时后开始无理由返回 429。"""
    ctl = AdmissionController(max_concurrency=2, max_queue=10)

    async def worker():
        async with ctl.slot():
            await asyncio.sleep(10)

    tasks = [asyncio.create_task(worker()) for _ in range(2)]
    await asyncio.sleep(0.05)
    assert ctl.running == 2
    for t in tasks:
        t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    assert ctl.running == 0, "名额没归还 —— async with 的 __aexit__ 没跑"
