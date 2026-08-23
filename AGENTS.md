# EDU Platform repository rules

本仓库是在线网课平台 Monorepo。开始任务前必须先检查 `git status`，保护用户已有修改；只修改任务声明范围，不覆盖、删除或格式化无关文件。

## 模块边界

- `java-platform/gateway-service`：浏览器统一 API 入口，只做路由、鉴权、限流、追踪和错误适配；不保存领域数据，不代理视频文件。
- `java-platform/iam-service`：账号、身份、角色、权限和登录审计；不保存课程或答题数据。
- `java-platform/course-service`：课程、版本、章节、课时、发布和课程访问规则；不拥有媒体文件或学习进度。
- `java-platform/media-service`：所有业务文件的上传会话、OSS 对象元数据、处理/转码状态、短期访问授权和过期回收；不保存领域实体，不决定课程、题目、AI 会话或审核材料可见性。
- `java-platform/learning-service`：报名、进度、题库、测评、作答和成绩；首期有意不再拆服务。
- `java-platform/operation-service`：互动、热点、通知、审核和运营配置；只能通过 API/事件读取其他领域事实。
- `java-platform/shared-kernel`：仅共享错误信封、Trace、事件信封等技术基础；禁止放业务实体、Mapper、Repository 或跨域 DTO。
- `edu_agent_app`：现有 Python 数学智能体，负责检索、推理和讲解；不得直连或写入 Java 业务数据库。不要在平台任务中重新实现它。
- `web-apps/apps/*`：学生、教师、管理三个独立应用；共享技术包位于 `web-apps/packages/*`，禁止共享带角色业务判断的整页组件。
- `docs/api/openapi.yaml` 是外部 REST API 字段的唯一事实来源；`docs/events/*` 是事件事实来源。
- `docs/frontend/page-specs.md` 是三端路由、页面职责、访问能力、页面状态和 Txx 归属的唯一事实来源；功能任务不得自行合并页面职责或发明平行路由。
- 平台消息队列统一使用 RabbitMQ 与 Spring AMQP。跨服务可靠事件必须采用 Outbox、发布确认、有限重试、死信和按 `eventId` 幂等消费；不得依赖 RabbitMQ 提供“恰好一次”，不得在各服务并行引入 RocketMQ/Kafka 第二套实现。

## 构建与测试命令

在仓库根目录执行：

```powershell
# Java（JDK 17+，Maven 3.6.3+）
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl gateway-service -am spring-boot:run

# Python Agent（Python 3.9+；完整运行还需要 .env 与本地模型/索引资产）
python -m pip install -e .
python -m pip wheel --no-deps --wheel-dir .m2/python-wheels .
python -m compileall -q edu_agent_app server.py
python -m unittest discover -s tests -v
python -m edu_agent_app

# Frontend（Node 20.19+；Windows PowerShell 使用 pnpm.cmd）
pnpm.cmd --dir web-apps install --frozen-lockfile
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run typecheck
pnpm.cmd --dir web-apps run test
pnpm.cmd --dir web-apps run build
pnpm.cmd --dir web-apps run mock:api
```

单个前端应用可用 `pnpm.cmd --dir web-apps --filter @edu/student-web run dev`（或 teacher/admin）运行。单个 Java 服务使用 `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl <module> -am spring-boot:run`。以上 Maven 写法适用于 PowerShell；其他 shell 去掉 `--%`。

## 接口与事件契约规则

1. 先修改并评审 `docs/api/openapi.yaml` 或对应事件 Schema，再改生产者和消费者。
2. 前端 API 类型必须由 `pnpm generate:api` 生成，禁止手写重复 DTO；Mock 必须读取同一 OpenAPI。
3. 禁止自行猜测 API 字段、枚举、分页、错误码或空值语义。契约不足时停止该接口实现并报告差异。
4. 兼容性变更优先新增可选字段；删除、重命名、收紧类型或改变语义属于破坏性变更，必须版本化并记录迁移方案。
5. 统一错误响应必须包含 `code`、`message`、`traceId`、`timestamp`、`path`、`fieldErrors`；日志和响应不得泄露密钥、Token 或隐私。
6. 事件必须带 `eventId`、`eventType`、`eventVersion`、`aggregateId`、`occurredAt`、`traceId` 和 `payload`，消费者按 `eventId` 幂等。

## 设计克制与安全边界

默认不新增 `hash`、冻结 `contract`、`baseline` 或 `gate`。只有能明确说出一个具体失败场景，并说明 Git、版本号、主键、事务、唯一约束、类型和普通测试为什么不足时，才允许加入。同时保留安全边界：不删除已有安全措施；认证、数据安全、不可逆操作和正式发布等高风险环节，仍然按照项目要求处理。

## 前端页面与产品边界规则

1. 涉及前端页面、路由、导航、跨应用跳转或权限表现的任务，开始前必须完整阅读 `docs/frontend/page-specs.md`。如需新增、删除或改变页面职责，先由总控任务更新页面规格和 backlog；功能任务发现缺口时停止扩展并报告，不得自行改写全局信息架构。
2. `/courses/:courseId` 是公共课程详情与学习决策页，`/learn/:courseId/:lessonId` 是受保护课程学习页，`/my-learning` 是学习记录入口；禁止为了阶段演示把正式播放器、进度、作答或 AI 会话重新塞回课程详情。
3. 公共课程中心对角色中立；学习空间按 `learning:access` 和领域访问事实授权，教学空间按 `teaching:access` 与所有权授权，管理空间按 `administration:access` 授权。角色和按钮隐藏不能替代服务端权限判断，教师账号可在后续按能力同时进入学习与教学空间。
4. 未接通真实契约的功能不得用假数据、空成功页、无效按钮或永久 `PlaceholderPage` 冒充完成。阶段未实现时应隐藏入口，或只在确有产品价值时给出明确不可操作说明，并在 page specs、backlog 和交付文档中标记真实状态。
5. 每个前端 Txx 只能实现页面规格分配给本任务的区域；后续任务应在稳定路由和布局内填充能力，不得重复创建“临时学习页”“临时管理页”或第二套登录/工作台导航。
6. 前端交付除类型检查和构建外，必须验证深链接、刷新恢复、权限、loading/empty/partial-error/401/403/404/409/422/429/offline 状态、响应式与基本键盘可访问性。播放器、上传、考试和 SSE 页面还必须验证离开页面后的实例、请求、定时器和临时 URL 清理。
7. 三端品牌、主导航槽位、标签和顺序必须与 `docs/frontend/page-specs.md` 的全局应用壳规格一致，不能随页面、登录状态或功能任务动态增删/换序。未交付槽位只能明确显示为不可操作的“建设中”，不得链接占位页；登录/头像、通知和工作台切换只能在固定账号工具区内改变状态。
8. 页面标题、课程名、面包屑和学习上下文必须位于页面内容区，禁止复用全局品牌标题造成“学生学习中心”等文案与导航重复。共享 `AppShell` 只负责渲染结构，不得内置角色业务判断；各应用通过稳定导航模型提供入口。

## 开发与测试数据规则

1. 构建、集成测试或功能验证缺少数据时，可以在专用开发/测试数据库中创建覆盖验收场景所需的最小合理伪数据。只能操作明确标识的专用数据库，例如 `edu_platform_dev`、`edu_platform_test`；数据库用途或来源不明确时必须停止操作并报告，不得修改、清空或覆盖其中的现有数据库和表。
2. 只能创建当前任务所属领域的数据库对象和数据，禁止提前创建其他任务的业务表或伪数据：
   - T02 不创建登录、课程、题库等业务表。
   - T04 才可创建用户、角色和登录测试数据。
   - T05 才可创建课程、章节和课时测试数据。
   - T09 才可创建题目和知识点测试数据。
   - T11 才可创建报名、进度、测评和作答测试数据。
3. 伪数据不得包含真实姓名、手机号、身份证、邮箱或任何用户隐私；必须使用 `student_test_01` 等明确测试标识，数量保持最小且只覆盖当前验收场景。
4. 伪数据必须结果确定、可重复生成、幂等并支持清理；重复执行不得产生无限重复数据。创建机制必须同时提供明确的清理或重新生成方式。
5. 优先在数据库迁移完成后通过 seed 脚本、测试 Fixture、集成测试初始化代码或明确标记为 `local`/`test` 的初始化器创建伪数据，不得依赖不可复现的手工数据库修改。
6. 生产配置下禁止自动初始化伪数据；禁止为方便测试而绕过权限、幂等或数据库约束；禁止在代码中固定使用 `root` 账号。数据库密码、数据库导出文件和任何真实业务数据禁止提交到 Git。
7. 最终答复必须说明创建了哪些数据库、表和伪数据，这些数据用于哪些验收场景，以及如何清理或重新生成。若未创建任何数据，也必须明确说明。

## 本地凭据规则

1. 不得依赖某个 Codex 对话中出现过的凭据信息。Windows 本地开发凭据统一通过 `scripts/Initialize-LocalEnvironment.ps1` 初始化到当前用户目录的 `.ccnu-edulion`，其中 MySQL 凭据使用 Windows DPAPI 加密，不进入仓库。
2. 需要本地凭据的命令必须通过 `scripts/Invoke-WithLocalEnvironment.ps1` 执行。禁止读取、打印、复制或在命令参数中拼接凭据文件内容；脚本或凭据文件缺失时停止数据库操作并报告。
3. 用户目录中的 `local.env` 只保存非敏感本地配置；密码、Token、AccessKey、Seed 密码和 JWT 密钥不得以明文写入该文件。需要新增其他秘密时必须使用本机密钥管理或同等级加密存储。
4. 共享本地配置中的 Schema 名称只是连接约定，不授权提前创建数据库、业务表或伪数据；实际操作仍必须遵守当前任务的数据范围。

## 代码注释与可读性规则

详细标准和 T01～T07 的存量技术债记录见 `docs/development/code-comment-guidelines.md`。所有新代码和本任务修改到的旧代码必须同时满足以下最低要求：

1. 面向人工阅读的业务类、公共接口、公共方法、复杂私有方法、状态机、消息消费者、定时任务、权限判断、幂等/重试/补偿逻辑必须有通俗中文注释。注释至少说明“功能是干什么的、主要输入是什么、输出/结果是什么”；存在异常、数据库/MQ/OSS 写入、状态变化、权限要求或重试副作用时必须一并说明。
2. Java 使用 Javadoc，Python 使用 docstring，TypeScript/JavaScript 使用 TSDoc/JSDoc；Vue 页面、组件和 composable 至少说明页面/组件职责、关键 props/emits 或数据来源。SQL/Flyway、OpenAPI、事件 Schema 和配置文件要对非显然的表、字段、状态、约束、默认值及风险添加说明。
3. 注释重点解释业务目的、边界、选择原因和失败语义，不得只把代码逐字翻译成中文。命名能够清楚表达的 getter/setter、简单赋值、标准框架样板和自解释测试不强制注释；复杂测试必须说明前置输入、操作和预期结果。
4. 输入必须说明业务含义、允许范围和关键空值语义；输出必须说明返回内容、状态变化或发出的事件。禁止写与真实实现不一致的注释，修改行为时必须同步更新注释。
5. 自动生成代码、构建产物和第三方代码禁止手工补注释；其说明应维护在 OpenAPI/Schema、生成源或适配层。注释不得包含密码、Token、AccessKey、真实签名 URL、个人隐私或生产数据。
6. 每个 Txx 交付前必须进行人工可读性检查，并在交付文档中记录注释覆盖情况；缺少必要注释属于未完成，不能只以测试通过代替。

## Git 推送规则

- 所有普通推送统一使用独立命令 `git push origin HEAD`。
- `git push` 必须直接申请在沙箱外执行，不先在沙箱内尝试。
- 禁止自动使用 `--force`、`--force-with-lease`、`--mirror` 或 `--delete`；这些操作必须获得针对当次操作的明确授权。

## 强制禁止事项

- 禁止跨服务查表、跨 Schema Join、跨服务外键或共享业务 Mapper；跨服务只允许版本化 API 或领域事件。
- 禁止任何前端绕过 Gateway 直连领域服务或 Python Agent。对象存储直传/播放仅可使用后端签发的短期 URL。
- 禁止 `course-service`、`iam-service`、`learning-service`、`operation-service` 或 Python Agent 自行接入 OSS SDK；课程封面/视频/课件、题目附件、AI 图片、头像/资质、运营图片和导入导出文件必须复用 `media-service`。业务表与写契约保存稳定 `mediaId`/`assetId`，不得保存 Bucket、Object Key 或短期签名 URL；读取响应中的 URL 只能是授权后的展示投影。阶段性 `coverUrl`/`avatarUrl` 的收口按 `docs/adr/0004-oss-media-asset-lifecycle.md` 执行。
- 禁止前端自行猜测 API 字段，禁止维护第二套手写 DTO 或 Mock Schema。
- 禁止提交密钥、永久签名 URL、数据库密码、大模型权重、FAISS 索引或用户隐私数据。
- 禁止在本任务中引入支付、Elasticsearch、Kubernetes，或把 `learning-service` 继续拆分。
- 禁止改写已合并的数据库迁移或事件版本；新增迁移只能向前演进。

## 交付文档规则

1. 每个 Txx 功能任务必须在同一任务中创建或更新一份独立交付文档，统一放在 `docs/delivery/`，文件名使用 `Txx-英文短名称.md`；不得只在最终对话答复中说明而不落盘。
2. 交付文档面向后续开发者、测试人员和演示人员，至少说明：任务目标与功能边界、主要架构和调用流程、实现的 API、数据库表与迁移、环境变量和启动方式、伪数据及清理/重新生成方式、测试命令与实际结果、人工演示步骤、已知风险与后续依赖，以及对应分支、PR（如已创建）和关键提交。
3. 文档内容必须与实际实现和验证结果一致；未执行的测试、未接通的外部依赖和未完成的功能必须明确标记，禁止把计划描述成已完成结果。
4. 任务完成时必须同步更新 `docs/roadmap/backlog.md` 和 `docs/roadmap/verification.md`。涉及接口、事件、环境配置或重大架构决策时，还必须分别更新 `docs/api/openapi.yaml`、`docs/events/*`、`docs/development/local-environment.md` 或 `docs/adr/*`。
5. 交付文档不得记录密码、Token、真实 AccessKey、永久签名 URL、真实用户隐私或其他敏感信息；测试账号只能记录无敏感性的固定标识，密码仍通过未跟踪的环境变量提供。

## 完成标准

变更必须通过所影响技术栈的构建、类型检查和测试；契约变更需重新生成客户端并验证 Mock；最终说明改动文件、实际执行的命令与结果、架构假设和未解决风险。
