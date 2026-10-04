# T17C 拆分与 T17C.1 前置审计

> 2026-10-04 总控只读审计；准备基线 main `98d5b02`。这是规划和开工条件，不是前端功能验收。未实现业务代码、修改契约或初始化数据。

## 1. 拆分与顺序

| 任务 | 范围 | 当前结论 |
|---|---|---|
| T17C.1 管理端应用壳与教师资格审核前端 | 固定侧栏、统一身份落点及旧路由收口、审核列表/详情、短期材料授权、批准/驳回和只读历史 | 契约、代码、本地基础依赖满足开工；业务服务需正常启动后真实验收 |
| T17C.2 账号权限管理前端 | 查询、合法学生/教师组合、影响预览、封禁/恢复、会话失效与课程协调状态 | 后端已存在；开始前另审计页面、命令重试及高风险保护，不在 .1 实现 |
| T17C.3 管理员邀请与受邀开户前端 | 权限页内邀请/详情/重发，统一身份壳内消费邀请并回登录 | 开始前另核对 SMTP、邮件引导及开户路由；没有邀请分页列表契约，不承诺该功能 |

不启用课程审核、治理、运营配置、平台摘要或报表占位页。三项都完成才称为管理端首期闭环。T17B.2～4 的工作台、媒体中心和资料缺口保持独立，不借管理端任务填充。

## 2. 合并与基线

- PR #87 最终 head `7b3170f` 已由总控复验，独立公告页面/适配器 29 项通过。实施方全量 438 项、类型检查、生成检查和三构建通过；原真实浏览器长链是交付文档的历史验收，本轮没有重跑。
- 2026-10-04 普通合并为 `98d5b02f5b519510e91190d040d43688fee7c5c2`，本地 main 已快进到 origin/main。T17B.1 本地分支删除，远端分支按用户要求保留。
- 合并后 [CI #37196215505](https://github.com/chengchen-ccnu/ccnu-edulion/actions/runs/37196215505) 全部成功。下一分支为 `codex/t17c1-admin-teacher-review-web`，规划提交本次只在本地，不推送。

## 3. 已有契约和实现证据

外部 OpenAPI **0.31.0** 和生成客户端已存在；本任务不需要新增 REST、DTO、事件或迁移。

| Gateway 接口 | 实际用途与边界 |
|---|---|
| GET `/api/v1/admin/teacher-applications` | 有界分页，四种状态、NEWEST/OLDEST、申请编号/账号身份精确查询；列表不变更审核状态，不返回资质文件或完整申请正文 |
| GET `/api/v1/admin/teacher-applications/{applicationId}` | 主动打开详情才开始审核；首次查看者与最终决定者独立，审核不独占；详情含当前及历史轮次 |
| POST `/api/v1/admin/teacher-applications/{applicationId}/material-access` | roundId＋PREVIEW/DOWNLOAD；由 IAM 解析绑定及保留状态，再调用 Media。只返回短期 MediaAccessGrant，无自选媒体 ID 或 OSS 元信息 |
| POST `/api/v1/admin/teacher-applications/{applicationId}/decision` | 稳定 clientRequestId、expectedRevision、APPROVED/REJECTED、materialConfirmed 与驳回理由；理由边界以生成契约为准 |

实际审查 `TeacherReviewController`、DTO、`TeacherReviewService`、材料客户端及 Gateway 路由：服务端重新验证可用管理员；详情打开按事务写首次查看；批准检查当前轮次、revision、可读取材料及有效申请人，在 IAM 同一事务保存不可变决定和教学能力，保持学生身份；结果消息由 Outbox 异步投递，不假称同步收到。重复请求可重放；并发最终决定 409，不允许改写批准/驳回历史。T16.2 交付已有 Gateway→IAM→Media→OSS/RabbitMQ 历史真实验收，本次没有重新执行。

列表没有每行首次查看时间字段，应在已打开详情展示，不能逐行请求详情制造 N+1 或提前开始审核。材料已清理的旧轮次保持只读不可用，不能把旧 URL 当可永久预览依据。

## 4. 应用壳修正属于本任务，不是后端缺口

当前 admin-web 根路由和共享身份包仍落到 `/admin/dashboard`，路由含 `/admin/reviews` 占位，导航保留七个历史建设中槽位。T17C.1 按 page-specs 改成固定“教师审核、账号权限”两项，仅审核可用；旧 dashboard/reviews 只重定向。同步共享 auth 的 ADMIN 默认路径和合法回跳，不创建第二套登录/账号工具。消息继续复用三端头像菜单的唯一 `/notifications`。

审核查询状态、排序、页码、选中申请及详情面板支持 URL、刷新/返回。精确账号身份不写页面 URL/本地存储/日志。详情只有主动选中或真实深链接才加载，不预取列表全部材料。详情、历史和决定在同一页面，不复制申请器、资质上传器或权限管理器。

材料通过成功预览或授权下载供人工审核，无法读取不得批准；材料确认须对应当前申请、轮次和 revision。短期 URL、图片/PDF、Blob 和请求在身份变化/离页清理，不记录正文、联系方式或材料地址。审批需明确确认；驳回保留输入并映射字段错误。未知写入结果保留原请求快照和离页保护，最多三次有限重试；409 显式刷新并重新决定，不能自动改 revision 覆盖。

## 5. 本地依赖只读核对

通过 `Invoke-WithLocalEnvironment.ps1` 加载配置，未打印或复制凭据，未修改 local.env。

| 检查 | 本次实际结果 |
|---|---|
| 工具 | JDK 17、Node 20.19.5、pnpm 10.22.0、MySQL/Redis CLI 可用 |
| 专用 dev Flyway | IAM V5、Media V6、Course V7、Learning V21、Operation V2；五域 PENDING Outbox 均为 0 |
| MySQL/IAM | 认证成功，当前可用 ADMIN 数 1，申请轮次数 0；未读取账号隐私 |
| Redis | 使用已有凭据 PING，PONG |
| RabbitMQ | 使用现有配置及应用默认 host，以 langgraph 环境 pika 实际连接认证并关闭；无声明/删除队列或消费消息 |
| 配置存在性 | JWT、Course→Media、Operation 内部令牌、RabbitMQ 和 OSS 必需配置已存在；不输出值 |
| 服务端口 | MySQL/Redis/RabbitMQ 在监听；Java 8080～8085 和三前端当前未常驻，实施时须启动 |

本任务不需要 Agent、Qdrant、Neo4j、FFmpeg 或 SMTP 发信。真实材料授权需要现有 Media/OSS，不能把配置存在视为上传已验收。已有 `Invoke-T17ABrowserEnvironment.ps1` 可启动六服务及三前端，启动前完整阅读、构建当前代码、确认端口及 Outbox，保留禁用既有 Seed 的设置；STOP 仅停止本轮启动实例。不得重放历史 FAILED 或运行清空式初始化。

## 6. T17C.1 实施与验收要求

1. 生成契约适配器、真实分页和深链接；待审/审核中/通过/驳回、精确查询、详情开启副作用和材料保留状态。审批历史不开放编辑；第一次批准不能通过权限勾选绕过。
2. 401/403/404/409/422/429/offline/5xx、loading/empty/partial-error、未知决定及离页保留；并发两管理员决定、同 ID 重放、批准材料不可读、拒绝非管理员。
3. 真实学生上传 READY 资质→提交→管理员读取/批准→同账号能力刷新，以及驳回/重提/只读旧轮次；不以 mock 成功替代真实授权或能力授予。结果消息复用 T17A，暂时投递失败不能回滚已批准事实。
4. 360/768/1440、键盘、焦点、抽屉/侧栏及退出清理；运行 check:api、workspace typecheck、完整前端测试和三端构建。后端未改时无需为了凑数重复全仓业务测试，真实权限链必须运行。
5. 目前没有申请轮次，实施方需通过正常 local/test 路径创建最小确定 Fixture，标记 UUID，提供幂等 Initialize/Cleanup 或等价可重放步骤。临时账号/申请/引用/决定/消息/Outbox/媒体精确清理；保留手工《线性代数》、既有题库及历史事实，不执行 purge、FLUSHDB 或整表删除。不可把本地管理员唯一账号删除或改成无管理能力。
6. 中文职责/输入/输出/权限/副作用注释，独立 `docs/delivery/T17C.1-admin-teacher-review-web.md`，同步 backlog/verification；提交推送后创建 Ready PR，待总控验收，不自行合并。

## 7. 本次操作与限制

本次只有合并、分支清理和文档规划。没有创建数据库、表、伪数据、缓存 Key、队列或 OSS 对象，没有启动业务服务、发送邮件或运行浏览器验收，因此无数据清理步骤。实际运行 `pnpm.cmd --dir web-apps run check:api` 成功、生成无差异，`git diff --check` 通过；未重复未受影响的全仓构建。后续发现契约缺口应停止对应接口并反馈总控，不在前端任务自行扩展后端或导航。
