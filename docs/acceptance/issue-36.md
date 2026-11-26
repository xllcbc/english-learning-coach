# Issue #36 验收记录：四道 gate 的评测回归与收口

日期：2026-11-26。父 spec：#25。分支：`agent/agent-platform-wave4/issue-36`。

## 验收对照

| 验收标准 | 实现 | 证据 |
| --- | --- | --- |
| 四个场景各有一个可重复运行的评测入口 | `tools/agent-platform-eval.sh` 提供 `teacher-conversation`、`learning-review`、`targeted-practice`、`speaking-practice` 与 `all`；对应四个 `*GateEvaluation` 测试类 | `tools/agent-platform-eval.sh`、`backend/src/test/java/com/writelab/**/*GateEvaluation.java` |
| 评测结果可对照阈值判断通过/失败 | 每个 gate 用 `GateReport` 记录指标、观测值、阈值与用例结果；任一阈值失败时抛出 `AssertionError`，脚本非零退出 | `GateReport.assertPassed()`；`backend/target/agent-eval/*.md` |
| 文档记录评测方法与结果；面试讲解稿覆盖「工作流 vs agent」取舍 | 新增评测方法/结果文档与面试讲解稿；路线图收口段落链接到两份文档 | `docs/agent-platform-evaluation.md`、`docs/interview-agent-platform.md` |
| 不破坏既有 CI 稳定性（重评测按需运行） | 评测类标注 `@Tag("evaluation")`，默认 Surefire 排除；`agent-eval` profile 只在手动触发时运行。`mvn -Pno-containers test` 与前端测试保持通过 | `backend/pom.xml`；下表的最终测试结果 |

## 评测入口

```bash
tools/agent-platform-eval.sh all
tools/agent-platform-eval.sh teacher-conversation
tools/agent-platform-eval.sh learning-review
tools/agent-platform-eval.sh targeted-practice
tools/agent-platform-eval.sh speaking-practice
```

报告写入 `backend/target/agent-eval/<gate>.md`。脚本会自动寻找本地 Maven 仓库，也可用 `WRITELAB_MAVEN_REPO_LOCAL` 指定。

## 本次评测结果

环境：2026-11-26，Java 25，Mock/确定性 fake，无真实模型、无数据库、无 Docker。命令 `tools/agent-platform-eval.sh all` 退出码 0。

| Gate | 指标 | 观测值 | 阈值 |
| --- | --- | --- | --- |
| A Teacher Conversation | grounded-rate | 100.0% | 100% |
| A | unsupported-assertions | 0 | 0 |
| A | tool-use-accuracy | 100.0% | 100% |
| A | accept/reject-accuracy | 100.0% | 100% |
| B Learning Review | precision | 100.0% | 100% |
| B | recall | 100.0% | 100% |
| B | recommendation-traceability | 100.0% | 100% |
| B | status-accuracy | 100.0% | 100% |
| B | level-trend-accuracy | 100.0% | 100% |
| B | grounding-material-coverage | 100.0% | 100% |
| C Targeted Practice | trend-accuracy | 100.0% | 100% |
| C | targeted-issue-decrease | 100.0% | 100% |
| C | average-change-on-improvement-cases | 1.33 | > 0 |
| D Speaking Practice | p95-latency | 1040 ms | ≤ 2500 ms |
| D | over-budget-detection | 通过 | 必检 |
| D | barge-in | 通过 | 必检 |
| D | degradation-coverage | 4/4 | 4/4 |

## 实现说明

- A：8 个标注回答；脚本化模型输出经真实 `TutorAgentCore` 与 `ConversationCitations` 规则判断 groundedness、引用合法性与工具选择。
- B：6 段标注练习历史；真实 `LearningReviewWorkflow` 计算 precision/recall，并逐条核对 recommendation evidence 是否可追溯。
- C：5 个练前/练后案例；真实 `LearningReviewWorkflow` → `TargetedPracticeWorkflow` → `TargetedIssueCounter` → `TargetedPracticeComparison`。新增 `TargetedIssueCounter` 让 store 与评测共用同一条「类别 + 严重度」计数规则。
- D：20 个语音回合测 p95；2600 ms 回合验证超预算识别；服务层 barge-in 取消；4 条降级路径。WebSocket 传输由既有 `SpeechChannelContractTest` 覆盖。

## 最终自动化测试

```bash
cd backend
env -u WRITELAB_MODEL_API_KEY -u WRITELAB_MODEL_BASE_URL \
    -u WRITELAB_MODEL_NAME -u WRITELAB_MODEL_EMBEDDING_NAME \
    mvn -q -Pno-containers -Dmaven.repo.local=/home/xllcbc/.cache/sandcastle/m2 test

cd ../frontend
npm test
```

| 项目 | 结果 |
| --- | --- |
| 后端 `-Pno-containers` | 通过，0 failures / 0 errors；默认运行未执行 `*GateEvaluation` |
| 前端 Vitest | 47 tests，0 failures |

容器测试（`*IntegrationTest` / `*RetrieverTest`）按仓库约定需要 Docker；本实现环境没有 Docker，因此未在本机执行。评测 gate 不依赖容器。

## 已知限制

- 标注集很小，只作为回归信号，不是统计基准；类别或语料变化后应补充用例。
- A 的自动规则检查引用编号与引用存在性，句子级 groundedness 由人工标注保证；真实模型输出仍需人工抽检。
- D 的评测在服务层测量，WebSocket 传输与浏览器 barge-in 行为由 `SpeechChannelContractTest` 与前端测试分别覆盖。
- 评测不接入默认 CI，需要手动重跑；这是刻意保留的「按需回归」。
