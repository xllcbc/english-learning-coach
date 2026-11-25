# Teacher Conversation 的检索工具按回合编排，不改变检索预算

## 背景

ADR-0008 允许 Teacher Conversation 的 agent 循环调用 `lookup_word`、`retrieve_grounding_material`、
`search_user_documents`、`query_learning_progress` 等结构化动作，同时要求「仍不允许模型自由检索」且
「依据材料的检索顺序、阈值与引用保持不变」。ADR-0007 又规定一次 Teacher Explanation 只构造一个查询
向量，同时服务 Grounding Material 与 User Document 两份语料。

A2 把这些工具真正接到 Teacher Conversation 上后，出现一个需要明确的边界：agent 在一个回合内可能
多次调用检索工具，每次查询随学习者的问题而变；这与「一次讲解只构造一个查询向量」的工作流前提并不
相同。

## 决策

1. **工具是结构化动作，不是自由检索。** 模型只能选择已注册、且在当前场景白名单内的工具，并给出
   工具声明的参数；它不能选择语料、SQL、向量索引或检索阈值。两份语料、既有检索预算、阈值与排序
   实现全部复用，不新增第二套检索逻辑。
2. **每个检索动作各自执行一次。** 一个 agent 回合内每次调用 `retrieve_grounding_material` 或
   `search_user_documents`，都按该次调用给出的 query 执行一次既有 retriever。REAL 模式下这是一次
   查询 embedding；回合的步数预算与工具预算（默认各为上界）限制最大调用次数，因此单回合成本有界。
3. **ADR-0007 的「一个查询向量」仍只约束 Teacher Explanation 工作流。** 该决策解决的是「一份讲解
   的两份语料不要各 embed 一次」；它不要求一个多动作 agent 回合把所有不同 query 合并成一个向量。
4. **引用编号在一个回合内统一。** Briefing 来源先占号；之后每个成功的工具结果按发生顺序预留下一段
   `[依据N]`，`lookup_word` 的词典条目与两个检索工具的 sources 共用同一序列。失败或越界的调用不占号，
   越界调用不执行。
5. **会话引用是可回放的快照。** 检索工具结果携带来源 id 供 Agent Step 审计，但 Teacher Conversation
   Citation 仍只保存标题、内容、来源与许可；重开对话不依赖文档当前状态。

## 与既有 ADR 的关系

- **ADR-0004 / ADR-0008：** 不推翻「不做 agentic 检索」。本 ADR 只是把 ADR-0008 已列出的三个工具在
  A2 落地，并明确 query 参数属于结构化动作参数。
- **ADR-0007：** 收窄其适用范围到 Teacher Explanation 工作流，而不是静默覆盖：多动作 agent 回合的
  每次检索是独立动作，成本由回合预算约束。
- **ADR-0010：** 不变。每次检索调用同时产生 Tool Invocation 与 Agent Step，引用快照独立落库。

## 后果

- A2 的多轮对话可以在一个回合内组合查词、依据检索与文档搜索，并保留统一引用。
- REAL 模式下，一个回合最多产生与工具预算相同次数的查询 embedding；这是有意的成本换灵活度。
- 将来若要把多个检索动作合并为一次共享查询向量，应新增 ADR，而不是在本路径上隐式改变预算。
