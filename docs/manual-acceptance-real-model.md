# 真实模型手动验收清单（Issue #7）

自动化测试只使用确定性适配器（MockFeedbackModel / MockExplanationModel / FakeFeedbackModelClient），
不依赖真实供应商。真实模型路径需要按下面清单手动验收一次。

## 前置配置

通过环境变量配置模型供应商（OpenAI 兼容接口）：

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `WRITELAB_MODEL_BASE_URL` | 供应商 Base URL | `https://api.openai.com` |
| `WRITELAB_MODEL_API_KEY` | API Key；**留空即进入 Mock 模式** | 空 |
| `WRITELAB_MODEL_NAME` | 模型名称 | `gpt-4o-mini` |

启动后端（`backend/`）与前端（`frontend/`），前端通过 `VITE_API_BASE_URL` 指向后端。

## 验收步骤

1. **Mock 模式（不设 API Key）**
   - 页面顶部模式标识显示“Mock 模式”，并出现 Mock 提示条。
   - `POST /api/practices` 响应中 `modelMode=Mock`、`mockMode=true`。
   - 提交一段 200–500 词的英文写作，能返回结构化反馈与教师讲解。

2. **真实模式（设置 API Key 后重启）**
   - 页面顶部模式标识显示“真实模型”，无 Mock 提示条。
   - `POST /api/practices` 响应中 `modelMode=Real`、`mockMode=false`。
   - 结构化反馈字段完整：correction、naturalRevision、estimatedLevel、scores、strengths、
     learningIssues、vocabularySuggestions。

3. **工具调用上限**
   - 在 Tool Trace 中确认 `lookup_word` 调用不超过 2 次；超过的第 3 次应为 `BLOCKED`（若触发）。

4. **独立流式教师讲解**
   - `GET /api/practices/{id}/explanation` 逐个返回 `delta` 事件，最终收到 `complete`；刷新页面后回放同一讲解。

5. **校验与重试（可通过不稳定的模型观察）**
   - 模型偶发返回不符合 Schema 的内容时，后端自动重试，最多共 3 次（1 次初始 + 2 次重试），
     每次将具体错误回传给模型。
   - 三次仍失败时，响应为稳定错误码 `MODEL_GENERATION_FAILED`（HTTP 502）与中文提示
     “模型多次返回的内容不符合要求，请稍后重试。”，日志中可见具体校验错误。
   - 模型调用本身失败（如 API Key 错误、网络不通）时不重试，返回稳定错误码
     `MODEL_CALL_FAILED`（HTTP 502）与中文提示“模型服务暂时不可用，请稍后重试。”。

6. **密钥与 Prompt 安全**
   - 数据库 `practice_attempt` 表中不存在 API Key 或完整 Prompt（仅保存写作原文、结构化反馈、
     教师讲解、工具调用记录）。
   - 在普通日志（INFO/WARN）中检索 API Key 与完整 Prompt，均不应出现；`org.springframework.ai`
     与 `com.openai` 日志级别已设为 WARN。

## 结果记录

| 步骤 | 结果 | 备注 |
| --- | --- | --- |
| 1 Mock 模式 | ☐ 通过 ☐ 不通过 | |
| 2 真实模式 | ☐ 通过 ☐ 不通过 | |
| 3 工具调用上限 | ☐ 通过 ☐ 不通过 | |
| 4 流式讲解 | ☐ 通过 ☐ 不通过 | |
| 5 校验与重试 | ☐ 通过 ☐ 不通过 | |
| 6 密钥与 Prompt 安全 | ☐ 通过 ☐ 不通过 | |
