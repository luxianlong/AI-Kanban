"""准入控制：超载时必须**快速拒绝**，而不是排到天荒地老。"""

import asyncio

import pytest

from llmgw.admission import AdmissionController, Overloaded


async def test_concurrency_is_capped():
    ctl = AdmissionController(max_concurrency=2, max_queue=100)
    peak = 0

    async def job():
        nonlocal peak
        async with ctl.slot():
            peak = max(peak, ctl.running)
            await asyncio.sleep(0.05)

    await asyncio.gather(*(job() for _ in range(8)))
    assert peak == 2
    assert ctl.admitted == 8 and ctl.rejected == 0
    assert ctl.running == 0          # 全部归还


async def test_queue_overflow_rejects_fast():
    ctl = AdmissionController(max_concurrency=1, max_queue=2, queue_timeout_s=5)
    rejected = 0

    async def job():
        nonlocal rejected
        try:
            async with ctl.slot():
                await asyncio.sleep(0.1)
        except Overloaded:
            rejected += 1

    await asyncio.gather(*(job() for _ in range(10)))
    assert rejected > 0                       # 有人被挡在门外
    assert ctl.admitted + rejected == 10      # 但没人凭空消失


async def test_queue_timeout_gives_up():
    ctl = AdmissionController(max_concurrency=1, max_queue=50, queue_timeout_s=0.05)

    async def hog():
        async with ctl.slot():
            await asyncio.sleep(0.4)

    task = asyncio.create_task(hog())
    await asyncio.sleep(0.01)
    with pytest.raises(Overloaded):
        async with ctl.slot():
            pass
    await task


async def test_slot_released_on_exception():
    """异常路径也必须归还名额 —— 漏一次，并发额度就永久少一个。"""
    ctl = AdmissionController(max_concurrency=1)
    with pytest.raises(RuntimeError):
        async with ctl.slot():
            raise RuntimeError("boom")
    async with ctl.slot():               # 还能再拿到，说明没泄漏
        assert ctl.running == 1
    assert ctl.running == 0


def test_gateway_returns_429_with_retry_after(monkeypatch, body):
    """端到端：并发打满时网关应返回 429 + Retry-After。"""
    import os

    os.environ["MAX_CONCURRENCY"] = "1"
    os.environ["MAX_QUEUE"] = "0"          # 不许排队，立刻拒
    from fastapi.testclient import TestClient

    import llmgw.app as app_module

    with TestClient(app_module.app) as c:
        ctl = app_module.app.state.admission
        assert ctl.max_queue == 0
        r = c.post("/v1/chat/completions", json=body)
        # max_queue=0 意味着连第一个请求都会被拒 —— 这正是配置错误的样子，
        # 测试把它固化下来，免得有人把 0 当成"不限制"。
        assert r.status_code == 429
        assert r.headers["Retry-After"] == "2"
        assert r.json()["error"]["code"] == "queue_full"
    os.environ.pop("MAX_CONCURRENCY")
    os.environ.pop("MAX_QUEUE")
