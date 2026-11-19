"""造一份 2000 行的样例日志，便于试跑 svclog.py。"""
import random, csv, sys
random.seed(42)
SPEC = {   # endpoint: (基准延迟 ms, 抖动, 错误率, 权重)
    "/v1/chat/completions": (700, 400, 0.04, 50),
    "/v1/embeddings":       (35,  20,  0.01, 30),
    "/v1/models":           (4,   2,   0.00, 12),
    "/healthz":             (1,   1,   0.00, 8),
}
eps = list(SPEC); weights = [SPEC[e][3] for e in eps]
w = csv.writer(sys.stdout); w.writerow(["ts", "endpoint", "status", "ms"])
t = 1_726_600_000.0
for _ in range(2000):
    t += random.expovariate(20)                       # 平均 20 QPS
    ep = random.choices(eps, weights)[0]
    base, jitter, err, _ = SPEC[ep]
    bad = random.random() < err
    ms = round(random.gauss(base, jitter) if not bad else random.uniform(1, 20), 1)
    if random.random() < 0.02: ms = round(ms * random.uniform(4, 9), 1)   # 长尾
    w.writerow([f"{t:.3f}", ep, 500 if bad else 200, max(0.1, ms)])
