# T15A 独立 AI 答疑前端

日期：2026-09-12。状态：首版实现已提交为 `e7a1ad2`；自动化与功能链路通过，但人工产品验收发现三项合并阻断，尚未创建或合并 PR。

## 目标与功能边界

学生端固定第五槽“AI 答疑”已启用，并交付 `/ai` 与 `/ai/conversations/:conversationId`。入口提供最近会话、新建会话、本人明确讲解偏好和使用边界；会话页提供分页历史、Markdown/LaTeX、安全题图、授权来源、九类 SSE、停止、断线后保存事件恢复、部分回答标识和最后一问重新生成。独立会话可持续使用，不应用 T15B 的 30 分钟换新规则。

未实现练习题悬浮窗、课程内嵌 AI、HOMEWORK/EXAM 入口、画像/长期记忆查看、人工转交、自主组题、自动提交或评分。没有修改 Agent/Media 后端契约，没有手写平行 DTO 或事件类型。

## 架构与调用流程

1. `agent-api.ts` 只使用 OpenAPI 0.23.2 生成的路径与类型，经统一 `AuthStore` 调用 Gateway。REST 与 SSE 遇到 401 均沿用一次刷新/重放边界。
2. `agent-stream.ts` 直接消费生成的 `AgentClientStreamEvent` 联合，按 `eventType` 收窄九类事件，校验连续 sequence 与唯一终态。网络中断后按 runId 调用保存事件端点，从 sequence 1 重建页面状态，不重发消息或调用模型。
3. 历史消息按服务端分页读取并按既有顺序展示。`PARTIAL` 和 `INTERRUPTED` 明确显示“回答已中断”；重新生成仅发送专用 `clientRequestId`，沿用服务端保存的最后一问与授权快照，并提示会计入新运行。
4. 文本和公式复用共享 `SafeArticleContent`；授权来源仅展示 Java 返回的安全投影，`USER_HELP.appRoute` 才作为站内链接。
5. AI 题图复用学生端同一 Media API：创建 `AI_QUESTION_IMAGE` 上传会话、OSS 单对象直传、complete、轮询 READY、本人短期读取授权。页面只把稳定 `mediaId` 放入消息上下文；JPG/JPEG、PNG、WebP 和 10 MiB 限制在上传前再次校验，短期 URL/请求头不进入状态、日志或消息。
6. 离页中止 SSE、上传、轮询和 Blob 请求并撤销对象 URL；仍在运行的回答以 `PAGE_CLOSED` 尽力取消。停止按钮使用 `USER_STOP`，未确认时不伪报成功。

## API、数据库与环境

消费既有会话、消息、保存事件、取消、重新生成、讲解偏好和 Media 上传/读取 API。所有字段来自 `@edu/api-client` 生成类型，OpenAPI 仍为 0.23.2；本任务未修改契约、Java、Python、Flyway、数据库表、Redis Key 设计或环境变量。

本地启动使用 `VITE_GATEWAY_BASE_URL`（默认 `http://localhost:8080`）。后端凭据仅通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入；没有读取、打印或写入 `local.env` 的秘密，因此没有需要从 `local.env` 删除或迁移的新增配置。

## 测试与实际结果

- `pnpm.cmd --dir web-apps run typecheck`：8 个 workspace 全部通过，生成客户端与 OpenAPI 0.23.2 一致。
- `pnpm.cmd --dir web-apps run test`：45 个文件、304 项测试全部通过。新增覆盖九类事件、sequence/唯一终态、分块 CRLF SSE、固定导航、两条权限路由、入口 loading/empty、创建深链、偏好允许值/revision 和键盘焦点。
- `pnpm.cmd --dir web-apps run build`：admin、student、teacher 三端全部通过；student/teacher 保留既有大 chunk 警告。
- Chrome 真实联合验收：1440×1000、820×1100、390×844 三档均通过真实 Gateway 会话深链接、刷新恢复、真实 Agent 历史、真实 OSS 题图、键盘焦点和无水平溢出；会话列表、明确偏好和第五槽高亮通过，page error 为 0。另以隔离契约响应覆盖偏好 409 和公式渲染状态，该层不写数据库。
- 真实链路：安全包装器运行既有 `Invoke-T14.2.4AgentWebContractRealAcceptance.ps1`。Gateway、Media/OSS、Agent Access、Python/百炼和 Redis 均就绪；题图为 `USER_PRIVATE/READY`，正常回答与重新生成形成 1 条用户消息、2 条助手消息、2 个 run，同一重新生成 key 恢复相同 run；偏好更新为 `COMPLETE_SOLUTION` revision 1，后续 run 完成；SSE 含阶段、文本和唯一终态且未输出敏感 payload。
- 错误与恢复由组件、状态机和浏览器覆盖 loading、empty、partial、offline、401/403/404/409/422/429/5xx 映射、断线保存事件恢复、停止/离页清理、偏好冲突和响应式。

## 人工演示

1. 以具备 `learning:access` 的账号打开 `/ai`，确认第五槽高亮，修改讲解偏好并新建会话。
2. 输入含 `$x$` 的问题并附加 JPG/PNG/WebP，观察上传 READY 后发送；确认流式阶段、正文公式和授权来源。
3. 生成中点击“停止生成”，刷新深链，确认保存片段显示“回答已中断”；点击“重新生成最后一问”，确认旧回答仍保留。
4. 模拟断网后刷新，确认只读取保存事件；恢复网络后可继续新问题。用窄屏和键盘 Tab/Enter 复核操作。

## 临时数据与清理

没有创建数据库、表、迁移、长期 Seed、账号、课程或题目。真实验收只在既有 `edu_platform_agent_dev`、`edu_platform_media_dev` 创建随机会话、消息、run、事件、用量、偏好命令、媒体上传/资产、Redis TTL key 和 OSS 精确前缀对象；脚本 `finally` 按 owner、稳定 ID 与对象前缀删除，最终复核 `agentRemainingFacts=0`、`redisRemainingKeys=0`、`mediaRemainingFacts=0`。浏览器隔离验收数据库写入为 0，临时脚本已删除。

## 可读性、风险与后续

新增 API 适配、SSE 状态机、上传流程和 Vue 页面均有中文职责/输入/结果/清理说明；生成文件未手工修改。已人工检查不含 Token、Object Key、Bucket、提示词、原始工具结果或画像字段。

已知风险：验收没有保留长期演示数据；生产发布前仍需按部署域名复核 OSS CORS 与长连接代理超时。重新生成会消耗真实额度，已发往供应商的请求不能保证不计费。T15B、T14.3/T15C 继续按独立任务实施。

## 2026-09-12 人工产品验收阻断与修订设计

首版虽然通过组件和链路验收，但真人使用后不能视为最终完成：

1. `/ai` 当前是会话卡片入口，`/ai/conversations/:conversationId` 是没有历史侧栏的独立聊天页，缺少成熟对话产品的连续工作区。修订后两条路由共用同一壳：桌面左侧 260～300 px 会话栏、中央聊天区、底部输入；移动端历史折叠为抽屉。全局 `Edu Lion` 顶栏及第五槽顺序保持不变，不照搬其他产品品牌、图标或文案。
2. 当前按钮条件为“存在最后用户消息且未生成中”，所以“重新生成最后一问（计入新运行）”会长期出现在来源和输入框之间。后端能力本身合理，它用于基于最后一问的保存快照生成另一份回答并保留旧答案；前端应把它改为最后一条 AI 回答的次级操作“重新生成回答”，只在空闲且有完整/中断回答时显示，生成中或没有 AI 回答时不显示，并以辅助说明告知会产生新运行和额度。
3. 公共题图的 `SafeAssessmentContent` 没有前端超时，只有领域授权或 OSS `fetch` 抛错/非 2xx 时才显示“图片暂不可用”，因此不能把现象直接归因于图片太慢。现实现串行加载、全量 Blob 后才显示、吞掉所有失败原因，且跨组件/返回页面没有请求合并、有限重试或有界缓存。修订应先保留安全 Blob 边界，增加状态分类、一次瞬时失败重试、`429 Retry-After`、手动重试、相同题图并发 Promise 合并和小型 LRU Blob 缓存；失败不得缓存，离页仍撤销组件 Object URL。只有采集授权耗时、下载耗时、响应大小与解码耗时后确认原图过大，才单独评审媒体派生图，不在本修复中先改 OpenAPI/Java/OSS 结构。

修复任务必须补充布局、移动抽屉/焦点、重新生成显示条件，以及题图首次失败后成功重试、非瞬时错误不重试、相同资源请求合并、缓存上限和离页清理测试。随后重新执行真实浏览器验收；三个阻断全部关闭前不得把 T15A 标记为完成或创建 Ready PR。

## 分支、提交与 PR

- 分支：`codex/t15a-student-ai-chat-web`
- 起点：`main@f7d0d8e`
- 首版实现提交：`e7a1ad2`
- Ready PR：三个产品验收阻断关闭、重新验收并补齐记录后再创建；本任务不自行合并。
