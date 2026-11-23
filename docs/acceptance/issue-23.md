# Issue #23 验收记录：REAL 向量检索、重建与 fixture 评测

日期：2026-11-23。分支：`agent/spec19-full2/issue-23`。父 spec：#19。

## 验收对照

| 验收标准 | 实现 | 证据 |
| --- | --- | --- |
| 配置 key 时上传在 `READY` 前完成所有 chunk 的 512 维 embedding；任一分块失败进入 `FAILED`，可重试且不留下半成品 | `EmbeddingDocumentChunkEmbedder` 分批请求、按批事务写入；处理器只有在全部 chunk 有向量时才提交 `READY`，失败记录 `EMBEDDING_FAILED`；重试只补 `embedding IS NULL` | `UserDocumentRealRetrievalIntegrationTest`、`DocumentErrorCode.EMBEDDING_FAILED` |
| 未配置 key 时不构造 embedding 客户端、不发起请求，继续 Mock `pg_trgm` | Mock 条件不注册 `EmbeddingClient` / `DocumentChunkEmbedder`；`UserDocumentRetriever` 仍是 `KeywordUserDocumentRetriever` | `ModelConfigurationTest`；既有 `UserDocumentApiIntegrationTest` / `UserDocumentExplanationIntegrationTest` |
| REAL 按 512 维 `halfvec` 余弦排序；阈值 0.55、最多 4 条、每文档 2 条、相同 chunk 去重；只查 `READY` 且 `active` | `VectorUserDocumentRetriever` 的窗口函数 SQL | `VectorUserDocumentRetrieverTest` |
| Grounding Material 与 User Document 共用同一个 query vector；一次讲解只发生一次查询 embedding 调用 | `ExplanationMaterialResolver` 先构建一次向量，再传给两个 retriever | `SharedQueryVectorTest`、`UserDocumentRealRetrievalIntegrationTest` |
| REAL/Mock 由既有 API-key 条件装配；REAL 失败时用户文档降级，讲解仍完成 | `ModelConfiguration` 的 `MockModelCondition` / `RealModelCondition`；resolver 捕获异常并写 `RETRIEVAL_FAILED` | `ModelConfigurationTest`、`ExplanationMaterialResolverTest`、`SharedQueryVectorTest` |
| 记录 `embedding_model` 与 `chunker_version`；提供 CLI 重建，不自动重建/扣费 | `user_document.embedding_model`、`chunker_version`；`tools/retrieval-tooling.sh documents` | `V11`、`UserDocumentSummary`、`docs/user-documents.md` |
| Mock 时期文档不会被误当作已建索引；面板显示“N 篇文档尚未建立向量索引”，重建后消失 | `embeddingIndexed` 要求当前模型 + 全部 active chunk 有向量；面板在真实模式显示计数 | `UserDocumentRealRetrievalIntegrationTest`、`UserDocumentsPanel.test.tsx` |
| 维度不是 512、embedding 失败、向量查询失败时文档检索降级并记录原因；Grounding Material 不受影响 | 共享向量与 retriever 都做 512/有限值校验；失败写 `RETRIEVAL_FAILED`，Grounding Material 结果独立 | `SharedQueryVectorTest`、`VectorUserDocumentRetrieverTest` |
| fixture 8–10 案例，覆盖中文笔记 + 英文例句、干扰、无关、注入、同文档多 chunk；expected 进 top-4、干扰零误引、注入不改变指令 | 10 个案例、14 条 fixture；见下结果 | `tools/retrieval-eval/document_cases.csv`、`document_fixtures.csv` |
| fixture 可手动重复执行，结果记录，不接入 Maven/前端/CI | `eval-documents` 手动命令，未加入构建 | `docs/retrieval-evaluation.md`、`tools/retrieval-eval/README.md` |
| 后端 retriever seam 用确定性 fake embedding；HTTP seam 覆盖 REAL 上传→READY→引用→重建提示 | 新增两个容器测试 | `VectorUserDocumentRetrieverTest`、`UserDocumentRealRetrievalIntegrationTest` |
| 既有 Mock 路径、Grounding Material 与 Corpus Sentence 行为不变 | 默认测试套件通过；共享向量在 Mock 下为 `null`，原 retriever 路径不变 | 全量 `-Pno-containers` |
| 测试命令通过 | 见下 | 后端 73 tests / 前端 24 tests |

## fixture 评测结果

命令（需要真实 embedding key；本环境通过 HTTPS 代理访问供应商）：

```bash
tools/retrieval-tooling.sh eval-documents
```

环境：`text-embedding-v3`，512 维；阈值 0.55、top-4、每文档 2 条；fixture 14 条、案例 10 个。

| 案例 | 结果 | top-4（key） |
| --- | --- | --- |
| chinese-note-english-example | 通过 | multi_rule, sv_zh, sv_example, articles_zh |
| articles | 通过 | articles_zh, multi_rule, sv_zh, sv_example |
| tenses | 通过 | tenses_zh, sv_zh |
| coherence | 通过 | coherence_zh, punctuation_zh, multi_mistake |
| punctuation-comma-splice | 通过 | punctuation_zh, tenses_zh |
| vocabulary-avoid | 通过 | vocab_zh, sv_zh, multi_rule, punctuation_zh |
| distractor-document | 通过 | sv_zh, multi_rule, sv_example, multi_example |
| irrelevant-query | 通过 | `<none>` |
| prompt-injection | 通过 | multi_rule, sv_zh, sv_example, articles_zh |
| same-document-multi-chunk | 通过 | multi_example, multi_mistake, sv_zh, sv_example |

汇总：**10/10 通过**；expected chunk 全部进入 top-4；干扰文档零误引；无关查询零返回；注入文档
未被引用；同一文档的两个 chunk 进入 top-4，第三个被每文档上限排除。

## 最后一轮自动化测试

```bash
cd backend
env -u WRITELAB_MODEL_API_KEY -u WRITELAB_MODEL_BASE_URL \
    -u WRITELAB_MODEL_NAME -u WRITELAB_MODEL_EMBEDDING_NAME \
    mvn -q -Pno-containers -Dmaven.repo.local=/home/xllcbc/.cache/sandcastle/m2 test

cd ../frontend
npm test
```

| 项目 | 结果 |
| --- | --- |
| 后端 `-Pno-containers` | 73 tests，0 failures，0 errors |
| 前端 Vitest | 24 tests，0 failures |

容器测试（`*IntegrationTest` / `*RetrieverTest`）按仓库约定需要 Docker。本实现环境没有可用的
Docker daemon，因此未能在本机执行；新增的 `UserDocumentRealRetrievalIntegrationTest` 与
`VectorUserDocumentRetrieverTest` 只能完成编译与代码审查。需要容器环境的验收项应在此环境之外
重跑：

```bash
cd backend && mvn -q test
```
