# User Document 在上传时建立向量，并与依据材料共用一次查询向量

## 背景

ADR-0005 决定 User Document 是第三份独立语料，Mock 模式用 `pg_trgm`，REAL 模式用 512 维
embedding + pgvector。实现 REAL 路径时有两个边界需要固定：

1. 文档什么时候算“已建立向量索引”？如果等到查询时才补向量，学习者会看到一篇 `READY` 文档却
   在第一次讲解时才发现没有索引，而且 embedding 成本会不可预算。
2. Grounding Material 与 User Document 的查询文本完全相同。如果两个 retriever 各自调用一次
   embedding，同一次讲解会产生两次查询向量，既增加成本，也可能因为供应商的批内浮点差异让两份
   材料基于略微不同的向量排序。

## 决策

1. **上传时建立向量。** 配置 API key 后，后台处理在 `CHUNKING` 之后进入 `EMBEDDING`，为该
   generation 的每个 Document Chunk 写入 512 维向量，全部成功后才进入 `READY`。任一批失败时
   文档进入 `FAILED`，错误码为 `EMBEDDING_FAILED`；已写入的批次保留，重试只补 `embedding IS NULL`
   的分块。文档的 `embedding_model` 只在完整成功时写入，因此 Mock 时期上传的文档不会被误认为
   已建立向量索引。
2. **一个查询向量，两份语料。** 讲解检索编排层先计算一次 query vector，再把它同时传给
   `GroundingMaterialResolver` 与 `UserDocumentRetriever`。REAL retriever 不再自行 embedding；
   Mock retriever 忽略该参数，继续走 `pg_trgm`。共享向量构建失败时，User Document 直接按
   `RETRIEVAL_FAILED` 跳过；Grounding Material 回退到自己的既有检索路径，因此它的失败边界不被
   用户文档的失败拖累。成功路径仍然只有一次查询 embedding 调用。
3. **重建是显式操作。** 缺少向量或 `embedding_model` 过期的 READY 文档由
   `tools/retrieval-tooling.sh documents` 手动重建。应用启动、上传和讲解都不自动重建，也不自动
   产生 embedding 费用。CLI 只刷新向量，不覆盖分块时记录的 `chunker_version`。

## 与既有 ADR 的关系

- **ADR-0002**（结构化纠错与流式讲解分离）不变：检索仍发生在讲解生成之前，SSE 与持久化边界不
  受影响。
- **ADR-0003**（Spring AI 集成）不变：embedding 仍由 Spring AI 的 `EmbeddingModel` 适配，只在
  既有 API-key 条件下构造。
- **ADR-0004**（用 Spring AI 做 embedding 而不是 VectorStore）不变：查询仍直接用 pgvector SQL，
  不引入 VectorStore 抽象。
- **ADR-0005**（独立 User Document 语料）不变：仍然只有一份独立语料、一个预算和一套引用快照。
- **ADR-0006**（分块在上传事务中落库、后台只提交 generation）不变：本 ADR 只给后台提交增加
  “先建立向量”的步骤，失败重试仍复用已落库的 chunk。

## 后果

- 上传请求只负责校验、分块与落库；向量在后台建立，慢供应商不会阻塞 `202` 响应，但 `READY`
  会晚于 Mock 模式。
- 一次讲解的在线 embedding 成本固定为一次查询 embedding；上传成本与 chunk 数成正比，可预算。
- 供应商换模型后，旧文档在 `embedding_model` 与新配置不一致时不会被 REAL 检索使用，面板提示
  “N 篇文档尚未建立向量索引”，直到手动重建。
- chunker 版本变化需要重新分块，而原始文件不保留，因此 CLI 只重建向量并保留分块时的
  `chunker_version`；要改变 chunk 边界仍需重新上传。文档的 `chunkerVersion` 因此仍能暴露分块
  策略漂移，而不会被向量重建掩盖。
