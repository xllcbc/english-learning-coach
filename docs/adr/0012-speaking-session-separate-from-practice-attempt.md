# Speaking Session 独立于 Practice Attempt

## 背景

口语练习没有书面提交，因此不能复用 Practice Attempt；`CONTEXT.md` 也明确不把口语术语混入书面练习。Teacher Conversation 锚定在单份 Practice Attempt 上，口语没有这个锚点，必须有自己的根。需要决定口语数据是否持久化以及如何建模。

## 决策

1. **独立聚合根。** 新增 Speaking Session：一次 Speaking Practice 的完整记录，包含语音转写回合与发现的学习问题，可回放。
2. **不复用 Practice Attempt。** Speaking Session 与 Practice Attempt 并列，互不替代。
3. **并列而非子集。** Speaking Session 与 Teacher Conversation 并列，共用回合与 Agent Step 机制，但各自是独立聚合根。
4. **学习问题独立记录。** 口语侧的学习问题独立存储，不挂到 `practice_attempt` 的聚合映射上。
5. **诊断先只读写作。** Learning Review 第一版只读 Practice Attempt；把口语数据接入 Learning Review 作为后续增量。

## 与既有 ADR 的关系

- **ADR-0005（User Document 独立语料）**：体现同样的「新概念建独立实体、不混入既有语料」原则；本 ADR 把该原则用于口语记录。
- **ADR-0010（Agent Step 溯源）**：Speaking Session 的回合同样产生 Agent Step。

## 后果

- 产生第二种产物，工作量与表结构增加；换来可回放、可评测与未来统一诊断。
- 在口语接入 Learning Review 之前，Learning Review 只反映书面练习。
