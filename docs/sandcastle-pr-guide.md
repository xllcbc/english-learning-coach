# 给 `mattpocock/sandcastle` 提 PR 的合规指南

> 读者：想给上游 Sandcastle 提 PR、但不想因为"形式不对"被直接关掉的人。
> 依据：`github.com/mattpocock/sandcastle` 的 `main` 分支，2026-11-17。
> 时效：上游迭代很快，本文的**规则**比**行号**可靠；行号对不上时按文件名找。
> 注意：本文讲的是**上游仓库**的规矩，不是本仓库（WriteLab）的 `AGENTS.md`。

---

## 0. 结论先行

这个仓库**没有 `CONTRIBUTING.md`**。规矩在四处：

| 文件 | 作用 | 必读程度 |
| --- | --- | --- |
| `AGENTS.md`（与 `CLAUDE.md` 内容相同） | 总要求：changeset、docs、验证流程 | 必读 |
| `.sandcastle/CODING_STANDARDS.md` | 真正的工程标准：测试位置、lint ratchet、Effect、路径、JSDoc | 必读 |
| `docs/agents/adding-an-agent-provider.md` | 改 agent provider 的专项清单 | 改 provider 时必读 |
| `docs/agents/issue-tracker.md` / `triage.md` / `releasing.md` | PR 如何被 triage、标签含义、发布流程 | 提 PR 前读 |

一句话版本：

> **用户可见改动要有 changeset；新行为要在 `tests/` 里用公共 API 证明；文档、GLOSSARY、ADR 的说法要跟着改；CI 全绿不等于可以合。**

---

## 1. 先理解 PR 的"身份"

`docs/agents/issue-tracker.md` 原文：

> **PRs as a request surface: yes.** External PRs are treated as feature requests.

外部 PR 会被当成**需求**，走和 issue 一样的 triage 流程，而不是直接进入 review/merge 队列。标签含义：

| 标签 | 含义 |
| --- | --- |
| `needs-triage` | 维护者还没评估 |
| `needs-info` | 等报告者补信息 |
| `ready-for-agent` | 规格完整，可以交给 AFK agent 实现 |
| `ready-for-human` | 需要人写 |
| `wontfix` | 不做 |
| `agent:implement` | PR 上触发 agent 处理 review 反馈 |
| `agent:in-progress` / `agent:blocked` | agent 正在处理 / 被阻塞 |

现实数据（2026-11-17）：

- 最近 500 个已合并 PR：作者 `mattpocock` 467 个，release 机器人 31 个，外部贡献者 **2 个**。
- 97 个 open issue 里只有 39 个有标签。
- 外部贡献者 DWMerit 在 11 月 7 日提的 6 个修复 PR，10 天后仍全部 open。

**结论**：不要默认"代码好就会被合"。形式齐全、范围小、证据充分，才有机会进入队列。

---

## 2. 硬门槛：CI 不过就是红叉

CI 的定义在 `.github/workflows/ci.yml`。Ubuntu job 的顺序就是本地自检顺序：

| 步骤 | 命令 | 检查什么 | 怎么修 |
| --- | --- | --- | --- |
| 1 | `npm run format:check` | Biome 格式 | `npm run format` |
| 2 | `npm run typecheck` | TypeScript 7，`src/` 和 `tests/` | 改类型，不要 `@ts-ignore` |
| 3 | `npm run build` | tsup + 模板复制 + 公共类型 effect-free | 看构建错误 |
| 4 | `npm run api:report -- --check` | 公共 API 报告与 `dist/*.d.ts` 一致 | `npm run api:report` 重写 |
| 5 | `npm run lint` | oxlint + Effect LS + dependency-cruiser + lint/test ratchet | 见第 2.2 节 |
| 6 | `npx vitest run --project tooling --project legacy` | tooling 与 legacy 测试 | 跑测试 |
| 7 | `npm run coverage:floor` | `tests/` 对 `src/` 的行覆盖率 | 新 `src/` 代码补 `tests/` 用例 |

另外两个 job：

- **Windows**：`tests/` e2e（含 `no-sandbox.windows.test.ts`）+ legacy 的 `src/sandboxes/no-sandbox.test.ts`。
- **macOS**：`npm run build` + `npx vitest run --project e2e`（`seatbelt()` 只能在这里验）。

合并走 **merge queue**，进队列后会再跑一遍。

### 2.1 本地 pre-commit

`.husky/pre-commit`：

```sh
node scripts/check-no-partially-staged.mjs
npx lint-staged --no-stash
```

- 不允许"部分暂存"（同一个文件既有 staged 又有 unstaged 改动）。
- lint-staged 只跑 Biome format，不跑 lint/测试。

### 2.2 lint ratchet 是最容易踩的

`npm run lint` 不只是 oxlint：

- **oxlint**：除 `restriction` / `nursery` 外全部当 error，type-aware。
- **Effect language service**：`tsconfig.json` 插件诊断。
- **dependency-cruiser**：`tests/` 只能用包名导入公共 API（`@ai-hero/sandcastle`、`@ai-hero/sandcastle/sandboxes/*`）和 `tests/` 内部文件，不能相对路径进 `src/`；`src/` 不能 import `tests/`。
- **lint baseline**（`scripts/lint-baseline.json`）：只能降不能升。新文件没有 baseline，相当于零容忍。
- **test ratchet**（`scripts/test-baseline.json`）：新测试文件必须在 `tests/`；`tests/` 里不能有 `as T` / `<T>x`（`as const` 可以）；不能删测试来降计数。
- **disable 注释**：`eslint-disable`、`oxlint-disable`、`@ts-ignore`、`@ts-expect-error`、`@effect-diagnostics` 都算 `ratchet/disable-comment`，白名单在 `scripts/lint-ratchet-config.ts`。

新 `src/` 文件的预算（没有 baseline，直接按上限算）：

- 单文件 300 行（含空行和注释）
- 单函数 50 行
- 单函数 20 条语句
- 圈复杂度 15
- 最多 10 个 import
- 不能 `async`（除非是 `scripts/lint-ratchet-config.ts` 里的 Effect 入口）
- 不能 `fs.*Sync` / `execSync`
- 不能 raw `git` spawn（走 `hostGit()`）

> 本地跑 `npm run lint` 前先 `npm run build`：`.sandcastle/` 和 `.factory/` 的脚本从 `dist` 取类型，不 build 会报假的 `no-unsafe-*`。

### 2.3 测试与覆盖率

`.sandcastle/CODING_STANDARDS.md` 的 "Where tests live" 是硬规则：

- 新测试放 `tests/`，只用公共 API，不能 mock 自己的模块。
- 测试只从系统边界 mock：外部 API、时间/随机、文件系统/数据库（不方便用真的时候）。
- 新 `src/` 代码必须在 `tests/` 有测试；旁边有个 legacy 测试不算。
- `coverage:floor` 的底线在 `scripts/coverage-baseline.json`：最多只能比基线低 0.1 个点，再低就失败。
- 要删 legacy 测试，先迁用例，再跑：

  ```sh
  npm run coverage:gate -- <file>
  ```

  把输出贴进 PR 正文。

### 2.4 公共 API

- `api-report/` 是每个 `exports` 入口的公共类型快照，`.github/CODEOWNERS` 把 `/api-report/` 指给 `@mattpocock`。
- 公共 API 一变，PR 里会出现 report diff，并自动请求 Matt review。
- 不要为了测试扩公共 API；测试够不到，说明代码要么是死的，要么缺一个真实的公共 seam。
- 公共属性/函数必须有 JSDoc。

---

## 3. 软门槛：CI 不拦，但 review 必查

### 3.1 Changeset（用户可见改动必须有）

位置：`.changeset/<唯一名字>.md`

```md
---
"@ai-hero/sandcastle": patch
---

Support OpenCode V2's `opencode session export` / `opencode session import` when capturing and resuming sessions. Thanks @yhsder.
```

规则：

- 包名用 `package.json#name`：`@ai-hero/sandcastle`。
- **bugfix → `patch`**；**新功能或破坏性改动 → `minor`**（项目在 1.0 之前）。
- 先看 `.changeset/` 里现有文件，别重复。
- 一个提交可以有多个 changeset，如果它改了多个用户可见行为。
- 文案写给用户看：改了什么、怎么变、要不要迁移；可以致谢报告者。

> **CI 不检查 changeset 是否存在**（只有 `release.yml` 用它）。所以"CI 绿"不代表合规；缺 changeset 会在 review 阶段被打回。

### 3.2 文档同步

`AGENTS.md` 原文：

> When changing public-facing behavior, check the user docs in `docs/guide/` to see if they need updating.

用户文档在 `docs/guide/`，由 `aihero.dev/sandcastle` 渲染。规则：

- 一个 `GLOSSARY.md` 主要术语一页；`docs/guide/glossary.md` 是手写的用户版术语表。
- 每个术语在最早提到它的 guide 页面解释一次，其他页面只链接。
- guide 里**禁止引用 ADR**（不写 `docs/adr/` 链接，也不写 "ADR 0018"）。
- 新页面要同时改三处：`docs/guide/<slug>.md`、`docs/guide/toc.json`、`README.md` 的 Documentation 列表。
- `tests/docs-guide.test.ts` 会检查：页面/toc/README 是否一致、首行是否 `# Title`、术语首次出现是否带链接、glossary 条目是否有 home、guide 里是否有 ADR 引用、相对链接是否有效。

哪些文档要跟着改：

| 改动 | 要改的文档 |
| --- | --- |
| 行为变化 | `docs/guide/` 对应页面 + `GLOSSARY.md`（如果术语含义变了） |
| 架构/决策变化 | `docs/adr/`：改现有 ADR 或新增 ADR；不要静默违背 |
| 新公共 option | `docs/guide/run.md`（Reference）+ 对应术语页 + JSDoc + `api-report/` |
| 新 agent provider | `docs/agents/adding-an-agent-provider.md` 的清单 + `docs/guide/agent-providers.md` |
| 纯文档 | 通常不需要 changeset（没有用户可见行为变化），但要过 `tests/docs-guide.test.ts` |

### 3.3 编码标准（会体现在 diff 里）

`.sandcastle/CODING_STANDARDS.md` 的重点：

- 能用 Effect 原语（`FileSystem` 等）就别用 Promise；Effect 不能泄漏到用户 API。
- Effect 闭包里禁止 `new Error()`，必须用 `errors.ts` 的 tagged error；失败通道不能是裸 `Error`。
- sandbox 路径一律 `posix.join`（Linux 路径）；host 路径用平台感知的 `join`。
- 比较路径前统一分隔符（`\` → `/`）。
- 不要 lazy `import()` Node 内置模块。
- 可选参数要极其谨慎；正确性优先于向后兼容。
- `exec` 的 `onLine` 必须实时回调，不能等命令结束再切分。
- sandbox provider 之间不共享 provider-specific 代码；只有纯工具函数可以共享。
- `sandcastle init` 的每个交互 prompt 必须有对应的非交互 flag。

### 3.4 PR 本身的形式

没有 PR 模板。按仓库习惯，标题用 conventional commit，正文至少包含：

```md
## 问题

OpenCode V2 删掉了顶层 `opencode export/import`，Sandcastle 0.14.0 仍在调用，
导致 session capture 在任务成功、commit 已产生之后抛
`SessionCaptureError: Unexpected positional argument`。

## 复现

- `@ai-hero/sandcastle` 0.14.0
- `opencode` 2.3.0
- `bwrap()` sandbox
- 开启 `captureSessions`（默认）

## 修复

改用 V2 的子命令：

- `opencode session export <id>`
- `opencode session import <file>`

（如保留 V1 兼容，写清检测/回退策略。）

## 验证

- [ ] `npm run format:check`
- [ ] `npm run typecheck`
- [ ] `npm run lint`
- [ ] `npm test`
- [ ] `npm run api:report -- --check`（如动公共 API）
- [ ] `npm run verify`（opencode × bwrap）
- 实测：`opencode session import` 保留原 session ID，`opencode run --session <id>` 可续聊。

## 兼容性 / 文档

- changeset：`.changeset/<name>.md`（patch / minor）
- 文档：`docs/guide/agent-sessions.md`、`GLOSSARY.md`、`docs/adr/0022-...md`

Closes #1509
```

建议：

- 一个 PR 只做一件事，别顺手重构。
- 先链接 issue（`Closes #1509` / `Refs #1509`），让 triage 有上下文。
- 把"为什么"和"验证证据"写进正文；维护者不需要从 diff 里猜。
- 如果是外部 PR，预期它可能被当成需求重新实现；保持 diff 小，降低被重写的成本。

---

## 4. 按改动类型的最小合规矩阵

| 改动类型 | changeset | 测试 | 文档 | api-report |
| --- | --- | --- | --- | --- |
| Bugfix，无公共 API 变化 | `patch` | `tests/` 回归用例 | 行为有文档就改 guide；决策变了就改 ADR | 不需要 |
| 新公共 API / 新 option | `minor` | `tests/` 行为用例 | 术语页 + Reference + JSDoc | 需要，`npm run api:report` |
| 新 agent provider | `minor` | `tests/` + init 相关测试 | `adding-an-agent-provider.md` 清单 + guide | 需要（新 factory/type 导出） |
| 纯文档 | 不需要 | `tests/docs-guide.test.ts` | 本身 | 不需要 |
| 只改 lint/测试基础设施 | 视情况 | 自身 | 一般不需要 | 不需要 |

### 4.1 提交前 12 项自检

- [ ] PR 只做一件事，标题是 conventional commit（如 `fix(opencode): ...`）
- [ ] 正文链接 issue，并写了复现步骤和验证证据
- [ ] 用户可见改动有 changeset，`patch` / `minor` 选对
- [ ] `.changeset/` 里没有重复条目
- [ ] 新测试在 `tests/`，只用公共 API，没有 `as T` / `<T>x`
- [ ] 没删测试；如删了 legacy 测试，贴了 `coverage:gate` 输出
- [ ] `docs/guide/` 对应页面已更新；术语首次出现带链接
- [ ] `GLOSSARY.md` / ADR 已同步（行为或决策变了）
- [ ] 没在 `docs/guide/` 里引用 ADR
- [ ] 没新增 disable 注释；没改 `scripts/lint-baseline.json`
- [ ] 本地跑过 `format:check` / `typecheck` / `build` / `lint` / `test`
- [ ] 公共 API 变了才跑 `api:report`；没变则确认没有意外改动公共类型

---

## 5. 案例：修 OpenCode V2 的 session capture

这是 #1509 的路径，也是写这份文档的起因。

### 5.1 事实

- `@ai-hero/sandcastle` 0.14.0 硬编码：
  - `opencode export <id> > <file>`
  - `opencode import <file>`
- OpenCode V2 改成：
  - `opencode session export <id>`
  - `opencode session import <file>`
- 实测（OpenCode 2.3.0）：
  - `opencode session import` **保留原 session ID**；
  - import 会把 session 重新挂到当前目录；
  - `--dangerously-skip-permissions` 仍被接受（帮助里不显示，但有隐藏别名）。
- 结论：真正坏的只有 export/import 两条命令；resume/fork 的底层机制仍可用。

### 5.2 逐文件清单

| 文件 | 改什么 |
| --- | --- |
| `src/AgentProvider.ts` | `opencode()` 的 `sandboxExport` / `sandboxImport` 命令 |
| `src/SessionStore.ts` | 第 542 行附近的注释还在说 `opencode export/import` |
| `docs/adr/0022-opencode-resume-via-export-import.md` | 补 V2 的实测与决策（或新增 ADR） |
| `docs/guide/agent-sessions.md` | 第 22 行附近的存储说明；第 60 行附近的 resume/fork 表 |
| `GLOSSARY.md` | 第 193 行附近 agent session 定义里的 `opencode export` |
| `.changeset/<name>.md` | 兼容 V1 → `patch`；只支持 V2（破坏 V1）→ `minor` |
| `tests/helpers/fakeOpencode.ts` | 假 CLI 要支持 V2 的 `session export` / `session import` |
| `tests/sessionSandboxDirs.test.ts` | 断言了 sandbox 命令字符串，必须同步 |
| `tests/bwrap-run.test.ts` | "captures an opencode session exported inside the sandbox" 用例扩到 V2 |
| `tests/seatbelt-run.test.ts` | 同上 |
| `tests/resume-session.test.ts` | 如涉及 resume 预检，补/改用例 |
| `api-report/index.api.md` | 只有新增公共 option/type 时才需要 |

### 5.3 设计选择要先想清楚

issue 里建议"检测或显式选择 V1/V2"，但上游会审设计：

1. **只改 V2 命令**：最简单，但破坏 V1；pre-1.0 可以用 `minor` changeset，前提是文档写清最低支持版本。
2. **shell 回退**：`opencode session export ... || opencode export ...`。兼容两者，但错误语义容易混（真正的 import 失败会再触发一次 V1 尝试，报错可能误导）。
3. **显式 option**（如 `cliVersion?: "v1" | "v2"`）：清楚，但扩公共 API，要改 `api-report/`、写 JSDoc、加文档，review 面更大。
4. **运行时探测**：在 `sessionStorage` 层探测一次并缓存。公共 API 不变，但多一次进程调用和状态管理，测试要覆盖。

没有"明显正确"的答案；把自己的取舍和证据写进 PR，比直接丢代码更重要。

### 5.4 提交前命令

```sh
npm ci
npm run build
npm run format:check
npm run typecheck
npm run lint
npm test
# 只有动了公共 API 才需要：
npm run api:report -- --check
# 按 AGENTS.md，在 opencode × bwrap 组合上做一次端到端验证：
npm run verify
```

`npm run test:local` 不用跑：它只覆盖真 Docker/Podman，CI 也不跑，发布前才需要。

---

## 6. 常见会被打回的原因

1. **缺 changeset**：CI 不报，但 review 会问。
2. **新测试放在 `src/`**：test ratchet 直接失败。`docs/agents/adding-an-agent-provider.md` 里"Tests in `src/AgentProvider.test.ts`"与 `CODING_STANDARDS.md` 的"新测试放 `tests/`"**冲突**，以 `CODING_STANDARDS.md` 为准。
3. **`tests/` 里用了 `as T`**：`ratchet/test-type-assertion` 失败。
4. **为了测试扩公共 API**：违反 CODING_STANDARDS。
5. **改了行为但不改 guide / ADR / GLOSSARY**：文档测试或 review 会拦。
6. **在 `docs/guide/` 里引用 ADR**：`tests/docs-guide.test.ts` 专门断言。
7. **手动改 `scripts/lint-baseline.json`**：会跟 `lint-shrink` 每夜 PR 冲突；修 bug 的 PR 不要动它。
8. **新增 disable 注释**：`ratchet/disable-comment`。
9. **没 build 就跑 lint**：假的 `no-unsafe-*`，浪费一轮。
10. **PR 太大 / 顺手重构**：外部 PR 本来就是"需求"，大 diff 更容易被关掉或重写。

---

## 7. 怎么跟上上游变化

规则文件可能变，提 PR 前重跑：

```sh
# 当前 npm 版本
npm view @ai-hero/sandcastle version

# 上游规矩
gh api repos/mattpocock/sandcastle/contents/AGENTS.md --jq '.content' | base64 -d
gh api repos/mattpocock/sandcastle/contents/.sandcastle/CODING_STANDARDS.md --jq '.content' | base64 -d
gh api repos/mattpocock/sandcastle/contents/docs/agents/adding-an-agent-provider.md --jq '.content' | base64 -d

# 目标 issue / 相关 PR
gh issue view 1509 --repo mattpocock/sandcastle --comments
gh pr list --repo mattpocock/sandcastle --state all --search opencode

# CI 实际跑什么
gh api repos/mattpocock/sandcastle/contents/.github/workflows/ci.yml --jq '.content' | base64 -d
```

---

## 8. 参考链接

- 仓库：<https://github.com/mattpocock/sandcastle>
- 本文相关 issue：<https://github.com/mattpocock/sandcastle/issues/1509>
- `AGENTS.md`：<https://github.com/mattpocock/sandcastle/blob/main/AGENTS.md>
- `CODING_STANDARDS.md`：<https://github.com/mattpocock/sandcastle/blob/main/.sandcastle/CODING_STANDARDS.md>
- `adding-an-agent-provider.md`：<https://github.com/mattpocock/sandcastle/blob/main/docs/agents/adding-an-agent-provider.md>
- ADR 0022：<https://github.com/mattpocock/sandcastle/blob/main/docs/adr/0022-opencode-resume-via-export-import.md>
- 本仓库的 Sandcastle 上手笔记：`docs/sandcastle-guide.md`
