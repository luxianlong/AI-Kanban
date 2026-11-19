"""练习 1.3 参考答案 —— 六个错误逐个修好，并说明为什么。"""

# 错误 1：可变默认参数 out=[] 被所有调用共享
# 错误 2：range(0, len(x)) 取下标 —— 非 Python 写法
def collect_slow(reqs, threshold=200, out=None):
    out = [] if out is None else out
    out.extend(r for r in reqs if r["ms"] > threshold)   # 或直接返回推导式
    return out


# 错误 3：闭包里给外层变量赋值 → UnboundLocalError（这里被 total 遮蔽成局部变量）
# 修法：根本不需要闭包，直接 sum 生成器
def summarize(reqs):
    return sum(r["ms"] for r in reqs)


# 错误 4：{} 是空 dict 不是空 set，dict 没有 .add()
def dedupe_endpoints(reqs):
    return {r["endpoint"] for r in reqs}                  # 集合推导


# 错误 5：`or` 把合法的 0 当成假值吞掉了
# 错误 6：配置项缺失与"显式设为 0"必须区分开
def pick_timeout(cfg):
    t = cfg.get("timeout")
    return 30 if t is None else t


if __name__ == "__main__":
    reqs = [{"endpoint": "/a", "ms": 300}, {"endpoint": "/b", "ms": 100}]
    print("collect  ", collect_slow(reqs), collect_slow(reqs))   # 两次都只有 /a
    print("summarize", summarize(reqs))
    print("dedupe   ", sorted(dedupe_endpoints(reqs)))
    print("timeout  ", pick_timeout({"timeout": 0}), pick_timeout({}))
