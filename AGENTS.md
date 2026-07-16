# EDU Platform repository rules

本仓库是在线网课平台 Monorepo。开始任务前必须先检查 `git status`，保护用户已有修改；只修改任务声明范围，不覆盖、删除或格式化无关文件。

## 模块边界

- `java-platform/gateway-service`：浏览器统一 API 入口，只做路由、鉴权、限流、追踪和错误适配；不保存领域数据，不代理视频文件。
- `java-platform/iam-service`：账号、身份、角色、权限和登录审计；不保存课程或答题数据。
- `java-platform/course-service`：课程、版本、章节、课时、发布和课程访问规则；不拥有媒体文件或学习进度。
- `java-platform/media-service`：上传会话、媒体元数据、转码状态和短期播放授权；不决定课程可见性。
- `java-platform/learning-service`：报名、进度、题库、测评、作答和成绩；首期有意不再拆服务。
- `java-platform/operation-service`：互动、热点、通知、审核和运营配置；只能通过 API/事件读取其他领域事实。
- `java-platform/shared-kernel`：仅共享错误信封、Trace、事件信封等技术基础；禁止放业务实体、Mapper、Repository 或跨域 DTO。
- `edu_agent_app`：现有 Python 数学智能体，负责检索、推理和讲解；不得直连或写入 Java 业务数据库。不要在平台任务中重新实现它。
- `web-apps/apps/*`：学生、教师、管理三个独立应用；共享技术包位于 `web-apps/packages/*`，禁止共享带角色业务判断的整页组件。
- `docs/api/openapi.yaml` 是外部 REST API 字段的唯一事实来源；`docs/events/*` 是事件事实来源。

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

## 强制禁止事项

- 禁止跨服务查表、跨 Schema Join、跨服务外键或共享业务 Mapper；跨服务只允许版本化 API 或领域事件。
- 禁止任何前端绕过 Gateway 直连领域服务或 Python Agent。对象存储直传/播放仅可使用后端签发的短期 URL。
- 禁止前端自行猜测 API 字段，禁止维护第二套手写 DTO 或 Mock Schema。
- 禁止提交密钥、永久签名 URL、数据库密码、大模型权重、FAISS 索引或用户隐私数据。
- 禁止在本任务中引入支付、Elasticsearch、Kubernetes，或把 `learning-service` 继续拆分。
- 禁止改写已合并的数据库迁移或事件版本；新增迁移只能向前演进。

## 完成标准

变更必须通过所影响技术栈的构建、类型检查和测试；契约变更需重新生成客户端并验证 Mock；最终说明改动文件、实际执行的命令与结果、架构假设和未解决风险。
