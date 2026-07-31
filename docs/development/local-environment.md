# Local development environment

T02 使用开发机已经运行的 MySQL `127.0.0.1:3306` 和 Redis `127.0.0.1:6379`。对象存储选择阿里云 OSS；当前没有 RocketMQ，只保留禁用配置。本地环境不依赖 Docker，也不要求安装新的系统软件。

## 配置约定

Java 服务仅在显式设置 `SPRING_PROFILES_ACTIVE=local` 时加载各模块的 `application-local.yml`。非 local profile 会关闭 JDBC/Redis 自动配置，继续支持不依赖外部服务的骨架启动；local profile 才启用连接和健康组件。所有地址、端口、凭据、Schema、Redis DB、OSS 和 RocketMQ 参数都由环境变量覆盖；仓库中的 `.env.example` 只有空值或安全示例。

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

## OSS 与 RocketMQ

`media-service` 只预留 `OSS_ENDPOINT`、`OSS_REGION`、`OSS_BUCKET`、AccessKey/临时安全令牌和 STS 角色变量。T02 没有 OSS SDK、上传接口或真实 Bucket 测试。生产优先使用运行时身份或服务端 STS 短期凭据；浏览器只能获得后端按对象和操作范围签发的短期授权，不能获得长期 AccessKey。

`ROCKETMQ_ENABLED` 默认是 `false`。当前开发机没有 RocketMQ，T02 不安装、不启动客户端、不执行消息健康检查；只有后续具备本地或托管实例后，才可配置 NameServer、Namespace 和凭据并引入实际集成。

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

仓库的 Java、Python、Frontend 和契约一致性命令统一记录在 [`docs/roadmap/verification.md`](../roadmap/verification.md)。默认 `clean verify` 不激活 `local` profile，因此 CI 不依赖开发机数据库、Redis、OSS 或 RocketMQ。
