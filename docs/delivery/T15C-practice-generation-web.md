# T15C 自主练习建议与创建结果卡片交付说明

## 任务目标与边界

本任务在既有学生独立 AI 工作区和普通公共题悬浮答疑中增加同一份自主练习建议卡片，并把 `/practice/:assessmentId` 接到既有 `PracticeAttemptPage`。页面只消费 OpenAPI 0.23.4 生成类型与路径，不解析模型文字来判断动作结果，也不构造题目、版本、action 或 assessment 身份。

本次没有修改 OpenAPI、Java、Python Agent、Skill、组题算法、数据库迁移、Redis、RabbitMQ、Qdrant、Neo4j、OSS、顶部导航或课程播放器；没有新增答题器、状态机、缓存、hash、contract、baseline 或 gate。HOMEWORK/EXAM 和课程播放器没有增加 AI 入口，公共题库仍只表达公共题与自主练习。

## 页面与调用流程

1. `AiHomePage` 与 `PracticeFloatingChat` 复用原会话、消息、SSE、停止生成和离页清理；`FINAL_RESULT.pendingPracticeAction` 被归并为生成类型，并由共享 `PracticeSuggestionCard` 渲染。
2. 刷新或恢复会话时，卡片调用会话当前动作接口；确认超时、响应丢失、409 并发或 EXECUTING 时，改用固定 `actionId` 查询真实状态。执行轮询最多三次，离页会清理请求、过期计时和轮询。
3. 卡片展示主题、难度、请求题数、实际召回题数、不足说明、服务端状态、revision 和有效期。`PENDING/CONFIRMED/EXECUTING/SUCCEEDED/FAILED/EXPIRED/CANCELLED` 都有明确反馈；401/403/404/409/422/429/503、离线与未知结果不会清空条件。
4. 确认和取消均携带服务端 `actionId`、当前 `revision` 及稳定 requestId。同一页面并发点击由共享 Promise 合并，服务端继续负责跨请求幂等；未知结果先查询再允许使用原键重试。
5. 用户一旦修改主题、难度或数量，旧确认在本次页面生命周期内永久禁用，即使把字段改回原值也不会重新启用。点击“按新条件重新生成”时先取消仍为 PENDING 的旧动作，再以普通用户消息请求新建议；不自动确认、创建、开始、提交或评分。
6. `SUCCEEDED` 只使用 `executionResult.startRoute` 展示“开始练习”，不自动跳转。`/practice/:assessmentId` 先用课程 assessment entry 读取课程归属；课程旧链安全替换为课程任务/结果路由，entry 为 404 时再由本人测评详情确认 `courseId=null` 的 AUTONOMOUS PRACTICE，并直接复用 `PracticeAttemptPage`。URL 查询参数和 ID 格式不参与归属判断。

## API 与数据

使用的生成客户端操作：

- `GET /api/v1/ai/conversations/{conversationId}/pending-practice-action`
- `GET /api/v1/ai/pending-practice-actions/{actionId}`
- `POST /api/v1/ai/pending-practice-actions/{actionId}:confirm`
- `POST /api/v1/ai/pending-practice-actions/{actionId}:cancel`
- 既有 Agent 会话消息/SSE/恢复接口
- `GET /api/v1/learning/assessments/{assessmentId}/entry`
- 既有本人测评、attempt 草稿、提交和结果接口

没有创建数据库、表、迁移、固定 Seed 或永久伪数据。真实验收只复用用途明确的 `edu_platform_agent_dev`、`edu_platform_learning_dev` 和既有 Qdrant `math_questions` 索引，创建随机 owner 下的临时会话、run、Skill 审计、动作、自主练习和 attempt。验收脚本在 `finally` 中按本轮 ID 精确删除这些事实和相关 Redis Key；两次运行最终均为 `learningRemaining=0`、`agentRemaining=0`、`redisRemaining=0`。重新运行同一脚本会使用全新随机身份；既有题库、索引和 Seed 未删除。Qdrant 容器在验收后恢复为任务开始前的停止状态。

## 环境、启动与人工演示

没有新增或修改环境变量，也没有读取、打印或写入用户提供的密码。`local.env` 没有本任务需删除或迁移的配置；本地秘密仍只通过 `scripts/Initialize-LocalEnvironment.ps1` 初始化，并由 `scripts/Invoke-WithLocalEnvironment.ps1` 注入。

开发启动：

```powershell
pnpm.cmd --dir web-apps --filter @edu/student-web run dev
```

人工演示步骤：登录具备 `learning:access` 的测试账号，在 `/ai` 新建会话，请求“一元一次方程、5 道、中等难度”的自主练习；检查卡片显示请求 5、实际召回数量和不足说明。确认前检查 Learning 无练习，明确确认后等待 `EXECUTING→SUCCEEDED`，再自行点击“开始练习”。完成草稿保存、提交、客观题评分和结果查看。随后分别验证修改条件、取消、过期和断开确认响应后的“查询真实结果”。在 `/practice/questions/:questionId` 的悬浮答疑重复建议卡流程，并验证课程 PRACTICE 旧链接迁移、HOMEWORK/EXAM/教师成绩册隔离。

## 测试与真实验收

- `pnpm.cmd --dir web-apps run check:api`：通过，OpenAPI 0.23.4 再生成无差异。
- `pnpm.cmd --dir web-apps run typecheck`：通过，admin/student/teacher 及共享包全通过。
- `pnpm.cmd --dir web-apps run test`：通过，53 个测试文件、343 项测试全绿；其中 T15C 覆盖真实 Vue 组件和路由、两个入口挂载、数量不足、全部动作状态、条件失效、重复点击、响应丢失查询、空状态、离页 abort 与自主/课程分流。
- `pnpm.cmd --dir web-apps run build`：通过，admin、student、teacher 三个生产构建均成功；student/teacher 保留既有大 chunk 警告。
- `scripts/Invoke-WithLocalEnvironment.ps1 -ScriptBlock { scripts/Invoke-T14.3PracticeGenerationRealAcceptance.ps1 ... }`：首次在 Qdrant 停止时得到 5→0 并按约束失败，仍精确清理为三项 0；启动既有 Qdrant 且不改索引后重跑通过，真实请求 5 道 MEDIUM、实际召回 4 道并保留不足。确认前零创建，确认/重复/两路并发只创建一次，未知结果查询恢复，保存、提交、自动评分、结果查看、条件替换、取消、过期、owner/课程/成绩册隔离全部通过，最终三项清理为 0。
- `git diff --check`：通过。
- PR #71 CI：最终 HEAD 的两组 Java、Python、Frontend、OpenAPI 共 8 项检查全部通过。

本次没有修改 Java/Python 生产代码，因此未重复运行全量 Maven 与 Python 单元测试；真实多服务脚本已覆盖受影响的既有 Agent Access、Python Runtime、Learning 和 Gateway 长链。没有另行执行“真实浏览器 + 实际后端”的人工登录录屏；浏览器交互由真实 Vue/jsdom 行为测试验证，后端事实由真实多服务验收验证，这一点保留为 PR 复核项。

## 注释覆盖、风险与后续依赖

新增共享卡片、幂等命令合并、错误映射、条件重新生成、路由归属分流和悬浮窗接线均补充了通俗中文 TSDoc/邻近注释，说明输入、服务端事实、状态变化、失败语义和清理副作用；生成文件未手工修改。

已知风险：卡片的进程内 requestId 只覆盖当前页面生命周期；浏览器刷新后不会重发未知命令，而是按 action 查询真实结果，这是契约要求的安全降级。EXECUTING 只做三次有限轮询，长任务需要用户手动查询。生产构建仍有既有大 chunk 警告。真实候选依赖本地 Qdrant 可用性；索引停止时实际数量可能为 0，页面会如实禁用确认，不伪造补足。

分支为 `codex/t15c-practice-generation-web`，前置审计提交为 `ebeeabd`，关键实现提交为 `88a4bdb`。[PR #71](https://github.com/chengchen-ccnu/ccnu-edulion/pull/71) 已于 2026-09-14 以普通 merge 合并为 `4d7eba5`。

## 合并后人工验收发现

用户在真实页面发现：停止按钮远离发送区；会话无重命名/删除入口；建议卡在没有组题动作时仍显示空状态；“我刚学完一元一次方程，请出 3 道题练习”未命中组题 Skill；输入区没有统一“＋”菜单和图片粘贴；浏览器显式 Skill 选择及“讲解后建议、确认后调用”的链路尚未形成。只读代码复核确认这些分别来自现有布局、缺失 OpenAPI、卡片永久挂载、规则遗漏和内部请求缺少显式选择字段，不归因于 Qdrant 或模型速度。T15D 负责修复，T15C 的已合并历史与既有真实验收记录保持不变。
