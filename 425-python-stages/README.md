# #425 · Python 学习：40 小时路线的五阶段实操版

把 [#422 的 40 小时路线](https://youraikanban.com/p/python-ai-infra-40h.html) 拆成五张实操网页，
每页都有可运行的示例、真实输出和带参考答案的练习。

## 在线页面

| 页面 | 地址 |
|---|---|
| 总览 | https://youraikanban.com/p/python-ai-infra-stages.html |
| 阶段一 · 把 Java 翻译成 Python | https://youraikanban.com/p/py-stage1-java-to-python.html |
| 阶段二 · 对象模型 + 生成器 | https://youraikanban.com/p/py-stage2-python-model.html |
| 阶段三 · 工程化与 GIL | https://youraikanban.com/p/py-stage3-engineering.html |
| 阶段四 · AI Infra 核心栈 | https://youraikanban.com/p/py-stage4-ai-infra.html |
| 阶段五 · Capstone 推理网关 | https://youraikanban.com/p/py-stage5-capstone.html |

## 目录

- `pages/` —— 六页的 Markdown 源码 + `build.py`（生成自包含 HTML 的转换器）
  - 重建：`python build.py 01-stage1.md out/`（需要 `markdown`、`pygments`）
- `code/` —— 页面里全部代码的可运行副本，**每一段都实际跑过**

## 代码清单

| 路径 | 内容 | 验证状态 |
|---|---|---|
| `code/stage1/` | 语法对照、Stream API 对照、六个坑的复现、`svclog.py`（167 行 CLI） | 全部运行通过，`ruff check` + `ruff format` 零告警 |
| `code/stage2/` | dataclass / Protocol / 生成器 / 装饰器 示例 | 全部运行通过 |
| `code/stage2/svclog-pkg/` | 重构成 src layout 的包（6 模块） | `ruff` 零告警，`mypy --strict` 通过，31 个 pytest 用例全过 |
| `code/stage3/` | GIL 自校准基准、asyncio 演示、剖析演示 | 全部运行通过 |
| `code/stage4/` | NumPy 张量、显存口算器、pickle 攻击面演示、最小推理服务、手写 KV cache | 全部运行通过（不需要 GPU） |
| `code/llmgw/` | **Capstone：LLM 推理网关**（1101 行） | `ruff` 零告警，`mypy --strict` 通过，24 个 pytest 用例全过，压测实测 |

## 复现方式

```bash
cd code/llmgw
uv sync
uv run pytest                                    # 24 passed
uv run ruff check src/ tests/ bench/             # All checks passed!
uv run mypy --strict src/llmgw                   # Success
MAX_CONCURRENCY=8 uv run uvicorn llmgw.app:app --port 8000 &
uv run python bench/loadgen.py --levels 1,2,4,8,16,32,64
```

## 实测环境

Python 3.12.14 · 2 vCPU 容器（cgroup 限流到约 1 个有效核，阶段三的基准脚本会自己量出这一点）·
无 GPU（PyTorch / vLLM 相关段落在页面中已明确标注为"需要 GPU"）。
