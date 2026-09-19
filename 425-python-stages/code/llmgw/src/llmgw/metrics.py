"""Prometheus 指标。

普通 Web 服务埋 QPS/时延/错误率就够了；LLM 服务**必须**额外埋四个：
    TTFT        —— 用户感知的"反应快不快"，和端到端时延是两回事
    排队时延     —— 区分"GPU 慢"和"根本排不上队"，定容量时全靠它
    in-flight   —— 当前占着显存的请求数，OOM 前的先行指标
    token 计数   —— 成本核算与容量规划的量纲（每千 token 成本）
"""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram

REGISTRY = CollectorRegistry()

# 分桶按"你要回答的问题"设计，不要用默认桶：
# TTFT 的关注区间是 50ms~5s，端到端是 0.5s~2min，它们的桶必须不同。
TTFT = Histogram("llmgw_ttft_seconds", "首 token 时延", ["model"],
                 buckets=(.05, .1, .2, .3, .5, .75, 1, 1.5, 2, 3, 5, 10),
                 registry=REGISTRY)
E2E = Histogram("llmgw_request_seconds", "端到端时延", ["model", "outcome"],
                buckets=(.1, .25, .5, 1, 2, 4, 8, 15, 30, 60, 120),
                registry=REGISTRY)
QUEUE_WAIT = Histogram("llmgw_queue_seconds", "排队时延", ["model"],
                       buckets=(.001, .01, .05, .1, .25, .5, 1, 2, 5, 10),
                       registry=REGISTRY)
ITL = Histogram("llmgw_inter_token_seconds", "token 间隔 (TPOT)", ["model"],
                buckets=(.002, .005, .01, .02, .05, .1, .25, .5),
                registry=REGISTRY)

REQUESTS = Counter("llmgw_requests_total", "请求数", ["model", "outcome"], registry=REGISTRY)
TOKENS = Counter("llmgw_tokens_total", "token 数", ["model", "kind"], registry=REGISTRY)

IN_FLIGHT = Gauge("llmgw_in_flight", "正在生成的请求数", registry=REGISTRY)
WAITING = Gauge("llmgw_waiting", "排队中的请求数", registry=REGISTRY)
READY = Gauge("llmgw_backend_ready", "后端就绪 (1/0)", registry=REGISTRY)
