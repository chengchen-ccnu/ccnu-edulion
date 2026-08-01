# T07 通用媒体后端交付说明

## 1. 目标与边界

T07 提供平台唯一的通用媒体后端：浏览器经 Gateway 创建受控上传会话，再使用 media-service 签发的对象级短期 URL 直连私有阿里云 OSS；media-service 负责元数据复核、处理状态、短期访问、引用台账、可靠事件和过期回收。

首期完整启用 `COURSE_COVER` 与 `LESSON_VIDEO`。media-service 不保存课程、课时、题目、用户或运营实体，不判断这些领域对象是否可见；业务绑定只交换稳定 `mediaId`。Gateway 与领域服务不代理文件字节，且只有 media-service 引入 OSS SDK。本任务不实现 T08 页面，也不修改 T06 页面或既有业务表。

## 2. 架构与调用流程

1. 已认证的教师经 Gateway 调用媒体 API，Gateway 转发可信用户与角色头。
2. media-service 校验用途、角色、MIME、扩展名、大小、并发会话和容量配额，以 `clientRequestId` 与请求指纹提供幂等响应，并生成不可猜测的隔离区 Object Key。
3. 小文件获得单对象 PUT 授权；大文件初始化 OSS Multipart，按需签发分片 PUT 授权，完成时提交分片清单。浏览器只接触短期、单对象、用途受限的签名 URL。
4. 上传完成后，media-service 使用 OSS HEAD 复核对象存在性、大小、MIME 和声明的 SHA-256 元数据，再在同一数据库事务中创建媒体资产、处理任务、审计和 Outbox 事件。
5. Spring AMQP 发布 `MediaUploaded`。处理消费者按 `eventId` 持久化幂等，调用可替换 Worker；封面执行最小基线处理，视频通过 FFmpeg 生成 MP4 派生对象，成功后发布 `MediaReady`，失败记录 `MediaFailed`。
6. 只有 owner 可查询资产和为 `READY` 原件/派生对象申请短期 GET 授权。业务领域通过 `MediaReferenceBound`/`MediaReferenceReleased` 事件维护引用台账。
7. 定时回收器精确删除过期未完成会话、长期未绑定资产及其派生对象；对象删除和数据库状态转换均可重入，不使用 Bucket 列举。

RabbitMQ 不可用不会回滚已经提交的上传/资产事实：Outbox 保留记录并按退避策略补投。消费失败最多尝试 3 次，超限拒绝并进入 DLQ，不无限重新入队。

## 3. 契约

外部 REST 唯一事实来源为 `docs/api/openapi.yaml` 0.5.0，新增：

- `POST /api/v1/media/uploads`：创建单对象或 Multipart 上传会话。
- `GET /api/v1/media/uploads/{uploadId}`：查询会话。
- `DELETE /api/v1/media/uploads/{uploadId}`：取消会话。
- `POST /api/v1/media/uploads/{uploadId}/parts/authorize`：签发分片授权。
- `POST /api/v1/media/uploads/{uploadId}/complete`：完成上传并触发复核/处理。
- `GET /api/v1/media`、`GET /api/v1/media/{mediaId}`：分页和详情查询。
- `POST /api/v1/media/{mediaId}/access-grants`：签发短期读取授权。

事件唯一事实来源为 `docs/events/media-events.v1.yaml`。所有事件携带 `eventId`、`eventType`、`eventVersion`、`aggregateId`、`occurredAt`、`traceId` 和 `payload`；当前定义 `MediaUploaded`、`MediaReady`、`MediaRejected`、`MediaFailed`、`MediaDeleted`、`MediaReferenceBound` 与 `MediaReferenceReleased`。数据库和事件均不保存短期签名 URL。

## 4. 数据库与迁移

向前迁移 `V1__create_media_tables.sql` 只在 media-service Schema 创建以下 10 张表，不建立跨 Schema 外键：

- `upload_session`：上传会话、幂等指纹、过期时间和状态。
- `upload_part`：Multipart 分片授权/完成信息。
- `media_asset`：稳定资产 ID、私有原件 Object Key、用途和状态。
- `media_variant`：FFmpeg 等 Worker 生成的派生资产。
- `processing_job`：处理任务及 `PENDING/RUNNING/SUCCEEDED/FAILED/CANCELED` 状态。
- `media_reference`：跨领域引用台账，不保存领域实体。
- `outbox_event`：可靠事件、发布状态、尝试次数和下次重试时间。
- `consumed_event`：按 `eventId` 持久化消费幂等。
- `media_audit_record`：状态与管理动作审计。
- `access_grant_audit`：短期访问授权审计，不保存签名 URL。

自动化测试使用 H2 MySQL 模式，进程退出即销毁。真实验收只创建并操作 `edu_platform_media_dev`，其中保留上述表与 `flyway_schema_history`，没有创建其他领域表。最终真实验收结束后 10 张数据表中的测试行均应为 0。

实施过程中首次创建专用 Schema 并应用 V1 后，又为迁移文件补充了不改变表结构的中文说明，Flyway 因校验和变化按设计拒绝继续运行。经只读确认该 Schema 仅含本任务 10 张表、`flyway_schema_history` 和本轮残留测试事件，并获得针对该专用 Schema 的删除授权后，精确删除并重建了 `edu_platform_media_dev`，再以最终 V1 完成迁移和真实验收；没有删除、清空或修改其他 Schema。

## 5. 配置与启动

所有本地命令必须通过仓库外凭据加载器执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& '.\scripts\Invoke-WithLocalEnvironment.ps1' -ScriptBlock { mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl media-service -am spring-boot:run }"
```

media-service local profile 使用 MySQL、Redis 逻辑 DB 3、RabbitMQ、OSS 与 FFmpeg。配置名和非敏感默认值见 `.env.example` 与 `docs/development/local-environment.md`。MySQL 密码继续由 DPAPI 管理；远程 OSS AccessKey 只允许从当前进程环境或同等级密钥管理注入，不得写入 `local.env`、仓库、日志或命令参数。默认非 local 构建禁用外部媒体适配器，保证基础构建无需真实基础设施。

## 6. RabbitMQ 资源与可靠性

- 业务 Exchange：`edu.media.events.v1`。
- 死信 Exchange：`edu.media.dlx.v1`。
- 队列：`edu.media.processing.v1`、`edu.media.references.v1`、`edu.media.lifecycle.v1`。
- DLQ：`edu.media.dlq.v1`。
- 发布：持久消息、mandatory、publisher confirm/return；仅确认后标记 Outbox 已发布，失败保留并有限退避补投。
- 消费：成功后确认；异常最多 3 次总尝试、指数退避，超限拒绝进入 DLQ；`consumed_event` 保证服务重启后仍按 `eventId` 幂等。
- 可观测性：事件信封与 AMQP header 传播 `traceId`，日志不输出消息体或凭据。

RabbitMQ 资源为可复用基础设施，验收后保留；测试产生的毒消息已从 DLQ 取出，唯一重投测试队列已删除。

## 7. 测试数据、OSS 对象与清理

真实验收使用固定测试 owner `00000000-0000-4000-8000-000000007901`，`clientRequestId`、`eventId` 和 Object Key 每次运行唯一。OSS 仅在 `t07-acceptance/<runId>/` 下创建最小 PNG、最小合成视频、Multipart 对象和 MP4 派生对象；测试保存自己创建的精确 Key 并逐个删除，禁止列举、覆盖或删除其他对象。

重新生成与清理均由以下受控测试完成：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& '.\scripts\Invoke-WithLocalEnvironment.ps1' -ScriptBlock { `$env:MEDIA_REAL_ACCEPTANCE='true'; mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl media-service -am -Dtest=MediaRealEnvironmentAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false test }"
```

测试启动时拒绝非 `edu_platform_media_dev` 数据库，结束时停止监听器，再按测试 owner、唯一事件 ID、上传/资产 ID 和精确 Object Key 清理。本任务没有创建真实姓名、手机号、邮箱或其他隐私数据，没有提交 OSS 对象、数据库导出、视频或转码产物。

## 8. 实际验证结果

- 环境预检：MySQL、Redis、RabbitMQ TCP 可达；Redis DB 3 `PING` 返回 `PONG`；FFmpeg 8.0 可执行。
- 真实环境验收：最终 `MediaRealEnvironmentAcceptanceTest` 1 项通过（15.85 秒）。覆盖 OSS 单对象/Multipart 直传、HEAD、访问和精确清理；FFmpeg 合成视频处理；RabbitMQ confirm、return、消费、有限重试、DLQ、幂等、未确认重投、Outbox 补投与 Broker 暂时不可用保留，并逐表断言 10 张 T07 数据表清理后均为 0。
- Java：完整 Maven `clean verify` 的 8 个 Reactor 模块全部通过（1 分 28 秒）；Gateway 9 项、media-service H2 5 项通过，真实环境测试在普通构建中按设计跳过并由上述显式命令单独执行。
- Frontend：`generate:api`、`typecheck`、4 个测试文件共 20 项测试及三端 `build` 通过。Prism 成功加载 OpenAPI 0.5.0，媒体分页 GET 返回 200；媒体 POST 的 PowerShell 客户端在工具层超时且 Prism 未收到，因此未记为 Mock 成功。
- Python：`compileall`、8 项既有单元测试和 `--no-deps` wheel 构建通过。
- 代码质量：`git diff --check`、敏感关键词/大文件/构建产物审计和中文注释审计通过；生成客户端的 `check:api` 在提交前按设计检测到相对 `HEAD` 的 T07 差异，提交后复跑确认。最终结果再由 PR CI 验证。

验证过程中曾依次发现并修复：目标 Schema 未初始化、Spring 测试参数优先级、OSS SDK 对分片清单原地排序、RabbitMQ 4.3 对临时队列类型的限制，以及监听器与清理的竞争条件。最终通过记录基于修复后的当前迁移与代码，不把早期失败写成成功。

## 9. 人工演示

1. 按本地环境文档启动 media-service 与 Gateway，不启动 T08 页面。
2. 使用教师身份经 Gateway 创建 `COURSE_COVER` 单对象会话，按返回的短期 PUT URL 上传最小图片，完成后轮询资产至 `READY`，再申请短期 GET 授权。
3. 创建 `LESSON_VIDEO` Multipart 会话，获取分片授权、上传并提交 ETag 清单；观察处理任务从 `PENDING` 到 `RUNNING` 再到 `SUCCEEDED`，资产变为 `READY`。
4. 使用相同 `clientRequestId` 和相同请求重放，确认返回同一会话；更改请求内容后重放，确认得到幂等冲突。
5. 验证浏览器只访问 Gateway 的 JSON API 和 OSS 短期 URL，不访问 media-service 内网地址，也不由 Gateway 传输文件字节。

## 10. 已知风险与后续依赖

- 当前 Worker 是最小可验证的 FFmpeg MP4 派生边界，不包含生产 HLS/多码率、截图、字幕或独立 Worker 调度；这些能力可在不改变上传/资产契约的前提下替换实现。
- 目前完成 OSS HEAD、大小/MIME/SHA 元数据复核和视频解码，但尚未接入真实病毒扫描、恶意文档检测和内容安全服务。生产开放前必须接入，并将拒绝路径落为 `MediaRejected`。
- 首期只启用 `COURSE_COVER`/`LESSON_VIDEO`；其他已枚举用途必须由后续任务补充独立策略和领域授权后才能启用。
- owner-only 访问授权是 T07 的安全下界；课程发布可见性、选课权限和审核可见性必须由 T08 及后续领域服务判定，不能下沉到 media-service。
- 真实 OSS 验收依赖专用开发/测试 Bucket 与外部网络，CI 只运行 H2/适配器自动化，不应持有远程 AccessKey。

## 11. 中文注释审计

按仓库注释规范检查了 T07 新增/修改的人维护代码：应用入口、配置、领域枚举/记录、存储适配器、服务、持久化、消息拓扑与重试、Worker、HTTP 控制器、Flyway 迁移和集成测试均说明了职责；涉及签名 URL、OSS 删除、状态转换、事务、幂等、Outbox、确认/重试/死信和临时文件清理的位置补充了输入输出、副作用及安全边界。未向生成的 OpenAPI TypeScript 文件添加手工注释。

## 12. 分支、提交与 PR

- 分支：`codex/t07-media-backend`
- 关键实现提交：`2659062e30f1c603e225dba6f993d8618734c771`
- PR：[#9 T07 通用媒体后端](https://github.com/chengchen-ccnu/ccnu-edulion/pull/9)，状态为 Ready for review；由用户决定是否合并。
