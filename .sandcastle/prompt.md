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
6. **绿色 = `cd backend && mvn -q -Pno-containers test` 通过。** 容器测试（`*IntegrationTest`、`*RetrieverTest`）不归你跑，红了也不许改。
7. **代码 AC 两个 commit：** `red: <AC 摘要>` 只含测试文件；`green: <AC 摘要>` 只含实现文件。**纯文档/工具 AC 一个 `docs: <AC 摘要>`**，只改 `docs/`、`tools/` 或 markdown，不碰 `src/main`、`src/test`；不要为了凑红绿去写假测试。文档改动并入 `green` 或单独的 `docs:`。

## 收尾

全部 AC 都有对应红绿提交（纯文档/工具 AC 有 `docs:` 提交）后：

1. `bash tools/check-redgreen.sh {{TARGET_BRANCH}}` 必须通过
2. 在 issue 里评论：逐条 AC + 对应测试方法名/文档路径 + commit sha；`docs:` 的 AC 只写文档路径和 sha
3. **不要关闭 issue。** 在评论里写明“等待人工合并 `agent/issue-<号>` 后再关闭”。

没有任何可做的了，输出：

<promise>COMPLETE</promise>
