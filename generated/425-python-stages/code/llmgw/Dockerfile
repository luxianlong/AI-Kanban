# ── 为什么从 CUDA 镜像起，而不是 python:slim ──────────────────────────
# 接真 vLLM 时需要 CUDA 运行时和驱动兼容的用户态库。自己往 python:slim 里装
# 是一条不归路。只跑假后端的话可以换成 python:3.12-slim，其余照抄。
FROM nvidia/cuda:12.4.1-runtime-ubuntu22.04 AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    HF_HOME=/models          # ← 权重放挂载卷，**绝不**打进镜像

COPY --from=ghcr.io/astral-sh/uv:0.5 /uv /usr/local/bin/uv
RUN apt-get update && apt-get install -y --no-install-recommends python3.12 curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ★ 依赖层和代码层分开 COPY：改一行代码不会让依赖重新装一遍。
#   这和你在 Java 里先 COPY pom.xml 再 mvn dependency:go-offline 是同一个技巧。
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src/ src/
RUN uv sync --frozen --no-dev

EXPOSE 8000

# liveness 用 /healthz（只证明进程活着）。
# ⚠️ 不要在这里用 /readyz：模型加载要几分钟，会被判定失败然后无限重启。
HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
    CMD curl -fsS http://localhost:8000/healthz || exit 1

# 单进程 + 多副本，不要用 --workers：
# 每个 worker 是独立进程，会各自去抢显存，也不共享准入控制的计数器。
# GPU 服务的正确扩展方式是"一卡一进程，前面放负载均衡"。
CMD ["uv", "run", "uvicorn", "llmgw.app:app", "--host", "0.0.0.0", "--port", "8000"]
