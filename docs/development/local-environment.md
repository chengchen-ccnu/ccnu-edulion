# Local development environment

## 2026-09-08 Agent 新架构前置

目标架构见 [Agent 重设计](../architecture/agent-platform-design.md)。旧 Python 代码仍加载 FAISS；新 T13 系列改为 Qdrant、Neo4j 派生图和独立 Index Worker。不能把旧启动条件或已有 8 项测试当作新架构验收。

| 组件 | 已取得证据 | 后续要求 |
|---|---|---|
| Java Agent Access | T14.1 已通过 PR #57 合并；T13.3 的 6 项 Java Agent Access/shared-kernel 回归通过 | local 端口 8086；只有显式 local/test profile 启用持久化；课程检索候选再次授权端点尚未冻结，Python 当前 fail-closed |
| bge-base-zh | `I:\大模型学习\edu_agent\bge-base-zh` 已用 CPU 完成 2 条中文文本真实推理，输出 768 维有限向量 | T13.2 增加明确 `BGE_MODEL_PATH` 配置并建立代表样本阈值，不把一次加载成功当成检索质量验收 |
| bge-reranker-base | `I:\大模型学习\edu_agent\bge-reranker-base` 已用 PyTorch 2.4.1/Transformers 4.57.5 离线加载；最小相关/无关文本排序通过 | T13.2 增加 `RERANKER_MODEL_PATH` 和锁定依赖；模型不进入 Git，真实候选集继续验证排序质量与延迟 |
| Qdrant | 1.19.1，127.0.0.1:6333/6334 可达；T13.2 已用 `qdrant-client==1.19.0` 建立四集合 | 使用 `multilingual` tokenizer 的服务端 BM25；模型/Schema 版本不一致会拒绝混写，升级前显式清理与重建 |
| Neo4j | Community 5.26.19，127.0.0.1:7687/7474；T13.2 已完成认证、受控约束/投影和有界查询 | 开发期密码可暂由仓库外 local.env 注入，项目完成后必须迁入 `neo4j.credential.clixml`/正式 Secret 并删除明文行 |
| MySQL/Redis/RabbitMQ | RabbitMQ 4.3.4 已完成 confirm、手动 ACK、有限重试和 DLQ 真实验收 | Worker 凭据使用 `rabbitmq.credential.clixml`/正式 Secret；MySQL 只由 Java 事实源访问，Python 不跨库读表 |
| Chat/Vision | T13.3 真实文本/视觉各 1 次调用通过，实际模型 `qwen3.8-flash`；分别为 142/269 总 Token、1848/2288ms | `CHAT_MODEL_NAME`/`VISION_MODEL_NAME` 可同模型；只记录计数、Token 和阶段延迟，不输出秘密、完整提示或完整回答 |
| Media | 复用既有平台链路；T14.1 已补 AI 图片 owner/引用/短期读取投影 | Python 不接 OSS SDK；后续 Agent 任务只消费 Java 授权结果 |

地址、模型路径是非敏感配置，密码/密钥按项目安全加载器处理。T13.2 的 Qdrant、BGE、reranker、Neo4j、RabbitMQ、Java 事实源和 Worker 参数见 `.env.example`。既有知识事实从 Java 分页内部 API 重建，不再次导入全量原题库。
索引 Worker 与在线 Agent 独立启动并限制资源，本地批量任务错峰；图故障跳过前置增强，检索故障按授权边界降级。新方案不要求 PostgreSQL、Milvus、Elasticsearch、Kubernetes 或另一套 OSS 客户端。

### T13.2 Index Worker 本地启动

Python 需 3.10+；项目声明锁定 `qdrant-client==1.19.0`、`pika==1.3.2`，复用 sentence-transformers、transformers 和 neo4j。大模型目录只配置绝对/相对本地路径，不下载或提交权重。首次配置 RabbitMQ/Neo4j 本地凭据：

```powershell
& .\scripts\Initialize-LocalRabbitMq.ps1
& .\scripts\Initialize-LocalNeo4j.ps1
```

随后用统一包装器执行初始化、重建、对账或消费：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
  & python -m edu_agent_app index-worker --mode init
}
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
  & python -m edu_agent_app index-worker --mode rebuild
}
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
  & python -m edu_agent_app index-worker --mode reconcile
}
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
  & python -m edu_agent_app index-worker
}
```

默认状态库 `.m2/agent-index/worker-state.sqlite3` 保存 eventId、最高 revision/tombstone 和分页检查点，一个状态库只对应一个 namespace。重建中断后直接重新执行 `--mode rebuild`；只有明确要从 Java 起点重新遍历时才加 `--restart-rebuild`。清理必须先停该 namespace 全部 Worker，再使用 `--mode cleanup --confirm-namespace <AGENT_INDEX_NAMESPACE>`；它只删该 namespace 的 Qdrant 点、Worker 标签 Neo4j 节点和专属状态表行，不删 collection 或来源不明图，清空状态后可重新应用相同 revision。

当前开发机的 `NEO4J_PASSWORD` 暂存在仓库外 `~/.ccnu-edulion/local.env`。项目全部开发结束前，运行 `Initialize-LocalNeo4j.ps1` 并验证包装命令，然后删除 local.env 中该密码行；RabbitMQ 密码若曾放入 local.env，也应迁至 `rabbitmq.credential.clixml` 后删除。生产/共享环境一律使用部署 Secret 管理，不复制 DPAPI 文件。


T02 使用开发机已经运行的 MySQL `127.0.0.1:3306` 和 Redis `127.0.0.1:6379`。对象存储使用阿里云 OSS，消息队列由 [ADR-0005](../adr/0005-rabbitmq-messaging.md) 统一为 RabbitMQ。T07 已实现 `media-service` 的 OSS、RabbitMQ 和 FFmpeg 链路；本地基础构建仍不依赖 Docker 或外部服务。

## 配置约定

Java 服务仅在显式设置 `SPRING_PROFILES_ACTIVE=local` 时加载各模块的 `application-local.yml`。非 local profile 会关闭 JDBC/Redis 自动配置，继续支持不依赖外部服务的基础启动；local profile 才启用连接和健康组件。所有地址、端口、凭据、Schema、Redis DB、OSS 和 RabbitMQ 参数都由环境变量覆盖；仓库中的 `.env.example` 只有空值或安全示例。

多个 Codex 任务或 Git Worktree 不共享对话上下文，也不应复制明文密码。Windows 开发机首次配置时运行：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\scripts\Initialize-LocalEnvironment.ps1
```

脚本会显示 Windows 安全凭据输入框，将非敏感配置保存到当前用户目录的 `.ccnu-edulion/local.env`，并使用 Windows DPAPI 将 MySQL 凭据加密为同目录下的 `mysql.credential.clixml`。T08A.1 起还会自动生成独立 256-bit 课程/媒体服务授权密钥并加密为 `course-media-auth.credential.clixml`；T14.1 再生成 `agent-index-service.credential.clixml`，供独立 Index Worker 调用 Course/Learning 内部索引源。已有本地环境可直接重跑初始化脚本：它只补齐缺失密钥和缺少的 Agent 非敏感配置行，不覆盖 MySQL 凭据或其他 `local.env` 行。加密文件只能由同一台计算机上的同一 Windows 用户解密，不会进入 Git。

需要凭据的命令通过受控脚本执行，例如：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl course-service -am spring-boot:run
}
```

`Set-ExecutionPolicy` 只对当前 PowerShell 进程生效，不修改用户级或系统级执行策略。加载脚本只在子命令执行期间注入环境变量，结束后恢复原值，并且不会打印凭据。不要直接读取或输出凭据文件，也不要把密码、JWT 密钥或云资源密钥写入 `local.env`、PowerShell 历史、命令参数、日志或提交内容。非 Windows 环境应改用操作系统密钥管理工具或 CI Secret，并保持相同的环境变量接口。

T08A.1 的加载脚本把 DPAPI 服务密钥临时注入为 `COURSE_MEDIA_AUTH_SECRET`，同时供 course-service 签发和 media-service 验证。生产/CI 必须通过各自 Secret 管理提供同名变量，不能复用 `IAM_JWT_SECRET`。非敏感参数 `COURSE_MEDIA_AUTH_ISSUER`、`COURSE_MEDIA_AUTH_AUDIENCE`、`COURSE_MEDIA_AUTH_TOKEN_TTL`、`MEDIA_SERVICE_AUTH_MAX_TOKEN_TTL` 和 `MEDIA_SERVICE_AUTH_CLOCK_SKEW` 可由环境覆盖；默认分别为 `edu-platform-course-service`、`edu-platform-media-service`、`30s`、`1m` 和 `5s`。

六个领域服务的 MySQL Schema 变量分别是 `IAM_MYSQL_DATABASE`、`COURSE_MYSQL_DATABASE`、`MEDIA_MYSQL_DATABASE`、`LEARNING_MYSQL_DATABASE`、`OPERATION_MYSQL_DATABASE` 和 `AGENT_MYSQL_DATABASE`。T02 不创建 Schema 或业务表；后续任务应保持每服务独立 Schema/账号。当前本地管理员账号只作为临时开发约定，通过 `MYSQL_USERNAME` 和 `MYSQL_PASSWORD` 注入，不能成为生产配置。

Redis 默认将 Gateway 和五个领域服务分配到逻辑 DB 0 到 5；这些编号都可覆盖。Redis 不是正式成绩、课程权限或其他业务事实源。

## 只读连通性验证

先确认端口，不读取或修改任何用户数据：

```powershell
Test-NetConnection 127.0.0.1 -Port 3306
Test-NetConnection 127.0.0.1 -Port 6379
redis-cli -h 127.0.0.1 -p 6379 PING
```

MySQL 客户端使用单独的 `-p` 让终端交互式询问密码，不要把密码拼到命令中。以下语句只执行常量查询，并显式使用只读事务：

```powershell
mysql -h 127.0.0.1 -P 3306 -u <本地管理员用户名> -p -e "START TRANSACTION READ ONLY; SELECT 1 AS connectivity_ok; ROLLBACK;"
```

若本次只验证 Spring Boot 连接且尚未建立服务 Schema，可以临时把对应 `*_MYSQL_DATABASE` 指向管理员可读的 `information_schema`；不要运行迁移或 DDL。启动服务后检查 `/actuator/health` 中的 `db` 和 `redis` 组件：

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:MYSQL_USERNAME = "<仅在当前终端设置>"
$env:MYSQL_PASSWORD = Read-Host "MySQL password"
$env:IAM_MYSQL_DATABASE = "information_schema"
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl iam-service -am package
java -jar java-platform/iam-service/target/iam-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

在另一个终端执行：

```powershell
Invoke-RestMethod http://127.0.0.1:8081/actuator/health
```

验证完成后用 `Remove-Item Env:MYSQL_PASSWORD` 清除当前进程变量。其他服务替换 Maven 模块名和对应 Schema 变量；端口依次为 Gateway 8080、IAM 8081、Course 8082、Media 8083、Learning 8084、Operation 8085、Agent Access 8086。

## T07 的 OSS、RabbitMQ 与转码环境

`media-service` 是唯一使用 OSS SDK 的业务服务。它根据用途生成不可猜测 Object Key，只向浏览器签发单对象、单操作、短时效的上传或访问 URL。Gateway 和领域服务不代理文件字节，也不获得服务端 AccessKey。资产用途、访问等级和后续任务见 [ADR-0004](../adr/0004-oss-media-asset-lifecycle.md)。

T07 可以先使用 OSS/MQ 适配器 Mock 完成契约、单元测试和不依赖外部服务的构建，因此开始编码前不强制安装 RabbitMQ、FFmpeg 或申请真实 OSS Bucket。真实端到端验收按能力分层准备：

| 前置项 | 何时需要 | 本地约定 |
|---|---|---|
| JDK 17+、Maven 3.6.3+ | T07 开发与 Java 验证必需 | 当前仓库基线；Spring AMQP 与 OSS SDK 作为 Maven 依赖，不需要全局安装 |
| Node 20.19+、pnpm | 修改 OpenAPI 后生成客户端、类型检查和 Mock 验证必需 | 使用仓库现有 `web-apps` 命令，不在 T07 开发媒体页面 |
| MySQL | 真实 `media-service` 本地验收必需 | 只连接用途明确的 `edu_platform_media_dev`；T07 通过向前 Flyway 迁移创建媒体域表，不得跨 Schema 建外键 |
| Redis | 本地缓存、短期会话或限流链路需要时使用 | 复用 `127.0.0.1:6379`，`media-service` 默认逻辑 DB 3；Redis 不是媒体资产事实源 |
| RabbitMQ 4.x + 兼容 Erlang/OTP | 真实发布、消费、重试、死信和重启恢复验收必需 | Windows 推荐 RabbitMQ 4.3.x 与 Erlang/OTP 27.x；应用端口 `5672`，管理 UI `15672` 仅本机使用 |
| 阿里云 OSS 专用开发/测试 Bucket | 真实直传、Multipart、HEAD 校验和短期访问验收必需 | 使用独立前缀和最小权限 RAM/STS；没有明确授权时必须使用 Mock，不读取个人或生产 Bucket |
| FFmpeg（含同目录 ffprobe） | 本机真实视频转码验收需要 | T08A 产生私有 `hls-v1` 播放清单和切片；单清晰度，第一条音轨可选并编码为 AAC，无音轨输入仍可转码；截图、多码率、多音轨选择、字幕和 DRM 不在本轮范围 |
| 内容安全/病毒扫描服务 | 生产公开前需要 | T07 的 OSS HEAD/哈希复核与 FFmpeg 可解码验证不等价于内容安全或病毒扫描；这是已知上线依赖 |

Windows 原生 RabbitMQ 的建议准备顺序是：安装一个受 RabbitMQ 支持的 64 位 Erlang/OTP 27.x，再安装 RabbitMQ 4.3.x；安装目录使用 ASCII 且尽量不含空格，以管理员权限安装服务。管理插件是可选的，可用于本机查看 Exchange、Queue 和死信。只对本机开放应用端口 `5672` 和可选管理端口 `15672`，不要把集群/CLI 端口暴露到公网。建议创建专用 vhost `/edu-platform` 和最小权限本地用户，不直接沿用默认 `guest` 作为共享或生产账号。

T07 使用 `RABBITMQ_ENABLED`、`RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_VHOST`、`RABBITMQ_USERNAME` 和 `RABBITMQ_PASSWORD`。本机 RabbitMQ 密码使用 `Initialize-LocalRabbitMq.ps1` 保存到当前用户 DPAPI 文件，并由统一包装器临时注入；不得新写入 `.ccnu-edulion/local.env`。OSS AccessKey、Security Token 及其他远程 API 凭据只能由调用进程环境或同等密钥管理注入，禁止写入 `local.env` 或仓库。

安装后可用 RabbitMQ 自带命令做不包含凭据的只读健康确认：

```powershell
rabbitmq-diagnostics.bat ping
rabbitmqctl.bat status
Test-NetConnection 127.0.0.1 -Port 5672
```

RabbitMQ 仍可在本地保持 `RABBITMQ_ENABLED=false`。此时 T07 应能完成基础构建与 Mock 测试，但发布确认、重试、死信、重启恢复和 Outbox 补投必须明确记录为尚未执行的真实集成验收。

T07 真实验收由 `MediaRealEnvironmentAcceptanceTest` 显式开启，测试在启动前会校验 `MEDIA_MYSQL_DATABASE` 严格等于 `edu_platform_media_dev`，OSS 只使用 `t07-acceptance/<runId>/` 唯一前缀，不列举 Bucket，最终只删除本轮精确 Object Key 和固定测试 owner 的记录：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:MEDIA_REAL_ACCEPTANCE = 'true'
    $env:RABBITMQ_ENABLED = 'true'
    $env:OSS_ENABLED = 'true'
    $env:MEDIA_WORKER_ENABLED = 'true'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl media-service -am '-Dtest=MediaRealEnvironmentAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
}
```

## T08A 课程媒体绑定与 HLS 参数

T08A 只在用途明确的 `edu_platform_course_dev` 与 `edu_platform_media_dev` 上向前迁移，不创建数据库。Course V2 为 `course_version` 增加可空 `cover_media_id`，将历史 `cover_url` 放宽为只读兼容，并创建 `lesson_media_resource` 与课程本域 `outbox_event`；Media V2 创建 `media_variant_component` 与 `temporary_access_object`。两侧不建立跨 Schema 外键。自动化测试使用随进程销毁的 H2 `course_test`、`course_seed_test` 和 `media_test`，不需要真实 MySQL、OSS、RabbitMQ 或 FFmpeg。

启动本地链路时先启动 media-service，再启动 course-service，并通过仓库规定的凭据包装脚本注入两个专用 Schema 的连接配置。`course-service` 使用 `MEDIA_SERVICE_BASE_URL` 调用 JSON 绑定校验；它不接收 OSS Bucket、Object Key 或 AccessKey。设置 `RABBITMQ_ENABLED=true` 后，两服务复用 `edu.media.events.v1`，课程绑定事务写本域 Outbox，media-service 按 `eventId` 幂等消费引用事件。

与资源占用相关的非敏感参数如下：

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `MEDIA_WORKER_MAX_CONCURRENT_JOBS` | `1` | 单个 media-service 进程允许同时运行的 FFmpeg 作业上限；本机不要无评估调大 |
| `MEDIA_HLS_SEGMENT_DURATION` | `6s` | VOD HLS 目标切片时长 |
| `MEDIA_FFMPEG_TIMEOUT` | `2m` | 单作业子进程上限，超时会失败并交给 MQ 有限重试 |
| `COURSE_MEDIA_REQUEST_TIMEOUT` | `3s` | 课程绑定校验调用 media-service 的超时 |
| `COURSE_MEDIA_OUTBOX_MAX_ATTEMPTS` | `20` | 课程引用事件发布失败上限，耗尽后保留 `FAILED` 供审计/人工重放 |

HLS 清单、切片和授权临时清单均为私有 OSS 对象。访问授权只返回短期 URL；数据库仅保留精确 Object Key 以便到期清理。FFmpeg 必选第一条视频流、可选第一条音频流；音频存在时编码为 AAC 128 kbit/s、48 kHz 双声道并封装为 MPEG-TS HLS，无音轨输入不会因找不到音轨失败。真实环境验收继续使用上方 `MediaRealEnvironmentAcceptanceTest` 开关：用例通过 lavfi 生成带 1 kHz 正弦波的输入，由 ffprobe 先断言源 video/audio，再断言真实 OSS 下载的首个 TS 切片包含 video/AAC audio；同时覆盖无音轨转码、清单/切片短期读取、重试/死信和按本轮标识精确清理。若未提供真实环境条件，测试会明确跳过，不能记录为已执行。

更新 `FfmpegCommandRunner`、FFmpeg 参数或 Worker 代码后必须完整重启 media-service；Git 文件变化不会替换已运行 JVM 中的旧类。已经达到 `READY` 的 HLS 变体不会因服务重启、课程重新保存或重新发布而自动转码。验证修复时应先确认新 media-service 已启动，再创建新的上传会话并等待新资产完成处理；旧无声资产需要由新 Worker 重新上传生成，不应继续绑定旧 mediaId。

T08A 不增加 Seed，也不会自动给 T05 固定课程绑定媒体。若人工使用 T05 固定课程进行验收，仍可执行 `cleanup-course-seed.sql`；脚本已按外键逆序清理该固定课程的课时媒体引用及对应课程 Outbox。重新生成方式仍是显式设置 `COURSE_SEED_ENABLED=true` 后重启 course-service。

## T08A.1 课程播放授权参数与迁移

浏览器只经 Gateway 调用 course-service 的课程资源播放授权接口；Gateway 不配置 `/internal/**` 路由。course-service 根据当前发布/可编辑版本、课程访问类型、课时试看标志、课程 owner 和资源绑定作出决定，再使用短时一次性服务 JWT 调用 media-service。media-service 不查询 course/learning Schema，并继续只对用途匹配且 `READY` 的资产签发短期 OSS/HLS 授权。

T08B 浏览器直传要求专用 OSS Bucket 配置精确 CORS：只允许实际部署的 teacher Web Origin（本地为 `http://localhost:5174`）使用 `PUT`，允许签名授权返回的请求头，并在响应中暴露 `ETag`，否则 Multipart 无法收集服务端实际分片标签并安全完成。生产环境不得用 `*` 放宽 Origin；短期 PUT/GET URL、授权请求头和 HLS 清单 URL 只能保存在组件运行时内存，不得写入 `localStorage`、日志或业务表。

Media V3 新增 `consumed_service_authorization`，以 jti 主键拒绝服务令牌重放并在到期后由维护任务清理。表中不保存 JWT、签名 URL、课程 ID、课时 ID、Object Key 或 Bucket。应用不会创建 `edu_platform_media_dev` 数据库；只在用途明确的 media dev/test Schema 中由 Flyway 向前迁移。T08A.1 没有 Course 迁移、Seed 或报名数据。

## T12F.1 课程内容、资源授权与自动海报

T12F.1 复用现有 MySQL、OSS、RabbitMQ、FFmpeg/ffprobe 和本机加密凭据，不要求安装新的基础软件，也不引入第二套对象存储客户端。OpenAPI 已从 0.18.0 向前演进到 0.19.0，并分别在用途明确的开发 Schema 应用 Course V4、Media V5 和 Learning V12；不得修改既有迁移、启用 baseline/out-of-order、跨 Schema 建外键或自动创建数据库。

启动和真实验收仍通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入既有配置，推荐顺序为 RabbitMQ → media-service → course-service → learning-service → Gateway。`course-service` 只能通过 `MEDIA_SERVICE_BASE_URL` 和版本化内部授权访问媒体；它与 learning-service 都不接收 OSS AccessKey、Bucket 或 Object Key。T12F.1 不新增必须写入 `local.env` 的秘密；若实现需要新的非敏感调优参数，应在交付文档中给出默认值，秘密继续由现有 DPAPI/进程环境注入。

真实验收至少覆盖：

- `ARTICLE_IMAGE` 的 JPG/JPEG、PNG、WebP 单对象直传与类型/10 MiB 上限，正文引用/绑定一致性、错用途和非 READY 拒绝；
- PDF/普通图片的 `INLINE` 与下载、PPTX/TXT 的下载动作，以及按课程、课时、资源行解析的短期授权；不得输出或持久化 Bucket、Object Key、签名 URL；
- 新视频在单清晰度 HLS 和 `poster-v1` 都成功后进入 READY，截图输出 WebP、最长边不超过 1280且不上采样；RabbitMQ confirm/有限重试/DLQ、处理失败和临时文件清理；
- 受保护课时内容的 owner/已报名/登录试看/未授权边界，以及图文完成命令的 0→100 单调、稳定请求 ID 幂等；
- 只操作 `edu_platform_course_dev`、`edu_platform_media_dev`、`edu_platform_learning_dev` 和唯一开发 OSS 前缀，结束后精确复核临时表记录、消息、OSS 对象和本地转码目录。

T12F.1 提供一套显式、生产禁用、固定 ID 的跨域 local/test 演示数据，分别由 Media、Course、Learning 自己的 Seed 测试操作本域 Schema，顶层脚本只负责编排，不跨库 Join/外键/事务。数据包含一门已发布课程、一个短视频课时、自动 `poster-v1` 与独立自定义海报、一个含公式/表格/代码/正文图片的图文课时、PDF/TXT/PPTX，以及初始未完成的学习进度。视频、图片、有效最小 PDF/PPTX 均在临时目录内合成，不提交二进制；临时目录由用例退出时精确删除。

所有命令从仓库根目录执行；脚本先经 `Invoke-WithLocalEnvironment.ps1` 注入凭据，再确认三个固定开发 Schema 以及 `OSS_ENDPOINT`、`OSS_REGION`、`OSS_BUCKET` 和访问凭据均非空。任何一项缺失都会在修改任一领域前失败，不能把本地目录或 Mock 冒充真实 OSS：

```powershell
# 固定 ID 已完整存在时只校验并保持幂等。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12F1CourseContentDemoSeed.ps1 -Action Initialize

# Learning → Course → Media 逆序精确删除固定进度/事实、课程子表和数据库记录所指向的 Object Key。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12F1CourseContentDemoSeed.ps1 -Action Cleanup

# 先精确清理再以相同 ID 重建，并把图文学习进度恢复为 0%。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12F1CourseContentDemoSeed.ps1 -Action Rebuild
```

可独立运行不访问 OSS 的真实 FFmpeg 海报验收。它在 JUnit 临时目录合成一秒黑场加四秒测试画面，确认跳过黑场关键帧、WebP、宽高比与不上采样，并由 JUnit 清理全部文件：

```powershell
$env:T12F1_FFMPEG_REAL_ACCEPTANCE = 'true'
mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl media-service -am -Dtest=T12F1FfmpegPosterRealAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false test
Remove-Item Env:T12F1_FFMPEG_REAL_ACCEPTANCE
```

正式环境不设置 `T12F1_DEMO_SEED_ENABLED`，普通启动和构建不会加载上述 Seed。本机已在仓库外 `local.env` 恢复项目专用开发 OSS 的非敏感 Endpoint、签名 Region 与 Bucket；AccessKey 仍由安全来源注入。阿里云 Endpoint/Location 可以是 `oss-cn-beijing`，但当前 OSS SDK 的 V4 签名 Region 应填写 `cn-beijing`。真实 `Rebuild` 与再次 `Initialize` 已通过，后者同时复核固定数据库记录和精确 OSS HEAD；当前按交付要求保留一套图文进度为 0% 的演示 Seed。不再需要时执行 Cleanup，重新演示执行 Rebuild。

历史 `READY` 视频不自动重处理。T12F.2 真实验收若需为少量本地视频补海报，只能先经 Course API 清点当前发布版本缺失项，再用正常授权下载、FFmpeg 截帧、正常 `VIDEO_POSTER` 上传/绑定和新版本发布；不枚举 OSS Bucket、不直接写媒体表，并记录前后课程版本、mediaId 与回退方式。多码率、清晰度选择、字幕实现、Office 转换和历史海报生产重处理 API 均不属于 T12F.1/T12F.2。

## T04 IAM 迁移与测试账号

T04 只允许把 `IAM_MYSQL_DATABASE` 指向用途明确的 IAM 专用开发或测试 Schema，例如 `edu_platform_iam_dev` 或 `edu_platform_iam_test`。应用不会创建数据库；启用 `local` profile 后，Flyway 只在该 Schema 中创建 `user`、`role`、`permission`、`user_role`、`role_permission`、`refresh_session` 和 `login_audit`。启动前必须同时通过环境变量提供至少 32 字节的 `IAM_JWT_SECRET`，不得写入仓库或命令历史。

最小验收账号默认不生成。只在确认目标是专用 IAM local/test Schema 后，显式设置 `IAM_SEED_ENABLED=true`，并通过当前进程环境或 IDE 密钥配置提供至少 12 位的 `IAM_SEED_PASSWORD`。初始化器会幂等写入四个服务端内部账号及 STUDENT、TEACHER、ADMIN 三个角色和最小权限，其唯一登录身份固定为 `student1@example.invalid`、`teacher1@example.invalid`、`teacher2@example.invalid`、`admin1@example.invalid`；全部标记已验证，昵称可重名，且 `.invalid` 地址被 SMTP 适配器硬拒绝。重复启动不会增加重复记录，production profile 不会加载该初始化器。

清理前需再次确认当前连接的数据库名称，然后在 MySQL 交互式客户端中选择该专用 Schema，并执行：

```sql
source java-platform/iam-service/src/main/resources/db/seed/cleanup-iam-seed.sql;
```

清理脚本只按四个固定 user ID 删除测试账号及其身份变更命令、协议同意、会话和安全审计，并只删除已无引用的固定角色/权限；不会按昵称、邮箱后缀或表级范围删除。重新生成时再次显式启用 Seed 并重启 IAM；完成后应清除当前进程中的 `IAM_SEED_PASSWORD` 和 `IAM_JWT_SECRET`。

### T12D 账号生命周期与 Redis 身份安全

IAM V2 只在明确的 `edu_platform_iam_dev`/`edu_platform_iam_test` 上向前增加 `user_identity`、`registration_request`、`protocol_agreement`、`teacher_application`、`teacher_application_command`、`account_security_audit` 和 `admin_account_command`，并为账号与 Refresh Session 增加持久会话版本/设备字段。Redis 只保存带 TTL 的验证挑战摘要、发送冷却、登录失败/短期锁定、Gateway 限流窗口和 `sessionVersion` 缓存；缓存缺失或 Redis 故障时返回显式 503，不会跳过验证、锁定或限流。

`scripts/Initialize-LocalEnvironment.ps1` 另外维护本机 `iam-security.credential.clixml`：内部的 JWT HMAC、验证码 HMAC 和 local 固定验证码均由 Windows DPAPI 加密。旧环境首次升级需重跑一次初始化脚本；它只补齐缺失的加密文件，不覆盖已有 MySQL 凭据。`Invoke-WithLocalEnvironment.ps1` 仅在子命令期间解密并注入 `IAM_JWT_SECRET`、`IAM_VERIFICATION_HMAC_SECRET` 和 `IAM_VERIFICATION_FIXED_CODE`，结束后恢复环境；禁止读取、打印或把它们复制到 `local.env`。生产环境必须将 `IAM_VERIFICATION_DELIVERY_MODE` 改为已审评的供应商适配器；T12D 未接入真实短信/邮件，因此非 local/test 环境默认明确返回 503。

固定 local/test Seed 仍只有 `student_test_01`、`teacher_test_01`、`admin_test_01`；T12D 为三个账号增加 `example.invalid` 测试邮箱身份，并为学生增加 `local-v1` 用户协议/隐私同意。不创建教师申请、资质媒体、审核决定或管理员命令。重复启动是幂等的；`cleanup-iam-seed.sql` 按外键逆序精确清理这些固定 ID 的身份、协议、会话、申请/命令派生事实和审计；重建方式是清理后显式设置 `IAM_SEED_ENABLED=true` 并经安全包装脚本重启 IAM。

真实 MySQL/Redis 与 Gateway/Redis 验收默认跳过，只能经安全包装脚本显式运行：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:IAM_REDIS_MYSQL_ACCEPTANCE = 'true'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl iam-service -am `
        '-Dtest=IamRedisMysqlRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
}
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:GATEWAY_REDIS_ACCEPTANCE = 'true'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl gateway-service -am `
        '-Dtest=GatewayRedisRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
}
```

IAM 用例在连接前强制核对 `edu_platform_iam_dev`，仅创建一个随机 `t12d_*` 账号并在 `finally` 中按精确 ID/Key 清理；Gateway 用例启动真实随机端口、本机 Redis Lua 和内存 IAM Stub，验证第 11 次登录返回 `429` 及 `Retry-After`，最终只删除本轮唯一限流 Key。

### T12D.1 唯一身份、协议、CAPTCHA 与 SMTP

IAM V3 只向前增加 `(user_id, identity_type)` 唯一约束、注册指纹、不可变 `protocol_document` 和身份变更幂等命令表。Redis 保存约两分钟、一次消费的算式验证码及有限刷新/错误计数，同时继续保存一次性验证码摘要、发送冷却、密码失败/锁定、Gateway 限流和 `sessionVersion` 缓存；任何 Redis 故障均显式返回 503。注册、找回密码和验证码登录发送前必须消费算式验证码，密码登录第 4～5 次也必须先消费验证码，第 5 次密码仍错误后锁定 15 分钟。

邮箱发送通过 `VerificationDeliveryPort` 接入标准 SMTP。生产或本地真实 SMTP 验收不得把用户名和密码写入命令、仓库或 `local.env`；由当前 Windows 用户交互执行以下命令，脚本只把 host/port/from 写入非敏感配置，并将凭据保存为当前用户 DPAPI 加密的 `iam-smtp.credential.clixml`：

```powershell
& .\scripts\Initialize-LocalSmtp.ps1 -HostName 'smtp.example.test' -Port 587 -FromAddress 'no-reply@example.test'
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    mvn.cmd '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl iam-service -am test
}
```

Windows PowerShell 5.1 若受本机执行策略限制，可仅对本次子进程使用
`powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-LocalSmtp.ps1 ...`，不修改用户级或系统级策略。已有 `IAM_SMTP_FROM` 时可省略 `-FromAddress`；脚本会复用该非敏感配置，并通过临时文件原子替换 `local.env`。SMTP 用户名和授权码仍只在本机凭据窗口输入并写入 DPAPI 文件，不得放入命令参数、对话或日志。

未配置该加密文件或 `IAM_VERIFICATION_DELIVERY_MODE` 不是 `smtp` 时，不得宣称真实发信通过。local/test 固定验证码继续用于确定性测试；手机渠道在 local/test 可用，生产没有短信适配器时 `/api/v1/auth/channel-capabilities` 会关闭手机注册和手机验证码登录。Refresh Cookie 的 Path 现为 `/api/v1`，Gateway 只向 IAM 路由保留 `EDU_REFRESH`，其他服务即使收到普通 Cookie 也不会收到刷新凭据。

T12D.1 的真实 IAM 验收仍使用上方安全包装命令和严格的 `edu_platform_iam_dev`；用例创建一个随机账号、已验证邮箱、临时协议（仅当库中没有对应当前版本时）及精确 Redis Key，并在 `finally` 中按 ID/Key 删除后断言账号不存在。Flyway V3 和协议表结构作为迁移事实保留。Seed 清理/重建沿用上方命令，四个 `.invalid` 身份和 local/test 协议正文均明确不可用于正式发布。

## T04 统一登录入口

本地前端地址固定为 student `http://localhost:5173`、teacher `http://localhost:5174`、admin `http://localhost:5175`。统一登录入口只有 student 的 `/login`；teacher/admin 未登录时通过 `VITE_STUDENT_WEB_URL` 跳转到该入口，并携带经过白名单校验的内部 `redirect`。三个地址分别由 `VITE_STUDENT_WEB_URL`、`VITE_TEACHER_WEB_URL`、`VITE_ADMIN_WEB_URL` 配置。Vite 使用严格端口，若端口已占用应停止旧进程，不会自动改用未被 Gateway CORS 允许的其他端口。

跨应用跳转后 Access Token 会随页面内存一起丢失，目标应用通过 Gateway 域下 Path 为 `/api/v1` 的 `EDU_REFRESH` HttpOnly Cookie 调用刷新接口恢复登录态。Gateway 会从非 IAM 请求精确剥离该 Cookie；不要为本地调试放宽 Cookie 或把 Access Token 写入 `localStorage`。

## T05 课程迁移与最小目录数据

T05 只允许把 `COURSE_MYSQL_DATABASE` 指向用途明确的课程专用开发 Schema `edu_platform_course_dev`（自动化环境可使用 `edu_platform_course_test`）。应用不会创建数据库；启用 `local` profile 后，Flyway 只在该 Schema 中创建 `category`、`course`、`course_version`、`chapter` 和 `lesson`。这些表只保存课程域事实，不包含媒体资源、题库、报名、学习进度或运营数据，也不建立跨 Schema 外键。

本地最小验收数据默认不生成。确认连接目标严格为 `edu_platform_course_dev` 后，可显式设置 `COURSE_SEED_ENABLED=true` 并启动 `course-service`。初始化器使用固定 UUID 幂等生成：一个“初中数学”分类、一门归属于 T04 `teacher_test_01` 固定用户 ID 的已发布“七年级代数基础”课程、一个章节和一个可试看视频类型课时。课时只有课程目录元数据，不包含媒体 ID 或播放地址。重复启动不会增加重复记录，production profile 不会加载该初始化器。

清理前再次执行 `SELECT DATABASE()` 并确认结果为 `edu_platform_course_dev`，然后在 MySQL 交互式客户端中执行：

```sql
source java-platform/course-service/src/main/resources/db/seed/cleanup-course-seed.sql;
```

脚本只删除上述固定课程 Fixture（包括验收期间由它派生出的草稿版本）和不再被任何课程版本引用的固定测试分类，不删除 Flyway 表或其他课程。重新生成时再次设置 `COURSE_SEED_ENABLED=true` 并重启 `course-service`；完成验收后恢复为 `false`。

## T09 题库、媒体绑定与异步导入

T09 只允许把 `LEARNING_MYSQL_DATABASE` 指向用途明确的 `edu_platform_learning_dev`（自动化环境可使用 `learning_test`）。应用不会创建数据库；Flyway V1 的 10 张题库表和 T09A 的 V2 均保持不变。T09B 只新增 V3：为组合子聚合标记父题、把父版本关系冻结到精确子版本，并新增历史 JSON 可恢复复核项及解决幂等表；V3 会从 V2 来源引用回填安全复核投影，但不保存原始 JSON、题干、答案、路径明文或外部 URL。迁移不创建报名、进度、试卷、作答、成绩或错题表，也不建立跨 Schema 外键。

本任务不提供默认 Seed。H2 集成测试以 `teacher_test_01` 对应的固定 UUID 和合成题目/知识点为最小 Fixture，测试进程退出即销毁；没有把真实题库样本写入仓库或数据库。若后续人工演示需要数据，应通过教师 API 创建并记录精确 `questionId`/`knowledgePointId`，验收后只清理这些 ID，禁止清空共享开发 Schema。

本地启动必须经凭据包装脚本注入 MySQL/RabbitMQ 配置，不读取或打印 DPAPI 凭据：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:RABBITMQ_ENABLED = 'true'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl learning-service -am spring-boot:run
}
```

learning-service 只通过 `MEDIA_SERVICE_BASE_URL` 调用 media-service 的绑定校验与短期读取授权，不配置 OSS SDK、Bucket、Object Key 或 AccessKey。题目版本可绑定当前教师本人、用途匹配且 `READY` 的 `QUESTION_IMAGE`、`QUESTION_AUDIO`、`QUESTION_ATTACHMENT`；导入任务只接受 `QUESTION_IMPORT_SOURCE`。四种用途的上传策略为：图片 `jpg/jpeg/png/webp`、最多 10 MiB、单对象；音频 `mp3/m4a/wav/ogg`、最多 50 MiB、允许 Multipart；附件 `pdf/docx`、最多 50 MiB、允许 Multipart；导入源 `csv/xlsx`、最多 100 MiB、允许 Multipart。浏览器上传仍由 T10 实现。

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `MEDIA_SERVICE_BASE_URL` | `http://127.0.0.1:8083` | media-service JSON API；只用于绑定验证和短期读取授权 |
| `LEARNING_MEDIA_REQUEST_TIMEOUT` | `5s` | learning-service 调用 media-service 的连接/请求上限 |
| `QUESTION_IMPORT_MAX_SOURCE_BYTES` | `104857600` | CSV/XLSX 源文件读取上限（100 MiB） |
| `QUESTION_IMPORT_RETENTION` | `7d` | 导入终态后继续保留源引用的期限，到期以 Outbox 发布 Released |
| `RABBITMQ_ENABLED` | `false` | 是否声明并启用题目导入消费者和 Outbox 发布器 |
| `LEARNING_OUTBOX_POLL_DELAY` | `2s` | Outbox 扫描间隔 |
| `LEARNING_OUTBOX_CONFIRM_TIMEOUT` | `10s` | mandatory publisher confirm 等待上限 |
| `LEARNING_OUTBOX_MAX_ATTEMPTS` | `20` | 发布失败的有限尝试上限，耗尽后保留 `FAILED` 审计记录 |

导入模板的首行必须精确为 `type,difficulty,grade,stem,optionsJson,answersJson,analysis,knowledgePointCodesJson,mediaJson`。CSV 使用 UTF-8；XLSX 只允许一个普通工作表，不执行公式，也不接受嵌入图片。`optionsJson`、`answersJson`、`knowledgePointCodesJson` 和 `mediaJson` 必须是 JSON 数组；服务端不会从题干猜测或拆分选项。外部图片、内嵌图片、公式单元格、不支持的工作簿结构和坏 JSON 都会形成明确逐行错误。

RabbitMQ 资源为 `edu.learning.events.v1`、队列 `edu.learning.question-import.v1`、死信交换机 `edu.learning.dlx.v1` 和 DLQ `edu.learning.question-import.dlq.v1`。生产者使用事务 Outbox、持久消息、mandatory、publisher confirm/return 和有限退避；消费者总计最多尝试 3 次，超限拒绝进入 DLQ，并以数据库 `eventId` 幂等。重复消费的每一行还使用任务 ID 与行号生成确定性 `clientRequestId`，不会重复创建题目。

## T11 学习、测评与报名访问投影

T11 不创建数据库。`LEARNING_MYSQL_DATABASE` 必须是明确的 `edu_platform_learning_dev`，在既有 V8 上正常向前应用 Learning V9；`COURSE_MYSQL_DATABASE` 必须是 `edu_platform_course_dev`，正常向前应用 Course V3。禁止补写 Learning V4、启用 Flyway out-of-order/baseline 或清空既有 Schema：真实 learning 开发库已经达到 V8，低版本 V4 无法按正常迁移顺序应用。V9 只创建 learning 域的报名、进度事件/汇总、笔记、测评/不可变题目快照、作答/答案、人工评分、错题及幂等记录；Course V3 只创建本地报名访问投影和消费幂等表，双方没有跨 Schema 外键或 Join。

learning-service 通过 `COURSE_SERVICE_BASE_URL` 调用 course-service 的既有版本化 JSON API，读取当前发布版本、目录课时和 owner 事实；连接或读取超过 `LEARNING_COURSE_REQUEST_TIMEOUT`、依赖 5xx 等统一返回可重试 503，404/非 owner 不会退化为本地猜测。报名事务同时写既有 `learning_outbox_event`，Course 消费 `edu.learning.enrollment.events.v1` 的 `learning.enrollment.access-changed.v1`，按 `eventId` 幂等且按 `changedAt` 防乱序，投影实际用于 `ENROLLMENT_REQUIRED` 非试看课时的播放决策。主队列 `edu.course.enrollment-access.v1` 总计最多尝试 3 次，耗尽后经 `edu.course.enrollment.dlx.v1` 进入 `edu.course.enrollment-access.dlq.v1`。

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `COURSE_SERVICE_BASE_URL` | `http://127.0.0.1:8082` | learning-service 读取发布课程、课时和 owner 事实的 JSON API |
| `LEARNING_COURSE_REQUEST_TIMEOUT` | `3s` | learning-service 调用 course-service 的连接/读取上限；超时返回 503 |
| `RABBITMQ_ENABLED` | `false` | local profile 下同时启用既有 Outbox 发布与 Course 报名事件消费；关闭时不会伪造报名投影 |

真实迁移、消息和服务间调用验收必须通过凭据包装脚本运行，并先确认两个 Schema 名称；命令不得读取或打印 DPAPI 文件内容：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    if ($env:LEARNING_MYSQL_DATABASE -ne 'edu_platform_learning_dev') { throw 'Unexpected learning Schema' }
    if ($env:COURSE_MYSQL_DATABASE -ne 'edu_platform_course_dev') { throw 'Unexpected course Schema' }
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:RABBITMQ_ENABLED = 'true'
    # 分别启动 course-service 与 learning-service，再经 Gateway/API 创建本轮固定测试 ID。
}
```

不启动长驻服务也可以显式运行两个可重复的真实环境用例；它们启动前会再次拒绝错误 Schema/未开启 RabbitMQ，Fixture 始终按本轮随机 ID 精确清理：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:LEARNING_MYSQL_ACCEPTANCE = 'true'
    $env:RABBITMQ_ENABLED = 'false'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl learning-service -am `
        '-Dtest=LearningMysqlMigrationAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
}
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:T11_REAL_ACCEPTANCE = 'true'
    $env:RABBITMQ_ENABLED = 'true'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl course-service -am `
        '-Dtest=CourseEnrollmentRealEnvironmentAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
}
```

T11 没有默认 Seed，也不运行历史题库 Import。人工验收只能使用明确的 `student_test_01`/`teacher_test_01` 对应固定 UUID、专用测试课程/课时/测评；清理时按本轮记录的 enrollment/assessment/attempt 等精确 ID 逆外键顺序删除，Course 投影按对应 learner/course 精确删除，不得删除现有 648 道 SYSTEM 题、图谱、T10 任务、媒体或检查点。自动化使用独立 H2 内存库，进程结束即销毁。

### T11A 测评时间、错题重做与题目媒体授权

T11A 不创建数据库，只在 `edu_platform_learning_dev` 上正常向前应用 Learning V10。V10 增加测评时间、attempt 开始/服务端截止、错题掌握/revision/有限重做字段和 `wrong_question_redo`；不得修改 V9、启用 baseline/out-of-order 或建立跨 Schema 外键。Media 复用 V3 的 `consumed_service_authorization` 一次性 jti 表，不新增媒体迁移。

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `MEDIA_SERVICE_BASE_URL` | `http://127.0.0.1:8083` | Learning 复用既有媒体 HTTP 客户端调用内部单资产授权 |
| `LEARNING_MEDIA_REQUEST_TIMEOUT` | `5s` | Learning 调用 Media 的连接/读取上限；失败映射为 503 |
| `LEARNING_MEDIA_AUTH_ISSUER` | `edu-platform-learning-service` | Learning 专属服务 issuer；Media 必须配置相同值 |
| `COURSE_MEDIA_AUTH_AUDIENCE` | `edu-platform-media-service` | 复用既有 Media audience |
| `LEARNING_MEDIA_AUTH_TOKEN_TTL` | `30s` | 测评题目媒体一次性 JWT 寿命，必须小于等于 Media 最大 TTL |
| `COURSE_MEDIA_AUTH_SECRET` | 无 | 复用 DPAPI 管理的 course/media HMAC 秘密，不进入 `local.env`、日志或参数 |

专用环境验收仍只能通过凭据包装脚本；两个用例都会再次确认 Schema 名称，只创建本轮随机/固定测试标识的最小数据并在 finally 精确清理：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:LEARNING_MYSQL_ACCEPTANCE = 'true'
    $env:RABBITMQ_ENABLED = 'false'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl learning-service -am `
        '-Dtest=LearningMysqlMigrationAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
}
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:MEDIA_REAL_ACCEPTANCE = 'true'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl media-service -am `
        '-Dtest=MediaRealEnvironmentAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
}
```

Learning 用例创建一份随机 EXAM、一个 GRADED attempt、一条错题和一次重做，按 redo → wrong → attempt → assessment 删除并断言为 0。Media 用例在本轮 UUID OSS 前缀内新增固定合成 `QUESTION_IMAGE`，验证 learning issuer 授权、真实下载和 jti 重放 403，再按数据库记录的精确 Object Key 与 owner/visitor 清理；禁止列举 Bucket、清空表或触碰既有媒体。

### T11B 测评关闭、草稿结算与结果公布

T11B 不创建数据库，只在用途明确的 `edu_platform_learning_dev` 上由 Flyway 从 V10 正常向前应用 Learning V11。V11 保留旧答案策略列并增加向前扩展策略、整场关闭/有限结算、成绩与答案独立公布事实，以及 attempt 最新成绩更新时间；不得修改 V9/V10、启用 baseline/out-of-order 或把 Redis/RabbitMQ 作为可见性事实。

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `LEARNING_ASSESSMENT_SETTLEMENT_POLL_DELAY` | `2s` | 关闭后草稿结算轮询间隔；每轮最多 10 场、每场 50 份，失败最多五次有限退避 |

真实 MySQL 验收继续使用上方 `LearningMysqlMigrationAcceptanceTest` 命令。用例会再次拒绝非 `edu_platform_learning_dev` 目标，以随机 UUID 创建最小 assessment/attempt/wrong/redo，验证 V11 关闭、公布、最后成绩时间和有限重试约束，并在 `finally` 中精确逆序删除全部 Fixture。没有 local seed；重复验收直接重跑命令，不会复用或修改现有正式题库。

### T11C.1 作业规则、成绩册与 CSV 导出

T11C.1 只在用途明确的 `edu_platform_learning_dev` 上由 Flyway 从 V13 向前应用 Learning V14。V14 新增 HOMEWORK 迟交/次数、应交名单、课程学生编号、截止调整审计、结算审计和成绩册导出审计；V10 的 EXAM-only 时间约束只通过 V14 向前替换，不改写 V1～V13。PRACTICE 的旧语义和 T11B 的关闭/公布事实保持不变。

| 变量 | 默认值 | 含义 |
|---|---:|---|
| `LEARNING_ASSESSMENT_SETTLEMENT_POLL_DELAY` | `2s` | 到期自动关闭和草稿有界结算的轮询间隔；每轮最多 10 场、每场 50 份 |
| `IAM_SERVICE_BASE_URL` | `http://127.0.0.1:8081` | Learning 调用 IAM 批量昵称投影的地址 |
| `LEARNING_IAM_REQUEST_TIMEOUT` | `1s` | IAM 昵称批量调用的连接/读取上限；失败只降级昵称，不丢失成绩册事实 |
| `IAM_INTERNAL_SERVICE_TOKEN` | 无 | Learning→IAM 内部调用令牌；由 `learning-iam-service.credential.clixml` 通过 DPAPI 注入，不能写入 `local.env`、命令参数或日志 |

首次已有本地环境可重新运行 `Initialize-LocalEnvironment.ps1`，脚本只补齐缺失的 `learning-iam-service.credential.clixml`，不会替换已有 MySQL、课程/媒体或 IAM 安全凭据。所有需要该令牌的启动、迁移和真实验收命令必须通过 `Invoke-WithLocalEnvironment.ps1` 执行；IAM 端点还会校验固定调用方 `learning-service` 和令牌，令牌缺失时成绩册保留编号与 Learning 数据并标记昵称依赖失败。

T11C.1 提供独立的 Learning 演示 Seed，但不会随应用启动自动运行。只有在显式设置 `T11C1_DEMO_SEED_ENABLED=true`、profile 为 `local` 且目标严格为 `edu_platform_learning_dev` 时，才可通过以下命令执行：

```powershell
& .\scripts\Invoke-T11C1HomeworkGradebookDemoSeed.ps1 -Action Initialize
& .\scripts\Invoke-T11C1HomeworkGradebookDemoSeed.ps1 -Action Cleanup
& .\scripts\Invoke-T11C1HomeworkGradebookDemoSeed.ps1 -Action Rebuild
```

Seed 在同一测试课程下保留一场已结算 HOMEWORK 和一场含待批主观题的 EXAM，覆盖三次/一次上限、准时/迟交/缺交/退课、客观自动判分、主观人工批改、课程学生编号和固定合成昵称。它只写 Learning Schema 的固定测试 UUID，不创建或复制 IAM、Course、题库、Media 数据；`Initialize` 可幂等重跑，`Cleanup` 只按固定 ID/前缀逆序清理。H2 集成测试使用进程内 V14 数据库并在进程结束时释放；演示 Seed 只允许访问 `edu_platform_learning_dev`，真实容量验收必须使用显式、专用的 local/test Schema，并在完成后精确清理。CSV 是同步响应，不写 OSS、本机临时文件、RabbitMQ 或 `REPORT_EXPORT`。

### T12B 测评闭环前端演示数据

T12B 不创建 Schema、表或迁移，只在用途明确的 `edu_platform_learning_dev` 中维护一组生产禁用、固定 ID、幂等的页面演示数据。`T12BAssessmentDemoSeedTest` 会先校验 `T12B_DEMO_SEED_ENABLED=true`、`local` profile 和目标 Schema，再从固定稳定 source ref 解析既有 `SYSTEM` 题版本；题目缺失、数量或类型不符合预期时直接失败，不随机抽题、不复制题干/答案，也不遍历历史 JSON。

演示集为固定测试教师 `teacher_test_01` 和学习者 `student_test_01` 创建或复用一条 ACTIVE 报名，并保留 6 场测评、6 份作答、10 条冻结测评题快照、2 条错题及确定性的重做历史。页面可覆盖普通练习可恢复草稿、限时考试、客观题、主观题、组合题、真实题图、待人工批改，以及成绩/参考答案“均未公布、仅成绩、仅答案、均已公布”四种状态。生产 profile 不会自动执行该测试。

所有操作必须从仓库根目录执行；脚本内部统一调用 `Invoke-WithLocalEnvironment.ps1`，不会读取、打印或拼接 DPAPI 凭据：

```powershell
# 首次初始化；若固定 ID 已存在则校验所有权与内容后保持幂等。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12BAssessmentDemoSeed.ps1 -Action Initialize

# 只删除本 Seed 固定 ID 拥有的测评、作答、答案、批改、错题、分配、幂等记录和由 Seed 创建的报名。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12BAssessmentDemoSeed.ps1 -Action Cleanup

# 先精确清理再按同一固定 ID 重建，恢复所有演示状态。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-T12BAssessmentDemoSeed.ps1 -Action Rebuild
```

`Cleanup` 在删除前逐项核对固定 assessment 的 owner、课程、标题标记和固定报名的 learner/course/version；任何归属异常都会拒绝执行。它按错题重做/发生记录 → 错题 → 人工批改/答案 → attempt → 分配/冻结题目 → assessment 的外键顺序删除，不清空表、不触碰既有 SYSTEM 题、媒体、课程、账号或非本 Seed 报名。

历史 JSON 不走 `QUESTION_IMPORT_SOURCE` 上传，也不让服务端接收目录路径。只读迁移脚本逐文件读取，默认 DryRun、每批最多 20、每任务最多 1000，终端只输出计数。0.13.0 起创建、批次和清理端点只对具备 `administration:access` 的管理员开放，任务冻结为 `SYSTEM`，正式题目直接为 `ACTIVE`，图片也创建为管理员持有的 `SYSTEM` 资产；教师端既有旧任务只读，不再复核、继续提交或清理历史 JSON。实际批量导入仍必须显式使用 `-Action Import`；单任务清理携带原任务 ID，全量编排可使用 Import 检查点逐任务精确清理。示例只做 5 项 dry-run：

脚本只经 Gateway 调用，必须先把当前管理员的短期 Access Token 放入当前进程的 `EDU_MIGRATION_ACCESS_TOKEN`；不得把 Token 写入命令参数、`local.env`、仓库或日志，操作后立即移除该环境变量。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action DryRun -SourceRoot 'I:\大模型学习\edu_agent\智能体项目\data\题库' `
  -MaxItems 5 -BatchSize 5
```

T10A 后脚本支持显式全量编排，但仍不会放大后端边界。先执行全部文件 dry-run；脚本对相对路径稳定排序，自动按最多 1000 项拆任务、按最多 20 项拆批次，并把非敏感检查点默认写入当前用户 LocalAppData。网络中断或短期令牌过期后，更新当前进程令牌并原样重跑即可从已确认批次继续：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action DryRun -SourceRoot 'I:\大模型学习\edu_agent\智能体项目\data\题库' `
  -AllItems -TaskSize 1000 -BatchSize 20
```

核对 dry-run 的 `validCount/reviewCount/failedCount` 和全部任务详情后，正式全量导入还必须额外提供确认开关；除非已经明确批准全量 SYSTEM 题库，否则优先使用后文的平衡小样本模式：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Import -SourceRoot 'I:\大模型学习\edu_agent\智能体项目\data\题库' `
  -AllItems -ConfirmFullMigration -TaskSize 1000 -BatchSize 20
```

`AllItems` 表示每份 JSON 都会被提交处理，不表示所有数据都会绕过校验直接创建题目。`NEEDS_REVIEW` 不会进入 VALID-only 正式样本；应修正只读来源的上游副本后重新 DryRun，或在后续管理员复核契约落地后处理，教师端不能启发式放行。只有 `importedCount` 计入已创建并启用的 SYSTEM 题。全量运行期间不得增删或改写源目录文件，否则清单指纹变化会拒绝复用旧检查点。检查点不能放在 `SourceRoot` 内，不保存 Token、绝对源路径、题干、答案或原始 JSON。

已有全量 DryRun 时，优先使用 T10A 的 VALID-only 模式，不要再次把已知 `NEEDS_REVIEW` 项提交到正式任务。先只读规划；脚本从 DryRun 检查点自动取得任务，无需复制任务 ID，且只输出安全聚合：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Import -AllItems -PlanOnly `
  -SourceRoot '<只读历史题库根目录>' `
  -ValidatedDryRunCheckpointPath '<completed-dryrun-checkpoint.json>'
```

核对 `selectedValidCount`、`selectedImageCount` 和 `imageHostCounts`，确认每个图片来源均有合法下载/转存授权，并把精确主机集合配置到 media-service 的 `MEDIA_EXTERNAL_IMAGE_ALLOWED_HOSTS`。正式执行时把同一集合同时交给脚本本地门禁；检查点显式放在来源目录之外，便于恢复和必要时精确清理：

```powershell
& .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Import -AllItems -ConfirmFullMigration `
  -SourceRoot '<只读历史题库根目录>' `
  -ValidatedDryRunCheckpointPath '<completed-dryrun-checkpoint.json>' `
  -AllowedImageHost @('<exact-image-host-1>', '<exact-image-host-2>') `
  -TaskSize 1000 -BatchSize 20 `
  -CheckpointPath '<outside-source-root\import-valid-checkpoint.json>'
```

需要“各现有年级/题型/难度 100 多道、稀缺分类全量、至少一半含图”的受控样本时，先使用相同参数执行 `-PlanOnly`，核对可用量、目标量和确定性选择结果；确认后移除 `-PlanOnly` 并增加 `-ConfirmSampleImport`。当前硬配额为每个现有分类 101 道，`BalancedSampleSize` 是总样本上限而非每类相加：

```powershell
& .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Import -PlanOnly -BalancedSampleSize 650 `
  -SourceRoot '<只读历史题库根目录>' `
  -SourceSystem 'KG_MATH_T10B_V3' `
  -ValidatedDryRunCheckpointPath '<管理员 SYSTEM dry-run-checkpoint.json>' `
  -AllowedImageHost @('v3.rawstonedu.net') `
  -ExcludeInvalidImageItems -ExcludeUnapprovedImageHostItems

& .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Import -ConfirmSampleImport -BalancedSampleSize 650 `
  -SourceRoot '<只读历史题库根目录>' `
  -SourceSystem 'KG_MATH_T10B_V3' `
  -ValidatedDryRunCheckpointPath '<管理员 SYSTEM dry-run-checkpoint.json>' `
  -AllowedImageHost @('v3.rawstonedu.net') `
  -ExcludeInvalidImageItems -ExcludeUnapprovedImageHostItems `
  -CheckpointPath '<来源目录之外\system-sample-import.json>'
```

该模式再次校验来源根/清单指纹、SourceSystem、DryRun 终态和持久化 VALID 计数，只把匹配的 VALID 来源提交；已知复核项不进入正式任务。图片 URL 由 learning-service 交给 media-service 转存为 OSS 对象，题目版本只保存稳定 `mediaId`。`AllowedImageHost` 只是一道本地审批门禁，不会修改 media-service 配置，也不替代其逐跳 DNS、MIME、大小和图片魔数校验。401 或网络中断后更新当前进程 Token，以完全相同参数重跑即可从已确认批次恢复；脚本不会自动保存或刷新账号、密码和 Token。

如需撤销本次仍未被后续修改的 SYSTEM Import，使用正式导入结果中的 `checkpointPath`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-LegacyQuestionJsonMigration.ps1 `
  -Action Cleanup -CheckpointPath '<import-checkpoint.json>'
```

清理逐任务调用管理员精确端点；不属于该任务、作用域不符或 revision 已变化的题目不会被静默删除。操作结束后必须从当前进程移除 `EDU_MIGRATION_ACCESS_TOKEN`。

外部图片仅由 media-service 转存。默认 `MEDIA_EXTERNAL_IMAGE_IMPORT_ENABLED=false`；人工实际导入图片前必须显式启用，并把 `MEDIA_EXTERNAL_IMAGE_ALLOWED_HOSTS` 设置为逗号分隔的精确 HTTPS 主机白名单。`MEDIA_EXTERNAL_IMAGE_MAX_BYTES` 默认 10 MiB、`MEDIA_EXTERNAL_IMAGE_TIMEOUT` 默认 10 秒、`MEDIA_EXTERNAL_IMAGE_MAX_REDIRECTS` 默认 2。每次请求和重定向都复核白名单、DNS 私网/回环地址、MIME、大小和图片魔数；URL 明文不写数据库、事件或日志。learning-service 仍只配置 `MEDIA_SERVICE_BASE_URL`，不配置 OSS SDK 或凭据。SYSTEM 转存同时传递管理员角色与 `administration:access`；media-service 不接受仅凭 `ADMIN` 角色名创建公共资产。

真实 MySQL 迁移验收默认跳过，并且不会创建数据库。先由环境管理员创建用途明确的 `edu_platform_learning_dev`，再通过安全包装脚本显式执行；测试在建立 Spring 上下文前复核 Schema 名。默认用例以最小虚构组合题和复核项验证 V3，并按随机 owner、任务和题目精确清理。设置 `LEGACY_QUESTION_ROOT` 后还会程序化选择一份无图片、无需复核的真实文件，分别执行一项 dry-run、一项实际导入和精确清理；终端不输出路径、题干、答案或 JSON。测试最终只保留 V1/V2/V3 Schema，不保留 Fixture：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& '.\scripts\Invoke-WithLocalEnvironment.ps1' -ScriptBlock { `$env:LEARNING_MYSQL_ACCEPTANCE='true'; `$env:LEGACY_QUESTION_ROOT='I:\大模型学习\edu_agent\智能体项目\data\题库'; `$env:RABBITMQ_ENABLED='false'; mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl learning-service -am '-Dtest=LearningMysqlMigrationAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test }"
```

若目标数据库不存在，Flyway 应以 `Unknown database 'edu_platform_learning_dev'` 停止；不得为通过验收而由应用或 Codex 自动创建 Schema。

## T10B 知识图谱迁移

T10B 的图谱脚本只经 Gateway 调用管理员接口，不读取本地凭据文件，也不连接 MySQL。短期管理员令牌只能放在当前 PowerShell 进程的 `EDU_MIGRATION_ACCESS_TOKEN`；脚本不会打印或写入令牌。检查点必须位于两个只读来源文件所在目录之外，只保存文件指纹、任务 ID、批次位置和聚合计数。

默认执行 DryRun：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-KnowledgeGraphMigration.ps1 `
  -NodeFile '<只读节点 JSON>' -RelationFile '<只读关系 JSON>' `
  -SourceSystem 'KG_MATH_T10B_DRYRUN_V1' -ApiBaseUrl 'http://127.0.0.1:8080' `
  -BatchSize 200 -CheckpointPath '<来源目录之外\kg-dryrun-checkpoint.json>'
```

正式 Import 除 `-Action Import -ConfirmImport` 外，还必须同时给出 `-ConfirmStageMapping`、`-ConfirmStatusMapping`、`-ConfirmTypeNames` 与 `-ConfirmParentKeySemantics`。这些开关表示负责人已经确认：`02/03/04` 分别为 `PRIMARY/JUNIOR/SENIOR`，无年级适用于整个学段，来源 `status=0` 原样保存但不直接决定平台状态，`parentId` 只写 `source_parent_key`，六组来源类型按已确认的“学段 + 数量”签名规范为“知识点”或“题型”。脚本与服务端都会拒绝偏离这些约束的输入；正式导入按平台策略启用小学/初中节点、高中保持禁用，两个 parent 外键均为空。每次正式导入应使用新的、可审计的 `SourceSystem` 和独立检查点；完成后使用 `Remove-Item Env:EDU_MIGRATION_ACCESS_TOKEN` 清除当前进程令牌。

V7 是导入完成后的平台启用策略迁移：只把所有来源图谱的 `PRIMARY/JUNIOR` 节点设为启用，并确保 `SENIOR` 禁用；不写 `parent_id`/`taxonomy_parent_id`，也不新增、删除或重排先修关系。教师题目接口由 OpenAPI 0.13.0 支持小学一至六年级和初中七至九年级，高中仍不进入题目写契约；同版本增加 SYSTEM/TEACHER 来源和公共题只读访问。专用开发库可通过上文 MySQL 验收命令幂等应用并验证该策略。

## T12G.1 进度缓冲与可靠落库环境

T12G.1 复用现有 MySQL 和 Redis，不新增 RabbitMQ 队列、Redis Stream、独立 Worker 微服务或其他基础软件。`learning-service` 在 local profile 中继续使用用途明确的 `edu_platform_learning_dev` 和 `LEARNING_REDIS_DATABASE=4`；OpenAPI 计划向前演进到 0.20.0，Learning Flyway 只能从现有版本正常向前增加 V13，不得修改历史迁移或创建数据库。

本地单机 Redis 可以继续用于功能验收，但真实可靠性验收必须确认：

- Redis 服务启用持久化；生产建议 AOF `everysec`，不可随意淘汰的进度缓冲使用 `noeviction`，不能与可淘汰页面缓存共用会驱逐数据的资源池；
- 普通 `HEARTBEAT` 使用 Hash＋dirty ZSet＋Lua 原子合并，Learning 内部多实例定时 Worker 领取并落库；不需要 RabbitMQ、DLQ 或单独启动另一个 Java 服务；
- 正常批量落库目标约 5 秒；MySQL 不可用时保留 dirty 状态并有限退避，积压超过配置阈值后普通心跳明确返回 503；暂停、切课、主动离开和结束继续直接尝试 MySQL；
- 真实验收覆盖 Redis 不可用/重启、MySQL 短暂不可用、多实例竞争、重复和乱序、版本变化时不误清 dirty、强制检查点、学生读合并、教师/报表只读 MySQL、积压恢复和精确 Key/Fixture 清理；
- 容量验收只请求进度 API：1,000 个模拟学习者、约 200 请求/秒、至少 10 分钟，普通心跳 p95 小于 200ms，正常最老未落库年龄不超过约 5 秒。该负载不得同时打开 1,000 路 OSS/HLS 视频。

T12G.2 的真实浏览器验收使用本地“线性代数”课程第 2 课“行列式定义”。验收前必须通过正常 Course API 解析并核对稳定课程/课时 ID、已发布版本、`READY` 视频和当前学生访问事实，不能只按标题命中或在缺失时替换其他课程。只播放现有低成本视频，不上传、复制、转码或删除其 OSS 对象。临时进度、Redis Key 和 Worker 租约必须带本轮 run ID 并在结束后精确清理，同时保留课程、媒体及验收前原始进度；若对象并非明确本地开发数据，应停止并报告。

T12G.1 已将批次大小、刷新间隔、租约、Worker 并发、课程事实短缓存和积压阈值做成配置，并在交付文档与 `.env.example` 记录真实变量名和默认值：`LEARNING_PROGRESS_BUFFER_ENABLED=true`、`LEARNING_PROGRESS_BUFFER_WORKER_DELAY=5s`、`LEARNING_PROGRESS_BUFFER_BATCH_SIZE=1000`、`LEARNING_PROGRESS_BUFFER_WORKER_CONCURRENCY=16`、`LEARNING_PROGRESS_BUFFER_LEASE_DURATION=15s`、`LEARNING_PROGRESS_BUFFER_BACKLOG_THRESHOLD=10000`、`LEARNING_COURSE_FACT_CACHE_TTL=1s`。这些变量均为非敏感调优项，不需要向仓库外 `local.env` 增加密码、Token 或新秘密；当前实现没有增加去重保留期，Redis Hash 只在成功落库且版本未变化时删除。

真实验收入口为 `scripts/Invoke-T12G1RealAcceptance.ps1` 和 `scripts/Invoke-T12G1CapacityAcceptance.ps1`，两者都通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入凭据并强制校验 `LEARNING_MYSQL_DATABASE=edu_platform_learning_dev`、`LEARNING_REDIS_DATABASE=4`。容量脚本使用独立随机 run ID、1,000 个合成 UUID 学习者和独立清理测试；不得在生产配置或未明确标识的数据库中运行。故障场景使用错误连接/隔离测试实例，不停止或重启共享 MySQL/Redis 服务；清理断言必须显示本轮 MySQL 行数和 Redis key 数为 0。

## T11C.2 本地真实浏览器启动复核

验收前必须通过 `Invoke-WithLocalEnvironment.ps1` 从当前分支重新构建 Learning，并先核对监听 PID/启动时间再停止对应旧 Java 进程；不要继续使用合入后端修复前启动的 jar。Gateway、IAM、Course 也必须与同一包装脚本提供的本地配置一致。后台启动用 `Start-Process -WindowStyle Hidden`，IAM 使用 `IAM_SEED_ENABLED=false`，测试账号从统一登录门户正常登录，不在命令、文档或仓库保存密码。

2026-09-07 发现本机 `GATEWAY_REDIS_DATABASE` 与 `IAM_REDIS_DATABASE` 不一致，导致 Gateway 查不到 IAM sessionVersion。当前 Gateway 只使用一个 Redis 连接读取会话缓存；本地启动时可在包装脚本的 ScriptBlock 内设置 `$env:GATEWAY_REDIS_DATABASE=$env:IAM_REDIS_DATABASE`，再启动 Gateway。该设置仅影响子进程，不改共享 local.env；不能通过关闭 session-validation 或 rate-limit 规避 503。正常登录及课程事实请求须重新确认 200。

T11C.2 专用浏览器 Fixture 及 INITIALIZE/REPORT/CLEANUP 命令见 [交付文档](../delivery/T11C.2-homework-gradebook-web.md)。它只允许 `edu_platform_learning_dev`，不创建数据库/账号/课程/题目；清理必须限定本轮测评、错题派生和明确记录的导出审计 UUID，不得运行按整个课程删除审计的清理替代。

## T14.1 Agent Access 与索引源环境

T14.1 新增 `agent-access-service`，local 端口为 8086，专用 Schema 约定为 `edu_platform_agent_dev`。服务不会创建数据库；只有显式 `local` 或 `test` profile 才启用 JDBC/Flyway，其他 profile 排除 DataSource 并关闭持久化。首次本地运行前应由开发者创建或确认用途明确的专用开发 Schema 和最小权限账号，不得把 Agent 表放入 IAM、Course、Learning 或其他服务 Schema。

通过统一凭据包装器启动：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl agent-access-service -am spring-boot:run
}
```

Flyway V1 创建 `ai_conversation`、`ai_message`、`ai_run`、`ai_run_event`、`ai_run_cancellation` 和 `ai_tool_call`。T14.2.1 的 V2 仅向 `ai_run` 增加一组有界私有结果报告列及唯一 `reportId`；这是 T14.2.2 正式用量/记忆建模前的协议事实保存，不是长期记忆或画像表。报告正文不得进入浏览器消息、SSE 或日志正文。开发环境清理应删除明确的测试 conversation 及其从表记录；禁止清空整个 Schema。自动化使用进程内 H2，测试结束后自动释放，没有持久 Seed。

`AGENT_RUNTIME_BASE_URL` 默认指向 `http://127.0.0.1:8000`，请求超时默认 95 秒。T14.1 的 Java 流代理测试使用协议替身；当前旧 Python 入口不等于 T13.3 动态五角色运行时，未完成 T13.3 前不要用本端口组合宣称真实 Agent 已接通。

Course/Learning 的 `/internal/v1/agent-index/**` 只在 `AGENT_INDEX_API_ENABLED=true` 时启用，Gateway 不路由。初始化脚本生成 DPAPI 加密的 `agent-index-service.credential.clixml`，包装器仅在子命令期间注入 `AGENT_INDEX_SERVICE_TOKEN`；调用方还必须发送固定 `X-Internal-Caller: agent-index-worker`。禁止打印令牌或写入 `local.env`。RabbitMQ 中题目、知识点、课程文字通知只携带稳定 ID 和 revision，正文由 T13.2 Worker 通过分页 API 拉取。

T14.2.1 另增加 Agent 在线链路的两段私有身份：Python→Agent Access 使用 `AGENT_RUNTIME_CALLBACK_TOKEN`，Agent Access→Course/Learning 使用 `AGENT_ACCESS_DOMAIN_TOKEN`。`Initialize-LocalEnvironment.ps1` 将两个随机令牌合并保存到当前用户 DPAPI 文件 `agent-access-internal.credential.clixml`；`Invoke-WithLocalEnvironment.ps1` 只在子命令期间解密并注入，不能把令牌写入 `local.env`、命令参数或日志。Course/Learning 私有端点由 `AGENT_ACCESS_DOMAIN_API_ENABLED=true` 启用，调用方固定为 `agent-access-service`；Python 调用方固定为 `edu-agent-runtime`。Gateway 不路由任何 `/internal/v1/agent-access/**` 或 Agent 回调路径。

T14.1 不需要 Qdrant、Neo4j、BGE、重排模型或新增 Python 包。它们属于 T13.2；届时应先核对现有 Python 版本、硬件和本机资产，再由任务锁定精确版本，避免现在提前安装出一套漂移环境。

## T13.3 动态 Agent 运行时环境

Python 在线服务现由 `python -m edu_agent_app` 启动 T13.1 v1 内部 HTTP/SSE；`python -m edu_agent_app index-worker` 仍是 T13.2 独立进程。建议使用已经验证过的 Python 3.11 环境，并通过统一包装器注入百炼与 Neo4j 等秘密：

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    D:\Anaconda\envs\langgraph\python.exe -m edu_agent_app
}
```

聊天与视觉读取 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`CHAT_MODEL_NAME`、`VISION_MODEL_NAME`；`AGENT_MODEL_REQUEST_TIMEOUT_SECONDS` 默认 30 秒。`AGENT_ONLINE_RETRIEVAL_ENABLED=true` 时复用 T13.2 的 Qdrant、BGE、reranker 和可选 Neo4j 配置；`AGENT_CONTEXT_COMPRESSION_THRESHOLD_CHARS` 默认 12000，只控制进程内有限摘要。每个 run 的 12 步/8 次模型/10 次工具/2 次转交/同参 2 次/约 90 秒预算由 Java请求携带，不应在本机配置另建第二套隐藏上限。

T14.2.1 中 Python 使用 `AGENT_ACCESS_BASE_URL`（默认 `http://127.0.0.1:8086`）和 `AGENT_ACCESS_REQUEST_TIMEOUT_SECONDS`（默认 5 秒）访问 Agent Access 私有回调；没有安全包装器注入的 callback token 时，课程候选和学习事实保持 fail-closed，运行结果也不会伪装成已上报。Agent Access 访问 Course/Learning 的超时继续由 `AGENT_DOMAIN_REQUEST_TIMEOUT` 控制。最近消息窗口默认最多 6 轮、16000 字符，可用非敏感变量 `AGENT_RECENT_MESSAGE_MAX_TURNS` 和 `AGENT_RECENT_MESSAGE_MAX_CHARS` 调整，但生产值不得突破代码的 12 轮/32000 字符硬上限。

本机真实验收的文本和内存 PNG 视觉均由百炼 `qwen3.8-flash` 成功处理；真实 Qdrant 混合检索、空命中和 Neo4j 有界查询也通过。真实模型专项必须显式设置 `AGENT_RUNTIME_REAL_ACCEPTANCE=true`，只使用有界样本并仅输出用量指标；默认全量测试不会产生模型费用。课程候选现经 Agent Access 和 Course 按 run owner、当前发布版本、资源字段、revision 与报名事实再次授权；任一私有依赖不可用时必须返回失败或空结果，禁止回退全课程搜索。

项目流程结束时，`OPENAI_API_KEY`、Neo4j 密码及所有服务令牌都必须继续存放在 DPAPI 文件或部署 Secret 中；若曾被人工临时写入 `local.env`，应删除 `AGENT_RUNTIME_CALLBACK_TOKEN`、`AGENT_ACCESS_DOMAIN_TOKEN` 等明文并重新运行初始化脚本迁入对应加密凭据。Base URL、模型名、模型路径、启用开关、超时和窗口上限属于非敏感配置，可保留在 `local.env`。

## 全量验证

仓库的 Java、Python、Frontend 和契约一致性命令统一记录在 [`docs/roadmap/verification.md`](../roadmap/verification.md)。默认 `clean verify` 不激活 `local` profile，因此 CI 不依赖开发机数据库、Redis、OSS 或 RabbitMQ。
