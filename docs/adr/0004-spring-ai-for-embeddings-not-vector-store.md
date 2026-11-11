# Spring AI 只负责 embedding 接入，向量表由 Flyway 拥有

检索增强生成需要调用 embedding 服务并保存向量。我们继续用 Spring AI 发起 embedding 请求，但不使用它的 `VectorStore` / `PgVectorStore` 抽象：向量列、索引和相似度查询由 Flyway 迁移与 `JdbcClient` 直接管理，与既有数据库风格保持一致，也避免 Spring AI 自带的表结构与配置和 Flyway 的迁移归属互相冲突。

这是对 ADR-0003「使用 Spring AI 接入模型」的收窄解释，不是替代，也不是静默覆盖：ADR-0003 仍然决定模型接入方式，本 ADR 只限定存储归属。没有配置模型 API key 时不构造 embedding 客户端，离线确定性实现继续承担同样的检索职责。

本 ADR 不改变 spec #10 的 Out of Scope 清单：agentic 检索、全量 Tatoeba 句库入库与索引、向量表扩容仍按各自 ticket 处理。其中与本 ADR 相关的只有向量列（#14）与语料规模（#15），两者都沿用「schema 由 Flyway 拥有」的前提，因此不存在未记录的冲突。
