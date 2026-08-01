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
