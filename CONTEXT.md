# WriteLab 英语写作教练

一个面向单个中文母语学习者的 LLM 辅助英语学习工具。第一版负责批改英文写作并保存本地练习记录；实时口语练习属于后续模式。

## 领域语言

**Writing Practice（写作练习）**：
学习者提交一段英文写作，以获得纠错和解释性反馈。
_避免_：作文、作业、练习

**Practice Attempt（练习记录）**：
一次写作提交，以及针对该提交生成的反馈。
_避免_：聊天、会话、运行

**Correction（纠错稿）**：
在保留学习者原意的前提下，修复语言问题的写作修改版本。
_避免_：答案、生成文本

**Learning Issue（学习问题）**：
一次写作练习中发现的具体语言问题，包含类别、严重程度、原句的最小英文修改稿和面向学习者的解释。
_避免_：错误、毛病

**Natural Revision（自然改写稿）**：
在保留学习者原意的前提下，提升流畅度和表达风格的替代版本。它不同于纠错稿。
_避免_：纠错稿、重写

**Estimated Level（估计等级）**：
根据一次练习记录推断出的非权威 CEFR 等级，仅用于学习反馈。
_避免_：分数、认证、精确等级

**Teacher Explanation（教师讲解）**：
针对选定的学习问题，以流式方式生成的中文解释。它不同于纠错稿。
_避免_：反馈、答案

**Vocabulary Suggestion（词汇建议）**：
助手认为对学习者写作有帮助而提供的单词或短语。
_避免_：词表、答案

**Dictionary Entry（词典条目）**：
本地预置的英文词条，包含音标、词性、中文释义和英文例句；批量导入的条目可能没有例句或音标。
_避免_：词典数据、词库

**Word Lookup（查词）**：
通过 Dictionary Entry 获取词义的行为；由模型生成反馈时触发的称为自动查词，由学习者直接触发的称为手动查词。
_避免_：查字典、搜索词

**Tool Invocation（工具调用记录）**：
一次模型调用应用工具的记录，包含工具名、参数、结果状态和耗时。工作流（应用代码）自行调用工具不产生 Tool Invocation。
_避免_：工具日志、调用历史

**Grounding Material（依据材料）**：
本项目自建的英语用法条目，按学习问题类别打标签，供教师讲解在生成前检索并引用出处。
_避免_：知识库、参考资料、规则文档

**Corpus Sentence（语料例句）**：
来自真实句库、包含指定词形的完整英文句子，用于替换词汇建议的例句并随句标明来源与许可；它不同于 Dictionary Entry 的英文例句。
_避免_：语料库例句、引用句、样例句

**User Document（用户文档）**：
学习者上传、用于在教师讲解中被检索引用的原始文本材料；它不同于依据材料和语料例句。
_避免_：知识库、上传文件、资料库

**Document Chunk（文档分块）**：
用户文档按结构切分后的检索单元，带标题路径与在文档中的起止位置。
_避免_：段落、片段

**Document Citation（文档引用）**：
教师讲解展示的文档引用，包含文档名、标题路径和被引用文本快照；底层溯源仍记在检索命中记录。
_避免_：依据材料、引用句

**Document Replacement（文档替换）**：
重新上传内容变化的同名文档时，在同一篇 User Document 内建立新 generation，全部就绪后再切换并删除旧 generation 的过程；它不同于上传一篇新文档。
_避免_：更新、覆盖、新版本

**Vector Index（向量索引）**：
User Document 当前 active generation 的每个 Document Chunk 都已用当前 embedding 模型写入 512 维向量的状态；缺少向量或向量过期的文档不参与真实检索。
_避免_：索引状态、embedding 状态

**Retrieval Hit（检索命中记录）**：
一次应用侧检索针对某份练习记录留下的溯源记录，包含命中种类、被引用记录、名次以及跳过或降级原因；它不同于 Tool Invocation——后者记录模型调用应用工具，前者记录应用为模型准备材料，两者不得混用。
_避免_：命中日志、检索日志、工具调用记录

**Saved Vocabulary（已保存词汇）**：
学习者明确选择保留下来供以后复习的词汇建议。
_避免_：建议词、自动词汇

### 教师对话

**Teacher Conversation（教师对话）**：
学习者针对一份 Practice Attempt 与教师进行的有序多轮问答，历史落库并可回放；它不同于 Practice Attempt，也不同于跨练习的 Learning Review。
_避免_：答疑、聊天、会话

**Teacher Conversation Turn（教师对话回合）**：
一次学习者发言以及教师随后的回应，是 Teacher Conversation 的有序组成单位。
_避免_：消息、记录、轮次

**Agent Step（代理步骤）**：
Teacher Conversation 或 Speaking Session 回合内由模型驱动的一次决策与动作，可能调用工具或给出最终回答；只存在于 agent 场景，工作流步骤不属于此概念。
_避免_：工具调用、执行记录、步骤日志

### 口语练习

**Speaking Practice（口语练习）**：
学习者通过语音与教师进行的对话式练习，其产物是 Speaking Session；它不同于书面的 Practice Attempt。
_避免_：语音聊天、口语会话

**Speaking Session（口语练习记录）**：
一次 Speaking Practice 的完整记录，包含语音转写回合与发现的学习问题，独立于 Practice Attempt，可回放。
_避免_：口语会话、语音记录、录音

### 学习复盘

**Learning Review（学习复盘）**：
跨多份 Practice Attempt 汇总反复出现的学习问题与估计等级变化，并产出 Study Plan 的过程；它是工作流，不是对话。
_避免_：诊断报告、学情分析

**Study Plan（学习计划）**：
Learning Review 的产物，按优先级排列薄弱项及对应的针对性行动。
_避免_：学习建议、提升方案

**Targeted Practice（针对性练习）**：
由 Study Plan 驱动、聚焦特定薄弱项的一次写作任务及其后续 Practice Attempt。
_避免_：自适应练习、定制练习
