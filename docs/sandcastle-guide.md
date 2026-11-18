# Sandcastle 从零上手：装好、跑通、然后逼 agent 真的走 TDD

> 读者：从没装过 Sandcastle、也没写过 agent 编排脚本的人。
> 目标：**三天内**能自己写 `main.mts`，并且能拦住 agent"交了活但没按 TDD 干"。
> 案例：#14（RAG-04 向量检索）与 #15（RAG-05 语料规模化）——两张票都合进了 master，但都不是测试驱动出来的。
> 依据：`github.com/mattpocock/sandcastle` 的 `docs/guide/`（当前 npm 版本 `0.14.0`，包名 `@ai-hero/sandcastle`）。
> 环境：Windows 主机 + WSL2（Ubuntu 24.04）；仓库在 `C:\Users\xllcbc\Desktop\myplay\myfirstai`。

---

## 0. 这份文档怎么用

别从头读到尾。按下面三条走：

| 时间 | 读哪几章 | 学完能做什么 |
| --- | --- | --- |
| 第 1 天（约 1.5 小时） | 第 1、2、3 章 | 在玩具仓库里跑出第一次 run，看得懂日志和 commit |
| 第 2 天（约 1 小时） | 第 4、5、6 章 | 能改 `main.mts`、能解释 commit 落在哪、能用 opencode 当 agent |
| 第 3 天（约 2 小时） | 第 7、8、9 章 | 能把 TDD 变成机器会拦的门禁，并知道本仓库怎么落地 |
| 随时 | 第 10、11 章 | 排错、跑学习路径、按 SOP 干活 |

约定：

- 所有命令都在 **WSL 终端**里执行（不是 Windows cmd / PowerShell）。提示符写成 `$`，不代表要输 `$`。
- 代码块里的 `<...>` 是占位符，要换成你自己的值。
- 本文所有"你应该看到"都是**可验证的期望输出**，对不上就跳到第 10 章。

关于可信度：API 形状、参数默认值、`init` 行为、bwrap 的挂载与 `HOME` 规则都对照过官方 `docs/guide/` 和源码（`0.14.0`）；第 7 章的每条结论都能在 `git log` 和测试文件行号里复查。第 2、3 章已在目标机器上实跑通过（2026-11-17：bwrap 0.9.0 + opencode 2.3.0 + Node 24 + JDK 25），其中 opencode v2 的 session 抓取不兼容是实跑时发现的，处理方式见 6.4。

### 先认清一个前提

Sandcastle 不替你决定做什么、也不替你测试。它只做三件事：**开沙箱 → 在沙箱里启动一个编码 agent → 把 agent 的 commit 收回来**。

所以"agent 没按 TDD 干"不是 Sandcastle 的 bug，而是**你的 prompt 和流程里没有机器可判定的检查点**。第 7、8 章专门解决这件事。

---

## 1. 心智模型

### 1.1 它是什么、不是什么

| 它**是** | 它**不是** |
| --- | --- |
| 一个 TypeScript 库，你写 `main.mts` 调 `run()` | 一个应用、一个 SaaS、一个"装完就能用"的 CLI |
| 一个把 agent 关进沙箱的执行器 | 一个替你决定做哪个 issue 的规划器 |
| 一个把 commit 收回来的 git 工具 | 一个测试框架，也不是 TDD 工具 |

它真正卖的是两件事：

- **隔离**：agent 在一次性容器/进程沙箱里跑，摸不到你的 home、SSH key、其它仓库。
- **并行**：同一台机器同时跑 3–5 个 agent，各自一个 worktree、各自一条分支，互不踩脚。

### 1.2 十个词（全篇地基）

| 词 | 意思 | 本仓库对应 |
| --- | --- | --- |
| **host** | 跑 `main.mts` 的那台机器 | 你的 WSL2 |
| **sandbox** | 关 agent 的笼子：容器、VM 或 OS 级进程沙箱 | 一个 bwrap 进程沙箱（第 2 章） |
| **sandbox provider** | 造笼子的函数 | `bwrap()` / `docker()` |
| **agent** | 真正写代码的编码 CLI | WSL 里的 `opencode` |
| **agent provider** | 启动 agent 的函数 | `opencode("...")` / `opencodeV2("...")` |
| **session** | agent 的对话状态；opencode v2 存在 SQLite 里，可导出成 JSON | `result.iterations[i].sessionId` |
| **session capture** | agent 退出后，把 session 导出并复制回 host | `~/.local/share/opencode/sandcastle-sessions/<id>.json` |
| **resume** | 导入 host 上的 session，用同一个 ID 继续对话 | `result.resume("...")` |
| **fork** | 从父 session 分叉出新 ID，父 session 保持不动 | `result.fork("...")` |
| **prompt** | 每次启动 agent 时喂给它的指令 | `.sandcastle/prompt.md` |
| **iteration** | agent 启动到退出的一次完整过程 | "干完一票" = 1 iteration |
| **task** | 一次 iteration 该干的那票活 | 一个 GitHub issue |
| **branch strategy** | agent 的 commit 落到哪 | `{ type: "branch", branch: "agent/issue-14" }` |
| **worktree** | 同一仓库的第二份 checkout，占独立分支 | `.sandcastle/worktrees/...` |
| **completion signal** | agent 说"没活了"的记号 | `<promise>COMPLETE</promise>` |
| **hook** | 沙箱起来前后要跑的命令 | `mvn -q -Pno-containers -DskipTests compile` |
| **red / green** | 失败测试 / 通过测试 | `mvn test` 的两种结果 |

记住两条对应关系：**一个 iteration 干一个 task；一个 task 走一次红-绿。**

### 1.3 一次 `run()` 的十二步

这张图是你之后排错的坐标系。左边是 host 上发生的事，右边是沙箱里发生的事：

```text
host（你的 WSL2）
  1. 读 promptFile，替换 {{KEY}}（来自 promptArgs）
  2. 按 branchStrategy 建 worktree，切到工作分支
  3. 跑 hooks.host.onWorktreeReady（顺序执行）
  4. 造 sandbox（bwrap / docker run ...），把 worktree 挂进去
  5. 跑 hooks.host.onSandboxReady 与 hooks.sandbox.onSandboxReady（并行）
  6. 展开 prompt 里的 !`cmd`（在沙箱里跑，并行），把输出填进 prompt
  7. 启动 agent，喂 prompt
       └─ agent 在沙箱里读 issue、写测试、写实现、跑测试、commit
  8. agent 退出（或打出完成信号后退出/超时）
  9. 收集这一轮产生的 commit；捕获 session（沙箱里 `opencode session export --standalone`，复制回 host）
 10. 下一轮 iteration（若 maxIterations > 1 且没收到完成信号）：回到第 6 步
 11. 按分支策略收尾：merge-to-head 合并回 HEAD；branch 留着
 12. 结果打包进 RunResult：iterations / commits / branch / output
```

这张图能解释四个常见困惑：

- 为什么 prompt 里的 `` !`gh issue view ...` `` 每轮都是最新的 → 它**每次 iteration 重新展开**（第 6 步）。
- 为什么依赖要在 `sandbox.onSandboxReady` 里装 → 它在 agent 启动前跑完，而且每次 `run()` 都跑。
- 为什么"一个 commit 装下所有东西"是可预期的坏结果 → 第 7 步里 agent 只有**一次**启动机会，它倾向一次做完再提交。**TDD 的强制点必须在这之前。**
- 为什么 `result.resume()` 依赖 host 上的 JSON → bwrap 的 home 是临时的，session 随沙箱销毁；capture 是唯一的桥（第 9 步）。

### 1.4 为什么"一个 iteration = 一个 commit"注定 TDD 失败

TDD 要的是**小步提交的权限**：红一个、绿一个、再红一个。而默认流程只给 agent 一次提交的机会，于是它唯一的可行策略就是：

```
全写完 → 补测试 → 跑通 → 提交一次
```

这不是 agent 不听话，是**流程的粒度不允许它听话**。第 8 章的第二层"分票"就是为此设计的：把"写测试"和"让测试变绿"拆成两次 `run()`。

---

## 2. 装环境（WSL + bwrap 主路径）

### 2.1 为什么选 bwrap，而不是 Docker

先看实测数据。同一台机器、同一个仓库、同一条 `git status`：

| 方式 | 耗时 |
| --- | --- |
| WSL 访问 `/mnt/c`（9p 文件系统） | **1.95 s** |
| Windows 原生 `git.exe`（NTFS） | **0.16 s** |
| WSL 里跑原生 Linux 命令（`gh --version`） | 0.04 s |
| WSL 里跑 `mvn -v`（转发 Windows exe 的 wrapper） | **0.69 s** |

结论：

- **`/mnt/c` 很慢**，慢的不是 WSL，而是跨文件系统。
- **bwrap 没有守护进程、没有 VM**，对你的 7.4 GB 内存几乎零开销。
- Docker Desktop 在 WSL2 里是一个独立 VM，空闲就常驻 1–2 GB，直接吃掉你 1/5 的内存预算。

所以主路径是：**WSL + bwrap + 把仓库克隆到 ext4（`~/code/...`）**。Docker 放到第 9.6 节当附录。

官方原文（`docs/guide/sandbox-providers.md`）：

> **`bwrap()` on Windows.** Run Sandcastle inside WSL2 and use `bwrap()`, with your repo on the Linux filesystem (ext4, for example `~/code/my-repo`), not under `/mnt/c`. Inside the sandbox, `/mnt`, `/run/WSL` and `WSL_INTEROP` are hidden, so the agent can't run a Windows `.exe`.

意思是：**项目留在 C 盘 = 这条路走不通**。这是必须接受的迁移成本。

### 2.2 前置检查

```bash
. /etc/os-release; echo "$PRETTY_NAME"
free -h | head -2
command -v node npm npx java mvn gh bwrap
```

当前这台机器的真实结果（作为对照）：

- Ubuntu 24.04.3 LTS，内存 7.4 GB。
- `node` = `~/.local/bin/node`（一个 **bash 脚本**，`exec "C:\Program Files\nodejs\node.exe"`）。
- `npm` / `npx` = `/mnt/c/Program Files/nodejs/...`（Windows 的）。
- `mvn` = `~/.local/bin/mvn`（一个 **bash 脚本**，`exec cmd.exe /c mvn`）。
- `gh` = 原生 Linux（能用）。
- `bwrap`、`docker` = 都没有。

**这就是第一个坑**：这些"转发到 Windows"的 wrapper 在 bwrap 沙箱里**全部失效**，因为沙箱里 `/mnt` 和 Windows interop 被隐藏了。所以下一步必须补 WSL 原生工具链。

### 2.3 装 bubblewrap

```bash
sudo apt update && sudo apt install -y bubblewrap
bwrap --dev-bind / / --proc /proc --dev /dev true && echo "bwrap ok"
```

期望输出：`bwrap ok`。

如果报 user namespace 相关错误（Ubuntu 24.04 起 AppArmor 默认可能限制），按官方给的修法：

```bash
printf 'abi <abi/4.0>,\ninclude <tunables/global>\nprofile bwrap %s flags=(unconfined) {\n  userns,\n}\n' "$(command -v bwrap)" \
  | sudo tee /etc/apparmor.d/bwrap >/dev/null && sudo apparmor_parser -r /etc/apparmor.d/bwrap
```

本机 `/proc/sys/kernel/apparmor_restrict_unprivileged_userns` 不存在，通常不需要这一步。

### 2.4 补 WSL 原生工具链

先把两个"转发 Windows"的 wrapper 挪走——**不挪走，你永远命中 Windows 的 node / mvn**：

```bash
mv ~/.local/bin/node ~/.local/bin/node.windows.bak
mv ~/.local/bin/mvn  ~/.local/bin/mvn.windows.bak
hash -r
```

装 Node 24（用 nvm，避免污染系统）：

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc
nvm install 24
node -v && npm -v
```

装 JDK 25 + Maven（Ubuntu 24.04 的 apt 仓库只有 JDK 21，所以用官方 tarball）：

```bash
mkdir -p ~/.local/opt ~/.local/bin
cd ~/.local/opt

# Temurin JDK 25（GA）
curl -fsSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" \
  -o /tmp/jdk25.tar.gz
tar -xzf /tmp/jdk25.tar.gz -C ~/.local/opt
ln -sfn "$(find ~/.local/opt -maxdepth 1 -type d -name 'jdk-25*' | head -1)" ~/.local/opt/jdk-25

# Maven（去 https://dlcdn.apache.org/maven/maven-3/ 确认当前版本号）
curl -fsSL "https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz" \
  -o /tmp/maven.tar.gz
tar -xzf /tmp/maven.tar.gz -C ~/.local/opt
mv ~/.local/opt/apache-maven-3.9.16 ~/.local/opt/maven

# 暴露到 ~/.local/bin（它已经在 PATH 上）
ln -sfn ~/.local/opt/jdk-25/bin/java  ~/.local/bin/java
ln -sfn ~/.local/opt/jdk-25/bin/javac ~/.local/bin/javac
ln -sfn ~/.local/opt/jdk-25/bin/jar   ~/.local/bin/jar
ln -sfn ~/.local/opt/maven/bin/mvn    ~/.local/bin/mvn
```

把 `JAVA_HOME` 写进 `~/.bashrc`（有些工具只认它，不认 PATH）：

```bash
cat >> ~/.bashrc <<'EOF'

# sandcastle-java: native JDK 25 + Maven (WSL)
export JAVA_HOME="$HOME/.local/opt/jdk-25"
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/maven/bin:$PATH"
EOF
```

> 也可以用 SDKMAN：`curl -s "https://get.sdkman.io" | bash`，然后 `sdk install java 25-tem`、`sdk install maven`。它的好处是版本切换方便，坏处是安装过程会交互、偶尔卡在"设为默认？"，脚本化时不如 tarball 确定。

期望：`node -v` 输出 `v24.x`，`java -version` 显示 25，`mvn -v` 显示 Maven 3.9+，并且**都在 Linux 路径下**（`which node` / `which mvn` 不再是 `/mnt/c/...` 或 `cmd.exe` 包装）。

### 2.5 把仓库克隆到 ext4

```bash
mkdir -p ~/code && cd ~/code
git clone https://github.com/xllcbc/english-learning-coach.git writeLab
cd writeLab && git log --oneline -3 && du -sh .
```

> ⚠️ 你 Windows 那份仓库现在有未提交改动（`.env.example`、`backend/pom.xml` 等）。**开工前先在 Windows 侧提交并推送**，否则这份 clone 会和你的 WIP 分叉。

之后两边怎么往返（这决定了你日常用哪个编辑器）：

```bash
# WSL 沙箱侧：agent 干完活，分支推回远端
git push origin agent/issue-14

# Windows 侧：取回来审查、合并
git fetch origin && git checkout master && git merge origin/agent/issue-14
```

Windows 那份继续用 IDEA 打开，负责代码审查和最终合并；WSL 这份只负责"关起门来让 agent 干活"。

### 2.6 环境检查清单

跑完第 2 章，下面每条都必须通过：

```bash
bwrap --dev-bind / / --proc /proc --dev /dev true && echo ok
node -v && npm -v
java -version && mvn -v
gh auth status
cd ~/code/writeLab && git status --porcelain | head
```

任何一条失败，回到对应小节。**不要带着半成品环境进第 3 章**——那样你分不清是工具的问题还是环境的问题。

---

## 3. 第一次跑通（玩具仓库）

第 1 天的目标只有一个：**看懂机制**。不要用 WriteLab 做这一步。

### 3.1 建仓库

```bash
mkdir -p ~/spike/sandcastle-toy && cd ~/spike/sandcastle-toy
git init -b main
printf '# Toy\n\n一个用来学 Sandcastle 的空仓库。\n' > README.md
git add . && git commit -m "init"
npm init -y
```

### 3.2 装包

```bash
npm install --save-dev @ai-hero/sandcastle
```

注意：**包名是 `@ai-hero/sandcastle`**。npm 上还有一个叫 `sandcastle` 的包，那是"运行不可信 JS 的沙箱"，跟这个工具毫无关系。

### 3.3 初始化

```bash
npx @ai-hero/sandcastle init
```

它会问四件事。你在这台机器上这样回答：

| 问题 | 答什么 | 为什么 |
| --- | --- | --- |
| sandbox provider | `bwrap`（列表里标 recommended 的那项） | 已装好、零内存开销；Docker 你有内存压力 |
| agent | `opencode` | WSL 里已经装好原生 `opencode`，不用再装 Claude Code |
| issue tracker | `custom`（玩具仓库没有 GitHub issue） | 避免第一天就去配 GitHub 标签和 `GH_TOKEN` |
| template | `blank` | 第一跑要最小、可控；`simple-loop` 留到第 4 章 |

非交互写法（flag 与四问一一对应）：

```bash
npx @ai-hero/sandcastle init \
  --sandbox bwrap --agent opencode \
  --template blank --issue-tracker custom
```

它给你这些文件：

```
.sandcastle/
├── main.mts        # 入口脚本，以后天天改
├── prompt.md       # agent 每次启动看到的指令
├── .env.example    # 凭据占位
└── .gitignore      # 忽略 .env / logs / worktrees
```

`custom` 模板会额外生成 `SETUP_ISSUE_TRACKER.md`。我们第 3.4 节直接**换掉 prompt 和 main**，所以不用管它。

> 若目录已存在，`init` 会直接报错，不覆盖你的修改。想重来就 `rm -rf .sandcastle`。

### 3.4 换成最小可跑的 prompt 与 main

`init` 用 `custom` 生成的内容是"配好之前故意跑不通"的。第一天我们不要那层复杂度，直接写最小版。

`.sandcastle/prompt.md`：

```markdown
# 任务

在 README.md 末尾追加一节「## 沙箱测试」，内容包含：

- 当前分支：!`git branch --show-current`
- 一句话说明你是被 Sandcastle 启动的

# 规则

- 只允许修改 README.md
- 做完后提交**一个** commit，消息必须是 `docs: add sandbox test section`
- 提交后输出完成信号

<promise>COMPLETE</promise>
```

`.sandcastle/main.mts`：

```ts
import { run, opencode } from "@ai-hero/sandcastle";
import { bwrap } from "@ai-hero/sandcastle/sandboxes/bwrap";

const result = await run({
  // 日志前缀，跑起来你会看到 [toy]
  name: "toy",

  // Windows 上没有原生沙箱；走 WSL2 的 bwrap
  sandbox: bwrap(),

  // 模型字符串用你 WSL 里平时能跑的那个（`opencode models` 可以列出）
  // captureSessions: false —— opencode v2 不再支持 `opencode export <id>`，
  // Sandcastle 0.14.0 的 session 抓取会报 SessionCaptureError，先关掉
  agent: opencode("opencode-go/deepseek-v4.1-flash", { captureSessions: false }),

  promptFile: ".sandcastle/prompt.md",

  // 一次只干一票
  maxIterations: 1,

  // 一票一条分支：不碰你的工作区，可审计
  branchStrategy: { type: "branch", branch: "agent/toy-1" },
});

console.log("iterations:", result.iterations.length);
console.log("commits:", result.commits);
console.log("branch:", result.branch);
```

> `opencode-go/deepseek-v4.1-flash` 是示例。换成你在 WSL 里平时能跑通的 model 字符串：`opencode models` 会列出全部可选模型。
>
> 这是**最小首跑**，故意关掉 session 抓取。跑通后按 §6.4 换成 `opencodeV2()`，恢复 capture / resume / fork；最终 `.sandcastle/main.mts` 见 §9.3。

### 3.5 凭据：为什么不能靠"我已经登录过了"

bwrap 每次给沙箱一个**全新的私有 home**，你宿主上的 `~/.local/share/opencode/auth.json` 在里面看不见。所以登录态必须从环境变量进：

```bash
cp .sandcastle/.env.example .sandcastle/.env
```

打开 `.sandcastle/.env`，填你 provider 的 key（`init` 给出的示例是 `OPENCODE_API_KEY=`；用 Anthropic 之类的 provider 就填对应变量，例如 `ANTHROPIC_API_KEY=`）。要在这个玩具仓库里用 `gh`，再加一行 `GH_TOKEN=`。

三个规则：

- `.sandcastle/.gitignore` 已忽略 `.env`，别提交。
- `process.env` 里已有的同名变量也会被读取，所以临时导出也行。
- **值不要带引号**，`KEY=abc` 而不是 `KEY="abc"`。

### 3.6 首跑

```bash
npx tsx .sandcastle/main.mts
```

你应该看到，按顺序：

1. 建 worktree 的日志（新目录会出现在 `.sandcastle/worktrees/` 下），以及 agent 日志的位置：

   ```text
   [toy] Started on branch agent/toy-1
     tail -f .sandcastle/logs/agent-toy-1-toy.log
   ```

2. agent 的实时输出（用 `tail -f` 看上面那个日志文件更完整）。
3. 最后打印三行：

   ```text
   iterations: 1
   commits: [ { sha: 'e659eeb...' } ]
   branch: agent/toy-1
   ```

然后手动验证：

```bash
git log --oneline -2
git worktree list
git show --stat agent/toy-1
```

关键观察点（第 1 天的全部任务）：

- **commit 落在哪条分支**：`agent/toy-1`。你的 `main` 没被动过。
- **worktree 在哪**：`.sandcastle/worktrees/` 下多了一个目录。
- **`result.commits` 里是什么**：一个 `{ sha }` 对象。

如果 5 分钟内没有任何 agent 输出，跳到第 10 章"agent 完全没输出"那一行。

---

## 4. 三个分支策略实验

三种策略的官方行为（`docs/guide/branch-strategies.md`）：

| 策略 | commit 落到 | 默认用于 |
| --- | --- | --- |
| `{ type: "head" }` | 直接写你的工作区，**不建 worktree** | bind-mount 类 provider 的默认值 |
| `{ type: "merge-to-head" }` | 临时分支 + worktree，完成后合并回 HEAD | isolated 类 provider 的默认值 |
| `{ type: "branch", branch: "agent/x" }` | 你指定的分支 + worktree | 想开 PR、想按票留档 |

注意两件事：

- **`head` 是 bind-mount provider（bwrap/docker）的默认值**，不是 `merge-to-head`。这是最容易记错的点。
- `head` 直接改你的工作区——跑偏了只能靠 stash 抢救。**无人值守时不要用它。**

### 实验 A：`head`

把 `main.mts` 里那行换成：

```ts
branchStrategy: { type: "head" },
```

跑一次，然后：

```bash
git log --oneline -2   # 新 commit 直接落在我当前分支（main）上
git status             # 工作区已被写干净
git worktree list      # 没有新 worktree
```

实测记录（2026-11-17）：

```text
[exp-head] Started on branch main
commits: [ { sha: '4db65f5...' } ]
4db65f5 docs: add sandbox test section (head)
fc7d53a init
```

体会：快，但它**直接改你的工作区**，而且 commit 落在你当前检出的分支上。跑偏了只能靠 stash / reset 抢救。**只在你要盯着它改的时候用。**

### 实验 B：`merge-to-head`

```ts
branchStrategy: { type: "merge-to-head" },
```

跑完看：

```bash
git log --oneline -3
git log --graph --oneline --all -5
git worktree list        # 跑完 worktree 已被清理
```

实测记录：

```text
[exp-mth] Started on branch sandcastle/exp-mth/20261117-091122-b41fb8
commits: [ { sha: '68d0ffb...' } ]
branch: main

# reflog 里能看到它到底怎么合进来的
68d0ffb merge sandcastle/exp-mth/20261117-091122-b41fb8: Fast-forward
```

两个容易误解的点：

1. **临时分支名是 `sandcastle/<name>/<时间戳>-<随机后缀>`**，不是你起的名字；合并完它会被删掉。
2. **不一定会产生 merge commit**。如果你当前分支在 run 期间没动，Sandcastle 做的就是一次 **fast-forward**，历史是一条直线。只有两边都前进时才会出现真正的合并提交。

体会：HEAD 是原子前进的；如果 run 失败，临时分支会被保留，并告诉你该怎么合并或丢弃。这是"跑失败了也不会污染工作区"的默认选择（注意：`head` 才是代码里的默认值，`merge-to-head` 只是**推荐给无人值守**的选择）。

### 实验 C：`branch`（本仓库要用的）

```ts
branchStrategy: { type: "branch", branch: "agent/issue-14" },
```

跑完看：

```bash
git branch -vv | grep agent/
git log --oneline master..agent/issue-14
git worktree list
```

实测记录（分支名换成了 `agent/toy-1`）：

```text
[toy] Started on branch agent/toy-1
iterations: 1
commits: [ { sha: 'e659eeb...' } ]
branch: agent/toy-1
```

体会：**一条票一条分支**，历史独立可审计，多条票可以并行跑。第 8 章的机器门禁就是靠这个分支形状做检查的。

注意：**run 结束后 worktree 会被清理，但分支留着**。下一次用同一个 `branch` 名重跑时，它会重建 worktree，并在安全时从 `origin` 快进。

> 实验完记得把 `main.mts` 改回 `branch` 策略；实验产生的一次性提交可以用 `git reset --hard <初始提交>` 清掉（玩具仓库里随便动）。

---

## 5. 读懂自己的 `main.mts` 与 prompt

### 5.1 参数表（只列你会用的）

```ts
await run({
  agent: opencode("..."),                      // 必填：谁干活
  sandbox: bwrap(),                            // 必填：关哪儿
  promptFile: ".sandcastle/prompt.md",         // 和 prompt 互斥，二选一
  promptArgs: { ISSUE_NUMBER: 14 },            // 填 {{ISSUE_NUMBER}}
  maxIterations: 1,                            // 默认 1
  completionSignal: "<promise>COMPLETE</promise>", // 默认就是这个
  completionTimeoutSeconds: 60,                // 看到信号后，等进程退出的宽限
  idleTimeoutSeconds: 600,                     // 一直没输出多久算卡死
  branchStrategy: { type: "branch", branch: "agent/issue-14" },
  hooks: {
    sandbox: { onSandboxReady: [{ command: "mvn -q compile", timeoutMs: 600_000 }] },
  },
  output: Output.object({ tag: "report", schema }), // 结构化回执，要求 maxIterations === 1
  name: "issue-14",
});

result.iterations   // 每轮的 IterationResult（含 sessionId）
result.commits      // [{ sha }]
result.branch       // 目标分支
result.output       // 结构化输出（用了 Output 时）
result.completionSignal // 命中的完成信号，没命中就是 undefined
```

### 5.2 prompt 的三种语法

只有 `promptFile` 支持下面全部三种；`prompt: "内联字符串"` 是**原样喂**的，不替换、不展开。

**① `{{KEY}}` 占位符**

```markdown
Work on issue #{{ISSUE_NUMBER}}.
```

由 `promptArgs: { ISSUE_NUMBER: 14 }` 填。两个内置的不用传：`{{SOURCE_BRANCH}}`（agent 正在工作的分支）、`{{TARGET_BRANCH}}`（`run()` 时 host 的活动分支）。

**② `` !`command` `` 动态上下文**

```markdown
## Open issues

!`gh issue list --state open --label ready-for-agent --json number,title --limit 100`
```

- 在**沙箱内**跑，在 `sandbox.onSandboxReady` 之后，所以它看到的是 agent 看到的同一份仓库状态。
- 一条失败（非 0 退出），整次 run 立刻失败。想留个不执行的示例，写进 HTML 注释：`` <!-- !`cmd` --> ``。
- 所有 `` !`cmd` `` 并行展开。
- **这是 TDD 门禁能生效的关键机制**：你可以让 prompt 每次都带上"上一轮的红色输出"，逼 agent 面对它。

**③ 完成信号**

不是引擎注入的，是你写在 prompt 里的约定：

```markdown
全部可做的票都做完，或者全部被阻塞时，输出：<promise>COMPLETE</promise>
```

`run()` 看到就提前结束 iteration 循环。可用 `completionSignal` 换成自己的（也接受数组）。

### 5.3 结构化回执（让 agent 自证）

```ts
import { z } from "zod";

output: Output.object({
  tag: "report",
  schema: z.object({
    issue: z.number(),
    acceptanceCriteria: z.array(z.object({
      criterion: z.string(),
      testFile: z.string(),
      redEvidence: z.string(),   // 第一次失败的真实输出片段
    })),
  }),
})
```

- 要求 `maxIterations === 1`。
- 解析/校验失败抛 `StructuredOutputError`，可加 `maxRetries` 让它自己修格式。
- **注意：这是 agent 的自我陈述，不是证据。** 但它把"谎报"的成本从"随口说做了"提高到"必须编造一段具体输出"，而伪造的输出可以和 `git log` 对账。

### 5.4 两个必踩的坑

1. **`promptFile` 相对 `process.cwd()` 解析，不是相对 `cwd`。** 在 monorepo 里跑特别容易踩。本仓库在根目录跑，所以写 `.sandcastle/prompt.md` 没问题；如果你 `cd backend` 再跑，就会找不到。
2. **`promptArgs` 里的内容不会被执行。** `` !`cmd` `` 只在 prompt 文件正文里展开。issue 标题、PR 描述可以放心经 `promptArgs` 传进去。

---

## 6. 用 opencode 当 agent

### 6.1 沙箱里到底执行了什么

`opencode()` 不是"调用你装好的 opencode"，它在**沙箱内**拼这条命令：

```text
opencode run --format json --model '<model>' [--variant <v>] [--agent <mode>] [--dangerously-skip-permissions] [--session <id>] '<prompt>'
```

所以：**沙箱里必须有一份 Linux 版 `opencode` 可执行文件**，并且它在 `PATH` 上。你现在 WSL 里那份 `/home/xllcbc/.opencode/bin/opencode` 正好满足。

### 6.2 Windows 上那份 opencode 为什么用不了

- 在 docker 沙箱里：容器里是 Linux，跑不了 `.exe`。
- 在 bwrap 沙箱里：`/mnt`、`/run/WSL`、`WSL_INTEROP` 都被隐藏，Windows 的 `.exe` 和 interop 都不可达。

**Windows 上的 opencode 只在 `sandbox: noSandbox()` 时才真正被用到。** 而 `noSandbox()` 没有隔离，还会把 prompt 里的 bash 语法（`2>/dev/null`、`||`）交给 PowerShell/cmd 解析，9.4 节那套 prompt 会直接挂。

### 6.3 三种凭据注入方式

沙箱给它一个私有 home，你的 `~/.local/share/opencode/auth.json` 和 `opencode.db` 都看不见。按推荐顺序：

**① 环境变量（推荐）**

```ts
agent: opencode("opencode-go/deepseek-v4.1-flash", {
  env: { OPENCODE_API_KEY: process.env.OPENCODE_API_KEY! },
}),
```

或者写进 `.sandcastle/.env`：

```dotenv
OPENCODE_API_KEY=...
GH_TOKEN=...
```

`.env` 的键会与 `process.env` 合并；agent provider 的 `env` 覆盖 `.env` 里的同名键。注意：**agent provider 的 env 与 sandbox provider 的 env 不能有重叠键**，否则 `run()` 直接报错。

**② 想挂载 `auth.json`？bwrap 里做不到。**

这条值得单独写清楚，因为很多人第一反应就是"把 auth 文件挂进去"。源码里有两条硬约束：

- `bwrap()` 的沙箱 home 固定是 **`/home/agent`**（不是你的宿主 home）。
- `bwrap()` 的 `mounts` 是 **identity mount**：`sandboxPath` 必须等于 `hostPath`，否则直接抛错：

```text
bwrap() mounts appear at their host path, so sandboxPath must equal hostPath
```

所以你把 `/home/xllcbc/.local/share/opencode/auth.json` 挂到同路径后，opencode 仍然去 `/home/agent/.local/share/opencode/` 找凭据——**看不见**。

`mounts` 的正确用途是暴露"程序会用绝对路径访问的目录"，比如第 9.2 节的 Maven 缓存（配合 `-Dmaven.repo.local=<绝对路径>`）。凭据请走 ①。

**③ `noSandbox()`**：直接用你宿主的登录态，没有隔离，只适合调试。

### 6.4 session 与 resume

opencode 的 session 存在 SQLite 里，Sandcastle 的协议是"导出成 JSON"：跑完在沙箱里导出，resume 时导入。

opencode v2 改了命令，Sandcastle 0.14.0 还在调 v1 的顶层命令：

| 动作 | v1（0.14.0 默认） | v2（本仓库用法） |
| --- | --- | --- |
| 导出 | `opencode export <id>` | `opencode session export <id> --standalone` |
| 导入 | `opencode import <file>` | `opencode session import <file> --standalone` |

不加适配层时，第一次跑会在**任务已经成功、commit 已经产生之后**抛错：

```text
SessionCaptureError: Session capture failed: `opencode export 'ses_...' > '/tmp/ses_....json'` exited with code 1:
ERROR Unexpected positional argument: "ses_..."
```

处理：用 `.sandcastle/opencode-v2.ts` 包一层，只替换 export/import 两条命令。

```ts
import { opencodeV2 } from "./opencode-v2.ts";

agent: opencodeV2("opencode-go/deepseek-v4.1-flash"),
```

`run`、流解析、权限参数、session ID 提取都还是上游行为。完整代码见文末附录。

**为什么必须加 `--standalone`**：bwrap 与宿主共享 network namespace，宿主 opencode 服务占用 49374，沙箱里的 `session export` 默认会尝试起托管服务并撞端口：

```text
Managed service port 49374 on 127.0.0.1 is already in use by another process.
```

`--standalone` 用独立临时服务，读沙箱自己的数据库。实测（2026-11-18）：不加会 capture 失败；加上后 capture / resume / fork 全通。

**版本要求**：适配层只支持 opencode v2，会先跑 `opencode --version`，低于 2.x 直接报清晰错误；`@ai-hero/sandcastle` 固定 `0.14.0`。

resume / fork 的用法：

```ts
const result = await run({ ... });
const sessionId = result.iterations[0].sessionId;

// 接着同一份上下文继续（只跑 1 个 iteration）
const resumed = await result.resume("接着上一轮继续");

// 从同一份上下文分叉；父 session 不动，子 run 拿到新 ID
const forked = await result.fork("换个方向试试");

// 也可以在新 run 里显式续某个已捕获的 ID
await run({ ..., resumeSession: sessionId });
```

`resume()` / `fork()` 只在 capture 成功时存在，且不能和 `maxIterations > 1` 同用。

背景、决策与实测证据见 [`docs/sandcastle-v2-compat.md`](./sandcastle-v2-compat.md)。上游 issue [#1509](https://github.com/mattpocock/sandcastle/issues/1509) 仍 open；本仓库不提交 PR。若将来要提，形式要求见 [`docs/sandcastle-pr-guide.md`](./sandcastle-pr-guide.md)。

---

## 7. 案例解剖：#14 / #15 为什么"不是 TDD 出来的"

先立规矩：**没有编辑过程记录，只能看结果。** 下面把"可复查的事实"和"推断"分开写，推断会明确标注。

### 7.1 可复查的事实

```text
$ git log --format="%h %p %s" -4
c487ad2 3dc8f3a 9349c69 merge: integrate Corpus Sentence scaling (#15) into master
3dc8f3a 97500df 86656ac merge: integrate REAL embedding retrieval (#14) into master
9349c69 97500df feat: scale Corpus Sentences to core word forms with twelve candidates
86656ac 97500df feat: add REAL embedding retrieval with pgvector cosine ranking (#14)
```

| 事实 | 证据 |
| --- | --- |
| **每票恰好一个 commit** | `86656ac`、`9349c69` 的父提交都是 `97500df` |
| **一个 commit 装下 31 个文件** | `86656ac`：18 个 `src/main/`（含迁移与配置）+ 7 个 `src/test/` + 4 篇 `docs/` + `.env.example`、`DEPLOY.md`，共 981 行改动 |
| **CSV 与导入代码同 commit** | `9349c69`：10 个文件、+241,559 行，其中 `sentences.csv` 单文件 +240,818 行 |
| **历史上不存在"只加了一个失败测试"的 commit** | `git log` 里没有任何红 commit |

第一张票的形状（31 个文件一个提交）在红绿协议下**根本走不通**——这就是第 8 章第三层的意义：把 #14 那种产物从"可能"变成"不可能"。

### 7.2 拿 `tdd` 技能自己的标准去量它

**① "Red before green"——#15 在结构上就排除了它。**

导入器的行为（阈值判断、冲突忽略、单事务）**只有把那份 CSV 放进去才观察得到**。CSV 与导入代码在同一个 commit，意味着"红"那一刻的快照从未存在过。测试只能事后补。

**② "Test only at pre-agreed seams"——#15 违反了。**

```java
// backend/src/test/java/com/writelab/retrieval/CorpusSentenceImporterTest.java:62-71
long streamed = CorpusSentenceImporter.forEachRow(row -> { ... });
assertThat(streamed)
    .isEqualTo(CorpusSentenceImporter.countRows())
    .isGreaterThan(200_000);
```

`parse` / `forEachRow` / `countRows` 是 **package-private static 工具方法**，不是"导入一份数据集之后库里有什么"这个 seam。而且 `streamed` 和 `countRows()` 是同一个类的两条路径互相印证——期望值由被测代码自己算出，通过是构造出来的（`tdd` 技能管这叫 tautological）。

**③ "Implementation-coupled"——#14 违反了，而且踩在自己写的验收标准上。**

#14 的验收标准第 10 条白纸黑字：

> 在线边际延迟目标（每次请求最多一次批量 embedding 调用）在文档中记录为容量说明，**但不写进测试断言**

而 `backend/src/test/java/com/writelab/retrieval/VectorLearningMaterialRetrieverTest.java:48,68`：

```java
void filtersCorpusSentencesByExactWordFormThenOrdersBySimilarityInOneBatchCall() {
    ...
    assertThat(embeddingClient.calls).hasSize(1);   // ← 正是"一次批量调用"
}
```

测试名里的 `InOneBatchCall` 和这两行断言，就是 AC 明令禁止的事。这不是理解偏差，是**写测试时没回去读验收标准**——而在 TDD 里，读验收标准是写第一个红灯之前的动作。

顺带：这个测试名描述的是 **HOW**（怎么调、调了几次），不是 **WHAT**（学习者拿到哪句例句）。

**④ 漏掉的验收标准。**

#15 的 AC 写着"启动导入具备阈值判断、逐条冲突忽略、**单事务失败即回滚**"。而 `CorpusSentenceImportIntegrationTest` 只有 4 个测试：

```
importReachesTheCorpusSentenceDatasetSize
repeatedStartupDoesNotWriteRowsAgain
restoresMissingCorpusSentencesWhenTheTableFallsBelowTheDatasetSize
topUpKeepsACuratedRowThatSharesAWordFormAndSentence
```

**没有一个是"中途失败则整体回滚"。** 这是"验收标准里有、测试里没有"的一条。TDD 的副产品恰恰是这种清单会被自动清空——因为你会先把每条 AC 翻译成一个测试名，翻译不出来的当场就暴露了。

**⑤ 一个叙事上的事实修正**：这两张票的 issue **至今仍是 OPEN**（`gh issue view 14/15`）。代码合进了 master，票没关。所以"做完就关票"并不是当时的实际行为，它只是 `simple-loop` 模板里的一句约定。

### 7.3 三个根因（都不是"agent 不听话"）

**根因一：约束是形容词，不是检查点。**

`implement` 技能里那句 "Use /tdd where possible" 之所以被绕过，是因为 "where possible" 是一个 agent 自己就能宣布成立的豁免。它不是拒绝执行，它是**在你的措辞里找到了合法出口**。

**根因二：粒度和约束不匹配。**

一次 iteration = 一次 agent 启动 = 一个 commit。你要求它在这一次启动里做完 11 条验收标准（#14）和 7 条（#15），它唯一可行的策略就是"全写完 → 补测试 → 跑通 → 提交"。**TDD 要的是小步提交的权限，而流程只给了它一次提交的机会。**

**根因三：没有任何一方会因为它跳步而失败。**

测试最终是绿的，AC 的勾也打了。跳步没有代价。一个只有结果检查的系统，永远只能得到"结果正确"的行为——而在这两票上，"结果正确"和"测试驱动"恰好可以同时成立，所以问题被完美地藏住了。

### 7.4 一句话总结

**agent 不会违反你写下的规则，它只会违反你没写成检查的规则。**

---

## 8. 把 TDD 变成门禁：四层，由弱到强

原则：**每一层都要让"跳步"产生一个可见的、机器判定的失败。** 只做 8.1，等于什么都没做。

### 8.1 第一层：prompt 里的硬顺序（弱，但必要）

把形容词换成**带输出要求的祈使句**：

```markdown
<!-- 弱：会被 "where possible" 豁免 -->
尽量用 TDD。先写测试。

<!-- 强：有可执行顺序、有必须留下的产物、有禁止项 -->
严格按以下顺序，任何一步没做到就停下并在 issue 上留言，不要继续。

对每一条验收标准，重复一次：
1. 在 issue 里回复第 N 条 AC 的原文
2. 写一个测试，只断言这一条的**外部行为**
   （通过 public 接口观察；不测 private/static 工具方法；不断言调用次数或顺序）
3. 运行 `mvn -q -Pno-containers -Dtest=<类名> test`，
   把**真实的失败输出**追加到 `docs/red/issue-<号>.md`
4. 只写让这一步变绿的最小代码
5. 运行同一命令，把通过输出追加到同一个文件
6. `git commit -m "red: <AC 一句话>"` 只包含测试文件
   `git commit -m "green: <AC 一句话>"` 只包含实现文件

禁止：
- 第 N 条还没有红色记录时，写第 N+1 条的测试
- 一个 commit 同时包含测试文件和实现文件
- 修改任何已有测试的断言来让新代码通过
```

`docs/red/*.md` 是这一层唯一的真价值：它把"你说是 TDD"变成"这里有 N 段真实的红色输出"。人扫一眼就知道真假。

### 8.2 第二层：分票（中，最省力）

把一票拆成两票，用**两次 `run()`**，第二个 agent 看不到第一个的对话：

- 票 A：「为 #14 的 11 条验收标准写测试，全部必须是红的。不许写实现。」
- 票 B：「让 #14 的测试变绿。不许修改测试。」

这比任何措辞都硬，因为票 B 的 agent **没有改测试的动机来源**——它不知道你为什么那么写，只知道有个红灯要灭。

这也是"pre-agreed seams"真正落地的地方：**票 A 写下的测试，就是票 B 的契约。**

### 8.3 第三层：commit 协议 + 机器检查（强，推荐）

约定 `red:` / `green:` 前缀，让脚本去查。`tools/check-redgreen.sh`：

```bash
#!/usr/bin/env bash
# 检查一条分支上的红绿序列。
# 用法：bash tools/check-redgreen.sh [base] [target]
#   base   对比基线，默认 origin/master
#   target 被检查的分支，默认 HEAD（在沙箱 worktree 里跑时就是 agent 分支）
set -euo pipefail

base="${1:-origin/master}"
target="${2:-HEAD}"
fail=0
count=0

for sha in $(git rev-list --reverse "$base".."$target"); do
  subject=$(git log -1 --format=%s "$sha")
  changed=$(git show --name-only --format= "$sha")

  has_test=$(printf '%s\n' "$changed" | grep -cE '(^|/)src/test/' || true)
  has_main=$(printf '%s\n' "$changed" | grep -cE '(^|/)src/main/' || true)

  case "$subject" in
    red:*)
      [ "$has_main" -eq 0 ] || { echo "x $sha 是 red 提交却改了 src/main/"; fail=1; } ;;
    green:*)
      [ "$has_test" -eq 0 ] || { echo "x $sha 是 green 提交却改了 src/test/"; fail=1; } ;;
    *)
      echo "! $sha 没有 red/green 前缀：$subject" ;;
  esac
  count=$((count + 1))
done

[ "$count" -ge 2 ] || { echo "x 提交数不足（红绿至少两次），实际 $count"; fail=1; }

shopt -s nullglob
red_files=(docs/red/issue-*.md)
[ ${#red_files[@]} -gt 0 ] || { echo "x 没有任何 docs/red 记录"; exit 1; }

exit "$fail"
```

落盘后 `chmod +x tools/check-redgreen.sh`，在分支上跑：

```bash
# 在沙箱 worktree 里（HEAD 就是 agent 分支）
bash tools/check-redgreen.sh master

# 在宿主的 master 上检查某条 agent 分支
bash tools/check-redgreen.sh master agent/issue-14
```

**能抓到什么**："一个 commit 装下 31 个文件"这种形状，在红绿协议下根本走不通。这就是把 #14 那种产物从"可能"变成"不可能"。

**抓不到什么**：`red:` commit 里的测试是不是真的红。要抓这个只能 checkout 后真跑一遍，成本高，放 CI 里做。

### 8.4 第四层：review agent（补 AC 覆盖漏项）

用 `sequential-reviewer` 模板，或者单独跑一次 `run()`，prompt 只干一件事：

```markdown
你不是来实现的。你是审的。

!`gh issue view {{ISSUE_NUMBER}} --json body,comments`

1. 把验收标准逐条列出来（含已勾选的）
2. 对每一条，指出证明它的测试文件与方法名；找不到就写「无」
3. 检查是否有测试断言了验收标准明令禁止断言的东西
4. 检查是否有 public 接口之外的测试（private / static 工具方法 / 直接查库 / 断调用次数）
5. 输出 <review> 包裹的 JSON：{ gaps: [{criterion, reason}], violations: [...] }

一条都不许放过，宁可误报。
```

这一层能抓住 #14 的 AC#10 违反和 #15 的回滚测试缺失，因为**它只干一件事**，不会被"先把功能做出来"的冲动带走。

### 8.5 四层的能力边界

| 层 | 抓到跳步的方式 | 抓不到 |
| --- | --- | --- |
| 1. prompt | 靠 `docs/red/` 产物 + 人工扫一眼 | agent 编造红色输出 |
| 2. 分票 | 结构上没有动机改测试 | 票 A 写的测试本身是不是"测外部行为" |
| 3. 脚本 | commit 形状、文件归属、是否有红色记录 | 红 commit 是否真红 |
| 4. review | AC 覆盖漏项、禁止项违反 | 需要人判断的"这个 seam 对不对" |

---

## 9. 本仓库落地

### 9.1 目录约定

```text
.sandcastle/
  main.mts        # 入口
  opencode-v2.ts  # OpenCode v2 会话适配层（见 6.4 / 文末附录）
  prompt.md       # 七条铁律
  package.json    # {"private": true, "type": "module"}
  .gitignore      # .env / worktrees/ / logs/
  .env            # 凭据（不提交）
  worktrees/      # 自动生成，不提交
  logs/           # 自动生成，不提交
docs/
  red/
    issue-14.md   # 红色输出记录（agent 写，你审）
tools/
  check-redgreen.sh
```

### 9.2 bwrap 跑 Java 的两个现实问题

**问题一：Maven 依赖缓存。**

bwrap 给沙箱一个私有 home，而沙箱里的 `HOME` 固定是 `/home/agent`（源码常量 `SANDBOX_HOME`）。所以 `~/.m2` 默认是空的——每次 run 都要重新下载全部依赖。

解决：把缓存目录挂进去，并**用绝对路径**显式指定仓库位置（这样就不依赖 `HOME` 是什么）。`bwrap()` 的挂载要求 `sandboxPath === hostPath`，所以路径两边写一样。

```bash
# 1) 在宿主上准备一个专用缓存（不要直接用 ~/.m2，避免 agent 污染）
mkdir -p ~/.cache/sandcastle/m2
cd ~/code/writeLab/backend
mvn -q -Dmaven.repo.local="$HOME/.cache/sandcastle/m2" dependency:go-offline
```

```ts
sandbox: bwrap({
  mounts: [
    {
      hostPath: "/home/xllcbc/.cache/sandcastle/m2",
      sandboxPath: "/home/xllcbc/.cache/sandcastle/m2",
    },
    {
      // JDK / Maven 装在 ~/.local/opt 时，bwrap 的私有 /home 会把它们藏掉
      hostPath: "/home/xllcbc/.local/opt",
      sandboxPath: "/home/xllcbc/.local/opt",
      readonly: true,
    },
  ],
}),
hooks: {
  sandbox: {
    onSandboxReady: [
      {
        command:
          "mvn -q -Dmaven.repo.local=/home/xllcbc/.cache/sandcastle/m2 " +
          "-Pno-containers -DskipTests compile",
        timeoutMs: 600_000,
      },
    ],
  },
},
```

> 如果沙箱里 `java -version` 报 `libjli.so: cannot open shared object file`，就是 bwrap 的私有 `/home` 把 `~/.local/opt/jdk-*` 藏了；上面的 `~/.local/opt` 只读 mount 解决这个问题。`mvn` 脚本同样需要这个 mount。

**问题二：Testcontainers 在沙箱里跑不起来。**

本仓库所有集成测试都是 `@SpringBootTest` + `@Testcontainers` + `PostgreSQLContainer("pgvector/pgvector:pg17")`，`pom.xml` 没配 failsafe，所以 `mvn test` 会连集成测试一起跑。沙箱里没有 Docker 守护进程 → 那 7 个类必然全红。

三条路：

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| **A. 分层跑（推荐）** | 沙箱里只跑不需要容器的测试 | 要把测试按"要不要 Docker"明确分层；agent 的绿灯不是全绿 |
| B. 挂 socket | 把 `/var/run/docker.sock` 挂进沙箱 | 等于把宿主机 root 交给 agent，隔离形同虚设 |
| C. DinD | 沙箱里跑 dockerd | 慢、重、容易炸 |

**A 的关键**：别靠类名猜。本仓库真正用 Testcontainers 的正好这 7 个：

```text
explanation/ExplanationApiIntegrationTest.java
practice/PracticeApiIntegrationTest.java
retrieval/CorpusSentenceImportIntegrationTest.java
retrieval/GroundingMaterialImportIntegrationTest.java
retrieval/KeywordLearningMaterialRetrieverTest.java
retrieval/VectorLearningMaterialRetrieverTest.java
vocabulary/VocabularyApiIntegrationTest.java
```

`VectorLearningMaterialRetrieverTest` 名字里没有 `Integration`，但它就带 `@Testcontainers`。所以用 profile 排除，而不是靠命名习惯：

```xml
<profile>
  <id>no-containers</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>**/*IntegrationTest.java</exclude>
            <exclude>**/*RetrieverTest.java</exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

验证排除真的生效：

```bash
cd backend
mvn -Pno-containers test 2>&1 | grep -E "^\[INFO\] Running "
```

期望：列表里**没有**上面那 7 个类。

然后 prompt 里把命令写死成 `mvn -q -Pno-containers test`，并明确告诉它：**容器测试不归你跑，红了也不许改。** 否则 agent 会去改测试让它变绿——那比"不写测试"更坏。

### 9.3 完整 `main.mts`（本仓库版）

```ts
import { run } from "@ai-hero/sandcastle";
import { bwrap } from "@ai-hero/sandcastle/sandboxes/bwrap";

import { opencodeV2 } from "./opencode-v2.ts";

const issue = Number(process.env.ISSUE ?? 0);
if (!issue) throw new Error("用法：ISSUE=14 npx tsx .sandcastle/main.mts");

await run({
  name: `issue-${issue}`,

  // Windows 上没有原生沙箱；走 WSL2 的 bwrap
  sandbox: bwrap({
    mounts: [
      {
        hostPath: "/home/xllcbc/.cache/sandcastle/m2",
        sandboxPath: "/home/xllcbc/.cache/sandcastle/m2",
      },
      {
        // JDK / Maven 装在 ~/.local/opt 时，bwrap 的私有 /home 会把它们藏掉
        hostPath: "/home/xllcbc/.local/opt",
        sandboxPath: "/home/xllcbc/.local/opt",
        readonly: true,
      },
    ],
  }),

  agent: opencodeV2("opencode-go/deepseek-v4.1-flash", {
    env: { OPENCODE_API_KEY: process.env.OPENCODE_API_KEY ?? "" },
  }),

  promptFile: ".sandcastle/prompt.md",
  promptArgs: { ISSUE_NUMBER: issue },

  // 一次只做一票
  maxIterations: 1,

  // 一票一条分支：可审计、可并行、不碰你的工作区
  branchStrategy: { type: "branch", branch: `agent/issue-${issue}` },

  hooks: {
    sandbox: {
      onSandboxReady: [
        {
          command:
            "mvn -q -Dmaven.repo.local=/home/xllcbc/.cache/sandcastle/m2 " +
            "-Pno-containers -DskipTests compile",
          timeoutMs: 600_000,
        },
      ],
    },
  },
});

console.log(`跑完先审计：bash tools/check-redgreen.sh master agent/issue-${issue}`);
```

> `opencodeV2()` 来自 `.sandcastle/opencode-v2.ts`（见 6.4 与文末附录）。它只支持 opencode v2；`@ai-hero/sandcastle` 固定 `0.14.0`。

### 9.4 完整 `prompt.md`（七条铁律）

```markdown
# 你在一票活上工作

仓库：WriteLab（Java 25 / Spring Boot / Maven）。
领域语言在 `CONTEXT.md`，架构决定在 `docs/adr/`。变量名、测试名、commit message 都用那里的词。

## 这一票

!`gh issue view {{ISSUE_NUMBER}} --json number,title,body,labels`

## 这一票已有的红色记录（为空说明还没开始）

!`cat docs/red/issue-{{ISSUE_NUMBER}}.md 2>/dev/null || echo "(空)"`

## 本分支已有的提交

!`git log --oneline {{TARGET_BRANCH}}..HEAD`

## 铁律（违反任何一条，立即停下并在 issue 上说明）

1. **一次只做一条验收标准。** 按 body 里的顺序，从第一条未完成的开始。
2. **先红后绿。** 写测试 → 跑 → 把**真实输出**追加到 `docs/red/issue-{{ISSUE_NUMBER}}.md` → 才允许写实现。
3. **测试只测外部行为。** 通过 public 接口观察。禁止：测 private/static 工具方法、直接查库验证、断言某个依赖被调用了几次或顺序如何。
4. **验收标准是唯一的测试清单。** 每条 AC 必须能对应到一个测试方法名。对应不上的，在 issue 里留言说明，不要自己发明行为。
5. **不许改已有测试的断言来让新代码通过。** 要改，先在 issue 里说清为什么原断言是错的。
6. **绿色 = `mvn -q -Pno-containers test` 通过。** 容器测试（`*IntegrationTest`、`*RetrieverTest`）不归你跑，红了也不许改。
7. **两个 commit：** `red: <AC 摘要>` 只含测试文件；`green: <AC 摘要>` 只含实现文件。文档改动并入 `green`。

## 收尾

全部 AC 都有对应红绿提交后：

1. `bash tools/check-redgreen.sh {{TARGET_BRANCH}}` 必须通过
2. 在 issue 里评论：逐条 AC + 对应测试方法名 + 红/绿 commit sha
3. 关 issue

没有任何可做的了，输出：

<promise>COMPLETE</promise>
```

### 9.5 附录：Docker 路线（内存允许时再用）

如果你哪天真需要 VM 边界，或者沙箱里必须跑 Testcontainers：

```bash
# 1) 装 Docker Desktop 并启用 WSL integration（Settings → Resources → WSL integration）
# 2) 限制内存（Windows 用户目录下 .wslconfig）
```

```ini
[wsl2]
memory=6GB
swap=2GB
```

```bash
# 3) 用 docker 模板初始化（生成的 Dockerfile 会把 opencode 装进镜像）
npx @ai-hero/sandcastle init --sandbox docker --agent opencode \
  --template simple-loop --issue-tracker github-issues --create-label false
npx @ai-hero/sandcastle docker build-image --image-name sandcastle:writelab
```

改过 Dockerfile 后必须重建镜像，然后 `main.mts` 里用同一个名字：

```ts
sandbox: docker({ imageName: "sandcastle:writelab" }),
```

本仓库要 JDK 25 + Maven，`init` 给的 Node 22 镜像不够，需要在模板基础上改（**保留非 root 用户、git、gh、agent CLI 在 PATH 上**）：

```dockerfile
FROM eclipse-temurin:25-jdk

RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl jq ca-certificates maven unzip \
    && rm -rf /var/lib/apt/lists/*

# gh（issue tracker 用），按官方 apt 源安装
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
      > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y gh && rm -rf /var/lib/apt/lists/*

# Node 22（opencode CLI 需要）
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*

# 非 root 用户：Claude Code / opencode 都不建议 root 跑
RUN useradd -m -s /bin/bash agent
USER agent
ENV PATH="/home/agent/.local/bin:/usr/local/bin:${PATH}"
RUN npm install -g opencode-ai@latest
WORKDIR /home/agent/repo
```

> 直接手写容易漏：`init` 的模板会把 `{{ISSUE_TRACKER_TOOLS}}` 替换成 gh 的安装与登录配置。**先 init 生成，再在模板上改**，比从零写安全。

---

## 10. 排错表

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| `NativeSandboxUnavailableError`（bwrap） | 没装 bubblewrap，或 AppArmor 限制 user namespace | 第 2.3 节：`apt install bubblewrap` + AppArmor profile |
| `init` 报目录已存在 | `.sandcastle/` 已存在，防止覆盖你的修改 | `rm -rf .sandcastle` 后重来 |
| 5 分钟内 agent 完全没输出 | 凭据没进沙箱（原生沙箱看不到宿主登录态） | 检查 `.sandcastle/.env` 里的 `OPENCODE_API_KEY` / `ANTHROPIC_API_KEY` |
| 跑完 HEAD 有奇怪改动 | 用了 `head` 策略 | 换 `merge-to-head` 或 `branch` |
| `AgentIdleTimeoutError` | agent 卡住，或子进程占着 stdout 不放 | 真卡住调 `idleTimeoutSeconds`；打完信号赖着不退是 `completionTimeoutSeconds`（默认 60） |
| 沙箱里 `mvn: command not found` | WSL 原生 Maven 没装，或还在用转发 Windows 的 wrapper | 第 2.4 节：装原生 JDK/Maven，`which mvn` 不能是 wrapper |
| 沙箱里 `mvn` 每次重下依赖 | bwrap 的私有 home 里没有 `~/.m2` | 第 9.2 节：挂缓存 + `-Dmaven.repo.local` |
| 沙箱里测试全红 | Testcontainers 需要 Docker 守护进程 | `-Pno-containers`，并明确告诉 agent 别管容器测试 |
| 一个 commit 装下 30 个文件 | prompt 没约束提交粒度 | 第 8.1 / 8.3 层 |
| agent 改了测试让它绿 | 你没禁止，且它只知道"要绿" | prompt 铁律第 5 条 + review agent 第 4 项 |
| `SessionCaptureError: Unexpected positional argument` | 还在用上游 v1 的 `opencode export/import`，或没走 `opencodeV2()` | 用 `.sandcastle/opencode-v2.ts`（见 6.4） |
| `SessionCaptureError: ... Managed service port 49374 ... already in use` | 沙箱里的 export/import 没加 `--standalone`，和宿主 opencode 服务撞端口 | 用 `opencodeV2()`；它已带 `--standalone`（见 6.4） |
| `{{KEY}}` 没被替换 | 用了内联 `prompt` 而不是 `promptFile` | 换 `promptFile` |
| 找不到 `promptFile` | 相对 `process.cwd()` 解析，不是相对 `cwd` | 在仓库根跑，或写绝对路径 |
| 整次 run 因为一条命令失败 | `` !`cmd` `` 非 0 退出即 fail | 加 `|| true`，或写进 HTML 注释 |
| 关掉 issue 但代码没进主分支 | 用了 `branch` 策略且没合并 | `git merge agent/issue-N` 后再关（#14/#15 就是"代码合了、票没关"的反面） |
| 把仓库放 `/mnt/c` 后 bwrap 里什么都看不到 | 沙箱内 `/mnt` 被隐藏 | 仓库必须在 ext4（`~/code/...`） |
| `git status` 慢到 2 秒 | 跨 `/mnt/c` 的 9p 文件系统 | 用 `~/code` 副本，或 Windows 原生操作 |
| Docker 一开内存就见底 | Docker Desktop VM 常驻 | `.wslconfig` 限内存，或改走 bwrap |
| `git worktree` 残留 | 实验失败留下的 worktree | `git worktree remove .sandcastle/worktrees/<名字>` |

---

## 11. 学习路径与每票 SOP

### 第 1 天：让它跑起来，别管流程

1. 玩具仓库（`~/spike/sandcastle-toy`）里跑 `init` + 最小 `main.mts`。
2. 只观察三件事：worktree 建在哪、commit 落在哪条分支、`result.commits` 里有什么。
3. 三种 `branchStrategy` 各跑一次（第 4 章）。

**过关标准**：能不看本文说出 `head` 和 `merge-to-head` 的区别。

### 第 2 天：让它做一票真活

4. 在 WriteLab 里挑一票小的（比如只改文档的 `ready-for-human`），用 `simple-loop` 的 prompt 原样不改。
5. 跑完只看一件事：**它交了几个 commit。**

**过关标准**：如果能说出"它只交了一个 commit，因为流程只给了它一次机会"——你已经复现了 #14 的病因。

### 第 3 天：上约束

6. 按 9.4 换掉 prompt，把七条铁律加进去，再跑同一票。
7. 跑 `bash tools/check-redgreen.sh master agent/issue-N`。

**过关标准**：第一次大概率不通过——**不通过就是这次练习的成功**，看它是怎么绕的。

### 第 4 天：上审计

8. 加 8.4 的 review agent，让它去审 #15 那个**已经合并**的提交。
9. 预期它会报出两件事：`CorpusSentenceImporter` 的 static 方法测试、缺失的回滚测试。

**过关标准**：它报出了这两条，说明这套流程能抓住人的漏项——包括你自己的。

### 之后：并行

10. #14 和 #15 按原文就是"可并行"的。用两条 `branch` 策略的 `run()` 同时跑，看 `git worktree list` 里并排的两条分支。

### 每票 SOP（照抄）

```text
[ ] 1. 选一票 ready-for-agent 的 issue，读完全部 AC
[ ] 2. 把每条 AC 翻译成一个测试方法名（翻译不出来的 AC，先停在 issue 里问）
[ ] 3. ISSUE=<号> npx tsx .sandcastle/main.mts
[ ] 4. 看 result.commits 数量：必须是偶数个（red/green 成对）
[ ] 5. bash tools/check-redgreen.sh master agent/issue-<号>
[ ] 6. 人审：读 docs/red/issue-<号>.md，确认红色输出是真的
[ ] 7. 跑 review agent（8.4），看 gaps / violations
[ ] 8. 自己跑一遍 mvn -Pno-containers test + 容器测试
[ ] 9. git merge agent/issue-<号>，推远端
[ ] 10. Windows 侧 fetch，IDEA 里做最终审阅
```

---

## 附：OpenCode v2 适配层

`opencode-v2.ts` 只做一件事：把上游 `opencode()` 的 session 导出/导入命令换成 v2 语法。它不改 `run`、流解析、权限参数、session ID 提取。

> 前提：本仓库的 `.sandcastle/package.json` 写 `{"private": true, "type": "module"}`；玩具仓库用根 `package.json`。`@ai-hero/sandcastle` 是 ESM-only，CJS 下会报 `ERR_PACKAGE_PATH_NOT_EXPORTED`。

```ts
import {
  opencode,
  type AgentProvider,
  type BindMountSandboxHandle,
  type ExecResult,
  type OpenCodeOptions,
} from "@ai-hero/sandcastle";
import { mkdir } from "node:fs/promises";
import { dirname, posix } from "node:path";

type OpenCodeAgent = ReturnType<typeof opencode>;
type OpenCodeStorage = NonNullable<AgentProvider["sessionStorage"]>;
type CaptureArgs = Parameters<OpenCodeStorage["captureToHost"]>[0];
type ResumeArgs = Parameters<OpenCodeStorage["resumeIntoSandbox"]>[0];

const shellEscape = (value: string) => `'${value.replaceAll("'", "'\\''")}'`;

const failureReason = (result: ExecResult) =>
  result.stderr.trim() || `opencode 退出码 ${result.exitCode}`;

const sandboxSessionPath = (
  handle: BindMountSandboxHandle,
  exportDir: string | undefined,
  sessionId: string,
) => posix.join(exportDir ?? handle.sandboxTmpdir ?? "/tmp", `${sessionId}.json`);

function hostSessionPath(
  storage: OpenCodeStorage,
  hostCwd: string,
  sessionId: string,
): string {
  const hostPath = storage.hostSessionFilePath(hostCwd, sessionId);
  if (!hostPath) {
    throw new Error(`无法确定 session 的 host 存档路径（id=${sessionId}）`);
  }
  return hostPath;
}

async function requireOpencodeV2(
  handle: BindMountSandboxHandle,
  cwd: string,
): Promise<void> {
  const result = await handle.exec("opencode --version", { cwd });
  const output = `${result.stdout}\n${result.stderr}`.trim();
  const match = output.match(/(\d+)\./);
  const major = match ? Number(match[1]) : Number.NaN;

  if (result.exitCode !== 0 || !Number.isInteger(major) || major < 2) {
    throw new Error(
      `不支持的 opencode 版本 ${output || "<empty>"}：本地适配层只支持 v2（需要 opencode session export/import）`,
    );
  }
}

function makeCaptureToHost(storage: OpenCodeStorage, exportDir?: string) {
  return async ({
    hostCwd,
    sandboxCwd,
    sessionId,
    handle,
  }: CaptureArgs): Promise<void> => {
    await requireOpencodeV2(handle, sandboxCwd);

    try {
      const sandboxPath = sandboxSessionPath(handle, exportDir, sessionId);
      const command = `opencode session export ${shellEscape(sessionId)} --standalone > ${shellEscape(sandboxPath)}`;
      const result = await handle.exec(command, { cwd: sandboxCwd });

      if (result.exitCode !== 0) {
        throw new Error(
          `\`${command}\` exited with code ${result.exitCode}: ${failureReason(result)}`,
        );
      }

      const hostPath = hostSessionPath(storage, hostCwd, sessionId);
      await mkdir(dirname(hostPath), { recursive: true });
      await handle.copyFileOut(sandboxPath, hostPath);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      throw new Error(
        `session 未保存（id=${sessionId}），本次 run 不能 resume；原因：${reason}`,
      );
    }
  };
}

function makeResumeIntoSandbox(storage: OpenCodeStorage, exportDir?: string) {
  return async ({
    hostCwd,
    sandboxCwd,
    sessionId,
    handle,
  }: ResumeArgs): Promise<void> => {
    await requireOpencodeV2(handle, sandboxCwd);

    const hostPath = hostSessionPath(storage, hostCwd, sessionId);
    const sandboxPath = sandboxSessionPath(handle, exportDir, sessionId);
    await handle.exec(`mkdir -p ${shellEscape(posix.dirname(sandboxPath))}`);
    await handle.copyFileIn(hostPath, sandboxPath);

    const result = await handle.exec(
      `opencode session import ${shellEscape(sandboxPath)} --standalone`,
      { cwd: sandboxCwd },
    );
    if (result.exitCode !== 0) {
      throw new Error(
        `session 导入失败（id=${sessionId}）；原因：${failureReason(result)}`,
      );
    }
  };
}

/**
 * opencode() with the session commands OpenCode v2 actually supports.
 *
 * Upstream sandcastle 0.14.0 still calls the v1 top-level `export` / `import`.
 * This wrapper only swaps those two commands; run, stream parsing, permissions
 * and session-id extraction stay upstream.
 */
export function opencodeV2(
  model: string,
  options: Omit<OpenCodeOptions, "captureSessions"> = {},
): OpenCodeAgent {
  const base = opencode(model, { ...options, captureSessions: true });
  const storage = base.sessionStorage;
  const exportDir = options.sessionStorage?.sandboxExportDir;

  return {
    ...base,
    sessionStorage: {
      ...storage,
      captureToHost: makeCaptureToHost(storage, exportDir),
      resumeIntoSandbox: makeResumeIntoSandbox(storage, exportDir),
    },
  };
}
```

为什么不是 `patch-package` / 本地 fork / 上游 PR：

| 方案 | 为什么不选 |
| --- | --- |
| `patch-package` | 升级或重装 `node_modules` 后补丁容易失效；补丁文件不可读 |
| 本地 fork | 要维护构建；为一个 30 行适配层不值得 |
| 上游 PR | issue #1509 仍 open，维护者未响应；当前目标是本地满血，不是贡献流程 |

验证命令：

```bash
npx tsx .sandcastle/verify-adapter.test.ts   # 零成本，假 handle
npx tsx .sandcastle/verify-session.ts        # 3 次真实调用：capture / resume / fork
```

实测证据（2026-11-18，bwrap + `opencode-go/deepseek-v4.1-flash`）：

```text
run #1 session: ses_e13f204a6ffeCif5UxQ34alRY5
captured: /home/xllcbc/.local/share/opencode/sandcastle-sessions/ses_e13f204a6ffeCif5UxQ34alRY5.json
resume session: ses_e13f204a6ffeCif5UxQ34alRY5   # 同一个 ID
resume stdout: 菠萝台风
fork session:   ses_e13f1b5f2ffe1F37ECF4DQxwrw    # 新 ID
fork stdout:    菠萝台风
```

---

## 附：一页速查

```ts
// 最小可跑
await run({
  agent: opencode("..."),
  sandbox: bwrap(),
  promptFile: ".sandcastle/prompt.md",
});
```

| 我想… | 用这个 |
| --- | --- |
| 换 agent | `claudeCode("...")` / `codex("...")` / `cursor(...)` / `pi(...)` / `copilot(...)` / `opencode(...)` |
| 不隔离（危险，仅调试） | `sandbox: noSandbox()` |
| 同时跑多票 | 多次 `run()`，各带不同 `branch` |
| 拿结构化回执 | `output: Output.object({ tag, schema })`，要求 `maxIterations === 1` |
| 干完就停 | prompt 里写 `<promise>COMPLETE</promise>`，或自定义 `completionSignal` |
| 每次带最新 issue | prompt 里 `` !`gh issue view ...` `` |
| 预装依赖 | `hooks.sandbox.onSandboxReady` + `timeoutMs` |
| 续上一轮对话 | `result.resume("...")`；分叉用 `result.fork("...")` |
| 用 opencode v2 | `opencodeV2("...")`（`.sandcastle/opencode-v2.ts`，见 6.4） |
| 给沙箱额外路径 | `bwrap({ mounts: [{ hostPath, sandboxPath: hostPath }] })` |
| 重建 Docker 镜像 | `npx @ai-hero/sandcastle docker build-image --image-name <name>` |

### 官方文档

- 总入口：<https://www.aihero.dev/sandcastle>
- 仓库 `docs/guide/`：`getting-started.md`、`core-concepts.md`、`glossary.md`、`run.md`、`prompts.md`、`iterations.md`、`hooks.md`、`branch-strategies.md`、`sandbox-providers.md`、`agent-providers.md`、`agent-sessions.md`、`structured-output.md`、`init.md`、`cli.md`、`run-logs.md`

### 三个最容易被记住的结论

1. **一个 iteration = 一个 task = 一次红-绿。** 超过这个粒度，TDD 就变成了事后补测试。
2. **"where possible" 不是约束，是豁免。** 约束必须带产物（`docs/red/*.md`）、带命令、带禁止项。
3. **agent 不会违反你写下的规则，它只会违反你没写成检查的规则。**
