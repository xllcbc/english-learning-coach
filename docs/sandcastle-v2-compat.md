# Sandcastle × OpenCode v2 本地兼容方案（共识记录）

> 状态：**适配层、指南、§9 harness、smoke #18 与首张真活票 #16 全部完成（2026-11-18）**；RAG 第一版已收尾，下一张是 #8。
> 日期：2026-11-17（2026-11-18 补充实测结果；2026-11-19 补充 #16 与 RAG 收尾）
> 适用：`@ai-hero/sandcastle@0.14.0` + `opencode 2.3.0`，WSL2 + `bwrap()`。
> 上游：`mattpocock/sandcastle#1509` 仍 open 且无人处理；本方案**不提交上游 PR**，只做本地适配。

## 0. 一句话

不改 `node_modules`、不提 PR：在 `.sandcastle/` 里用 `opencodeV2()` 包住上游 `opencode()`，只替换 session 导出/导入两条命令，恢复 `captureSessions`、`result.resume()` 和 `result.fork()`。

## 1. 术语

| 词 | 含义 |
| --- | --- |
| **session** | opencode 存在 SQLite 里的对话状态；`opencode session export` 会把它导出成一份 JSON |
| **session capture** | 沙箱里的 agent 跑完后，把 session JSON 导出并复制到 host 的存档目录 |
| **resume** | 把 host 上的 session JSON 导入新沙箱，并用同一个 session ID 继续对话 |
| **fork** | 在 resume 的基础上加 `--fork`，从父 session 分叉出一个新 ID，父 session 保持不动 |

这四个词已补进 `docs/sandcastle-guide.md` §1.2 的术语表（2026-11-18）。

## 2. 问题与证据

### 2.1 坏在哪

`@ai-hero/sandcastle@0.14.0` 的 opencode provider 把命令硬编码成了 v1 语法：

```text
capture: opencode export <id> > <file>
resume:  opencode import <file>
```

opencode v2 删掉了顶层 `export` / `import`，只剩子命令。实际报错：

```text
SessionCaptureError: Session capture failed: `opencode export 'ses_...' > '/tmp/ses_....json'` exited with code 1:
ERROR Unexpected positional argument: "ses_..."
```

时序很坑：任务已经成功、commit 已经产生，才在收尾阶段抛这个错。

### 2.2 当前绕过方式的代价

改动前玩具仓库的 `main.mts` 写的是：

```ts
opencode("opencode-go/deepseek-v4.1-flash", { captureSessions: false })
```

这样能跑完，但 `result.resume()` 和 `result.fork()` 都不能用，等于阉割。

### 2.3 已实测的 v2 事实

| 事实 | 证据 |
| --- | --- |
| v2 的正确命令是 `opencode session export/import` | `opencode session export --help` / `import --help` |
| `session import` **保留原 session ID** | 在隔离的 `XDG_DATA_HOME` 里导出再导入，输出 `Imported session: ses_e1c4...`，ID 不变 |
| `session import` 会把 session 绑定到当前目录 | 不传 `--directory`、在目标目录里执行，`session list` 显示的 `directory` 就是该目录 |
| `--dangerously-skip-permissions` 在 v2.3.0 仍被接受 | 二进制里有隐藏别名；传入后不会报未知参数 |
| `--auto` 不是本轮必须改的 | 同上；权限参数保持不动 |
| 沙箱里的 export/import 必须加 `--standalone` | bwrap 与宿主共享 network namespace，宿主 opencode 服务占用 49374；实测不加会报 `Managed service port 49374 ... already in use`，加上后 capture/resume/fork 全通 |

所以真正要改的只有 export/import 两条命令；权限、流解析、`run` 命令都不用动。唯一额外的坑是沙箱里的 export/import 必须加 `--standalone`（见 2.3）。

## 3. 决策

| # | 决定 | 放弃的选项 | 理由 |
| --- | --- | --- | --- |
| D1 | 用 `.sandcastle/opencode-v2.ts` 本地适配层 | `patch-package`、本地 fork 依赖、上游 PR | 只覆盖两条命令，公开 API 足够；升级/重装不会打回原形；无需维护 fork |
| D2 | 只支持 opencode v2，沙箱内探测版本 | `session export \|\| export` 回退 | 宿主就是 2.3.0；回退会把真实失败变成难懂的错误 |
| D3 | capture 失败直接抛错 | 警告后继续 | 修好后若还失败，说明适配层或 CLI 又变了；静默降级会让人误以为能 resume |
| D4 | 导出函数名 `opencodeV2()`，不做 drop-in | 也叫 `opencode`、独立 workspace 包 | 显式名字能看出这里绕过了上游；30 行不值得做包 |
| D5 | 验收 capture + resume + fork，只在 bwrap | 只验 capture/resume；加 `interactive()` | fork 是独立代码路径（`--fork`）；`interactive()` 需要 TTY，留到后面 |
| D6 | 先在玩具仓库验证，再复制进本仓库 | 直接在本仓库调 | 玩具仓库是学习沙盘，改坏不心疼 |
| D7 | 提交 `.sandcastle/` 脚本、prompt、`package.json`、`tsconfig.json`、`package-lock.json` 和根 `.gitignore`，忽略凭据与产物 | 全部本地保留 / 全部提交 | 换机器不用重建；`.env`、`worktrees/`、`logs/`、`node_modules/` 不能进 git |
| D8 | §9 第一票用临时 smoke issue | 直接开 #8 或 #16 | 先隔离“Java/bwrap/Maven/TDD 流水线”这一个风险，再上真活 |
| D9 | 纯文档/工具 AC 用 `docs:` 提交，不参与红绿配对 | 强行写假测试 / 把文档硬塞进 green | #16 这类票有文档/评测 AC；假测试和混提交都会破坏门禁 |
| D10 | 纯运行时配置默认值用 `config:` 提交，不参与红绿配对 | 为配置默认值写断言同一常量的假测试 | 配置默认值本身没有测试价值；行为变化才需要 red/green；整张票纯 docs/config 时门禁不要求红绿/red 记录 |

## 4. 设计

### 4.1 文件布局

玩具仓库（先做）：

```text
~/spike/sandcastle-toy/
  package.json            # "private": true, "type": "module"；精确 0.14.0
  package-lock.json
  .gitignore              # node_modules/
  .sandcastle/
    opencode-v2.ts        # 适配层
    main.mts              # 改成 opencodeV2()
    verify-adapter.test.ts# 假 handle 测试
    verify-session.ts     # 三次真实运行
    tsconfig.json         # typecheck 用
    prompt.md
```

玩具仓库的根 `package.json` 必须是 `"type": "module"`：`@ai-hero/sandcastle` 是 ESM-only，CJS 下 `require` 会报 `ERR_PACKAGE_PATH_NOT_EXPORTED`。本仓库则把同样的 `package.json` 放进 `.sandcastle/`。

本仓库（已完成）：

```text
~/code/writeLab/
  .sandcastle/
    opencode-v2.ts          # 同一个文件
    main.mts                # §9.3 版本，改用 opencodeV2()
    verify-adapter.test.ts  # 假 handle 测试
    prompt.md               # §9.4 的七条铁律
    package.json            # private + type module + devDeps
    package-lock.json
    tsconfig.json
    .gitignore              # .env / node_modules/ / worktrees/ / logs/
    .env                    # 不提交
    worktrees/              # 不提交
    logs/                   # 不提交
  tools/check-redgreen.sh
  docs/red/.gitkeep         # 第一票生成 issue-N.md
  backend/pom.xml           # no-containers profile
```

首次 clone 后先装依赖：`cd .sandcastle && npm install`；`npm test` 跑假 handle，`npm run typecheck` 检查 TS。

版本锁定：`@ai-hero/sandcastle` 用精确版本 `0.14.0`（不要写 `^0.14.0`）；opencode 记录为 `2.3.0`。适配层是围绕这两个版本的行为写的，升级前先重跑第 5 节。

### 4.2 适配层职责

`opencode-v2.ts` 只做三件事：

1. 调用上游 `opencode(model, options)`，强制 `captureSessions: true`。
2. 覆盖 `sessionStorage.captureToHost`：在沙箱里执行 `opencode session export`，把 JSON 复制回 host。
3. 覆盖 `sessionStorage.resumeIntoSandbox`：把 host 上的 JSON 复制进沙箱，执行 `opencode session import`。

**不改**的部分：`buildPrintCommand`（v2 的 `opencode run --format json --model ... --session ...` 已经可用）、流解析、session ID 提取、权限参数。

**不在本轮范围**：`interactive()`、no-sandbox / Windows 的 cmd 引号、E2BIG 长 prompt、U+2028 流解析。

### 4.3 命令与路径

capture：

```text
opencode --version                          # 探测 >= 2.x，否则抛错
opencode session export '<id>' --standalone > '<sandboxTmp>/<id>.json'
copyFileOut(sandboxTmp/<id>.json, hostSessionFilePath)
```

resume：

```text
mkdir -p '<sandboxTmp>'
copyFileIn(hostSessionFilePath, sandboxTmp/<id>.json)
opencode session import '<sandboxTmp>/<id>.json' --standalone
```

路径来源：

- 沙箱临时目录：`handle.sandboxTmpdir ?? "/tmp"`；如果显式传了 `sessionStorage.sandboxExportDir`，优先用它（与上游一致）。
- host 存档路径：`base.sessionStorage.hostSessionFilePath(hostCwd, sessionId)`，正常是
  `~/.local/share/opencode/sandcastle-sessions/<id>.json`。
- host 目录不存在时先 `mkdir -p`，再复制。
- 引号沿用上游的 POSIX 单引号转义；本轮只保证 WSL + bwrap。

`--standalone` 是必需的，不是可选项：bwrap 与宿主共享 network namespace，宿主 opencode 服务占用 49374，沙箱里的 `session export` 默认会尝试起托管服务并撞端口。`--standalone` 用独立临时服务，读沙箱自己的数据库。`run` 仍然走上游默认服务，二者共用沙箱内的数据库。

### 4.4 版本探测与失败行为

版本探测放在 capture / resume 真正执行前，在沙箱里跑 `opencode --version`，解析主版本号：

- `< 2`：抛出
  `不支持的 opencode 版本 <v>：本地适配层只支持 v2（需要 opencode session export/import）`
- 探测命令本身失败：按同一条错误处理，附上 stderr。

capture 失败时抛出，错误信息必须包含：

```text
session 未保存（id=<id>），本次 run 不能 resume；原因：<原始错误>
```

整个 capture 过程（export 非 0、host 路径缺失、`mkdir`、`copyFileOut`）都在同一个 try/catch 里，统一加上这段前缀；export 失败时 `<原始错误>` 里保留失败的完整命令。不写“警告后继续”的分支。

## 5. 验证方案

### 5.1 零成本测试

假 handle 测试在玩具仓库和本仓库各有一份（`.sandcastle/verify-adapter.test.ts`）；本仓库用 `cd .sandcastle && npm test` 跑：

- 假 `exec` 记录命令，断言 capture 调用的是 `opencode session export`，resume 调用的是 `opencode session import`。
- 断言 `opencode --version` 返回 `1.x` 时抛错。
- 用一个**不存在的嵌套目录**作为 `hostSessionsDir`，断言 capture 后该目录被创建（不能拿 `mkdtemp` 已经建好的目录当断言）。
- 让 `copyFileOut` 抛错，断言错误信息仍包含“不能 resume”（覆盖 host 写盘失败，不只覆盖 export 非 0）。
- 不启动沙箱、不调用模型。

### 5.2 三次真实运行（bwrap）

模型沿用 `opencode-go/deepseek-v4.1-flash`，暗号用随机词（例如“菠萝台风”）。

| 步骤 | 动作 | 通过判据 |
| --- | --- | --- |
| run #1 | 带暗号跑一个最小任务，capture 开启 | 日志无 `SessionCaptureError`；host 出现 `<id>.json`；`result.iterations[0].sessionId` 有值 |
| resume | `result.resume("我刚才让你记住的暗号是什么？")` | 回答包含暗号；使用同一个 session ID |
| fork | `result.fork("再复述一次暗号")` | 新 session ID 与父不同；回答包含暗号；父 session 文件仍在 |

脚本里 resume 用 `assert.equal` 断言 session ID 与父相同，fork 用 `assert.notEqual` 断言不同；不是只打印日志。

三次真实运行的 `verify-session.ts` 只留在玩具仓库；本仓库只带零成本的假 handle 测试，避免每次 clone 都误跑真实模型。

### 5.3 验收清单

- [x] 玩具仓库不再出现 `captureSessions: false`。
- [x] `opencode-v2.ts` 没有改 `node_modules`，`npm install` 后仍工作。
- [x] v1 版本探测会给出清晰错误，而不是 `Unexpected positional argument`。
- [x] capture 失败时错误信息明确说“不能 resume”。
- [x] 三次真实运行的证据（session ID、日志片段、host 文件路径）记录在本文件或学习笔记里。

实测证据（2026-11-18，`bwrap()` + `opencode-go/deepseek-v4.1-flash`）：

```text
run #1 session: ses_e13f204a6ffeCif5UxQ34alRY5
captured: /home/xllcbc/.local/share/opencode/sandcastle-sessions/ses_e13f204a6ffeCif5UxQ34alRY5.json
resume session: ses_e13f204a6ffeCif5UxQ34alRY5   # 同一个 ID
resume stdout: 菠萝台风
fork session:   ses_e13f1b5f2ffe1F37ECF4DQxwrw    # 新 ID
fork stdout:    菠萝台风
```

第一次尝试失败于端口冲突（`Managed service port 49374 ... already in use`），加上 `--standalone` 后通过。

### 5.4 Smoke issue #18（§9 harness）

| 项 | 结果 |
| --- | --- |
| issue | #18 `[smoke] Add GET /api/health` |
| red commit | `f142ddb`（只含测试；真实输出 `expected: 200 but was: 404`） |
| green commit | `2b96b5f`（controller + `docs/red/issue-18.md`） |
| 全量测试 | `cd backend && mvn -q -Pno-containers test`：54 tests，0 failures |
| 红绿门禁 | `bash tools/check-redgreen.sh master agent/issue-18`：通过 |
| issue | 已评论并关闭（旧 prompt 的 smoke 例外）；新 prompt 不再关未合并 issue；分支 `agent/issue-18` 保留未合并 |

### 5.5 真活票 #16（RAG-06）

| 项 | 结果 |
| --- | --- |
| issue | #16 `RAG-06 大语料建索引 + 离线评测与阈值调优` |
| 分支 / 合并 | `agent/issue-16`（`5db465f`）→ master `2b43caf` |
| 主要提交 | `dbfd046` 离线索引、`5b39179` 评测集与脚本、`a8afa11` config 阈值、`5c59b9c` 文档、`c7dd66f` 验收；复核补 `8ac7467`/`83411be` 阈值默认值红绿与 `5db465f` 评测扫描范围 |
| 全量测试 | `cd backend && mvn -q -Pno-containers clean test`：54 tests，0 failures，exit 0 |
| 红绿门禁 | `bash tools/check-redgreen.sh master agent/issue-16`：exit 0 |
| 结果 | 240,843 行全部填充 512 维向量；阈值 0.60 / per-category 2 / max-hits 6；数据库 647 MB；详见 `docs/rag-acceptance.md` |
| issue | 已逐条回填 AC，合并后关闭 |

## 6. 执行顺序

| # | 步骤 | 完成判据 | 依赖 |
| --- | --- | --- | --- |
| 1 | 玩具仓库加 `opencode-v2.ts`，`main.mts` 改用 `opencodeV2()`；把 `package.json` 里的 `^0.14.0` 改成精确 `0.14.0` | `captureSessions: false` 被移除；依赖版本是精确值 | 无 |
| 2 | 跑第 5.1 节的假 handle 测试 | 全部断言通过 | 1 |
| 3 | 跑第 5.2 节三次真实运行 | 表格三行都通过 | 2 |
| 4 | 更新 `docs/sandcastle-guide.md`（见第 7 节） | 指南不再说“等上游适配” | 3；先确认该文件的归属 |
| 5 | 提交 `.gitattributes`/`.gitignore` + 三份文档，推送 master | Windows 仓库干净；远端有 EOL 策略与文档 | 4 |
| 6 | 同步 `~/code/writeLab`，补 `no-containers` profile、`check-redgreen.sh`、JDK/Maven mount、`.sandcastle/` | `mvn -Pno-containers test` 通过；`npm --prefix .sandcastle install && npm test` 通过；`.env` 已填 | 5 |
| 7 | 建 smoke issue #18 跑通 Java/bwrap/Maven/TDD 流水线 | red `f142ddb` + green `2b96b5f`；54 tests pass；issue 已关（smoke 例外） | 6 |
| 8 | 首张真活票 #16（RAG-06） | 已合并到 master（`2b43caf`） | 7 |
| 9 | 下一张 #8（响应式整合 + Playwright） | 先定前端/浏览器门禁 | 8 |

步骤 1–8 已于 2026-11-18 完成；步骤 9 待开。原会话的 126 个“修改”是 CRLF/LF 误判，`.gitattributes` 的 `* text=auto eol=lf` 已消除，不再有 WIP 闸门。

## 7. 文档改动清单（已完成，2026-11-18）

`docs/sandcastle-guide.md`：

- §1.2 术语表补 `session`、`session capture`、`resume`、`fork`。
- §1.3 十二步里的第 9 步补 session capture，并注明 v1/v2 命令差异。
- §6.4 改写：删掉“等 Sandcastle 适配 v2”，换成 `opencodeV2()` 的用法和实测事实。
- §10 排错表：`Unexpected positional argument` 一行改为“v1 命令未被替换”的排查。
- 附录：放完整 `opencode-v2.ts`，并写“为什么不是 patch-package / fork / PR”。
- §9.3 的 `main.mts` 改用 `opencodeV2()`。
- §6.3 增加 `.env` 覆盖警告；§10 增加 JDK mount / `Model unavailable` / `cd backend` / 门禁四行。
- §8.3 门禁要求 red/green 交替、奇数提交失败、未前缀提交直接失败；§9.4 prompt 不再关 issue。
- §8.3/§9.4 增加 `docs:` 提交类型（纯文档/工具 AC）；门禁不把它计入红绿配对，但不能碰 `src/main`/`src/test`。
- §8.3/§9.4 增加 `config:` 提交类型（纯运行时配置默认值）；不碰 `src/main/java`/`src/test`，行为变化仍走 red/green。
- 门禁区分纯 docs/config 票：没有 Java 代码/测试时跳过红绿配对和 `docs/red` 要求；一旦有代码/测试就恢复强检查。
- §9.1 补 `npm install`、`verify-adapter.test.ts`、`docs/red/.gitkeep`；附录验证命令改为本仓库 `npm test`，真实 E2E 留在玩具仓库。

首页或 §6.4 加一句链接：本方案的背景与决定见 `docs/sandcastle-v2-compat.md`。

`docs/sandcastle-pr-guide.md` 保持不动；它讲的是“如果将来要提 PR”的规矩，与当前“不提 PR”的决定不冲突。

> 三份文档已随 `1a1e5f9` 提交推送；后续的 JDK mount、`cd backend`、`.env` 覆盖、门禁脚本修正分别有独立 commit。

## 8. 风险与回滚

| 风险 | 应对 |
| --- | --- |
| 上游 0.15 改了 `AgentSessionStorage` 接口 | 适配层依赖公开类型；升级后跑第 5.1 节测试，先看类型/命令是否还对 |
| 沙箱镜像里的 opencode 其实是 v1 | 版本探测直接报错，不会静默降级 |
| `session import` 某天不再保留 ID | 第 5.2 节 resume/fork 会失败；这是必须重建适配层的信号 |
| `--dangerously-skip-permissions` 未来被删 | 改 `buildPrintCommand` 的包装（本轮不做），或改用 `--auto` |
| 沙箱里的 export/import 撞宿主 opencode 端口 | 已用 `--standalone` 解决；即使将来 bwrap 隔离 network，保留它也无副作用 |
| JDK/Maven 装在 `~/.local/opt`，bwrap 的私有 `/home` 隐藏 `libjli.so` | 在 `bwrap({ mounts })` 里只读挂载 `~/.local/opt`；见指南 §9.2 |
| 把 `process.env.OPENCODE_API_KEY ?? ""` 传给 provider，覆盖 `.env` 里的真 key | 用 `.sandcastle/.env` 时不要传 `env`；宿主没 export 时它会覆盖成空字符串 |
| worktree 根没有 `pom.xml`，hook 里 `mvn` exit 1 | hook 和 prompt 都写成 `cd backend && mvn ...` |
| 宿主上 `check-redgreen.sh` 报“没有任何 docs/red 记录” | 脚本从 `$target` 的树里读 `docs/red/...`，不看当前工作区 |
| CRLF/LF 造成 `git status` 假修改 | `.gitattributes` 固定 `* text=auto eol=lf`；用 `git diff --ignore-cr-at-eol` 复核 |
| 未前缀或奇数提交绕过门禁 | 门禁要求 red/green 交替、奇数提交失败、未前缀提交直接 fail |
| 纯文档 AC 被门禁判失败 | 用 `docs:` 提交；门禁已支持，docs 不参与配对，但不能碰 `src/main`/`src/test` |
| 纯配置 AC 被门禁判失败 | 用 `config:` 提交；不碰 `src/main/java`/`src/test`，行为变化仍写测试 |
| 整张票纯 docs/config 却被要求红绿 | 门禁只在 `code_count > 0` 时要求配对和 `docs/red`；纯票跳过 |
| agent 自己关 issue 但分支未合并 | prompt 已改为不关；人工合并后再关 |
| 需要临时回到阉割版 | 把 `main.mts` 换回 `opencode(..., { captureSessions: false })`，保留适配层文件不动 |

## 9. 未决事项

- smoke 分支 `agent/issue-18` 保留未合并；`#18` 是旧 prompt 下关闭的 smoke 例外，新 prompt 不再关未合并 issue。
- RAG 第一版已收尾：#11–#16 全部验收关闭，spec #10 关闭；验收记录见 `docs/rag-acceptance.md`。
- 下一张真活票是 `#8` 响应式整合与浏览器端到端验收。当前 `tools/check-redgreen.sh` 只覆盖 Java 后端，开工前需要先定前端/Playwright 的可重复门禁，或明确改为手工验收。
- #16 实跑后仍成立的 harness 待办：`main.mts` 的 `MAX_ITERATIONS` / `IDLE_TIMEOUT_SECONDS` 尚未参数化；`prompt.md` 尚未加入「不要运行 docker/psql/Testcontainers、长批处理分批报进度」。

## 10. 参考

- 上游 issue：<https://github.com/mattpocock/sandcastle/issues/1509>
- 引入 v1 命令的已合并 PR：#1260（`feat(opencode): resume and fork OpenCode sessions via export/import`）
- 上游 ADR 0022：`docs/adr/0022-opencode-resume-via-export-import.md`
- 本地用法与学习路径：`docs/sandcastle-guide.md` §6.4、§9、§11
- 上游贡献规矩（本轮不使用）：`docs/sandcastle-pr-guide.md`
