#!/usr/bin/env python3
"""压测器：扫并发档位，画吞吐-时延曲线，自动找膝点。

为什么不用 JMeter / locust：LLM 服务的关键指标是 **TTFT** 和 **每 token 间隔**，
通用压测工具默认只给端到端时延 —— 那个数字会把"生成得慢"和"排队排得久"混在一起。

用法：
    uv run python bench/loadgen.py --url http://localhost:8000 --levels 1,2,4,8,16,32
"""
from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import time
from dataclasses import dataclass, field

import httpx

PROMPT = "请解释一下 PagedAttention 是怎么把 KV cache 当虚拟内存来管理的。"


@dataclass(slots=True)
class Sample:
    ttft: float = 0.0
    e2e: float = 0.0
    tokens: int = 0
    status: int = 0
    itls: list[float] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.status == 200 and self.tokens > 0


async def one_request(client: httpx.AsyncClient, url: str, max_tokens: int) -> Sample:
    body = {"model": "fake-1b", "stream": True, "max_tokens": max_tokens,
            "messages": [{"role": "user", "content": PROMPT}]}
    s = Sample()
    t0 = time.perf_counter()
    last = t0
    try:
        async with client.stream("POST", f"{url}/v1/chat/completions", json=body) as r:
            s.status = r.status_code
            if r.status_code != 200:
                await r.aread()
                return s
            async for line in r.aiter_lines():
                if not line.startswith("data: ") or line == "data: [DONE]":
                    continue
                delta = json.loads(line[6:]).get("choices", [{}])[0].get("delta", {})
                if not delta.get("content"):
                    continue
                now = time.perf_counter()
                if s.tokens == 0:
                    s.ttft = now - t0          # ★ 第一个**有内容**的 chunk 才算 TTFT
                else:
                    s.itls.append(now - last)
                last = now
                s.tokens += 1
    except Exception:
        s.status = -1
    s.e2e = time.perf_counter() - t0
    return s


async def run_level(url: str, concurrency: int, requests: int, max_tokens: int) -> dict:
    """固定并发数打满 requests 个请求（closed-loop，和线上真实流量形态一致）。"""
    limits = httpx.Limits(max_connections=concurrency + 10)
    async with httpx.AsyncClient(timeout=120.0, limits=limits) as client:
        await one_request(client, url, 4)                    # 预热，排除首次连接开销
        sem = asyncio.Semaphore(concurrency)

        async def guarded() -> Sample:
            async with sem:
                return await one_request(client, url, max_tokens)

        t0 = time.perf_counter()
        samples = await asyncio.gather(*(guarded() for _ in range(requests)))
        wall = time.perf_counter() - t0

    ok = [s for s in samples if s.ok]
    if not ok:
        return {"concurrency": concurrency, "ok": 0,
                "rejected": sum(1 for s in samples if s.status == 429)}

    def pct(xs: list[float], q: float) -> float:
        xs = sorted(xs)
        return xs[min(len(xs) - 1, int(q * len(xs)))]

    all_itls = [i for s in ok for i in s.itls]
    return {
        "concurrency": concurrency,
        "ok": len(ok),
        "rejected": sum(1 for s in samples if s.status == 429),
        "failed": sum(1 for s in samples if s.status not in (200, 429)),
        "rps": len(ok) / wall,
        "tok_s": sum(s.tokens for s in ok) / wall,
        "ttft_p50": pct([s.ttft for s in ok], 0.50),
        "ttft_p95": pct([s.ttft for s in ok], 0.95),
        "e2e_p50": pct([s.e2e for s in ok], 0.50),
        "e2e_p95": pct([s.e2e for s in ok], 0.95),
        "itl_mean": statistics.mean(all_itls) if all_itls else 0.0,
        "wall": wall,
    }


def find_knee(rows: list[dict]) -> dict | None:
    """膝点 = 再加并发，吞吐涨得还不如时延涨得快的那一档。

    判据：吞吐增速 < 时延增速的一半。这是个工程判据，不是数学定义 ——
    但它能给你一个可以写进容量规划文档的数字。
    """
    best = None
    for prev, cur in zip(rows, rows[1:], strict=False):
        if not prev.get("rps") or not cur.get("rps"):
            continue
        d_tp = cur["tok_s"] / prev["tok_s"] - 1
        d_lat = cur["ttft_p95"] / max(prev["ttft_p95"], 1e-9) - 1
        if d_lat > 0 and d_tp < d_lat / 2:
            best = prev
            break
    return best


def render(rows: list[dict]) -> None:
    head = (f"{'并发':>5}{'成功':>6}{'429':>5}{'RPS':>8}{'tok/s':>9}"
            f"{'TTFT p50':>10}{'TTFT p95':>10}{'E2E p95':>9}{'ITL 均值':>10}")
    print("\n" + head)
    print("-" * len(head))
    for r in rows:
        if not r.get("rps"):
            print(f"{r['concurrency']:>5}{r['ok']:>6}{r.get('rejected',0):>5}"
                  f"{'—':>8}{'—':>9}{'—':>10}{'—':>10}{'—':>9}{'—':>10}")
            continue
        print(f"{r['concurrency']:>5}{r['ok']:>6}{r['rejected']:>5}{r['rps']:>8.1f}"
              f"{r['tok_s']:>9.0f}{r['ttft_p50']*1000:>9.0f}ms{r['ttft_p95']*1000:>9.0f}ms"
              f"{r['e2e_p95']:>8.2f}s{r['itl_mean']*1000:>9.1f}ms")

    knee = find_knee(rows)
    print()
    if knee:
        print(f"▶ 膝点在并发 ≈ {knee['concurrency']}："
              f"吞吐 {knee['tok_s']:.0f} tok/s，TTFT p95 {knee['ttft_p95']*1000:.0f}ms")
        print(f"  建议把入口并发上限设在 {knee['concurrency']} 附近，"
              f"超出的请求返回 429 让客户端重试 ——")
        print("  排队排到 GPU 里去，只会让**所有人**都变慢，吞吐一点没多。")
    else:
        print("▶ 在测试的档位内还没出现膝点：可以继续往上加并发。")

    # 一张纯文本的吞吐-时延曲线，不依赖任何绘图库
    good = [r for r in rows if r.get("rps")]
    if len(good) > 1:
        print("\n吞吐-时延曲线（横轴=TTFT p95，纵轴=吞吐）")
        mx = max(r["tok_s"] for r in good)
        for r in good:
            bar = "█" * max(1, round(40 * r["tok_s"] / mx))
            print(f"  c={r['concurrency']:<4} {bar} {r['tok_s']:>5.0f} tok/s "
                  f"@ {r['ttft_p95']*1000:.0f}ms")


async def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:8000")
    p.add_argument("--levels", default="1,2,4,8,16,32")
    p.add_argument("--requests", type=int, default=40)
    p.add_argument("--max-tokens", type=int, default=48)
    p.add_argument("--json-out")
    a = p.parse_args()

    rows = []
    for c in [int(x) for x in a.levels.split(",")]:
        r = await run_level(a.url, c, a.requests, a.max_tokens)
        rows.append(r)
        print(f"  并发 {c:>3} 跑完：{r['ok']}/{a.requests} 成功", flush=True)
    render(rows)
    if a.json_out:
        with open(a.json_out, "w") as f:
            json.dump(rows, f, indent=2)
        print(f"\n原始数据 → {a.json_out}")


if __name__ == "__main__":
    asyncio.run(main())
