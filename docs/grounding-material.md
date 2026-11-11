# Grounding Material 数据集

## 为什么需要它

教师讲解原先完全由模型组织语言，学习者无法判断某条建议是通用常识还是「有据可查」的说法，也无从追问「这段讲解当时依据了什么」。
这份数据集提供**项目自建的短篇英语用法条目**，按学习问题类别打标签；检索接缝在讲解生成前按当前聚焦的类别取回少量条目，
模型据此引用编号，接口在完成事件里返回同一份清单，界面展示为「本次依据」。

它不同于 Corpus Sentence：Corpus Sentence 服务 Vocabulary Suggestion 的例句来源；Grounding Material 服务 Teacher Explanation 的引用出处。

## 来源与许可

| 内容 | 来源 | 许可 |
| --- | --- | --- |
| 中文用法说明 | WriteLab 自建依据材料（项目自撰，不使用受版权限制的第三方考试资料） | CC BY 4.0 |
| `source` 列 | 固定为 `WriteLab 自建依据材料` | — |
| `license` 列 | 固定为 `CC BY 4.0` | — |

## 当前规模与筛选口径

随包 5 个类别 × 每类 4 条，共 20 条，文件为 `backend/src/main/resources/corpus/grounding_material.csv`。策展口径：

1. 每个 `IssueCategory`（`GRAMMAR`、`VOCABULARY`、`SENTENCE_STRUCTURE`、`COHERENCE`、`PUNCTUATION`）各 4 条，保证每个类别都能独立满足最小命中数。
2. 每条是一句可直接引用的中文说明，讲清「规则是什么」或「为什么这样改」，不针对某篇具体写作。
3. `category` 必须是 `IssueCategory` 的枚举名；导入时大小写不敏感，未知类别让启动失败。
4. 类别与正文唯一（`UNIQUE (category, content)`），重复导入同一数据集不会产生新行。

## 检索与阈值

`LearningMaterialRetriever.groundingMaterials(categories)` 是应用侧接缝的第二个方法，确定性实现按以下规则工作：

- **按聚焦类别过滤**：类别来自 `StructuredFeedback.focusedIssues()`（最严重的至多 5 条学习问题），所以检索、提示词编号与离线讲解文本共用同一顺序。
- **固定次序**：先取每个类别中正文最短的条目（长度、id 依次兜底），按类别名轮转，再取第二层；结果与传入类别的顺序无关，同一类别集合每次得到同一列表。
- **数量上限**：每类至多 `grounding-per-category-hits` 条、总计至多 `grounding-max-hits` 条。
- **阈值**：命中数少于 `grounding-min-hits` 视为「没有相关依据」，返回空列表，讲解不引用任何条目。确定性实现没有相似度，因此先用条数阈值占位；#14 引入向量检索后会改为相似度阈值，配置项随之替换。

配置位于 `application.yml` 的 `writelab.retrieval.*`，可用环境变量覆盖：

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `WRITELAB_GROUNDING_MAX_HITS` | 6 | 单次讲解最多引用的条目数 |
| `WRITELAB_GROUNDING_PER_CATEGORY_HITS` | 2 | 每个聚焦类别最多取回的条目数 |
| `WRITELAB_GROUNDING_MIN_HITS` | 2 | 少于该条数视为没有相关依据 |

## 讲解与引用

- `ExplanationRequest` 增加 `groundingMaterials` 字段（可以为空列表）；`ExplanationModel` 的接口签名不变，检索始终发生在应用服务层。
- 真实模型收到的用户提示词只在非空时附上「可引用的依据材料（引用时标注 [依据N]，N 为下面的编号）」清单，并要求没有材料时不输出任何引用编号、也不写「依据：无」这类占位。
- 离线实现（MOCK 模式）为每个聚焦类别引用其首条材料的编号，正文可直接断言；没有材料时完全不加标记。
- 流式事件仍是 `start / delta / complete / error`；`complete` 新增可选的 `groundingMaterials` 列表，每项含 `reference`、`category`、`content`、`source`、`license`。

## Retrieval Hit

依据材料的命中写入同一张 `retrieval_hit` 表，`kind = 'GROUNDING_MATERIAL'`：

- 提供的每一条材料一行，`grounding_material_id` 指向条目，`ranking` 是它与讲解中 `[依据N]` 对应的编号，`ordering = FIXED_ORDER`。清单是「本次可引用的材料」，离线实现只引用每个聚焦类别的首条，因此正文可能引用其中一部分。
- 没有候选或命中低于阈值时只写一行 `skip_reason = NO_GROUNDING_MATERIAL`；检索抛异常时只写一行 `skip_reason = RETRIEVAL_FAILED`，讲解照常完成，仅不引用依据。
- 讲解成功保存时才写入命中（与 `teacher_explanation` 同一事务），并先删除该次练习已有的 `GROUNDING_MATERIAL` 命中，避免重试后叠加两套溯源。
- 表上的唯一键是 `(practice_attempt_id, kind, position)`：语料命中在练习创建时占用 0..n-1，依据材料命中在讲解生成时按自己的 `position` 写入，互不冲突。

## 重放

已保存讲解的再次请求不重新检索：`complete` 事件从数据库读回当时写入的 `GROUNDING_MATERIAL` 命中并连接条目表，返回与首次相同的清单，也不会新增 Retrieval Hit。

清单引用的是条目表当前内容，不保存正文快照。如果之后按下面的步骤删除了某条依据材料（`grounding_material_id` 为 `ON DELETE SET NULL`），这条命中不再能解析出正文，重放清单会缺少该条目；历史讲解正文里的编号不会因此改写。

## 导入时机与幂等

`GroundingMaterialImporter` 是 `ApplicationRunner`，在 Flyway 之后执行：

- 阈值判断只数「本次数据集中已入库的 id」：全部 id 都在库里才跳过，额外手工行不会掩盖缺失行。
- 逐批多行 `INSERT ... ON CONFLICT (category, content) DO NOTHING`，整个导入在单个事务内，失败即回滚。
- 行 id 由 `(category, content)` 派生（`UUID.nameUUIDFromBytes`），重复导入不会产生新 id，已有 Retrieval Hit 的引用不会失效。

### 数据集更新后如何刷新已有的库

`ON CONFLICT DO NOTHING` 不会覆盖已存在的正文。修改某条正文相当于新增一行；要让旧行消失，先删掉该类别再重启：

```bash
docker exec writelab-postgres psql -U writelab -d writelab -c "DELETE FROM grounding_material WHERE category = 'GRAMMAR';"
```

`retrieval_hit.grounding_material_id` 是 `ON DELETE SET NULL`，历史命中记录仍保留名次与跳过原因。
