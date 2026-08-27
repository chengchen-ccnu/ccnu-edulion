# T11A assessment UI contract closure delivery

## 任务目标与边界

T11A 在既有 `learning-service` 内补齐 T12B 所需的测评领域事实：学习者列表的当前 attempt/安全状态、EXAM 服务端时间裁决、刷新草稿恢复、归档结果题目投影、错题详情/掌握/最多三次独立重做，以及教师私有题媒体的测评访问授权。契约先由 `docs/api/openapi.yaml` 从 0.14.0 演进到 0.15.0，再实现服务端并重新生成 TypeScript 客户端。

本任务没有实现 T12B Vue 页面，没有拆分 `learning-service`，没有让 Learning 接入 OSS SDK，没有新增第二套媒体客户端、跨 Schema 查询/Join/外键、监考、复杂防作弊或无限重考。Gateway 仍是浏览器唯一 API 入口，`/internal/**` 不经 Gateway。

## 架构与调用流程

学习者测评列表由服务端在同一响应时间点组合测评窗口与本人唯一 attempt，返回 `NOT_OPEN/AVAILABLE/IN_PROGRESS/SUBMITTED/PARTIALLY_GRADED/GRADED/CLOSED`、当前 attempt ID/状态、`resumeAllowed`、`resultAvailable` 和 `serverTime`。刷新后先读列表，再按 attempt ID 恢复未超时草稿或读取结果；浏览器倒计时只展示，不能延长服务端冻结的 `deadlineAt`。

EXAM 可选 `opensAt`、`closesAt`、`timeLimitSeconds`。开始作答时服务端冻结 `startedAt`，并将 `deadlineAt` 裁决为 `closesAt` 与 `startedAt + timeLimitSeconds` 的较早值。开放前/截止后不能开始，超时草稿不能继续写；提交端点忽略超时后的客户端新答案，只用最后一次服务端保存的草稿原子完成一次性提交。

结果响应内嵌从 `assessment_question.snapshot_json` 投影的安全试卷，归档或取消报名后本人仍按 attempt owner 读取。题目投影在类型上没有答案、解析或评分规则；`correctAnswer/analysis` 继续严格服从 `AFTER_SUBMISSION`、`AFTER_GRADED`、`NEVER`。

错题详情使用原测评不可变快照，只暴露安全题目内容。掌握状态用 revision 乐观锁和 `clientRequestId` 幂等更新；重做写独立不可变记录，最多三次，主观题可返回空 score/correct 但仍消耗一次。任何重做都不修改或重开原 `assessment_attempt`。幂等指纹只解决“同一请求 ID 被错误复用于另一掌握目标或另一答案”这一具体冲突，不是 hash gate、baseline 或契约冻结。

题目媒体链路为：浏览器携学习令牌经 Gateway 请求 Learning；Learning 校验发布测评+ACTIVE 报名，或历史 attempt owner，并确认 `mediaId` 存在于冻结快照；既有 `HttpMediaClient` 用 `AssessmentMediaAuthorizationIssuer` 签发 30 秒一次性内部 JWT；Media 校验签名、专属 learning issuer、audience、jti、访问者、mediaId、用途、`ASSESSMENT_QUESTION`、`ORIGINAL` 和 disposition，原子消费 jti 后签发短期 OSS 读授权。服务 JWT 不返回浏览器、不记录；课程 issuer 与学习 issuer 不能交叉代签。

## API 契约

OpenAPI 0.15.0 新增或扩展：

- `GET /api/v1/learning/assessments`：`LearnerAssessmentPage` 与刷新安全 attempt 状态。
- `GET /api/v1/learning/attempts/{attemptId}/draft`：本人未超时草稿、冻结截止时间及服务时间。
- `POST /api/v1/learning/attempts/{attemptId}/submission`：超时以最后保存草稿结算；只成功一次且重放安全。
- `GET /api/v1/learning/attempts/{attemptId}/result`：归档后可读的自包含安全题目投影。
- `GET /api/v1/learning/wrong-questions/{wrongQuestionId}`、`PUT .../mastery`、`POST .../redos`：错题查看、掌握和有限重做。
- `POST /api/v1/learning/assessments/{assessmentId}/media/{mediaId}/access-grants`：测评访问事实换单资产短期授权。
- 教师测评创建/更新/摘要增加可空 EXAM 时间字段。

所有新增外部路径继续匹配 Gateway 的 Learning 路由；学习路径要求 `learning:access`，教师路径要求 `teaching:access`。统一错误信封保持 `code/message/traceId/timestamp/path/fieldErrors`，契约显式列出相关 401/403/404/409/422/429/503。

## 数据库迁移

只新增 Learning `V10__close_assessment_ui_contract.sql`，不修改已合并迁移：

- `assessment` 增加 `opens_at`、`closes_at`、`time_limit_seconds` 和 EXAM/窗口/范围约束。
- `assessment_attempt` 增加 `started_at`、`deadline_at`；历史行以 `created_at` 回填开始时间。
- `wrong_question` 增加 `mastery_status`、`revision`、`redo_attempts_used`、`updated_at` 并回填历史行。
- 新建 `wrong_question_redo`，本 Schema 内关联错题，序号/请求唯一且序号限定 1～3。

没有新建数据库，没有跨 Schema 外键或 Join。Media 复用既有 `consumed_service_authorization` 唯一 jti 记录，不新增媒体迁移。

## 环境变量与启动

- `MEDIA_SERVICE_BASE_URL`：Learning 调用 Media 的内部基址，默认 `http://127.0.0.1:8083`。
- `LEARNING_MEDIA_REQUEST_TIMEOUT`：内部媒体请求上限，默认 `5s`。
- `LEARNING_MEDIA_AUTH_ISSUER`：Learning 专属 issuer，默认 `edu-platform-learning-service`；Media 必须配置相同值。
- `COURSE_MEDIA_AUTH_AUDIENCE`：复用既有 Media audience，默认 `edu-platform-media-service`。
- `LEARNING_MEDIA_AUTH_TOKEN_TTL`：一次性服务令牌寿命，默认 `30s`，不得超过 Media 的最大 TTL。
- `COURSE_MEDIA_AUTH_SECRET`：复用 DPAPI 管理的既有 course/media HMAC 秘密；不会写入 `local.env`、参数、日志或数据库。

真实启动、迁移和验收必须经 `scripts/Invoke-WithLocalEnvironment.ps1` 注入凭据。推荐依次启动 Media、Course、Learning、Gateway；浏览器只访问 Gateway。

## 测试与实际结果

已完成的定向自动化：

- `LearningAssessmentIntegrationTest`：6/6，通过刷新状态、开放边界、服务端 deadline、超时草稿结算、一次性提交/幂等/revision、答案防泄漏、归档结果、owner、错题掌握/重放/三次上限及媒体访问事实。
- `MediaHttpIntegrationTest#internalAssessmentMediaAuthorizationRequiresLearningIssuerAndRejectsReplay`：1/1，通过 learning issuer 精确用途、课程 issuer 越权拒绝、disposition 绑定和 jti 防重放。
- `GatewayRouteSecurityTest`：12/12，通过新增 draft、错题重做和测评媒体路径的路由/能力边界，并保留统一 401/403 信封。
- `HttpMediaClientTest`：内部请求同时绑定服务 JWT 与 JSON 参数；404/409/503 适配不泄露内部令牌。

完整验证结果：

- `mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml verify`：8 个 Reactor 模块 `BUILD SUCCESS`；Gateway 12/12、IAM 9/9、Course 17 项（1 跳过）、Learning 52 项（4 个真实环境项按开关跳过）、Media 25 项（4 个真实环境项按开关跳过），无失败；Operation 无测试。
- `pnpm.cmd --dir web-apps run generate:api`：成功重新生成 OpenAPI 0.15.0 客户端；`typecheck`：3 应用和 5 共享包通过；`test`：23 文件 140 项通过；`build`：学生、教师、管理三端通过。沙箱内 Vitest/Vite 因 esbuild `spawn EPERM` 无法启动，批准的沙箱外同命令通过。
- `LearningMysqlMigrationAcceptanceTest`：真实 MySQL 4 项中 3 项通过，1 个旧版 root 凭据用例按设计跳过；Flyway 确认 `edu_platform_learning_dev` 位于 V10。
- `MediaRealEnvironmentAcceptanceTest#validatesAssessmentQuestionMediaAuthorizationAndReplayProtection`：真实 MySQL/OSS/服务授权 1/1 通过，首次授权和固定字节下载成功，同一 jti 重放为 403，finally 精确清理通过。
- 完整 `MediaRealEnvironmentAcceptanceTest` 的 T11A 媒体步骤执行成功后，仍在既有 RabbitMQ unacked-redelivery 时序断言失败；临时 poison 消息被既有消费者有限重试后进入 DLQ，第二连接取不到预期临时消息。该既有综合用例如实保留为未全绿，T11A 关键链路由上面的独立真实用例隔离验证。
- 带 `clean` 的完整 Java 命令曾因工作区外既有 Java 进程锁定 `shared-kernel` 产物无法删除；未终止未知用户进程，改用不删除产物的完整 `verify` 并通过。

## 真实环境、伪数据与清理

`LearningMysqlMigrationAcceptanceTest` 只允许 `edu_platform_learning_dev`，验证真实 MySQL 从 V9 正常前进到 V10；使用随机 UUID 创建一份 EXAM、一个 GRADED attempt、一条错题和一次重做，验证时间/约束后按 redo → wrong → attempt → assessment 精确删除并逐项断言为 0。

`MediaRealEnvironmentAcceptanceTest` 只允许 `edu_platform_media_dev`，在本轮 UUID 前缀下上传固定字节 `t11a-question-image` 的 `QUESTION_IMAGE`，经真实处理、真实 OSS、learning issuer 内部授权下载，再重放同 jti 得到 403；finally 从数据库取得精确 Object Key，逆序删除本轮对象、媒体、授权 nonce 和审计。它不列举或清空 Bucket/表。

迁移表与列作为正式开发 Schema 向前演进保留，不属于伪数据。没有创建账号、真实姓名、手机号、邮箱、课程或跨域 Seed，也不会修改既有题库、课程、媒体或 T12A 资产。重新生成仅需再次运行两个显式门控验收；每次使用新 UUID，且同轮精确清理。

## 人工演示

1. 教师创建带私有题图的 EXAM，设置开放/截止/限时并发布；确认开放前返回 409，窗口内可开始。
2. 学习者开始并保存草稿，刷新 `/practice`；确认列表返回同一 attempt ID、`IN_PROGRESS` 和可恢复草稿，deadline 不因刷新变化。
3. 将服务端截止时间推进到当前时间前，提交携带不同新答案；确认仅按最后保存草稿判分，第二次不同提交返回 409。
4. 教师归档测评并取消报名；确认本人结果和安全题目仍可读，`NEVER` 不出现答案/解析。
5. 查看错题、切换掌握状态并重做三次；确认第四次 409，原 attempt 状态/revision 不被重开。
6. 请求私有题图授权并读取；重放内部令牌得到 403，未报名且无历史 attempt 的其他账号得到 404，错误 disposition 得到 422，停止 Media 时得到 503。

## 已知风险与后续依赖

- 429 仍由平台现有 Gateway/基础设施策略产生；本任务冻结响应契约和 `Retry-After` 暴露语义，不新增没有容量证据的第二套限流器。T12B 必须有限退避，禁止无限重试。
- 服务 JWT 与课程媒体链路复用同一 DPAPI 秘密，但 issuer 与 accessType 强绑定；后续若轮换为独立密钥，应采用统一密钥管理和兼容轮换，不把秘密写入仓库。
- T12B 只能消费生成客户端，在 `/practice`、`/practice/:assessmentId`、`/practice/:assessmentId/result` 与 `/wrong-questions` 内实现页面；不得把作答塞回课程详情。
- 不包含监考、复杂防作弊、无限重考、LLM 主观判分或批量历史媒体迁移。

## 注释与 Git 交付

人工可读性检查已覆盖新增/修改的 DTO、服务状态机、仓储 SQL、JWT 签发/验证、控制器、迁移和真实验收；权限、输入、输出、时间裁决、数据库/媒体副作用、重试和失败语义均有中文 Javadoc 或边界注释。生成客户端没有手工加注释。

- 分支：`codex/t11a-assessment-ui-contract`
- 关键提交：提交后回填
- Ready PR：创建后回填；不得由本任务自行合并
