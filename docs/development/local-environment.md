# Local development environment

T02 使用开发机已经运行的 MySQL `127.0.0.1:3306` 和 Redis `127.0.0.1:6379`。对象存储使用阿里云 OSS，消息队列由 [ADR-0005](../adr/0005-rabbitmq-messaging.md) 统一为 RabbitMQ。T07 已实现 `media-service` 的 OSS、RabbitMQ 和 FFmpeg 链路；本地基础构建仍不依赖 Docker 或外部服务。

## 配置约定

Java 服务仅在显式设置 `SPRING_PROFILES_ACTIVE=local` 时加载各模块的 `application-local.yml`。非 local profile 会关闭 JDBC/Redis 自动配置，继续支持不依赖外部服务的基础启动；local profile 才启用连接和健康组件。所有地址、端口、凭据、Schema、Redis DB、OSS 和 RabbitMQ 参数都由环境变量覆盖；仓库中的 `.env.example` 只有空值或安全示例。

多个 Codex 任务或 Git Worktree 不共享对话上下文，也不应复制明文密码。Windows 开发机首次配置时运行：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\scripts\Initialize-LocalEnvironment.ps1
```

脚本会显示 Windows 安全凭据输入框，将非敏感配置保存到当前用户目录的 `.ccnu-edulion/local.env`，并使用 Windows DPAPI 将 MySQL 凭据加密为同目录下的 `mysql.credential.clixml`。加密文件只能由同一台计算机上的同一 Windows 用户解密，不会进入 Git。

需要凭据的命令通过受控脚本执行，例如：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl course-service -am spring-boot:run
}
```

`Set-ExecutionPolicy` 只对当前 PowerShell 进程生效，不修改用户级或系统级执行策略。加载脚本只在子命令执行期间注入环境变量，结束后恢复原值，并且不会打印凭据。不要直接读取或输出凭据文件，也不要把密码、JWT 密钥或云资源密钥写入 `local.env`、PowerShell 历史、命令参数、日志或提交内容。非 Windows 环境应改用操作系统密钥管理工具或 CI Secret，并保持相同的环境变量接口。

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
| FFmpeg | 本机真实视频转码验收需要 | T07 产生 `baseline-mp4-v1`、无音频的 MP4 派生资产；HLS、截图和多清晰度不在本轮范围 |
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

## 全量验证

仓库的 Java、Python、Frontend 和契约一致性命令统一记录在 [`docs/roadmap/verification.md`](../roadmap/verification.md)。默认 `clean verify` 不激活 `local` profile，因此 CI 不依赖开发机数据库、Redis、OSS 或 RabbitMQ。
