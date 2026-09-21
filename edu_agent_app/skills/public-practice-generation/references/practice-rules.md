# 组题候选规则

## 输入规范化

- `topic` 去除首尾空白后必须非空，最长 200 个字符；不从候选题干猜主题。
- `difficulty` 缺失时使用 `MEDIUM`，只接受 `EASY`、`MEDIUM`、`HARD`。
- `allowDifficultyFallback` 缺失为 false；只有用户未指定难度时 Runtime 才传 true，Java 先选中等题，不足时用同主题的简单、困难题补齐。明确难度和历史 Action 继续严格过滤，不放宽自动评分或权限。
- `requestedCount` 缺失时使用 5，只接受 1～20 的整数；布尔值不视为整数。

## QUESTION_SEARCH 候选

用户不需要知道题库标签。Runtime 用一次模型调用输出 `knowledgePoints` 名称列表（0～3 项、每项 1～80 字符），超纲或不确定时返回空列表，不得硬猜相近主题。空列表不检索、不执行提案脚本；非空时逐项调用 `KNOWLEDGE_SEARCH` 各取至多两个可靠命中，去重后把真实 ID 交给 `QUESTION_SEARCH`。预测名不要求与库内标签完全一致；ID 不能来自浏览器或模型。知识点检索为空时不查全库，模型或工具故障安全失败，全部调用沿用原有预算。关联题的重排用于排序，不以题干未复述标签名称为由丢弃；Java 继续验证最终领域条件，学生可见数量由 Java 最终确定。

脚本只接收 Runtime 从 `QUESTION_SEARCH` 安全投影得到的候选。每项只使用 `evidenceType`、`accessDecision`、`questionId` 和 `questionVersionId`；不得传递或输出题干、答案、解析、选项、签名 URL、Token 或其他敏感字段。`evidenceType` 必须为 `QUESTION`，`accessDecision` 必须为 `AUTHORIZED_BY_JAVA`，两个 ID 必须同时是 UUID。

候选身份是 `questionId + questionVersionId` 联合键。按工具返回顺序稳定去重，不随机重排；组合题保持根题整体，按一道主问题计数。若输入提供 `recentQuestionKeys`，在不改变候选内部相对顺序的前提下先保留非近期题，再追加近期题；这是软避让，不能突破硬过滤或虚构替代题。

最多保留 20 道主问题作为 Java 领域复核池；`requestedCount` 决定 Java 复核通过后最终截取的数量，不能据此提前截短复核池。可靠候选不足时返回实际数量，不扩大主题、明确指定的难度、学段、来源或题型；零候选不是提案，Runtime 只返回自然的学生提示，不得创建 Action。

教师私题、非初中题、非 `ACTIVE SYSTEM` 当前版本以及不可自动评分题不得进入正式候选。Python 结果只是身份提案，不是最终领域事实；Learning 必须在 pending action 写入前和确认时再次验证这些条件。

Java 继续拥有 T14.3 的 pending action、明确确认、过期、并发与幂等边界。Skill 不写平台数据库，不创建、保存、提交或评分练习。
