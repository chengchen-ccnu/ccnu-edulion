---
name: public-practice-generation
description: 当学生要求按主题、难度和数量从公共题库准备自主练习时使用；仅服务 Practice Coach 的 RECOMMEND_PRACTICE。
metadata:
  version: 1.1.0
---

# 公共题库自主组题

只处理 `PRACTICE_COACH` 的 `RECOMMEND_PRACTICE`。主题缺失时先澄清；未指定难度时优先 `MEDIUM`，不足时允许同主题的其他难度补齐；明确难度仍严格遵守。数量默认 5，允许 1～20 道主问题。组合题作为一道主问题并保持整体。

Runtime 先用一次结构化模型调用预测 0～3 个可能的知识点名称，再逐项用 `KNOWLEDGE_SEARCH` 召回真实知识点 ID，通过 `QUESTION_SEARCH` 获取关联题。超纲或不确定时返回空列表，不得替换成相近的初中主题；空列表直接返回无匹配说明，不检索或运行提案脚本。无可靠知识点命中不扩大到全库，模型失败不循环重试。正式候选必须是 `ACTIVE SYSTEM` 的初中数学公共题。尽量避开近期做过的题；数量不足时返回真实数量，不得静默扩大主题、明确指定的难度或来源。模型临时编写的例题不能写入正式练习。

需要规范化、筛选或组装候选时，先读取 [详细组题规则](references/practice-rules.md)，再把主题、难度、数量和 `QUESTION_SEARCH` 安全候选作为 JSON 输入调用 `scripts/validate_proposal.py`。只有脚本返回的结构化结果可形成组题提案；模型文字或自行构造的 JSON 不能替代脚本结果。

Java 负责候选二次复核、学生确认和正式创建。Skill 不替学生保存、提交、作答或评分。
