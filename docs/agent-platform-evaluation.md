# Agent 平台四道 gate 的评测与回归

本文件记录 Teacher Conversation、Learning Review、Targeted Practice、Speaking Practice 四个场景的「过硬」gate 如何重复运行、如何对照阈值判断通过/失败，以及最近一次结果。方法论沿用 [`docs/retrieval-evaluation.md`](retrieval-evaluation.md)：小规模人工标注集、离线/按需执行、不把评测结果写进常规 CI 断言。

## 为什么单独做评测

单元测试和集成测试证明「契约没有变」，但不能回答「这道 gate 是否仍然达标」。例如：

- Teacher Conversation 能流式返回回答，不代表回答里的每条规则都有依据；
- Learning Review 能返回 Study Plan，不代表聚类结果和人工标注一致；
- Targeted Practice 能展示对比，不代表针对性问题真的在练后下降；
- Speaking Practice 能收发 WebSocket 消息，不代表 p95 仍在上限内、barge-in 和降级路径仍可用。

评测集是这些问题的回归信号。它故意很小：用于发现明显退化，不是统计意义上的基准。

## 按需运行，不进入默认 CI

评测类统一带 JUnit `evaluation` tag，`backend/pom.xml` 默认把该 tag 排除出 Surefire；只有 `agent-eval` profile 会运行它们。因此：

- `mvn test` / `mvn -Pno-containers test` 的行为和耗时不变；
- 评测通过 `tools/agent-platform-eval.sh` 手动触发；
- 评测不需要真实模型、Docker 或麦克风：A 用脚本化模型输出，B/C 用确定性工作流，D 用确定性语音/模型 fake 与内存 Speaking Session。

```bash
tools/agent-platform-eval.sh all
tools/agent-platform-eval.sh teacher-conversation
tools/agent-platform-eval.sh learning-review
tools/agent-platform-eval.sh targeted-practice
tools/agent-platform-eval.sh speaking-practice
```

每次运行会打印 Markdown 报告，并写入 `backend/target/agent-eval/<gate>.md`。任一阈值失败时脚本以非零状态退出。

## 评测集与阈值

| Gate | 入口 | 标注集 | 通过条件 |
| --- | --- | --- | --- |
| A Teacher Conversation | `TeacherConversationGateEvaluation` | `teacher-conversation-cases.json`，8 个回答用例 | grounded-rate 100%；无依据断言 0；工具选择 100%；接受/拒绝判断 100% |
| B Learning Review | `LearningReviewGateEvaluation` | `learning-review-cases.json`，6 段练习历史 | precision 100%；recall 100%；建议可追溯 100%；状态判断 100%；Level 趋势 100%；依据材料覆盖 100% |
| C Targeted Practice | `TargetedPracticeGateEvaluation` | `targeted-practice-cases.json`，5 个练前/练后案例 | 趋势判断 100%；改善案例下降率 100%；改善案例平均下降 > 0 |
| D Speaking Practice | `SpeakingPracticeGateEvaluation` | 确定性 fake + 20 个语音回合延迟分布 | p95 ≤ 2500 ms；超预算可识别；barge-in 可取消；降级覆盖 4/4 |

标注集位于 [`backend/src/test/resources/agent-eval/`](../backend/src/test/resources/agent-eval/)。每个用例的期望值由人工按产品规则写出，不读取实现内部状态。

## 各 gate 的方法

### A. Teacher Conversation：grounded-rate 与零无依据断言

每个用例声明：

- 学习者问题；
- 本轮可见的 Grounding Material / User Document 依据；
- 模型是否应调用工具（`lookup_word`、`retrieve_grounding_material`）；
- 模型回答与人工标注的 `expectedGrounded`、`expectedAccepted`。

评测把脚本化模型决策送入真实的 `TutorAgentCore`，再由真实的 `ConversationCitations` 规则判断：

- 回答是否引用了可用的 `[依据N]`；
- 是否出现不存在的引用编号；
- 有可用依据却完全不引用时是否被拒绝；
- 没有依据时是否明确拒绝而不是编造。

`grounded-rate` 的分母是人工标为「应当有依据」的用例，分子是实际被判为 grounded 的用例；`unsupported-assertions` 统计「标注为无依据但实际被接受」的回答，必须为 0。

**局限：** 自动规则检查引用编号与引用存在性，不做断言级语义判断；句子级「每条事实都有引用」由人工标注集保证。真实模型的回答质量需要另做人工抽检。

### B. Learning Review：诊断 precision/recall 与建议可追溯

每段标注历史包含若干 Practice Attempt、每篇的 Learning Issue 类别/严重度，以及人工认为「值得进入 Study Plan」的类别/严重度集合。评测运行真实的 `LearningReviewWorkflow`：

- `precision`：Study Plan 里有多少项命中标注；
- `recall`：标注项有多少进入 Study Plan；
- `recommendation-traceability`：每条建议的 evidence 是否能按 id、Practice Attempt、原句、建议句、讲解逐项对应回标注历史；
- `status-accuracy`：数据不足时是否明确返回 `INSUFFICIENT_DATA`；
- `level-trend-accuracy`：Estimated Level 趋势是否与标注一致；
- `grounding-material-coverage`：每条建议是否都带 Grounding Material。

标注集覆盖：反复问题 + 一次性噪声、同类别不同严重度、练习次数不足、同一篇里重复出现但只算一次、只有一次性问题、Level 趋势。

### C. Targeted Practice：针对性问题练前/练后下降

每个案例给出练习历史、目标类别/严重度、练后反馈中的 Learning Issue，以及人工期望的 `beforeCount`、`afterCount`、`change`、`trend`。评测运行真实链路：

`LearningReviewWorkflow` → `TargetedPracticeWorkflow.generate` → `TargetedIssueCounter.count` → `TargetedPracticeComparison.between`。

改善案例必须 `IMPROVED` 且 `change > 0`；同时保留 `UNCHANGED` 与 `WORSENED` 控制案例，防止评测把「任何变化」都误报成进步。`out-of-scope-severity-is-not-counted` 验证 MAJOR→MINOR 不计入同一目标簇。

### D. Speaking Practice：延迟 p95、barge-in 与降级覆盖

评测直接驱动真实的 `SpeakingPracticeService`，使用确定性的 `SpeechModel` / `TutorModelClient` fake 和内存 `SpeakingSessionRepository`：

- 20 个语音回合，给合成器注入 120–1100 ms 的延迟分布，读取 `SpeakingLatencyStats`；
- 单个 2600 ms 回合必须让 `withinBudget` 变为 false；
- barge-in 在合成后、落库前取消：回合不落库、不进入延迟样本；
- 降级覆盖 4 条路径：转写失败、转写超时、合成失败、失败后在同一会话改用文字。

WebSocket 传输、`interrupted` 消息和真实服务器行为由常规测试 `SpeechChannelContractTest` 覆盖；本 gate 关注服务层的延迟、取消和降级契约。

## 最近一次结果

环境：2026-11-26，Java 25，Mock 模式，无真实模型、无数据库、无 Docker。

命令：`tools/agent-platform-eval.sh all`，退出码 0。

| Gate | 关键指标 | 观测值 | 阈值 |
| --- | --- | --- | --- |
| A | grounded-rate | 100.0% | 100% |
| A | unsupported-assertions | 0 | 0 |
| A | tool-use-accuracy | 100.0% | 100% |
| B | precision | 100.0% | 100% |
| B | recall | 100.0% | 100% |
| B | recommendation-traceability | 100.0% | 100% |
| C | targeted-issue-decrease | 100.0% | 100% |
| C | average-change-on-improvement-cases | 1.33 | > 0 |
| D | p95-latency | 1040 ms | ≤ 2500 ms |
| D | barge-in | 通过 | 必检 |
| D | degradation-coverage | 4/4 | 4/4 |

完整的按用例结果在 `backend/target/agent-eval/*.md`，也可重新运行脚本复现。

## 如何扩充评测集

- A：在 `teacher-conversation-cases.json` 增加回答；`expectedGrounded` / `expectedAccepted` 必须按产品规则人工判断，不能从实现结果反推。
- B：在 `learning-review-cases.json` 增加历史与人工标注的 `expectedPatterns`。新增类别或调整 `IssueCategory` 后应补充用例。
- C：在 `targeted-practice-cases.json` 增加练前/练后案例；至少保留一个 `UNCHANGED` 或 `WORSENED` 控制案例。
- D：调整延迟分布时保留至少 20 个样本；新增降级路径时同步更新 `degradation-coverage` 的分母。

评测集变化后必须重跑 `tools/agent-platform-eval.sh all` 并把结果记入本文件的「最近一次结果」。

## 与其他评测的关系

- [`docs/retrieval-evaluation.md`](retrieval-evaluation.md) 评估真实 embedding 下的检索质量，需要供应商与 pgvector；本文件评估 agent 平台的四个产品 gate，使用确定性 fake。
- 常规集成测试继续验证 HTTP/SSE/WebSocket 契约与落库/回放；评测不替代它们，只回答 gate 是否达标。
- 评测不接入 Maven 默认测试、前端构建或 CI。
