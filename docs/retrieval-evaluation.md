# 检索离线评测与索引构建

## 这是什么

`tools/RetrievalTooling.java` 与 `tools/retrieval-tooling.sh` 是**手动执行**的离线工具，负责两件事：

1. `build`：把随包的 Corpus Sentence / Grounding Material 数据集交给真实 embedding 模型，写入
   `corpus_sentence.embedding` 与 `grounding_material.embedding`（`halfvec(512)`），并让既有 HNSW
   余弦索引生效。
2. `eval`：用 [`tools/retrieval-eval/`](../tools/retrieval-eval/) 里的小标注集跑检索，
   报告例句命中率、例句包含目标词形的比例、依据材料命中情况，并扫描阈值与 top-k。
3. `documents`：为缺少向量或 embedding model 过期的 User Document 手动重建
   `document_chunk.embedding`，并把当前模型写回文档；`chunker_version` 保持分块时的实际版本。
4. `eval-documents`：用同一目录下的 User Document fixture 跑内存检索，验证 expected chunk 进入
   top-4、干扰文档零误引、无关查询零返回。

`stats` 与 `checksum` 只读，用来记录容量和验证重建前后的索引行为。

## 为什么存在

自动化测试使用确定性 fake embedding，只证明「检索接缝的契约没有变」，不能回答「这批语料在真实
embedding 下检索得准不准」。评测需要真实供应商、pgvector 数据库和几百 MB 的向量数据，既不可复现
也不适合放进持续集成，因此单独保留为离线工具。它让「依据材料阈值」和「例句候选」由证据决定，而不是
凭感觉固定。

## 明确不接入构建与持续集成

- 不在 `backend/pom.xml` 的插件或 profile 里；
- 不被 Maven、Surefire、前端构建或 CI 调用；
- 需要 `.env` 里的真实 API Key 与一个已跑过 Flyway / 语料导入的 PostgreSQL；
- 评测结果不写成测试断言。CI 继续只验证外部契约与确定性行为。

## 前置条件

1. PostgreSQL（`pgvector/pgvector:pg17`）已启动，Flyway 已迁移到 `V7__add_retrieval_embeddings.sql`。
2. 后端已至少启动一次，Corpus Sentence 与 Grounding Material 已导入。
3. 环境变量与后端一致：`SPRING_DATASOURCE_*`、`WRITELAB_MODEL_BASE_URL`、
   `WRITELAB_MODEL_API_KEY`、`WRITELAB_MODEL_EMBEDDING_NAME`。维度固定 512。
   如果供应商的 embedding 端点不是「Base URL + `/embeddings`」，可用
   `WRITELAB_MODEL_EMBEDDING_URL` 直接给出完整端点。
4. 本机有 PostgreSQL JDBC 驱动；脚本会从 `~/.m2` / `~/.cache/sandcastle/m2` 里查找，找不到时用
   `WRITELAB_PG_JDBC_JAR=/path/to/postgresql.jar` 指定。

## 建索引

```bash
# 填所有 NULL 向量；已填充的行不会重复请求
tools/retrieval-tooling.sh build

# 只处理某张表、限制行数、调整批量与并发
tools/retrieval-tooling.sh build --table corpus --limit 10000 --batch-size 10 --concurrency 8

# 换 embedding 模型后强制覆盖所有向量
tools/retrieval-tooling.sh build --refresh
```

行为说明：

- 默认只处理 `embedding IS NULL` 的行，因此中断后重跑会从缺口继续；`--refresh` 才会覆盖已有向量。
- 每批默认 10 条、默认并发 4。供应商限制批量时用 `--batch-size` 降低；遇到 429 / 5xx 会指数退避重试。
- 每个批次在一个短事务里更新，单批失败不会留下半批向量。
- 行 id 仍是 `(word_form, sentence)` / `(category, content)` 派生的稳定 id；重新导入语料不会产生新 id，
  已存在的向量也不会被重写。删除后重新导入的缺失行会先变成 `NULL`，下一次 `build` 只补这些行。
- 本机实测（2026-11-18，`text-embedding-v3`，512 维，batch 10，并发 8）：240,843 行约 25–30 分钟；
  供应商在批量请求中对同一文本的浮点结果有约 `1.5e-4` 的批内差异，但重建前后的检索名次一致（见下）。

### 容量实测

以随包 240,843 行 Corpus Sentence 为例：

| 指标 | 实测 |
| --- | --- |
| 数据库总大小 | 647 MB |
| `corpus_sentence` 表 + TOAST + 全部索引 | 625.1 MB |
| 其中 HNSW 余弦索引 `idx_corpus_sentence_embedding` | 294 MB（约 1.22 KB/行） |
| 表本体（扣除索引） | 约 284 MB（约 1.18 KB/行） |
| `grounding_material` 表 + 索引 | 152 KB |

与 [Corpus Sentence 数据集](corpus.md#容量与成本) 记录的 `halfvec(512)` + HNSW 约 **2.2 KB/行**
（240k 行约 0.5–0.65 GB）同一量级；实测总增量约 555 MB。HNSW 索引与向量列各占约一半，符合
512 维而不是 1,536 维的选择。

## 评测

```bash
# 默认使用当前 .env 的 threshold/per-category/max-hits 作为对照，并扫描 0.30–0.70
tools/retrieval-tooling.sh eval

# 指定扫描范围
tools/retrieval-tooling.sh eval \
  --thresholds 0.45,0.50,0.55,0.60,0.65,0.70 \
  --per-category 1,2 \
  --max-hits 3,4,6
```

标注集与格式见 [`tools/retrieval-eval/README.md`](../tools/retrieval-eval/README.md)。
指标定义：

- **例句命中率**：该词形至少返回一条候选 Corpus Sentence 的比例。
- **top-1 / top-3 命中标注例句**：人工标注的可接受句出现在第 1 名 / 前 3 名的比例。
- **例句包含目标词形**：被采用的第一条候选按词边界包含目标词形的比例。
- **依据材料命中率**：至少返回一条 Grounding Material 的比例。
- **依据材料命中标注条目**：人工标注的预期条目出现在返回清单中的比例。
- **expected-precision**：返回清单中命中标注条目的比例。标注每个用例只写了一到两条「预期」，
  同一类别的其他条目也可能合理，因此这是保守下界。
- **avg-materials**：每个用例平均返回多少条依据材料。

### 本次评测结果

环境：2026-11-18，PostgreSQL 17 + pgvector，`text-embedding-v3`，512 维；Corpus Sentence
240,843 行 / 33,215 个词形，Grounding Material 20 条；标注集 12 个例句用例 + 12 个依据材料用例。

**Corpus Sentence**

| 指标 | 结果 |
| --- | --- |
| 例句命中率 | 100.0% |
| top-1 命中标注例句 | 91.7%（11/12） |
| top-3 命中标注例句 | 100.0% |
| 第一条候选包含目标词形 | 100.0% |
| 平均候选数 | 12.1 |

未命中的一条是 `environment`：top-1 是语料里本身有语法错误的句子
`Algerian needs to protect is environment.`，人工标注的正确句子排第 2。这是**语料质量问题**，
不是向量维度或阈值问题；本票不改写语料，也不改 Vocabulary Suggestion 的语义。

**Grounding Material**

| threshold | per-cat | max-hits | 命中率 | 标注命中 | expected-precision | avg-materials |
| --- | --- | --- | --- | --- | --- | --- |
| 0.30（原默认） | 2 | 6 | 100.0% | 100.0% | 25.0% | 4.0 |
| 0.45 | 2 | 6 | 100.0% | 100.0% | 25.0% | 4.0 |
| 0.55 | 2 | 6 | 100.0% | 100.0% | 26.7% | 3.8 |
| **0.60** | **2** | **6** | **100.0%** | **100.0%** | **38.7%** | **2.6** |
| 0.65 | 2 | 6 | 83.3% | 83.3% | 62.5% | 1.3 |
| 0.70 | 2 | 6 | 83.3% | 83.3% | 83.3% | 1.0 |
| 0.60 | 1 | 6 | 100.0% | 100.0% | 57.1% | 1.8 |
| 0.60 | 2 | 3 | 100.0% | 100.0% | 42.9% | 2.3 |

## 阈值与 top-k 的最终默认值

最终默认值：

| 配置 | 原值 | 最终值 | 依据 |
| --- | --- | --- | --- |
| `WRITELAB_GROUNDING_SIMILARITY_THRESHOLD` | 0.3 | **0.6** | 0.60 是仍保持 100% 标注命中的最高阈值；平均材料从 4.0 降到 2.6，expected-precision 从 25.0% 升到 38.7%。0.65 起标注命中跌到 83.3%，说明再高会漏掉相关材料。 |
| `WRITELAB_GROUNDING_PER_CATEGORY_HITS` | 2 | **2（保持）** | 每个聚焦类别取 1 条虽然更精简，但会让确定性实现的单类别 `grounding-min-hits=2` 失去材料；本次评测没有证据表明第二条材料有害。 |
| `WRITELAB_GROUNDING_MAX_HITS` | 6 | **6（保持）** | 最多 5 个聚焦类别时，6 条上限仍能保证每个类别有机会出现；实测平均只返回 2.6 条，上限本身不是噪声来源。 |

阈值依赖 embedding 模型。更换 `WRITELAB_MODEL_EMBEDDING_NAME` 或供应商后，应重跑本评测再调整；
本次数据来自 `text-embedding-v3`，不是仓库默认模型名 `text-embedding-3-small`。

## 重建与索引一致性

本次验证步骤：

1. 记录 `environment` 与 `challenge` 两个词形的行数与索引校验和；
2. 删除这两个词形共 24 行，使表行数低于数据集行数；
3. 运行真实的 `CorpusSentenceImporter`，日志为
   `Imported corpus dataset corpus/sentences.csv (240829 rows read, 24 written, 240843 rows in table)`；
4. 重跑 `build`，只补这 24 行（`24 rows to embed`），不碰其余 240,819 行；
5. 重跑评测，12 个例句用例的 top-1 名次与重建前完全一致，例句命中率、top-1/top-3、词形包含比例
   也一致。

校验和本身会因供应商批量请求的浮点噪声变化，因此「一致」按**索引覆盖的稳定 id 与检索名次**判断，
不按逐位浮点相等判断。

## User Document 向量重建

`documents` 是手动命令，不在应用启动、Maven、前端构建或 CI 中执行：

```bash
# 只补缺失向量或 embedding model 过期的 READY 文档
tools/retrieval-tooling.sh documents

# 换模型后强制重算所有 READY 文档的 active generation
tools/retrieval-tooling.sh documents --refresh

# 限制文档数、调整批量
tools/retrieval-tooling.sh documents --limit 10 --batch-size 10
```

行为：

- 只处理 `READY` 文档的 `active_generation`，不会碰到待切换的 replacement generation；
- 默认只请求 `embedding IS NULL` 的 chunk；模型变化或 `--refresh` 时重算全部 active chunk；
- 每批在一个短事务里更新，单批失败不会留下半批向量；全部完成后才写入 `embedding_model`；
- 不自动运行，也不自动产生 embedding 费用。Mock 时期上传的文档在运行前 `embedding_model` 为
  `NULL`，不会被 REAL 检索误用；运行后前端“N 篇文档尚未建立向量索引”提示消失。

## User Document fixture 评测

标注集在 [`tools/retrieval-eval/document_fixtures.csv`](../tools/retrieval-eval/document_fixtures.csv)
与 [`document_cases.csv`](../tools/retrieval-eval/document_cases.csv)。fixture 语料在内存中构造，
不需要 PostgreSQL；评测使用与 REAL retriever 相同的规则（余弦阈值 0.55、top-4、每文档最多 2 条、
相同 chunk 文本去重），但需要真实 embedding 供应商。它同样**不接入 Maven、前端构建或 CI**。

```bash
tools/retrieval-tooling.sh eval-documents
```

10 个案例覆盖：中文笔记 + 英文例句、冠词、时态、连贯性、标点、词汇搭配、干扰文档、无关查询、
注入文档、同一文档多 chunk。判定标准：每个 expected key 必须进入 top-4；distractor 不能出现；
`expect_none` 查询必须返回空列表。

### 本次评测结果

环境：2026-11-23，`text-embedding-v3`，512 维，阈值 0.55、top-4、每文档 2 条；fixture 14 条、
案例 10 个。

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
没有被引用，讲解指令仍由 system prompt 固定。同一文档多 chunk 的案例中，`multi.md` 的两个 chunk
进入 top-4，第三个被每文档上限排除，验证了 2 条上限。

fixture 只证明“真实 embedding 下这组小样本的排序符合预期”，不证明模型不会执行注入内容；注入
防护仍由 `ExplanationPrompt` 的分隔符与 system prompt 声明承担，并由后端单元测试覆盖。

## Vocabulary Suggestion 的中文解释与真实例句

本评测只覆盖「检索是否返回真实、含目标词形、且与查询相近的句子」，**不**评估模型中文解释与替换后
例句的语义连贯性；没有证据表明需要改变 Vocabulary Suggestion 的语义，也没有把候选例句前移进提示词。
如果后续人工抽检发现明显脱节，应另立工单，而不是在本票里改模型选词或提示词。

## 已知限制

- 标注集很小，只用于回归信号，不是统计意义上的基准；语料或 `IssueCategory` 变化后应补充用例。
- Grounding Material 每个类别只有 4 条，阈值扫描的分辨率有限；评测结果对 embedding 模型敏感。
- 例句检索没有相似度阈值（先按词形精确过滤再排序），因此 `top-1` 仍可能选中语料中质量较差的句子；
  这是语料策展问题，不是检索实现问题。
- 评测脚本直接复用生产检索 SQL 的等价查询；修改 `VectorLearningMaterialRetriever` 的过滤、排序或
  窗口函数时，必须同步更新工具并重跑本评测。
