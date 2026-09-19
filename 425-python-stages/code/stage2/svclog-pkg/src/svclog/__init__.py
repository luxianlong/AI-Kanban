"""svclog —— 把访问日志聚合成时延/错误率报表。

分层（和你在 Java 里的分包习惯一一对应）：
    models     领域对象      ≈ record / VO
    reader     数据入口      ≈ Repository，全部是生成器
    aggregate  业务逻辑      ≈ Service
    report     输出适配      ≈ View / Serializer，按 Protocol 多态
    cli        程序入口      ≈ @SpringBootApplication + CommandLineRunner
"""

from svclog.models import Entry, Stat

__all__ = ["Entry", "Stat"]
__version__ = "0.2.0"
