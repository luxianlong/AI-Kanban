"""练习 1.3 —— 下面这段代码有 6 个 Java 人典型错误。找出来并修好。
   （它能跑，但结果全错 —— 这才是最可怕的）"""
def collect_slow(reqs, threshold=200, out=[]):
    for i in range(0, len(reqs)):
        r = reqs[i]
        if r["ms"] > threshold:
            out.append(r)
    return out

def summarize(reqs):
    total = 0
    def add(x):
        total = total + x
    for r in reqs:
        add(r["ms"])
    return total

def dedupe_endpoints(reqs):
    seen = {}                       # 想要一个 set
    for r in reqs:
        seen.add(r["endpoint"])
    return seen

def pick_timeout(cfg):
    return cfg.get("timeout") or 30

if __name__ == "__main__":
    reqs = [{"endpoint": "/a", "ms": 300}, {"endpoint": "/b", "ms": 100}]
    print(collect_slow(reqs), collect_slow(reqs))
    print(summarize(reqs))
    print(dedupe_endpoints(reqs))
    print(pick_timeout({"timeout": 0}))
