"""契约层：pydantic 是否真的在运行时拦住了坏请求。"""

import pytest

from llmgw.schemas import ChatRequest


def test_defaults():
    r = ChatRequest(model="m", messages=[{"role": "user", "content": "hi"}])
    assert (r.max_tokens, r.temperature, r.stream) == (128, 0.7, False)


@pytest.mark.parametrize(
    "patch",
    [
        {"messages": []},                                   # 空消息
        {"max_tokens": 0}, {"max_tokens": 99999},           # 越界
        {"temperature": -1}, {"temperature": 5},
        {"messages": [{"role": "root", "content": "x"}]},   # 非法 role
        {"messages": [{"role": "user", "content": ""}]},    # 空内容
    ],
)
def test_rejects_bad_input(patch):
    base = {"model": "m", "messages": [{"role": "user", "content": "hi"}]}
    with pytest.raises(ValueError):
        ChatRequest(**{**base, **patch})


def test_api_returns_422_not_500(client, body):
    r = client.post("/v1/chat/completions", json={**body, "max_tokens": -1})
    assert r.status_code == 422
    assert r.json()["detail"][0]["loc"] == ["body", "max_tokens"]
