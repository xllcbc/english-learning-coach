# User Document 分块在上传事务中落库，后台任务只负责提交 generation

## 背景

ADR-0005 的“上传时索引”决策写的是：上传请求完成校验与分块，**后台任务把分块落库**并切换状态。实现生命周期工单（#21）时，重试失败任务与原地替换都要求解析结果在后台任务失败、进程退出后仍然存在：原始文件按要求不保留，如果分块只写在后台上，失败任务就没有可重试的输入。替换还要求新 generation 先完整落库、再原子切换，失败时旧 generation 继续可检索。

## 决策

1. **上传事务落库。** 上传请求完成大小、编码、空文档、同名/内容哈希与分块预算判断后，在同一个数据库事务里写入 `user_document` 与目标 generation 的 `document_chunk`。事务提交后才调度后台任务。
2. **后台任务提交 generation。** 后台任务不再写 chunk，只负责状态推进与原子切换：初始 generation 进入 `READY`；替换 generation 在全部 chunk 就绪后切换 `active_generation`、更新 `content_hash`/`char_count`，再删除旧 generation。
3. **失败可重试。** 后台失败只记录 `error_code` 或 `pending_error_code`，保留已落库的 chunk。重试重新调度同一个 generation，不重新分块、不新增文档；进行中的任务可被再次调度，提交操作按 generation 幂等。
4. **预算按实际 chunk 计算。** 分块总数上限同时考虑 `chunk_count` 与 `document_chunk` 实际行数，待切换 generation 也计入预算，避免替换期间临时超过 5,000 个 chunk。

## 与 ADR-0005 的关系

这是对 ADR-0005 决策 2 中“后台任务负责把分块落库”的**显式修订**。ADR-0005 的其他决策不变：独立语料、只参与教师讲解、引用快照、限额与日志约束都继续有效。这样做的代价是上传请求事务稍重，换来失败重试、进程中断恢复与替换原子性不依赖原始文件或内存状态。

## 后果

- 上传请求失败时整个事务回滚，不会留下半套文档或 chunk。
- 后台进程退出后，已落库的 generation 可以通过重试恢复；没有已落库 chunk 的历史失败文档会返回稳定错误，要求删除后重新上传。
- `document_chunk` 的实际行数是分块预算的最终依据；逻辑 `chunk_count` 只描述当前 active generation。
