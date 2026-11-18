# RAG 第一版验收回填（Spec #10）

日期：2026-11-18。分支：`agent/issue-16`。相关工单：#10（spec）、#11–#16。

## 承诺与结果

| Spec 承诺 | 结果 | 证据 |
| --- | --- | --- |
| Teacher Explanation 生成前检索依据材料，并引用出处 | 已兑现 | #13 确定性竖切；#14 REAL 向量排序；[`grounding-material.md`](grounding-material.md) |
| 没有相关依据时不假装引用、不填充占位内容 | 已兑现 | `NO_GROUNDING_MATERIAL` 降级路径；[`grounding-material.md`](grounding-material.md#retrieval-hit) |
| 学习者看到本次讲解的依据材料清单 | 已兑现 | SSE `complete.groundingMaterials`；前端 `ExplanationView` |
| Vocabulary Suggestion 的例句来自真实句库并替换模型自撰例句 | 已兑现 | #12/#15；[`corpus.md`](corpus.md) |
| 例句一定包含目标词形，并优先贴近学习者原句 | 已兑现 | 词形精确过滤 + 余弦排序；本次评测词形包含 100%、top-3 标注命中 100% |
| 界面展示语料来源与许可 | 已兑现 | 后端 `corpusSentenceSource`；前端 `VocabularySection` |
| Mock 模式保持确定性、测试不依赖真实模型 | 已兑现 | `KeywordLearningMaterialRetriever` 与既有 `-Pno-containers` 测试 |
| Retrieval Hit 记录命中种类、名次、相似度、跳过原因 | 已兑现 | `retrieval_hit` 与 `V7`；[`vector-retrieval.md`](vector-retrieval.md#schema-与索引) |
| REAL / MOCK 由既有 API key 条件选择，不新增开关 | 已兑现 | `ModelConfigurationTest`；#14 |
| 检索异常不影响 Practice Attempt / Teacher Explanation | 已兑现 | `CorpusSentenceResolver`、`GroundingMaterialResolver` 降级测试 |
| schema 由 Flyway 拥有，运行期不建表 | 已兑现 | `V1`–`V7` 迁移 |
| embedding 模型名独立配置，维度固定 512 | 已兑现 | `WRITELAB_MODEL_EMBEDDING_NAME`；`OpenAiEmbeddingModelFactory` |
| 依据材料阈值与 top-k 可配置 | 已兑现 | `writelab.retrieval.*`；#16 回填为 0.60 / 2 / 6 |
| 规模化语料建立 512 维向量索引 | 已兑现 | #16 离线 `build`；240,843 行全部覆盖 |
| 离线评测脚本与标注集存在，且不接入构建/CI | 已兑现 | [`retrieval-evaluation.md`](retrieval-evaluation.md)、[`tools/retrieval-eval/`](../tools/retrieval-eval/) |
| 一次性 embedding 成本与容量可预算 | 已兑现 | 本文与 [`corpus.md`](corpus.md#容量与成本) 的实测 |
| 不引入 agentic / 多跳检索 | 遵守 | 仍是推理前一次性检索；无新工具 |
| 不改变 Vocabulary Suggestion 的语义，不把候选例句前移进提示词 | 遵守 | 只替换 `example` 字段；#16 未改提示词或选词逻辑 |
| 手动 Word Lookup 的情境化例句（可选） | 推迟 | spec 标注为可延后；`Word Lookup` 返回结构未变 |

## 本次评测发现

- Corpus Sentence：12 个标注用例中例句命中率 100%、top-3 标注命中 100%、词形包含 100%；
  top-1 标注命中 11/12。唯一未命中的 `environment` 是语料自身有语法错误的句子
  `Algerian needs to protect is environment.`，正确句排第 2。这是语料策展问题，不是检索实现问题。
- Grounding Material：阈值 0.60 时标注命中 100%，平均返回 2.6 条；0.65 起标注命中降到 83.3%。
  因此最终默认值为阈值 0.60、per-category 2、max-hits 6。
- 未观察到「模型中文解释与被替换真实例句明显脱节」的证据，因此没有在本票改动 Vocabulary Suggestion
  语义，也没有把候选例句前移进提示词。若后续人工抽检发现脱节，应另立工单。

## 最后一轮全量测试

干净环境（清空模型相关环境变量，避免宿主的 `WRITELAB_MODEL_API_KEY` 把测试切到 REAL 模式）：

```bash
cd backend
env -u WRITELAB_MODEL_API_KEY -u WRITELAB_MODEL_BASE_URL \
    -u WRITELAB_MODEL_NAME -u WRITELAB_MODEL_EMBEDDING_NAME \
    mvn -q -Pno-containers clean test
```

| 项目 | 实际值 |
| --- | --- |
| 退出码 | 0 |
| Tests run | 53 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |

容器测试（`*IntegrationTest`、`*RetrieverTest`）按本票约定不在本环境执行，也不由本票修改。
