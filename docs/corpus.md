# Corpus Sentence 数据集

## 为什么需要它

Vocabulary Suggestion 的 `example` 原先由模型自撰（MOCK 模式抄 Dictionary Entry 的英文例句）。学习者照着记却在词典里查不到出处。
这份数据集提供**真实句库里的句子**，由检索接缝在反馈通过校验后替换 `example`，并在界面上展示来源署名。

它和 Dictionary Entry 的英文例句是两回事：后者是每个词条一条「最短的 6–18 词句子」，只服务于查词；Corpus Sentence 是独立表，
为同一个核心词形保留最多 12 句候选，供不同情境挑用。

## 来源与许可

| 内容 | 来源 | 许可 |
| --- | --- | --- |
| 英文句子 | [Tatoeba](https://tatoeba.org) 英文句库的 detailed per-language 导出（2026-11-12 下载） | CC BY 2.0 FR |
| `source` 列 | `Tatoeba / <作者用户名>`；导出没有用户名时退回 `Tatoeba` | — |
| 许可原文 | `backend/src/main/resources/corpus/LICENSE-tatoeba.txt` | CC BY 2.0 FR |

Tatoeba 的 detailed 导出没有逐句许可列；许可标识来自 Tatoeba 数据集级的 CC BY 2.0 FR 声明，而不是构建时逐行验证。
旧的 Tatoeba 搜索 API 有逐句 `license` 字段，新流程改用 detailed 导出是为了拿到每条句子的作者用户名。

署名要求：

- 界面在例句旁展示后端返回的 `source` 与 `license`（`Tatoeba / 用户名 · CC BY 2.0 FR`），许可文本不在前端硬编码。
- 分发或再使用这份数据集时，保留 `source` 列，并链接到 <https://creativecommons.org/licenses/by/2.0/fr/>。
- 句子只做筛选与排序，不改写文本。
- 完整法文法律文本与署名说明随包附在 `backend/src/main/resources/corpus/LICENSE-tatoeba.txt`，本节说明它的用途与位置。

## 当前规模与筛选口径

随包 **240,829 行**，覆盖 **33,215 个词形**（47,024 个词典词形中的 33,215 个在 Tatoeba 里有合格句子）；每个词形 1–12 句，
其中 13,729 个词形取满 12 句。文件为 `backend/src/main/resources/corpus/sentences.csv`，22.4 MB。

候选词形直接取自 `backend/src/main/resources/dictionary/ecdict-ielts-common.csv` 的 `word` 列。因此「核心词定义 + 词形扩展」
与 [Dictionary Entry 数据集](dictionary.md) 是同一份输出，不存在两套口径；重建词典后需要重新生成语料（见下文）。

ADR-0004 把「全量 Tatoeba 句库入库与索引」排除在范围外；这里不是全量入库：2,040,567 句里只保留 240,829 句
（33,215 个词形 × 至多 12 句），本次改动也没有新增向量列。全量句库与向量索引仍不在范围内。

单句筛选口径：

1. 仅可打印 ASCII（`\x20-\x7E`），避免 Unicode 字母把词边界切得含糊。
2. 不含双引号，以大写字母开头，无首尾空白或连续两个空格。
3. 词数 6–18（按 `[a-z0-9][a-z0-9_'-]*` 分词）。
4. 句子必须包含该词形：词形等于整枚 token，或是 token 内由 `'` / `-` 切出的部分
   （`chan's` 含 `chan`，`4chan` 不含 `chan`，`e-mail` 含 `mail`）。
5. 同一词形按小写句子文本去重。
6. 同一词形按「词数升序、再按句子文本」排序，保留前 12 句。

`word_form` 统一存小写（导入时规范化，数据库用 `CHECK (word_form = lower(word_form))` 兜底），检索因此可以按
`idx_corpus_sentence_word_form` btree 索引做精确匹配。

输出按词形升序，同一份导出得到同一顺序。上游导出会随下载时间变化，重跑是一次重新策展，不是可复现构建。

同一词形有多句候选时，默认走确定性实现的「固定次序」：同一输入每次得到同一顺序。配置 API key 后改用向量实现：
先按 `word_form` 精确过滤，再用学习者原句的 embedding 按余弦相似度排序；详见 [REAL 向量检索](vector-retrieval.md)。


## 生成方式

```bash
# 1. 下载并解压 Tatoeba detailed 英文导出（约 35 MB 压缩、190 MB 解压）
curl -o /tmp/eng_sentences_detailed.tsv.bz2 \
  https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences_detailed.tsv.bz2
bunzip2 -c /tmp/eng_sentences_detailed.tsv.bz2 > /tmp/eng_sentences_detailed.tsv

# 2. 从词典数据集生成语料例句数据集
node tools/build-corpus.mjs \
  backend/src/main/resources/dictionary/ecdict-ielts-common.csv \
  /tmp/eng_sentences_detailed.tsv
```

默认输出 `backend/src/main/resources/corpus/sentences.csv`；第三个参数可以改输出路径。

这个脚本是什么、为什么存在：它把真实句库切成「核心词形 × 至多 12 句」的候选集，供 Vocabulary Suggestion 在反馈校验后替换
模型自撰例句。它**不在 Maven 或 CI 链路里**，只在重建词典、调整筛选口径或补充语料时手动执行；上游 190 MB 的 TSV 与 ECDICT 源文件都不入仓库。

本机实测：输入 2,040,567 句，命中 33,215 个词形，输出 240,829 行；Node 耗时约 22 秒（不含下载与解压）。

## 导入时机与幂等

`CorpusSentenceImporter` 是 `ApplicationRunner`，在 Flyway 之后执行：

- **阈值判断**：先流式数出数据集行数，再读 `SELECT count(*) FROM corpus_sentence`；表内行数 ≥ 数据集行数就跳过。
  阈值是粗粒度的：额外手工行可能让表长期 ≥ 数据集行数；数据集行数不变或变小、但句子内容变化时也会直接跳过。
  要让已有库拿到新句子，先用下面的 `DELETE` 让表低于数据集行数，再重启。数据集变大时表行数会小于新数据集行数，会自动补写。
- **逐条冲突忽略**：逐批 500 行 `INSERT ... ON CONFLICT (word_form, sentence) DO NOTHING`。
- **单事务回滚**：整个导入在一个事务内，任一批失败即全部回滚，下次启动重跑。
- **稳定 id**：行 id 由 `(word_form, sentence)` 派生（`UUID.nameUUIDFromBytes`），重复导入不产生新 id，已有 Retrieval Hit 的引用不会失效。
- **行数与耗时**：本机 Testcontainers（`pgvector/pgvector:pg17`）首次导入 240,829 行约 3.0–3.5 秒；删除少量行后的补写仍需重扫并冲突检查
  全部 240k 行，约 1.6–1.8 秒。日志形如
  `Imported corpus dataset corpus/sentences.csv (240829 rows read, 240829 written, 240829 rows in table) in 3003 ms`。
- 导入逐行流式读取，不把整份 CSV 放进内存；导入事务内再读一次文件。

### 数据集更新后如何刷新已有的库

`ON CONFLICT DO NOTHING` 不会覆盖已有行。只刷新某些词形的 Tatoeba 例句、保留精编条目：

```bash
docker exec writelab-postgres psql -U writelab -d writelab -c \
  "DELETE FROM corpus_sentence WHERE word_form IN ('many','moreover') AND source LIKE 'Tatoeba%';"
```

删掉的行数让表低于数据集行数，重启后导入补回。`source` 不是 Tatoeba 的人工精编条目不会被这条 SQL 删除；
即使人工条目与新数据集共享 `(word_form, sentence)`，`ON CONFLICT` 也会保留已有行，不会覆盖来源与许可。

要全量重置可以 `TRUNCATE corpus_sentence;`，但 `retrieval_hit.corpus_sentence_id` 是 `ON DELETE SET NULL`：
历史命中的名次与跳过原因仍在，只是不再能连回具体句子。

## 容量与成本

以随包 R = 240,829 行为例（实测平均词形 7.4 字节、句子 45.4 字符、来源 16.0 字符）：

- **表与索引增量**：约 140 B/行堆元组 + 主键/唯一键/`word_form` 索引约 160 B/行 ≈ **70–90 MB**（含页开销与 20% 余量）。
  CSV 本身 22.4 MB，打进 jar 后会再压缩。
- **向量增量（#14 落地后）**：`halfvec(512)` = 1,024 B/行；HNSW（cosine，m≈16）索引约 1.2 KB/行，合计约 **2.2 KB/行**。
  240k 行约 **0.5–0.65 GB**；若增长到 40 万行约 **0.9–1.1 GB**。这是单机 PostgreSQL 可接受的量级，也是 spec 选择 512 维而不是
  1,536 维的原因。
- **实测（#16 建索引后）**：240,843 行全部填充 512 维向量后，`corpus_sentence` 表 + TOAST + 索引共
  **625.1 MB**，其中 HNSW 索引 **294 MB**（约 1.22 KB/行），表本体约 **284 MB**（约 1.18 KB/行）；
  数据库总大小 **647 MB**。与上面的 0.5–0.65 GB 估算同一量级。建索引是手动离线操作，见
  [检索离线评测与索引构建](retrieval-evaluation.md)。
- **一次性 embedding 成本**：按 `text-embedding-3-small` 的 0.02 美元/百万 token、平均 11 token/句估算，
  240,829 × 11 ≈ 2.65 M token ≈ **0.05 美元**；按 2 倍重试/批处理余量计 **< 0.11 美元**。
  40 万行约 4.4 M token ≈ **0.09 美元**，余量后 **< 0.18 美元**。价格与分词器会变，公式是
  `行数 × 平均 token × 单价`；在线请求只 embedding 学习者原句，边际成本可忽略。

## Retrieval Hit

`retrieval_hit` 记录每次 Practice Attempt 的检索溯源：命中种类、词形、被采用的语料句子 id、名次、相似度与跳过原因。
它同时记录教师讲解引用的依据材料，见 [Grounding Material 数据集](grounding-material.md)。

- 数据集为每个词形提供至多 12 句候选；确定性实现按固定次序采用第一条，向量实现（#14）在同一候选集中选更贴近学习者原句的一条。
  本次不新增「多句任选」的 API 或界面字段，多句候选只扩大检索可用的范围，不改排名语义。
- 确定性实现按固定次序排序，`similarity_score` 恒为 `NULL`，`ordering` 列为 `FIXED_ORDER` —— 这就是「相似度为空」的说明。
- 向量实现按余弦相似度排序，`ordering = VECTOR_SIMILARITY`，`similarity_score` 记录该分数。
- 没有候选句子（含语料表为空、行尚未填充向量）时记录 `skip_reason = NO_CORPUS_SENTENCE`；检索或 embedding 抛异常时记录 `RETRIEVAL_FAILED`。

- `ordering`、`similarity_score`、`skip_reason`、`corpus_sentence_id` 的含义也写在数据库列注释（`COMMENT ON COLUMN`）里，只查数据库即可读懂。
- 两种情况下 Vocabulary Suggestion 都保留模型原例句，请求仍成功。

句子行数从 37 涨到 240,829 不改变外部契约：`example` 仍非空且包含建议词形，API 字段形状、`ranking = 1`、
`FIXED_ORDER`、跳过原因与降级行为都不变；变化只在于某个词形现在有更多候选句子。
