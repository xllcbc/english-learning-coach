# REAL 向量检索

## 为什么需要它

确定性检索已经能保证「例句一定来自语料、一定包含目标词形」，但它无法回答「哪一句更接近学习者这次的语境」。配置模型 API key 之后，
同一条检索接缝切换到 embedding + pgvector：词汇建议先在**同词形候选**里按语义相似度挑最贴近学习者原句的一句；教师讲解的依据材料
先按学习问题类别过滤，再按语义相关度挑选。

没有配置 API key 时行为与本 feature 的前两张票完全一致：不构造 embedding 客户端，也不会发起任何 embedding 请求。

## 装配：仍然只有一个开关

`ModelConfiguration` 用**既有的** API-key 条件同时选择模型适配器与检索实现，不引入第二个开关：

| API key | 检索实现 | embedding 客户端 |
| --- | --- | --- |
| 空 | `KeywordLearningMaterialRetriever`（确定性） | 无 bean，任何路径都不会调用 |
| 有值 | `VectorLearningMaterialRetriever`（向量） | `SpringAiEmbeddingClient` 包装 OpenAI embedding 模型 |

## 配置

embedding 模型名是独立配置项，维度固定 512；base url、API key 与超时复用既有模型配置，不新增密钥面。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `WRITELAB_MODEL_EMBEDDING_NAME` | `text-embedding-3-small` | embedding 模型；请求时固定 `dimensions=512` |
| `WRITELAB_GROUNDING_MAX_HITS` | 6 | 每次讲解最多引用的依据材料条数 |
| `WRITELAB_GROUNDING_PER_CATEGORY_HITS` | 2 | 每个聚焦类别按相似度取前 N 条 |
| `WRITELAB_GROUNDING_SIMILARITY_THRESHOLD` | 0.3 | 低于该余弦相似度视为「无相关依据材料」 |
| `WRITELAB_GROUNDING_MIN_HITS` | 2 | **仅确定性实现使用**，向量模式以相似度阈值替代 |

阈值与 top-k 的最终默认值由离线评测（#16）调整并回填本文档。

## Schema 与索引

`V7__add_retrieval_embeddings.sql` 为 `corpus_sentence` 与 `grounding_material` 各新增一列 `halfvec(512)`，
并建立 HNSW 余弦索引（`halfvec_cosine_ops`）。schema 仍完全由 Flyway 拥有，应用运行期不建表、不建索引。
相似度查询直接使用余弦距离运算符 `<=>`，`1 - distance` 即余弦相似度。

`retrieval_hit.ordering` 因此新增取值 `VECTOR_SIMILARITY`：

- `FIXED_ORDER`：确定性排序，`similarity_score` 为 `NULL`；
- `VECTOR_SIMILARITY`：按余弦相似度排序，`similarity_score` 记录该分数。

## 查询构造

- **语料例句**：每个词形独立查询；SQL 先 `WHERE word_form = :wordForm AND embedding IS NOT NULL` 做精确过滤，
  再 `ORDER BY embedding <=> CAST(:query AS halfvec)`。查询文本是学习者在写作中使用该词的原句；找不到原句时退回词形本身。
- **依据材料**：查询文本是本轮聚焦学习问题的原文、修改稿与解释拼接；SQL 先 `WHERE category IN (:categories)` 过滤，
  窗口函数在每个类别内按距离取前 `grounding-per-category-hits` 条，再按全局相似度取前 `grounding-max-hits` 条，
  低于 `grounding-similarity-threshold` 的条目被丢弃。全部低于阈值时返回空列表，讲解不引用任何依据，也不填充占位内容。
- 两类查询都只读取语料表，在线请求不写入语料表；只有 `retrieval_hit` 会增长。

## 降级

检索异常对学习者不可见，功能自动退回今天的表现：

| 情况 | Practice Attempt / 教师讲解 | Retrieval Hit |
| --- | --- | --- |
| embedding 调用失败 | 保留模型原例句 / 无依据完成讲解 | `skip_reason = RETRIEVAL_FAILED` |
| 向量查询失败 | 同上 | `skip_reason = RETRIEVAL_FAILED` |
| 语料表为空或没有向量 | 同上 | `NO_CORPUS_SENTENCE` / `NO_GROUNDING_MATERIAL` |

用户可见错误码不新增；讲解失败仍复用既有错误码。完整提示词与 API key 既不落日志也不落库，日志只记录尝试次数与错误计数。

## 在线成本与延迟（容量说明）

每次检索请求最多发起**一次批量 embedding 调用**：语料例句把所有词形的查询文本合并成一批，依据材料只有一条查询文本。
向量查询本身先按词形/类别过滤，候选集很小；HNSW 索引为语料规模化准备，具体执行计划由查询规划器决定（按类别分组的窗口查询不一定走索引扫描）。
在线边际延迟目标为每次请求 < 150 ms；该数字受供应商抖动影响，只作为容量说明记录，**不写进任何测试断言**。

## 向量填充的边界

本票只新增向量列、索引、在线消费逻辑与配置；语料行的向量由 #16 的离线索引流程填充。`embedding IS NULL` 的行会被向量检索跳过，
因此在一个尚未建立索引的库上，REAL 模式表现为「没有候选」并正常降级，而不是报错。离线索引重建完成后无需改代码即可生效。
