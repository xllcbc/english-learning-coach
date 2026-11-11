# Corpus Sentence 数据集

## 为什么需要它

Vocabulary Suggestion 的 `example` 原先由模型自撰（MOCK 模式抄 Dictionary Entry 的英文例句）。学习者照着记却在词典里查不到出处。
这份数据集提供**真实句库里的句子**，由检索接缝在反馈通过校验后替换 `example`，并在界面上展示来源署名。

它和 Dictionary Entry 的英文例句是两回事：后者是每个词条一条「最短的 6–18 词句子」，只服务于查词；Corpus Sentence 是独立表，
可以覆盖检索需要的候选集，也可以与词典例句不同。

## 来源与许可

| 内容 | 来源 | 许可 |
| --- | --- | --- |
| 英文句子 | [Tatoeba](https://tatoeba.org)（每条句子来自 CC BY 2.0 FR 的条目） | CC BY 2.0 FR |
| `source` 列 | `Tatoeba / <作者用户名>`，作者取自 Tatoeba 句子元数据 | — |

## 当前规模与筛选口径

随包 10 个核心词形 × 至多 4 句，共 37 句（`consequently` 过滤后只剩 1 句）。筛选口径：

1. 句子必须包含该词形（词边界匹配，大小写不敏感）。
2. 词数 6–18。
3. 仅保留可打印 ASCII，去掉首尾空白与连续空格。
4. 仅保留 `CC BY 2.0 FR` 许可的 Tatoeba 句子。
5. 同一词形内部按「词数升序、再按句子文本」排序后取前 4 句。

`word_form` 统一存小写（导入时规范化，数据库用 `CHECK (word_form = lower(word_form))` 兜底），检索因此可以按 btree 索引做精确匹配。

排序结果是确定性实现的「固定次序」：同一输入每次得到同一顺序。

## 生成方式

```bash
node tools/build-corpus.mjs
```

默认输出到 `backend/src/main/resources/corpus/sentences.csv`，词表写在脚本里。该脚本访问 Tatoeba 公共搜索 API，
只在调整词表或补充句子时手动执行；**不在 Maven 或 CI 链路里**。上游搜索结果会变化，因此重跑是一次重新策展，不是可复现构建。

## 导入时机与幂等

`CorpusSentenceImporter` 是 `ApplicationRunner`，在 Flyway 之后执行：

- 阈值判断只数「本次数据集中已入库的 id」：全部 id 都在库里才跳过，因此额外的手工行或后续数据集不会掩盖缺失行。该判定按数据集的 id 列表绑定参数，服务于当前手工小语料；#15 语料规模化时随导入流程一起替换。
- 逐批多行 `INSERT ... ON CONFLICT (word_form, sentence) DO NOTHING`，整个导入在单个事务内，失败即回滚。
- 行 id 由 `(word_form, sentence)` 派生（`UUID.nameUUIDFromBytes`），所以重复导入不会产生新 id，已有 Retrieval Hit 的引用不会失效。

### 数据集更新后如何刷新已有的库

`ON CONFLICT DO NOTHING` 不会覆盖已有行。要让某个词形拿到新句子，先删掉该词形的旧行再重启：

```bash
docker exec writelab-postgres psql -U writelab -d writelab -c "DELETE FROM corpus_sentence WHERE word_form = 'many';"
```

`retrieval_hit.corpus_sentence_id` 是 `ON DELETE SET NULL`，历史命中记录仍保留名次与跳过原因。

## Retrieval Hit

`retrieval_hit` 记录每次 Practice Attempt 的检索溯源：命中种类、词形、被采用的语料句子 id、名次、相似度与跳过原因。

- 确定性实现按固定次序排序，`similarity_score` 恒为 `NULL`，`ordering` 列为 `FIXED_ORDER` —— 这就是「相似度为空」的说明。
- 没有候选句子时记录 `skip_reason = NO_CORPUS_SENTENCE`；检索本身抛异常时记录 `RETRIEVAL_FAILED`。
- `ordering`、`similarity_score`、`skip_reason`、`corpus_sentence_id` 的含义也写在数据库列注释（`COMMENT ON COLUMN`）里，只查数据库即可读懂。
- 两种情况下 Vocabulary Suggestion 都保留模型原例句，请求仍成功。
