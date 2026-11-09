# Issue Tracker：GitHub

本仓库的 issue 和 spec 都发布到 GitHub Issues，使用 `gh` CLI 操作。

## 常用操作

- 创建：`gh issue create --title "..." --body "..."`
- 查看：`gh issue view <number> --comments`
- 列表：`gh issue list --state open`
- 评论：`gh issue comment <number> --body "..."`
- 标签：`gh issue edit <number> --add-label "..." --remove-label "..."`
- 关闭：`gh issue close <number> --comment "..."`

## PR 作为需求入口

PR 作为请求入口：否。

## 技能约定

- “发布到 Issue Tracker”：创建 GitHub Issue。
- “获取相关工单”：执行 `gh issue view <number> --comments`。
- Wayfinder 的 map 和 ticket 均使用 GitHub Issues；阻塞关系优先使用 GitHub 原生依赖。

## Wayfinder 操作

- Map 是带 `wayfinder:map` 标签的 GitHub Issue，保存 Destination、Notes、Decisions so far、Not yet specified 和 Out of scope。
- Ticket 是 Map 的子 Issue，带 `wayfinder:<type>` 标签。
- 阻塞关系使用 GitHub 原生 issue dependencies；不可用时才使用 `Blocked by` 正文约定。
- 领取 Ticket 时先分配给自己；完成时先评论结论、关闭 Issue，再更新 Map 的 Decisions so far。
