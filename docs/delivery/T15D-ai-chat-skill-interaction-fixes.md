# T15D AI 会话与 Skill 交互修复交付说明

## 当前交付范围与版本（2026-09-21）

分支 `codex/t15d-ai-chat-skill-interaction-fixes`；现有 [PR #72](https://github.com/chengchen-ccnu/ccnu-edulion/pull/72) 目标 main，保持 Open，不合并。保留规划及已有实现历史，未新建分支、reset 或覆盖用户工作。本轮以旧 HEAD `31f43cd` 为审查起点；该提交的绿色 CI 不代表当前工作树。

实际外部 OpenAPI **0.24.2**、内部 Agent **1.6.2**、组题 Skill **1.1.0**。0.24.1/1.6.1 是此前零候选修复的中间版本，不是当前版本。仅修改学生独立 AI、普通题悬浮答疑、组题检索与确认及其测试文档；不实施 AI 生成题、课程播放器、HOMEWORK/EXAM、Skill 商店或 T12C.1。

## 根因与调用链

1. 原规则把学习前缀、礼貌语及请求尾句留在 topic。通用槽位清洗保留数学主题，识别数量 1～20，未指定难度保持空值；否定、引用、讨论如何出题与普通解题仍有回归测试。
2. 单次结构化模型预测 0～3 个知识点名称（每项 1～80 字符），逐项 KNOWLEDGE_SEARCH 召回至多两个真实 ID，再按这些 ID 约束 QUESTION_SEARCH，最多 20 个根题。预测名无需与标签逐字一致，不能由模型编造 ID。
3. 2026-09-20 发现强制非空预测可能将“椭圆积分”硬猜为圆几何。用户于 09-21 批准最小修正：允许空列表、禁止替换成近似初中主题；空列表不检索、不执行提案脚本，直接自然说明。该规划属于私有节点，不新增 Java HTTP DTO、模型循环或数据库表。
4. 关联题题干可能只包含公式，已有知识点关联约束时重排只排序，不因题干没有复述标签而丢弃；仍校验关联 ID。Java/Learning 继续验证 owner、公共 ACTIVE SYSTEM、当前版本、初中数学及可自动评分性。
5. 未指定难度才设置 `allowDifficultyFallback=true`：优先中等，不足再用同主题简单/困难题。明确难度和旧请求缺失字段仍严格过滤。Java 复核后的实际题量决定回答与卡片，不把来源数量视为可练习数量。
6. Python 零候选不输出 proposal；Java 复核归零不保存 Action；历史零候选/计数不一致 Action 查询和确认安全 404；前端不挂载空卡 DOM。
7. 用量报告仅对 `AGENT.RUN_NOT_TERMINAL` 最多提交六次，退避 1+2+3+4+5 秒，固定 reportId/载荷；其他冲突立即失败。数据库 run/report 唯一约束继续防重复计费。
8. 真实联调发现通用秘密字段检查把合法 `skillOffer.prompt` 误判，导致完成回答变 FAILED。现仅对严格事件校验后的精确路径放行字段名，其文本仍执行秘密/URL 检查，其他 prompt 字段仍拒绝；集成测试覆盖正常及恶意文本。

## API、状态与安全边界

先扩展外部/内部契约再生成客户端；没有手写重复前端 DTO。新增可选难度补齐复用 criteria_json。部署先升级 Learning/Agent Access，再升级 Python，避免旧严格校验器拒绝新字段。

- PATCH/DELETE 会话使用稳定 requestId、expectedRevision；改名校验标题与所有权，冲突 409 保留输入。删除复用 ARCHIVED，活动 run 为 409；列表、消息、详情、run、Action 与继续发送按 owner+ACTIVE 收口，安全 404。
- 浏览器只能传 GENERATE_AUTONOMOUS_PRACTICE，Java 映射受信内部名。显式选择、自然语言请求、有效单轮 Offer 同意是三类入口；eligibility、工具授权、预算、审计、脚本与 Java 二次复核都保留。
- Offer 与 Action 不同。Offer 生成阶段不执行组题 Skill/候选题检索；普通讲解本身仍可检索知识资料。紧邻下一轮同意才组题，拒绝、转移或错过一轮即失效，不作长期记忆。
- 最初 T15D 的 Agent V8 命令事实表仍保留，用于改名/归档重放；现有 create_request_id 不能表达不同命令。本轮补充修复没有新增迁移，也未修改已发布 Flyway。
- Java 是事实、授权及写入边界，Python 不直连 MySQL；题图仍走 AI_QUESTION_IMAGE 原上传/短期授权链。

## 页面行为与可读性

独立页发送按钮原位切换 Stop，沿用 SSE 取消与部分回答保存；两输入区 Enter 发送、Shift+Enter 换行并保护 IME。加号菜单只提供题图和生成自主练习，单轮选择可取消；粘贴文本不拦截，图片复用类型/大小/取消/卸载清理。

来源默认两条编号标签，其余通过有界浮层展开，支持区域悬停、点击、键盘聚焦、Escape 回焦、外部点击；顶部空间不足时向下展开。练习条件默认折叠，数量、难度与操作常显。只调整既有学生页面视觉与文案，不改导航信息架构。

修复悬浮窗 30 分钟超时后旧来源/Action 泄漏到空态，并添加 DOM 回归。公共方法、复杂检索/过滤、幂等、恢复和焦点逻辑已做中文注释审查；生成文件不手改注释。

## 已执行验证

| 项目 | 最终本地结果 |
|---|---|
| Python compileall + 完整 unittest（09-21） | 100 项：90 通过、10 按外部资产/Windows 条件跳过 |
| 全仓 Maven clean verify（09-20 16:30，含 Offer 新修复） | 9 模块成功；285 项，250 通过、35 条件跳过；Agent Access 58 项含 3 跳过 |
| 前端 generate:api、typecheck、全量 test、build（09-20） | 54 文件、355 测试通过；admin/student/teacher 构建成功 |
| 外部客户端重新生成（09-21） | 生成前后文件完全一致；提交后另跑 check:api |
| 内部契约 | Python 引用/正反例校验通过，openapi-typescript 解析 1.6.2 成功，临时输出仅在忽略目录 |
| 外部 OpenAPI Mock | Prism 成功加载当前契约并监听；不冒充真实环境 |
| git diff --check | 通过；提交前再检查 |

Java 覆盖空 proposal、复核归零、历史空 Action 404、权限/归档、非空幂等/并发和难度补齐；Python 覆盖清洗、预测落库、零/非零、1～20、Offer 与有限重试；前端覆盖两入口零卡/404/恢复、Stop/断线/卸载、菜单/单轮选择/键盘/粘贴、来源及成功焦点恢复。

### 真实页面结果

- 09-20 原二次函数句：主题正确、默认 5、补齐 true，Java actualCount=候选数组长度=1。确认成功、刷新恢复开始练习；进入真实答题器成功，空 courseId 原 500 已复验修复。
- 09-21 悬浮窗再次输入原句：仍为真实 1 道中等题，确认成功、刷新、转到独立深链恢复一致。
- 09-16 查得的 15 道召回池含 4 道解答题、10 道含解答子题的组合题、1 道可评分填空题。因此该池只有 1 道合格，不等于全题库只有 1 道；未放宽评分边界。09-21 Offer 同意组题实际返回简单 1 道，证明非中等题未被统一排除。
- 零候选：独立页及悬浮窗均真实验证自然说明、DOM 无卡、数据库无 Action、刷新恢复仍无卡。修正后的椭圆积分请求完成且提案脚本路径为空。
- Offer：09-21 讲解完整终态且没有 Skill/Action；“好的”执行来源 EXPLICIT 的 Skill。拒绝、改聊三角形及之后“好的”均不执行 Skill、不创建 Action。各新 run 的用量记录恰好一条，没有重复。
- 布局：桌面 1440×1000、手机 390×844；真实来源点击展开、Escape 回焦和外部点击关闭通过，手机浮层在可视区内。悬停移入/移出有自动化覆盖，未单独完成真实指针验收。
- 早期真实 Stop、改名、删除确认/归档深链、两个菜单、文本与 PNG 粘贴链路验收记录保留在 verification 历史。成功图片上传仍未验收通过；09-20 答题页题图服务不可用，未伪报成功。401/403/404/409/422/429/offline、文件错误与页面清理主要由自动化覆盖，不能声称本轮逐项真实故障注入。

## 精确数据清理（09-21）

未创建新数据库、表、账号、题库题目、课程、正式媒体或索引点。通过本地凭据包装器，仅按下列已记录会话 UUID 清理临时事实：
`97ce0c2d-0cd5-450e-a937-17045fbe9cdf`、
`845af58e-9d1d-4978-b73a-b89374a6e513`、
`679834dd-f397-4df8-a5b4-230a03ff95f0`、
`a150dc9d-6d51-4606-abae-4f21f7c8ca4a`、
`e8dd4d22-702a-4608-b600-7841a9d34cbc`、
`a0621cb4-4661-4b33-a3d5-139098efd6bb`、
`94c450b1-0f59-4be2-aa08-bc2ae5f2dcf4`、
`bbbeace1-17cc-435c-ab29-cb8f93fc2335`、
`69d34f70-0169-498a-89e2-7615e885f9fb`、
`7c5d7cf5-7ffc-4b78-951c-10946018b610`。

删除 10 会话、34 消息、17 run、352 事件、7 Action、16 Action 审计、9 Skill 审计、16 用量记录；逐表复核全部为 0。旧 run 缺失的一条报告没有伪造补录。没有本轮记忆、画像、媒体引用或索引 Outbox。

按 Action 反查清理 4 份自主练习/assessment、4 题快照、2 个 DRAFT attempt 及关联幂等记录；Learning 所有目标残留为 0。assessment ID：`984fd194-9d51-4796-8501-6adff21e564c`、`b8f6c344-abc2-4d28-9cda-ce7b04ce1a93`、`67ccf222-8ca4-485d-a9b2-298776ab3478`、`9f288559-bc47-49ec-b56f-8d28acdc0b9d`。

Redis DB6 精确 run/会话幂等键残留为 0，共享账号 quota 键也未发现；没有按 owner 清空其他数据。固定 Seed、既有题库和其他会话保留。正常登录态刷新属于共享认证会话，未按时间/owner 批量删除；本轮没有新登录或上传事实。临时审计脚本及运行日志只在忽略的 .m2 目录，不提交数据库导出。重新演示通过页面创建新随机会话即可。

## 环境、演示与回退

通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入既有 DPAPI 凭据；使用已有 `scripts/Invoke-T15DBrowserEnvironment.ps1` 启动本地服务，Python 检索需 `AGENT_ONLINE_RETRIEVAL_ENABLED=true` 与既有模型/Qdrant。没有新增秘密或 local.env 字段，本轮无需要删除的配置；不得把密码/Token 放入 local.env，应使用 DPAPI/部署 Secret。

演示：打开 /ai → 新会话 → 输入二次函数原句 → 检查主题/真实数量/来源前两条 → 确认创建 → 刷新 → 开始练习。题库单题打开悬浮窗重复；零题用椭圆积分请求；Offer 先说不明白并要求短讲解，再分别同意、拒绝、换话题。演示数据为临时事实，按新会话与 Action ID 清理，不复用上面的已删除 ID。

回退应用提交时使用普通前向 revert，不重写历史。先停止 Python 产生新可选字段，再回退 Java；存在带补齐条件的历史 Action 时保留兼容读取或等待其过期，不能直接降级严格反序列化器。V8 迁移不回写、不删除。图片环境、大 chunk 警告、模型语义召回仍可能偏离/不足，以及 Offer 标题可能保留较长讲解请求，是如实保留的风险；未实现 AI 编题补齐。

提交、push 与最新 HEAD CI 的最终状态以 PR #72 本轮更新为准，不使用旧 CI 绿灯替代。

## 工作树逐文件范围审查

原 38 个已跟踪修改与 3 个新增文件全部保留，另增加 Offer 实际缺陷修复，共 42 个文件；没有发现需要移除的无关修改。

| 文件 | 本任务关联 |
|---|---|
| `docs/api/agent-internal.yaml` | 契约、架构、页面职责与真实交付记录 |
| `docs/api/openapi.yaml` | 契约、架构、页面职责与真实交付记录 |
| `docs/architecture/agent-platform-design.md` | 契约、架构、页面职责与真实交付记录 |
| `docs/delivery/T15D-ai-chat-skill-interaction-fixes.md` | 契约、架构、页面职责与真实交付记录 |
| `docs/frontend/page-specs.md` | 契约、架构、页面职责与真实交付记录 |
| `docs/roadmap/backlog.md` | 契约、架构、页面职责与真实交付记录 |
| `docs/roadmap/verification.md` | 契约、架构、页面职责与真实交付记录 |
| `edu_agent_app/indexing/models.py` | 真实知识点 ID 关联检索，避免题干阈值误淘汰 |
| `edu_agent_app/indexing/qdrant_store.py` | 真实知识点 ID 关联检索，避免题干阈值误淘汰 |
| `edu_agent_app/indexing/retriever.py` | 真实知识点 ID 关联检索，避免题干阈值误淘汰 |
| `edu_agent_app/runtime/agent_access_client.py` | 私有报告有界幂等重试 |
| `edu_agent_app/runtime/intent.py` | 通用主题清洗与未指定难度语义 |
| `edu_agent_app/runtime/service.py` | 一次知识点规划、RAG、零候选与学生文案 |
| `edu_agent_app/runtime/tools.py` | 组题授权工具与知识点过滤参数 |
| `edu_agent_app/skills/public-practice-generation/SKILL.md` | 可信 Skill 空候选及难度补齐规则 |
| `edu_agent_app/skills/public-practice-generation/references/practice-rules.md` | 可信 Skill 空候选及难度补齐规则 |
| `edu_agent_app/skills/public-practice-generation/scripts/validate_proposal.py` | 可信 Skill 空候选及难度补齐规则 |
| `java-platform/agent-access-service/src/main/java/com/eduplatform/agentaccess/service/AgentConversationService.java` | 精确放行合法 Offer 询问字段，保留秘密检查 |
| `java-platform/agent-access-service/src/main/java/com/eduplatform/agentaccess/service/AgentStreamEventValidator.java` | 提案非空及可选补齐字段校验 |
| `java-platform/agent-access-service/src/main/java/com/eduplatform/agentaccess/service/PendingPracticeService.java` | Java 零候选拒绝及最终题量投影 |
| `java-platform/agent-access-service/src/main/java/com/eduplatform/agentaccess/web/AgentDtos.java` | 消费既有契约可选补齐字段 |
| `java-platform/agent-access-service/src/main/java/com/eduplatform/agentaccess/web/AgentInternalDtos.java` | 消费既有契约可选补齐字段 |
| `java-platform/agent-access-service/src/test/java/com/eduplatform/agentaccess/AgentAccessIntegrationTest.java` | 所属修复的回归测试 |
| `java-platform/agent-access-service/src/test/java/com/eduplatform/agentaccess/service/AgentStreamEventValidatorTest.java` | 所属修复的回归测试 |
| `java-platform/learning-service/src/main/java/com/eduplatform/learning/service/AutonomousPracticeService.java` | Learning 当前版本/可评分复核与难度补齐 |
| `java-platform/learning-service/src/main/java/com/eduplatform/learning/service/LearningAssessmentService.java` | 自主练习空 courseId 的安全归属处理 |
| `java-platform/learning-service/src/main/java/com/eduplatform/learning/web/AgentAccessDtos.java` | 消费既有契约可选补齐字段 |
| `java-platform/learning-service/src/test/java/com/eduplatform/learning/QuestionBankIntegrationTest.java` | 所属修复的回归测试 |
| `tests/test_agent_internal_contract.py` | 所属修复的回归测试 |
| `tests/test_dynamic_runtime.py` | 所属修复的回归测试 |
| `tests/test_hybrid_index_retrieval.py` | 所属修复的回归测试 |
| `tests/test_practice_generation_skill.py` | 所属修复的回归测试 |
| `web-apps/apps/student-web/src/components/PracticeFloatingChat.vue` | 悬浮答疑输入、恢复、空态与视觉 |
| `web-apps/apps/student-web/src/components/PracticeSuggestionCard.vue` | 真实数量、无卡边界、恢复及焦点 |
| `web-apps/apps/student-web/src/t15a-pages.test.ts` | 所属修复的回归测试 |
| `web-apps/apps/student-web/src/t15b-pages.test.ts` | 所属修复的回归测试 |
| `web-apps/apps/student-web/src/t15c-pages.test.ts` | 所属修复的回归测试 |
| `web-apps/apps/student-web/src/views/AiHomePage.vue` | 独立 AI 输入、来源、恢复与视觉 |
| `web-apps/packages/api-client/src/generated/schema.d.ts` | OpenAPI 自动生成类型，未手写 |
| `tests/test_practice_report_retry.py` | 所属修复的回归测试 |
| `web-apps/apps/student-web/src/agent-sources.test.ts` | 所属修复的回归测试 |
| `web-apps/apps/student-web/src/components/AgentSources.vue` | 来源前两条及可访问浮层 |
