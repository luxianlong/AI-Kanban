import os

import pytest
from fastapi.testclient import TestClient

os.environ.setdefault("LLMGW_BACKEND", "fake")


@pytest.fixture
def client():
    """TestClient 会触发 lifespan —— 所以 app.state 是真的装配好的。"""
    from llmgw.app import app

    with TestClient(app) as c:
        yield c


@pytest.fixture
def body():
    return {"model": "fake-1b",
            "messages": [{"role": "user", "content": "你好，介绍一下 PagedAttention"}],
            "max_tokens": 12}
