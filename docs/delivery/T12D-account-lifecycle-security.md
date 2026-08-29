# T12D account lifecycle and Redis identity security delivery

## 任务目标与边界

T12D 先将外部契约从 OpenAPI 0.16.0 向前演进到 0.17.0，再在既有 Gateway、IAM 和 Media 边界内实现学生注册、一次验证挑战、密码找回/重置/修改、持久会话版本、当前/全部设备退出、教师申请提交、管理员账号控制以及 Redis 多实例限流和短期身份安全状态。

本任务没有实现 T12E Vue 页面或第二套登录入口，没有接入真实短信/邮件供应商，没有实现 T16 教师审核决定/教学能力授予，没有新增 OSS 客户端、跨 Schema 查询/外键或第二套手写 DTO。Redis 不保存账号、Refresh Session、协议、申请或审计唯一事实，也不代替 MySQL 事务、主键、唯一约束或乐观修订。

## 主要架构与调用流程

Gateway 在 Spring Security 完成 JWT 校验后运行两个全局边界。第一个以 Redis Lua `INCR + PEXPIRE` 对登录、注册、验证挑战、密码重置、媒体上传凭证、题库导入和答题提交实施多实例共享固定窗口；超限统一返回 429 和 `Retry-After`。第二个将 JWT `sessionVersion` 与 Redis 缓存比较，密码变更、全端退出、禁用或强制下线时 IAM 先使缓存失效，Gateway 不再接受旧令牌。刷新/退出和公开账号恢复端点不依赖旧会话缓存，保证用户能恢复或清理已失效会话。

IAM 以 `IdentitySecurityStore` 隔离短期 Redis 状态：验证码和目标均先 HMAC 摘要，创建挑战同时写发送冷却，Lua 脚本核对 purpose/type/target/code 并原子删除，错误最多五次。登录以账号摘要和 IP 摘要独立计数，账号达五次或 IP 达二十次进入 TTL 锁定；账号成功只清除该账号计数，不用成功账号洗掉 IP 风险累计。local/test 只允许明确的固定且不记日志适配器；非 local/test 没有供应商时返回 503，不伪造发送成功。Redis 缺失、超时或脚本异常也统一显式 503，不降级为无验证/无锁定/无限流。

注册只接受服务端固定的 `STUDENT + learning:access`，一个 MySQL 事务写用户、唯一手机/邮箱身份、TERMS/PRIVACY 版本同意、注册幂等事实和安全审计；并发占用由 MySQL 唯一键最终裁决。密码重置的正确挑战对未知/不可用账号也返回相同 204 形状且不改任何账号，不使结果成为账号存在性探针。真正匹配时修改密码、递增持久 `sessionVersion`、撤销全部 Refresh Session 并审计。

Refresh Token 继续只以 SHA-256 摘要入库，新增会话版本、设备 ID/名称和最后使用时间。当前设备退出撤销当前 Refresh Session 并清除 Cookie；已签发 Access Token 最多保留配置的短 TTL。全部设备退出、修改/重置密码、禁用和强制下线则通过持久版本立即使全部 Access Token 失效，同时撤销全部 Refresh Session。

教师申请只允许尚未获得 `teaching:access` 的 ACTIVE STUDENT。普通账号先通过 Gateway 调用既有 Media 上传 API，Media 仅对 STUDENT 开放 PDF/JPEG/PNG/WebP、20 MiB 以内的 `TEACHER_QUALIFICATION`；IAM 仅向 Media JSON 绑定校验传递稳定 `mediaId`，必须是本人、精确用途且 `READY`。申请事实仅创建 `SUBMITTED`；`UNDER_REVIEW/APPROVED/REJECTED` 预留给 T16，T12D 不修改角色或权限。

管理员接口要求 `administration:access`，列表/详情只返回掩码身份；解锁、禁用和强制下线均要求原因、`clientRequestId` 和 `expectedRevision`。MySQL 乐观更新先于 Redis 副作用，修订冲突不会误解锁或误使缓存失效；同一命令可幂等重放。禁止管理员通过该接口自我禁用。

## API 契约

OpenAPI 0.17.0 新增：

- `POST /api/v1/auth/registration-challenges`、`POST /api/v1/auth/register`。
- `POST /api/v1/auth/password-reset-challenges`、`POST /api/v1/auth/password-reset`、`POST /api/v1/auth/password/change`。
- `POST /api/v1/auth/logout-all`、`GET /api/v1/users/me/sessions`；既有 `POST /api/v1/auth/logout` 保留为当前设备退出。
- `POST /api/v1/teacher-applications`、`GET /api/v1/teacher-applications/me`。
- `GET /api/v1/admin/users`、`GET /api/v1/admin/users/{userId}`、以及 `/unlock`、`/disable`、`/session-revocation` 管理命令。

`LoginRequest` 向前增加可选 `deviceId/deviceName`，`CurrentUser` 增加 `sessionVersion`。新增安全不可用、验证失败、身份占用、账号不存在和修订冲突响应。所有 429 均包含 `Retry-After`，敏感请求字段标记 `writeOnly`。TypeScript 类型已用同一 OpenAPI 重新生成，没有手写平行 DTO。

## 数据库表与迁移

IAM `V2__account_lifecycle_security.sql` 为 `user` 增加 `session_version/revision`，将旧的非 TTL `LOCKED` 状态迁移为 ACTIVE，之后的短期锁定只由 Redis TTL 管理；为 `refresh_session` 增加 `session_version/device_id/device_name/last_used_at`。新增七类事实表：

- `user_identity`：规范化手机/邮箱的 MySQL 唯一事实。
- `registration_request`：注册 `clientRequestId` 重放事实。
- `protocol_agreement`：TERMS/PRIVACY 版本同意。
- `teacher_application` 与 `teacher_application_command`：申请状态、稳定资质 `mediaId`、修订和幂等命令。
- `account_security_audit`：密码、会话、教师申请和管理命令审计。
- `admin_account_command`：管理员命令指纹、目标和结果修订。

无跨 Schema 外键，无 Redis 锁，无数据库创建命令。真实验收已在严格命名的 `edu_platform_iam_dev` 上将 Flyway 从 V1 正常向前迁移到 V2；保留的变更只有 V2 Schema 和 Flyway 成功历史。

## 环境变量与启动

Gateway 增加 `GATEWAY_RATE_LIMIT_ENABLED`、`GATEWAY_RATE_LIMIT_KEY_PREFIX`、`GATEWAY_SESSION_VALIDATION_ENABLED`、`GATEWAY_SECURITY_RETRY_AFTER`，并使用 `REDIS_*` 和 `GATEWAY_REDIS_DATABASE`。IAM 增加 `IAM_SECURITY_STORE`、`IAM_LOGIN_RATE_LIMIT_*`、`IAM_VERIFICATION_*`、`IAM_SESSION_VERSION_*`、`MEDIA_SERVICE_BASE_URL` 和 `IAM_MEDIA_REQUEST_TIMEOUT`。默认 Gateway/IAM 都启用 Redis 安全状态；只有 test profile 显式使用进程内存替身。

`scripts/Initialize-LocalEnvironment.ps1` 使用 Windows DPAPI 生成不进仓库的 `iam-security.credential.clixml`，`Invoke-WithLocalEnvironment.ps1` 仅在子命令期间注入 JWT HMAC、验证码 HMAC 和 local 固定码。存量开发环境需由当前 Windows 用户重跑一次初始化脚本补齐该加密文件。无供应商的非 local/test 验证挑战返回 503，这是预期的安全关闭。

启动 IAM/Gateway 均必须经安全包装脚本，示例和真实验收命令见 `docs/development/local-environment.md`。

## 伪数据、清理与重建

local/test Seed 继续使用固定用户 `student_test_01`、`teacher_test_01`、`admin_test_01`；T12D 为三者增加固定 `example.invalid` 邮箱身份，并为学生增加 `local-v1` TERMS/PRIVACY 同意。这些数据只用于登录、身份查询、协议和后续 T12E 本地验收；不包含教师申请、资质文件或审核决定。初始化默认关闭，重复执行不增加重复行。

清理时先确认 `SELECT DATABASE()` 是专用 IAM dev/test Schema，再执行 `java-platform/iam-service/src/main/resources/db/seed/cleanup-iam-seed.sql`。脚本按外键逆序只清理固定三个 user ID 的教师申请/命令派生事实、管理命令、安全审计、协议、注册、身份、会话、角色关联和用户，不清空其他数据。重建时设置 `IAM_SEED_ENABLED=true`、通过未跟踪的安全方式提供至少 12 位 `IAM_SEED_PASSWORD`，再经包装脚本重启 IAM。

自动化 H2 数据库随测试进程销毁。真实 IAM 验收只创建一个随机 `t12d_*` 账号及其最小身份/协议/会话/审计，并在 `finally` 中按精确 user ID 删除后断言为 0；Redis 只创建本轮挑战、冷却、失败/锁定、会话和 Gateway 限流 Key，均已按精确 Key 删除。没有创建 Media 资产、OSS 对象、RabbitMQ 消息或其他领域数据。

## 测试命令与实际结果

- `pnpm.cmd --dir web-apps run generate:api`：成功生成 OpenAPI 0.17.0 TypeScript 契约。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl gateway-service,iam-service -am test`：专项回归通过；Gateway 17 项中 16 项通过、1 项真实环境用例按开关跳过，IAM 14 项中 13 项通过、1 项真实环境用例按开关跳过。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl media-service -am test`：所有 Reactor 模块通过；Media 26 项中 22 项通过，4 个需外部环境的既有用例按开关跳过。
- `IamRedisMysqlRealAcceptanceTest`：通过安全包装脚本在真实 MySQL 8.0 `edu_platform_iam_dev` 和 Redis DB 1 通过；Flyway V1 正常前进 V2，一个随机 Fixture 及精确 Redis Key 已清理。
- `GatewayRedisRealAcceptanceTest`：首次真实执行暴露 Lua 参数序列化问题并安全返回 503；参数改为字符串后复跑通过，真实 Gateway HTTP 前 10 次转发成功、第 11 次返回 429/`Retry-After`，本轮限流 Key 已删除。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify`：8 个 Reactor 模块全部通过；共发现 134 项测试，122 项通过，12 项外部环境用例按显式开关跳过，0 失败/错误。
- `pnpm.cmd --dir web-apps run typecheck`：通过；OpenAPI 新增的必填 `sessionVersion` 已同步到既有测试 Fixture。`pnpm.cmd --dir web-apps run test` 在受限沙箱内因 esbuild `spawn EPERM` 无法启动，经批准在沙箱外复跑后 29 个文件、191 项测试全部通过。`pnpm.cmd --dir web-apps run build` 经相同批准完成管理、学生和教师三端生产构建。
- `python -m compileall -q edu_agent_app server.py` 与 `python -m unittest discover -s tests -v`：通过，8 项既有 Python Agent 测试全部成功。旧版 pip 的隔离构建在沙箱内无法取得 setuptools，经批准允许下载后，`python -m pip wheel --use-feature=in-tree-build --no-deps --wheel-dir .m2/python-wheels .` 成功生成 wheel。
- 两个本地环境 PowerShell 脚本均通过 AST 语法解析。本轮尝试替存量用户目录自动补建新增 DPAPI IAM 安全文件时被沙箱策略拒绝，未绕过；现有环境需由当前 Windows 用户手动重跑 `scripts/Initialize-LocalEnvironment.ps1`，仓库实现与真实验收不受影响。

专项测试覆盖挑战一次消费/错误上限、发送冷却、不可枚举重置、唯一身份、协议事实、密码重置/修改、sessionVersion 递增、Refresh 族轮换/重放撤销、当前/全端会话、教师申请 Media 绑定和幂等、未授予 TEACHER、管理命令 revision/重放/审计、Redis 故障关闭、下游异常保真以及全部要求的高风险 Gateway 端点映射。

## 人工演示步骤

1. 在 local profile 以固定验证码请求注册挑战，查看掩码 `deliveryHint`；重复请求确认 429/`Retry-After`。
2. 使用新 `clientRequestId`、同一 challenge、STUDENT 账号和两项协议注册；重放相同命令返回原用户，换新命令重用 challenge 固定 422。
3. 登录并查询 `/users/me/sessions`，验证当前设备标记；执行当前退出只撤销该 Refresh Session。
4. 先输错密码五次观察短期锁定，由管理员使用原因、修订和命令 ID 解锁；重放不新增第二条命令事实。
5. 重置/修改密码后使用旧 Access Token 经 Gateway 访问受保护资源，确认会话版本不匹配；旧 Refresh Token 也无法轮换。
6. 学生上传一份 `TEACHER_QUALIFICATION` 并等待 READY，提交教师申请；确认状态只是 `SUBMITTED`、只保存 `mediaId`、账号仍无 `TEACHER/teaching:access`。
7. 管理员查询掩码身份，执行强制下线与禁用；旧令牌立即失效，重用旧 revision 返回 409。
8. 暂停 Redis 后尝试验证挑战、登录或高风险 Gateway 写入，确认显式 503/`Retry-After`，不会继续转发。

## 已知风险与后续依赖

- 当前设备退出依赖 Refresh Session 撤销；已签发 Access Token 最长在 15 分钟默认 TTL 内结束。需要当前设备 Access Token 立即单独撤销时，未来必须增加持久会话族/jti 语义，不应用无事实的 Redis 黑名单临时拼接。全端高风险撤销已立即生效。
- `sessionVersion` 缓存过期时 Gateway 安全返回 503；客户可调用公开 Refresh 端点从 MySQL 权威事实重建。这一可用性/安全取舍需在 T18 容量和故障演练中重新校准 TTL，不可改成缓存缺失即放行。
- T12D 不含真实验证供应商；上线注册/找回前必须完成供应商安全评审、发送结果及未知身份不发送的不可枚举编排。当前生产默认 503 是明确的未就绪状态。
- T16 才能写教师审核决定并授予同账号教学能力；T12E/T17C 分别消费本契约实现用户和管理页面，不得复制登录或手写 DTO。
- 本地 DPAPI IAM 安全文件是新增的启动前置；存量环境必须由当前 Windows 用户运行初始化脚本，不能由他人复制 DPAPI 文件。

## 可读性、分支与发布

OpenAPI、V2 SQL、Redis Lua 端口、会话版本、验证挑战、密码生命周期、媒体绑定、管理命令、故障关闭、Seed 和真实验收均补充了中文 Javadoc/注释，说明输入、结果、状态变化、幂等/重试和外部副作用。生成的 TypeScript 文件未手工修改。

分支为 `codex/t12d-account-lifecycle-security`。最终回归已通过；关键提交和 Ready PR 在创建后补入，本任务不自行合并。
