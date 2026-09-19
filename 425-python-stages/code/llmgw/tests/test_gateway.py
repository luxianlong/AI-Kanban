"""网关行为。全部跑在假后端上 —— 单测里永远不加载真模型。"""

import json


def test_non_streaming(client, body):
    r = client.post("/v1/chat/completions", json=body)
    assert r.status_code == 200
    data = r.json()
    assert data["object"] == "chat.completion"
    assert len(data["choices"][0]["message"]["content"]) == body["max_tokens"]
    assert data["usage"]["total_tokens"] > 0
    assert r.headers["x-request-id"]


def test_request_id_is_echoed(client, body):
    r = client.post("/v1/chat/completions", json=body, headers={"x-request-id": "abc123"})
    assert r.headers["x-request-id"] == "abc123"


def test_streaming_shape(client, body):
    with client.stream("POST", "/v1/chat/completions", json={**body, "stream": True}) as s:
        assert s.status_code == 200
        assert s.headers["content-type"].startswith("text/event-stream")
        frames = [ln[6:] for ln in s.iter_lines() if ln.startswith("data: ")]
    assert frames[-1] == "[DONE]"
    parsed = [json.loads(f) for f in frames[:-1]]
    assert parsed[0]["choices"][0]["delta"] == {"role": "assistant", "content": ""}
    assert parsed[-1]["choices"][0]["finish_reason"] == "stop"
    text = "".join(p["choices"][0]["delta"].get("content", "") for p in parsed)
    assert len(text) == body["max_tokens"]


def test_health_and_ready(client):
    assert client.get("/healthz").text == "ok"
    ready = client.get("/readyz")
    assert ready.status_code == 200
    assert ready.json()["ready"] is True
    assert set(ready.json()) >= {"running", "waiting", "admitted", "rejected"}


def test_metrics_exposes_llm_specific_series(client, body):
    """⚠️ Prometheus 的 registry 是**进程全局**的，计数器会跨测试累加。
    所以断言必须看**增量**，不能看绝对值 —— 否则测试的通过与否取决于执行顺序。
    """
    from llmgw import metrics as M

    before = M.REQUESTS.labels(body["model"], "ok")._value.get()
    client.post("/v1/chat/completions", json=body)
    after = M.REQUESTS.labels(body["model"], "ok")._value.get()
    assert after - before == 1

    text = client.get("/metrics").text
    for series in ("llmgw_ttft_seconds", "llmgw_queue_seconds", "llmgw_request_seconds",
                   "llmgw_inter_token_seconds", "llmgw_tokens_total", "llmgw_in_flight",
                   "llmgw_requests_total", "llmgw_backend_ready"):
        assert series in text, series
