# 固定流程用工作流，只有开放对话用小 agent 循环

## 背景

引入 agent 的动机是支持 Teacher Conversation、Learning Review、Targeted Practice 与 Speaking Practice。但「引入 agent」不等于「把所有流程交给模型自主编排」：写作批改、学习复盘、针对性练习的控制流本来就固定，如果让模型决定步骤，会变得不可预测、难测，还可能空转——这是过度设计。需要划清哪类任务用工作流、哪类才真正需要 agent，并明确它与 ADR-0004「不做 agentic 检索」的关系。

## 决策

1. **工作流场景。** 写作批改、Learning Review、Targeted Practice 由应用代码决定步骤顺序，模型只在固定位置被调用；控制流可以在所有现实输入下预先画出。
2. **agent 场景。** 只有 Teacher Conversation 与 Speaking Practice 使用有界的 agent 循环：模型在回合内决定调用哪些工具、以什么顺序、何时结束；循环带步数预算与工具预算。
3. **共享工具注册表。** 两种场景共用一个工具注册表，每个场景声明可用工具子集，避免重复实现。
4. **溯源边界。** 只有 agent 的模型驱动工具调用记 Tool Invocation；工作流代码调用工具只是工作流步骤，不记 Tool Invocation。
5. **自研循环。** agent 循环由应用层实现并藏在应用接口之后，不引入 agent 框架；保留确定性 fake 供测试，与 ADR-0003 的薄 vendor seam 一致。

## 与既有 ADR 的关系

- **ADR-0004（Spring AI 只负责 embedding，且「agentic 检索」不在范围内）**：本 ADR 不推翻它。仍不允许模型自由检索；agent 只编排结构化动作（查词、按问题类别检索依据材料、查询学习进度、搜索用户文档）。依据材料的检索顺序、阈值与引用保持不变。即 ADR-0004 的「不做 agentic 检索」继续成立，本 ADR 只新增「允许 agentic 动作编排」。
- **ADR-0002（结构化纠错与流式讲解分离）**：不冲突。批改仍是工作流，讲解仍是第二阶段。
- **ADR-0003（使用 Spring AI 接入模型）**：沿用。agent 循环用 Spring AI 作为单次模型传输，循环控制权留在应用层。

## 后果

- 批改与复盘保持可测、可评测；只有真正开放的对话承担 agent 的非确定性。
- 「不是所有流程都 agent 化」是有意为之，需要在评审中能解释取舍。
- 工具注册表成为 A/B/C/D 的共享契约，新增工具必须声明场景白名单。
