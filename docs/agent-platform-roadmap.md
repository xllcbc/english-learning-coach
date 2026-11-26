# Agent 平台路线图（6–8 周）

本文件记录 WriteLab 引入 Teacher Conversation、Learning Review、Targeted Practice、Speaking Practice 四个场景的实施接力。架构决策见 `docs/adr/0008`–`0012`，术语见 `CONTEXT.md`。

## 目标与范围

- **做**：A Teacher Conversation、B Learning Review、C Targeted Practice、D Speaking Practice。
- **不做**：E（间隔重复复习）。
- **架构**：混合——批改/B/C 走工作流；A/D 走手写的有界 agent 循环；共享工具注册表；回合制核心 + 通道适配器（ADR-0008、0009）。
- **过硬定义（统一 DoD）**：每条功能同时具备 ADR + 领域术语 + 确定性 fake + 集成测试（Testcontainers）+ 一个可量化评测 + 故障降级行为。
- **投递方向**：后端工程 + AI 应用。

## 依赖与并行

```
Track 1（串行）
  阶段 0 地基 → A → B → C
Track 2（并行，从第 1–2 周开始）
  阶段 D 语音基础设施 →（第 6 周）接入同一个脑
```

两轨唯一共享点：`TutorAgentCore`（回合制核心）与工具注册表。阶段 0 先把它定下来，Track 2 才能安全并行。

## 工具注册表（共享契约）

| 工具 | 可用场景 |
| --- | --- |
| `lookup_word` | agent(A/D) + 工作流(批改，现有) |
| `retrieve_grounding_material` | 工作流(B/C/讲解) + agent(A) |
| `search_user_documents` | 两者 |
| `query_learning_progress` | agent(A) + 工作流(B) |
| `generate_practice_prompt` | 工作流(C) |
| `save_vocabulary` | agent(A/D)（用户要求时） |

## 阶段计划

### 阶段 0：地基（第 1 周）

- 抽出一个共享工具注册表（现有 `LookupWordTool` 等改造成可注册的工具）。
- 定义 `TutorAgentCore` 回合制接口、步数预算、确定性 fake（无模型也能跑通循环）。
- 落库：`teacher_conversation` / `teacher_conversation_turn` / `agent_step`（ADR-0010）。
- 把工作流编排从现有 service 中显式化（批改/B/C 共用同一套“步骤”写法）。

**DoD**：一个确定性 fake 的回合核心能跑通「输入 Turn → 多步 Agent Step → 输出 Turn」，并有 Testcontainers 集成测试。

### 阶段 A：Teacher Conversation（第 2–3 周）

- 锚定单份 Practice Attempt、SSE 流式、可回放（复用 `ExplanationService` 的流式/回放模式）。
- 工具子集：`lookup_word` / `retrieve_grounding_material` / `search_user_documents` / `query_learning_progress`。
- 前端：`FeedbackView` 内加对话面板。

**Gate**：grounded-rate（每条规则/事实有引用或词典来源）、工具使用恰当（该查才查）、评测集零无依据断言。

### 阶段 B：Learning Review（第 3–4 周）

- 工作流：读取近期 Practice Attempt 与 Learning Issue → 聚类反复出现的问题 → 为每类检索依据材料 → 合成优先级 Study Plan。
- 每条建议可追溯到具体 Learning Issue。
- 前端：学习复盘页/面板。

**Gate**：诊断 precision/recall（小规模人工标注）、建议可追溯率 100%。

### 阶段 C：Targeted Practice（第 5 周）

- 工作流：由 Study Plan 生成聚焦薄弱项的任务 → 学习者写作 → 走既有批改 → 对比练前/练后。
- 前端：从 Study Plan 一键发起针对性练习，练后展示增量。

**Gate**：针对性问题在练前/练后的下降可量化。

### 阶段 D：Speaking Practice（第 1–2 周起并行，第 6 周接脑）

- `SpeechModel` seam + 确定性 mock（固定转写、固定音频字节）。
- WebSocket 通道：`MediaRecorder` + 静音切句 → Whisper STT → 核心 → OpenAI TTS 流式回放。
- barge-in（取消播放）；STT/TTS 失败降级到文本（ADR-0011）。
- Speaking Session 独立聚合根落库（ADR-0012）。

**Gate**：延迟 p95 ≤ ~2.5s、barge-in 可用、降级路径有测试覆盖。口语数据接入 Learning Review 列为后续增量。

### 收口（第 7–8 周）

- 四场景评测集回归、文档、面试讲解稿：评测入口见 `tools/agent-platform-eval.sh`，方法与结果见
  [`docs/agent-platform-evaluation.md`](agent-platform-evaluation.md)，面试讲解见
  [`docs/interview-agent-platform.md`](interview-agent-platform.md)。
- 补监控/延迟指标；把「工作流 vs agent」的边界写成可讲的取舍。

## 风险与降级

| 风险 | 应对 |
| --- | --- |
| 4 场景摊薄深度 | 每阶段达到 Gate 才进下一阶段；宁可延后也不并行铺半成品 |
| D 语音供应商延迟/失败 | 降级到文本通道，语音不阻塞学习（ADR-0011） |
| B 与 A 职责重叠 | A 锚定单份 Practice Attempt，跨练习归 B（CONTEXT/ADR-0012） |
| agent 非确定性 | 步数预算 + 确定性 fake + 评测集；只让真正开放的对话承担非确定性 |
| C 闭环范围膨胀 | Learning Review 第一版只读写作；口语接入列为增量 |
