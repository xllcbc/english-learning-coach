# Agent Step 作为对话回合内部的溯源单位

## 背景

现有 Tool Invocation 只记录「模型调用应用工具」。对话回合里还有「最终回答」这类非工具步骤，而且需要按回合顺序回放。如果把整轮对话塞进 Tool Invocation，会混淆「工具执行记录」与「回合编排记录」；Retrieval Hit 记录的是检索溯源，也不能混用。需要一个新的、回合级的溯源单位。

## 决策

1. **新增 Agent Step。** 每个回合内由模型驱动的一次「决策 + 动作」记为一条 Agent Step，按 position 排序，记录种类（工具调用或回答）、可选工具名与参数、结果状态与耗时。
2. **不合并。** 工具调用步骤同时产生 Tool Invocation（沿用既有定义）；Agent Step 是回合编排记录，Tool Invocation 是工具执行记录，二者不互相替代。
3. **工作流不产生 Agent Step。** 只有 agent 场景（Teacher Conversation、Speaking Practice）产生 Agent Step。
4. **落库与回放。** 回合、Agent Step 与工具调用结果一并落库；重开对话回放已有记录，不重新调用模型。

## 与既有 ADR 的关系

- **ADR-0008（工作流 vs agent）**：本 ADR 是其在溯源层的实现，边界与之一致。
- 强化 `CONTEXT.md` 已声明的边界：Tool Invocation、Retrieval Hit、Agent Step 三者描述不同的事，不得混用。

## 后果

- 对话可审计、可评测、可回放；代价是新增表与写入路径。
- 日志与持久化只记录工具名、参数、状态、耗时，不记录完整提示词或 API key。
