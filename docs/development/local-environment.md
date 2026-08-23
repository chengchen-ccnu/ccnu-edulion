# Local development environment

T02 使用开发机已经运行的 MySQL `127.0.0.1:3306` 和 Redis `127.0.0.1:6379`。对象存储使用阿里云 OSS，消息队列由 [ADR-0005](../adr/0005-rabbitmq-messaging.md) 统一为 RabbitMQ。T07 已实现 `media-service` 的 OSS、RabbitMQ 和 FFmpeg 链路；本地基础构建仍不依赖 Docker 或外部服务。

## 配置约定

Java 服务仅在显式设置 `SPRING_PROFILES_ACTIVE=local` 时加载各模块的 `application-local.yml`。非 local profile 会关闭 JDBC/Redis 自动配置，继续支持不依赖外部服务的基础启动；local profile 才启用连接和健康组件。所有地址、端口、凭据、Schema、Redis DB、OSS 和 RabbitMQ 参数都由环境变量覆盖；仓库中的 `.env.example` 只有空值或安全示例。

多个 Codex 任务或 Git Worktree 不共享对话上下文，也不应复制明文密码。Windows 开发机首次配置时运行：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\scripts\Initialize-LocalEnvironment.ps1
```

脚本会显示 Windows 安全凭据输入框，将非敏感配置保存到当前用户目录的 `.ccnu-edulion/local.env`，并使用 Windows DPAPI 将 MySQL 凭据加密为同目录下的 `mysql.credential.clixml`。T08A.1 起还会自动生成独立 256-bit 课程/媒体服务授权密钥并加密为 `course-media-auth.credential.clixml`。已有本地环境缺少该文件时可直接重跑初始化脚本，它只补齐服务密钥，不覆盖 MySQL 凭据或 `local.env`；加密文件只能由同一台计算机上的同一 Windows 用户解密，不会进入 Git。

需要凭据的命令通过受控脚本执行，例如：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl course-service -am spring-boot:run
}
```

`Set-ExecutionPolicy` 只对当前 PowerShell 进程生效，不修改用户级或系统级执行策略。加载脚本只在子命令执行期间注入环境变量，结束后恢复原值，并且不会打印凭据。不要直接读取或输出凭据文件，也不要把密码、JWT 密钥或云资源密钥写入 `local.env`、PowerShell 历史、命令参数、日志或提交内容。非 Windows 环境应改用操作系统密钥管理工具或 CI Secret，并保持相同的环境变量接口。

T08A.1 的加载脚本把 DPAPI 服务密钥临时注入为 `COURSE_MEDIA_AUTH_SECRET`，同时供 course-service 签发和 media-service 验证。生产/CI 必须通过各自 Secret 管理提供同名变量，不能复用 `IAM_JWT_SECRET`。非敏感参数 `COURSE_MEDIA_AUTH_ISSUER`、`COURSE_MEDIA_AUTH_AUDIENCE`、`COURSE_MEDIA_AUTH_TOKEN_TTL`、`MEDIA_SERVICE_AUTH_MAX_TOKEN_TTL` 和 `MEDIA_SERVICE_AUTH_CLOCK_SKEW` 可由环境覆盖；默认分别为 `edu-platform-course-service`、`edu-platform-media-service`、`30s`、`1m` 和 `5s`。

五个领域服务的 MySQL Schema 变量分别是 `IAM_MYSQL_DATABASE`、`COURSE_MYSQL_DATABASE`、`MEDIA_MYSQL_DATABASE`、`LEARNING_MYSQL_DATABASE` 和 `OPERATION_MYSQL_DATABASE`。T02 不创建 Schema 或业务表；后续任务应保持每服务独立 Schema/账号。当前本地管理员账号只作为临时开发约定，通过 `MYSQL_USERNAME` 和 `MYSQL_PASSWORD` 注入，不能成为生产配置。

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

验证完成后用 `Remove-Item Env:MYSQL_PASSWORD` 清除当前进程变量。其他服务替换 Maven 模块名和对应 Schema 变量；端口依次为 Gateway 8080、IAM 8081、Course 8082、Media 8083、Learning 8084、Operation 8085。

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

T07 使用 `RABBITMQ_ENABLED`、`RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_VHOST`、`RABBITMQ_USERNAME` 和 `RABBITMQ_PASSWORD`。纯本机的 RabbitMQ/Redis 开发密码可保存在仓库外 `.ccnu-edulion/local.env`；OSS AccessKey、Security Token 及其他远程 API 凭据只能由调用进程环境或同等密钥管理注入，禁止写入 `local.env` 或仓库。

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

## T04 IAM 迁移与测试账号

T04 只允许把 `IAM_MYSQL_DATABASE` 指向用途明确的 IAM 专用开发或测试 Schema，例如 `edu_platform_iam_dev` 或 `edu_platform_iam_test`。应用不会创建数据库；启用 `local` profile 后，Flyway 只在该 Schema 中创建 `user`、`role`、`permission`、`user_role`、`role_permission`、`refresh_session` 和 `login_audit`。启动前必须同时通过环境变量提供至少 32 字节的 `IAM_JWT_SECRET`，不得写入仓库或命令历史。

最小验收账号默认不生成。只在确认目标是专用 IAM local/test Schema 后，显式设置 `IAM_SEED_ENABLED=true`，并通过当前进程环境或 IDE 密钥配置提供至少 12 位的 `IAM_SEED_PASSWORD`。初始化器会幂等写入 `student_test_01`、`teacher_test_01`、`admin_test_01` 及 STUDENT、TEACHER、ADMIN 三个角色和各自一项最小权限；重复启动不会增加重复记录。生产 profile 不会加载该初始化器。

清理前需再次确认当前连接的数据库名称，然后在 MySQL 交互式客户端中选择该专用 Schema，并执行：

```sql
source java-platform/iam-service/src/main/resources/db/seed/cleanup-iam-seed.sql;
```

清理脚本只删除上述固定测试账号、角色、权限及其会话和登录审计。重新生成时再次显式启用 Seed 并重启 IAM；完成后应清除当前进程中的 `IAM_SEED_PASSWORD` 和 `IAM_JWT_SECRET`。

## T04 统一登录入口

本地前端地址固定为 student `http://localhost:5173`、teacher `http://localhost:5174`、admin `http://localhost:5175`。统一登录入口只有 student 的 `/login`；teacher/admin 未登录时通过 `VITE_STUDENT_WEB_URL` 跳转到该入口，并携带经过白名单校验的内部 `redirect`。三个地址分别由 `VITE_STUDENT_WEB_URL`、`VITE_TEACHER_WEB_URL`、`VITE_ADMIN_WEB_URL` 配置。Vite 使用严格端口，若端口已占用应停止旧进程，不会自动改用未被 Gateway CORS 允许的其他端口。

跨应用跳转后 Access Token 会随页面内存一起丢失，目标应用通过 Gateway 域下的 `EDU_REFRESH` HttpOnly Cookie 调用刷新接口恢复登录态。不要为本地调试把 Access Token 写入 `localStorage`。

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

## 全量验证

仓库的 Java、Python、Frontend 和契约一致性命令统一记录在 [`docs/roadmap/verification.md`](../roadmap/verification.md)。默认 `clean verify` 不激活 `local` profile，因此 CI 不依赖开发机数据库、Redis、OSS 或 RabbitMQ。
