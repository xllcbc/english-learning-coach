# Dictionary Entry 数据集

## 为什么需要它

Flyway `V2` 只预置了 4 条手写 Dictionary Entry（moreover / consequently / effective / particularly），而自动查词
和手动查词都只读 `dictionary_entry` 表，所以学习者点击任何一个真实单词都会得到「词典中暂时没有…」[
`ApiExceptionHandler`](../backend/src/main/java/com/writelab/practice/ApiExceptionHandler.java)。仓库因此随包附带
一份由 ECDICT 派生的常用条目，由 [`DictionaryDatasetImporter`](../backend/src/main/java/com/writelab/dictionary/DictionaryDatasetImporter.java)
在启动时补齐。

## 来源与许可

| 内容 | 来源 | 许可 |
| --- | --- | --- |
| 释义、音标、词性、考试标签、词形变化 | [ECDICT](https://github.com/skywind3000/ECDICT) | MIT（许可原文见 `backend/src/main/resources/dictionary/LICENSE-ecdict.txt`） |
| 英文例句 | [Tatoeba](https://tatoeba.org) 英文句库 | CC-BY 2.0 FR |

## 筛选口径（47,024 条）

1. 只取单个英文单词（`^[a-z][a-z'-]{1,23}$`）。
2. 核心词 = 带 `zk/gk/cet4/cet6/ky/toefl/ielts/gre` 标签 ∪ Collins 星级 > 0 ∪ 词频（frq/bnc）前 20,000。
3. 词形扩展 = `exchange` 字段中 `0:lemma` 指向上一步核心词的词形（focused / focusing / focuses / hypotheses…），
   这样学习者点什么形态都能查到。
4. 释义必须含中文；只有英文缩写或领域标签（`[计]`、`[军]`）的行会被丢弃。

## 字段映射

| 列 | 规则 |
| --- | --- |
| `phonetic` | 补上斜杠，`'` → `ˈ`，`ә` → `ə`；超过 62 字符则留空 |
| `part_of_speech` | 从释义行首的 `n.` / `v.` / `vt.` / `a.` / `adv.` 等映射为中文，最多 3 个，用 `/` 连接；识别不出留空 |
| `meaning_zh` | 多个词性的释义用 `；` 连接，超过 400 字截断 |
| `example_en` | Tatoeba 中包含该词形的最短真实句子（6–18 词）；没有则留空 |

留空是设计允许的：前端会隐藏空字段，`MockFeedbackModel` 也只挑有例句的条目生成 Vocabulary Suggestion
（`StructuredFeedback` 契约要求 `example` 非空）。

当前随包的例句来自 Tatoeba 英文句库的一部分（约 128 万句，截断自 24.9 MB 的导出文件），覆盖
28,018 / 47,024 条条目。取全量句库重跑构建脚本可以提高这个比例。

## 重建方式

```bash
node tools/build-dictionary.mjs <path/to/ecdict.csv> [eng_sentences.tsv] [out.csv]
```

默认输出到 `backend/src/main/resources/dictionary/ecdict-ielts-common.csv`。该脚本不在 Maven 或 CI 链路里，
只在调整筛选口径或补例句时手动执行。ECDICT 全量 CSV 约 66 MB，不入仓库。

## 导入时机与幂等

`DictionaryDatasetImporter` 是 `ApplicationRunner`，在 Flyway 之后执行：

- 只有 `dictionary_entry` 行数 < 1000 才导入。不能用「表为空」判断，因为 Flyway 已经预置了手写条目。
- 逐条 `ON CONFLICT (word) DO NOTHING`，所以 `V2` 里那 4 条带精编例句的条目不会被批量条目覆盖。
- 整个导入在单个事务内，失败即回滚，下次启动会重跑；47,024 条耗时约 3 秒。

### 数据集更新后如何刷新已有的库

行数超过阈值后启动时不再写库，已写入的行也不会被新数据集覆盖（`DO NOTHING`）。要让本地已有的库拿到新条目
（例如本次补进的例句），先删掉待刷新的行再启动：

```bash
docker exec writelab-postgres psql -U writelab -d writelab -c "DELETE FROM dictionary_entry WHERE example_en = '';"
```

剩下的行数低于阈值，下次启动即按新数据集重新导入；`V2` 的精编条目带有例句，不会被删掉。

## 对自动查词链路的影响

`MockFeedbackModel` 原先调用 `DictionaryService.findAll()` 把整张表读进内存，再对每个条目跑一次正则；条目数上到
数万后这条路走不通。现在改为：文本分词 → 过滤语法词（`FUNCTION_WORDS`）→ `WHERE word IN (…)` 判断是否收录，
仍然按「首次出现顺序」挑选候选词。

副作用是集成测试的填充文本不能再用真实英文单词——`PracticeApiIntegrationTest.words()` 因此使用 `lorem`，
它不在数据集里，不会混进自动查词候选。
