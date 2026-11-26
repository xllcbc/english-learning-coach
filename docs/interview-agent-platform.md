# 面试讲解稿：Agent 平台里的「工作流 vs agent」取舍

这份稿子用于讲清 WriteLab 为什么采用混合架构，而不是「把全项目 agent 化」。重点不是展示用了多少模型，而是说明**哪些地方不该让模型决定控制流**，以及这些取舍如何被测试和评测验证。

## 1. 一句话版本

WriteLab 是一个面向英语学习者的写作教练。它把控制流固定的任务——写作批改、Learning Review、Targeted Practice——做成确定性工作流；只把真正开放的对话——Teacher Conversation 和 Speaking Practice——交给有界 agent 循环。两者共享一个工具注册表和一个回合制核心。

## 2. 问题背景

学习者原来只能交一篇 Practice Attempt，拿到一次性批改和一段 Teacher Explanation。之后：

- 不能针对这篇练习追问「这里为什么用 although？」；
- 看不到跨多篇练习反复出现的薄弱项；
- 不能把诊断结果变成下一轮针对性练习；
- 没有口语入口；
- 对话和工具调用没有可回放、可审计的溯源。

所以产品需要从「批改器」变成「教练」。但「教练」不等于「全流程让模型自由发挥」。

## 3. 核心取舍：工作流 vs 有界 agent 循环

| 维度 | 工作流 | 有界 agent 循环 |
| --- | --- | --- |
| 控制流 | 应用代码决定步骤顺序 | 模型在回合内决定工具、顺序、何时结束 |
| 适用场景 | 写作批改、Learning Review、Targeted Practice | Teacher Conversation、Speaking Practice |
| 确定性 | 可重复、可断言 | 非确定性，但有预算与护栏 |
| 模型位置 | 只在固定位置调用 | 每个 Agent Step 调用一次「下一步」 |
| 失败模式 | 单步失败可降级，流程仍可解释 | 步数/工具预算耗尽，返回明确停止原因 |
| 主要收益 | 可测、可回放、可审计 | 能处理开放式追问与多轮对话 |

**为什么不全用 agent？**

- 固定流程让模型决定步骤，会把可测的排序、聚类、对比变成不可复现的输出；
- 成本与延迟不可控；
- 审计困难：学习者问「为什么给我这个建议」时，无法稳定追溯到具体 Learning Issue；
- 失败时无法给出确定的降级路径。

**为什么不全用工作流？**

- Teacher Conversation 的问题是开放的：学习者可能问词义、问原句、问历史模式，工具选择与顺序本来就该由模型判断；
- Speaking Practice 的回合也是开放的；
- 用固定流程硬编码所有追问，会把产品做成决策树，维护成本高且体验僵硬。

结论：**只在真正开放的对话上承担非确定性，并且给它加边界。**

## 4. 有界 agent 循环怎么工作

一个 Turn 的输入是学习者消息、场景 allowlist、预算、briefing 和历史；输出是一个 Turn，里面可以有多个 Agent Step。

循环由应用层实现（ADR-0008）：

1. 模型返回「调用某个工具」或「回答」；
2. 核心检查工具是否在场景 allowlist 内、是否超出工具预算；
3. 执行工具，把结果作为 observation 写回 Agent Step；
4. 重复，直到回答、步数预算耗尽或工具预算耗尽。

关键护栏：

- **步数预算**：默认 6 步，防止循环；
- **工具预算**：默认 4 次，防止单个问题触发无止境查询；
- **场景 allowlist**：共享工具注册表，但 Teacher Conversation 和 Speaking Practice 各自只能看到自己的子集；
- **停止原因**：`COMPLETED`、`STEP_BUDGET_EXHAUSTED`、`TOOL_BUDGET_EXHAUSTED` 都落库，可回放。

这就是「agent 的能力，工作流的边界」。

## 5. 溯源三分：Tool Invocation、Agent Step、Retrieval Hit

面试里可以主动讲这个设计，因为它体现「可审计」不是口号：

- **Tool Invocation**：只记录模型驱动的工具调用；
- **Agent Step**：记录回合内每次「决策 + 动作」，含序号、种类、工具名、参数、状态、耗时；
- **Retrieval Hit**：既有检索命中，保持原定义。

工作流代码调用工具只是工作流步骤，不产生 Tool Invocation。三者不混用，回放时才能解释「这一步是谁决定的」。

## 6. 通道与降级

- 文本走 SSE：`start` / `delta` / `complete` / `error`，复用既有讲解的流式与回放；
- 语音走 WebSocket：STT → 回合核心 → TTS；
- barge-in、取消播放、音频缓冲都在通道层，核心不感知通道类型（ADR-0009）；
- 语音失败降级到文本：STT 失败/超时提示改用文字，TTS 失败显示文字回答，同一会话可以继续（ADR-0011）；
- Speaking Session 是独立聚合根，不与 Practice Attempt 混用（ADR-0012）。

## 7. 四道 gate 与评测

「过硬」的定义是：ADR + 领域术语 + 确定性 fake + 集成测试 + 一个可量化评测 + 故障降级行为。四道 gate 分别是：

### A Teacher Conversation

- grounded-rate：每条规则/事实有 Grounding Material、User Document 或词典来源；
- 零无依据断言：没有来源时明确说「不能可靠回答」，不能编造引用；
- 工具使用恰当：该查词才查词，不需要工具时不乱调用。

评测用 8 个标注回答跑真实的 Agent Core 与引用规则，grounded-rate 100%，无依据断言 0。

### B Learning Review

- 诊断 precision/recall：小规模人工标注集上，Study Plan 不漏掉该有的模式，也不把一次性噪声升格；
- 建议可追溯率 100%：每条建议都能回到具体 Learning Issue；
- 数据不足时明确说明，不虚构趋势。

评测用 6 段标注历史，precision/recall/可追溯率都是 100%。

### C Targeted Practice

- 针对性问题在练前/练后下降可量化；
- 对比按「类别 + 严重度」定义目标簇；
- 同时保留 UNCHANGED / WORSENED 控制案例，避免把任何变化都当成进步。

评测中改善案例下降率 100%，平均下降 1.33。

### D Speaking Practice

- p95「说完 → 开始播放」≤ 2.5 秒；
- barge-in 可取消正在播放的回复；
- 降级路径有覆盖：转写失败、转写超时、合成失败、失败后改用文字。

评测用 20 个语音回合测 p95 = 1040 ms，超预算可识别，barge-in 可取消，降级覆盖 4/4。

评测入口是 `tools/agent-platform-eval.sh`，按需运行，不进入默认 CI；方法与结果见 [`docs/agent-platform-evaluation.md`](agent-platform-evaluation.md)。

## 8. 为什么这些评测能说明问题

- 评测集是人工标注的，不读取实现内部状态；
- 阈值在运行前写死，失败会让脚本非零退出；
- A 用脚本化模型输出，B/C 用确定性工作流，D 用确定性语音 fake，因此结果可重复；
- 评测不替代集成测试：集成测试证明 HTTP/SSE/WebSocket 契约与落库/回放，评测证明 gate 达标。

## 9. 常见追问

**Q：为什么不用 LangGraph / Spring AI 的 agent 抽象？**

A：这个项目的控制流很小、边界很清楚：一个回合、一张工具表、两组预算。手写循环更容易解释预算、停止原因和落库时机，也避免把框架的隐式行为带进可审计链路。引入框架没有带来足够的复杂度收益。

**Q：模型选错工具怎么办？**

A：三道防线：场景 allowlist、工具预算、Agent Step 记录。选到未注册或未授权工具会记 `UNKNOWN_TOOL`，不会执行；超出预算会记 `BLOCKED` 并停止。模型可以基于 observation 恢复，但永远不会越权。

**Q：Teacher Conversation 为什么锚定单份 Practice Attempt？**

A：它回答的是「这篇练习为什么这样改」。跨练习的模式识别是 Learning Review 的职责；把两者混在一起会让会话边界和数据归属都不清楚。

**Q：口语数据为什么不进 Learning Review？**

A：第一版刻意不接。Speaking Session 是独立聚合根，口语 Learning Issue 先随会话回放；接入 Learning Review 是后续增量，避免一次把范围铺得过大。

**Q：如果模型回答没有引用怎么办？**

A：有可用依据却完全不引用时，服务拒绝该回合并返回错误，不落库；没有可用依据时，允许明确拒绝回答。评测里的 `rejects-an-uncited-assertion` 和 `refuses-without-a-citable-source` 就是在守这条线。

**Q：为什么评测不放进 CI？**

A：A 的评测依赖脚本化模型输出，B/C/D 依赖确定性 fake；它们适合按需回归，不适合拖慢每次提交。默认 CI 继续只跑契约与确定性行为，评测通过 `agent-eval` profile 手动触发。

## 10. 收尾话术

这个项目最重要的设计判断不是「怎么让 agent 更自由」，而是「在哪里不让 agent 自由」。固定流程用工作流保证可测、可回放、可追溯；开放对话用有界 agent 循环保证体验，同时用预算、allowlist 和溯源把风险关住。四道 gate 把这种取舍变成了可重复的评测，而不是停留在架构图上。
