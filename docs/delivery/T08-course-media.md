# T08A/T08A.1/T08B 课程媒体绑定、上传与播放闭环交付

## 任务目标与边界

T08A 将课程封面和课时资源的写事实从 URL 收口为稳定 `mediaId`，并把 T07 的最小视频派生演进为可授权播放的私有 HLS。课程服务只拥有课程版本与媒体引用，不接入 OSS SDK、不保存 Bucket、Object Key 或短期签名 URL；媒体服务只拥有上传、对象、处理和授权事实，不保存课程实体或决定课程可见性。

T08A 完成 `COURSE_COVER`、`LESSON_VIDEO` 领域绑定，并显式启用 `VIDEO_POSTER`、`SUBTITLE`、`COURSEWARE`、`ARTICLE_ATTACHMENT` 上传策略。T08B 已完成课程封面/课时视频 Vue 上传器、处理状态交互与播放器；报名、学习进度、“我的学习”、题库和 Agent 均未实现。

T08A.1 在既有绑定与 HLS 之上补齐播放授权闭环：course-service 决定课程/版本/课时/资源可见性，media-service 只验证一次性服务授权并签发 READY 媒体的短期 OSS/HLS 凭证。T11 前不创建或伪造报名，报名制课程的非试看课时固定返回 403；T08B 只消费这两条课程域授权，不扩展该临时权限边界。

## 架构与调用流程

1. T08B 浏览器页面经 Gateway 使用 T07 上传会话，把对象字节直传 OSS，并轮询到 `READY`。
2. 教师创建/编辑课程时只提交 `coverMediaId`；目录写入只提交课时资源的 `mediaId`、用途和顺序。
3. `course-service` 在本地写事务前调用 `POST /api/v1/media/{mediaId}/binding-validations`，media-service 校验上传者、精确用途和 `READY` 状态。失败时课程不落库；没有跨库查询。
4. 课程事务保存版本级 `cover_media_id` 或 `lesson_media_resource`，同时写本域 Outbox。绑定、替换和解除分别发布已有 `MediaReferenceBound`/`MediaReferenceReleased` v1；mandatory、publisher confirm、有限退避和失败留痕用于可靠补投。
5. media-service 按 `eventId` 幂等维护引用台账。已发布版本及其媒体引用保持不变；首次编辑已发布课程会复制新草稿及其独立引用，草稿替换不会污染线上版本。
6. `LESSON_VIDEO` 上传完成后进入有限并发 FFmpeg Worker。Worker 在随机临时目录下载原件，生成 `hls-v1` VOD 清单和切片，逐个上传私有对象并记录组件；成功、失败或超时都会清理本机临时目录，部分上传失败会按已知精确键补偿删除。
7. 请求 HLS 访问授权时，media-service 为登记切片逐个签发短期 GET，重写并上传同期限临时清单，再返回该清单的短期 URL。到期回收只删除记录中的精确临时 Object Key，不在业务表或日志中保存签名 URL。
8. 学生/普通用户经 Gateway 调用 `POST /api/v1/courses/{courseId}/lessons/{lessonId}/resources/{resourceId}/playback-grants`；请求不包含 mediaId、用途或访问结论。course-service 只查询当前发布版本，PUBLIC 课程或 `previewable=true` 课时可继续；报名制非试看在 T11 前返回 `COURSE.ENROLLMENT_REQUIRED`。
9. 教师经 `POST /api/v1/teacher/courses/{courseId}/lessons/{lessonId}/resources/{resourceId}/playback-grants` 预览当前草稿（无草稿时当前发布版本），查询始终带 owner ID。历史版本、他人课程、错课时、错资源与非视频用途统一不可见。
10. course-service 使用独立 HMAC 密钥签发 30 秒服务 JWT，绑定 issuer/audience、访问者、mediaId、`LESSON_VIDEO`、`COURSE_PLAYBACK`、`hls-v1`、`INLINE`、traceId、iat/exp 与 jti。media-service 的 `/internal/v1/media/{mediaId}/access-grants` 逐项校验并原子消费 jti；Gateway 不路由 `/internal/**`。

## 契约与事件

T08A 将 OpenAPI 更新到 `0.6.0`：

- 课程创建/编辑请求使用可空 `coverMediaId`，不再接受 `coverUrl`。
- 课程读取响应返回稳定 `coverMediaId`，并保留只读、可空 `coverUrl` 兼容历史数据。
- 课时请求/响应增加 `resources`，用途限定为 `LESSON_VIDEO`、`VIDEO_POSTER`、`SUBTITLE`、`COURSEWARE`、`ARTICLE_ATTACHMENT`。
- 新增媒体绑定验证接口 `POST /api/v1/media/{mediaId}/binding-validations`。
- `MediaVariant` 可报告 HLS 组件数量，访问授权说明明确 HLS 临时清单与切片共同过期。

`docs/events/media-events.v1.yaml` 继续使用 T07 事件信封和路由键，补充课程引用语义：

- `COURSE_VERSION_COVER` 的 `referenceId` 是不可变课程版本 ID。
- `LESSON_RESOURCE` 的 `referenceId` 是课程域课时媒体引用行 ID。
- HLS 处理完成事件可携带 `componentCount`；事件仍不包含签名 URL。

前端 TypeScript 类型由同一 OpenAPI 重新生成。为保持契约编译兼容，现有页面仅停止提交 `coverUrl`、保存目录时透传既有 `resources`，并兼容历史封面为空；没有实现上传和播放交互。

T08A.1 将 OpenAPI 更新到 `0.7.0`，新增两个课程域播放授权 POST。外部契约只暴露课程/课时/课程资源 ID 和短期 `CourseMediaPlaybackGrant`；内部服务 API 不属于 Gateway 外部契约。404 用于资源不存在、版本/绑定不匹配和 owner 不匹配，报名制非试看使用明确 403，READY 冲突为 409，媒体依赖不可用为 503。T08B 直接复用生成的 TypeScript 字段和路径调用这些接口，没有修改 OpenAPI，也没有手写重复 DTO。

## 数据库表与向前迁移

没有改写 T05/T07 已合并迁移，只新增两个 V2。验收后另行核对发现注释审计 PR #11 曾向已执行的 IAM/课程 V1 加入 SQL 注释；本分支已把两份 V1 恢复为各自 T04/T05 首次发布提交的精确 blob，并将说明移至对应持久化包 `package-info.java`，从而恢复既有 Flyway checksum，而非形成新的迁移版本：

### course-service V2

- `course_version.cover_media_id`：可空、稳定 `COURSE_COVER` 资产 ID，无跨 Schema 外键。
- `course_version.cover_url`：放宽为可空，只供历史读取兼容，新写契约不接受该字段。
- `lesson_media_resource`：保存课时、稳定媒体 ID、限定用途和同用途连续顺序；只对本域 `lesson` 建外键。
- `outbox_event`：保存媒体引用事件信封、路由键、发布状态、次数、下次补投时间和失败摘要。

### media-service V2

- `media_variant_component`：记录 HLS 变体的切片名、顺序、精确 Object Key、MIME、大小和 ETag。
- `temporary_access_object`：记录短期授权生成的临时清单精确 Object Key、到期和删除时间；不保存清单内 URL。

### media-service V3（T08A.1）

- `consumed_service_authorization`：以随机 jti 为主键记录 issuer、audience、访问者、到期与消费时间，用唯一约束拒绝重放。
- 表中不保存原始 JWT、课程/课时事实、媒体用途、Object Key、Bucket 或签名 URL；过期记录由 media-service 维护任务删除。
- course-service 无新增迁移；发布/试看/owner 和资源绑定判断完全使用既有课程表。

应用不会创建数据库。只可将迁移运行在用途明确的 `edu_platform_course_dev`、`edu_platform_media_dev` 或相应 test Schema；本次 Codex 没有连接、创建或修改真实 MySQL Schema。

## 环境变量与启动

沿用 T07 的 MySQL、OSS、RabbitMQ 和 FFmpeg 配置，新增或明确以下非敏感参数：

- `MEDIA_SERVICE_BASE_URL`、`COURSE_MEDIA_REQUEST_TIMEOUT`
- `COURSE_MEDIA_OUTBOX_POLL_DELAY`、`COURSE_MEDIA_CONFIRM_TIMEOUT`、`COURSE_MEDIA_OUTBOX_MAX_ATTEMPTS`
- `MEDIA_FFMPEG_TIMEOUT`、`MEDIA_WORKER_MAX_CONCURRENT_JOBS`、`MEDIA_HLS_SEGMENT_DURATION`
- `COURSE_MEDIA_AUTH_SECRET`（敏感、至少 32 UTF-8 byte，仅 DPAPI/CI Secret）
- `COURSE_MEDIA_AUTH_ISSUER`、`COURSE_MEDIA_AUTH_AUDIENCE`、`COURSE_MEDIA_AUTH_TOKEN_TTL`
- `MEDIA_SERVICE_AUTH_MAX_TOKEN_TTL`、`MEDIA_SERVICE_AUTH_CLOCK_SKEW`

默认单进程只运行一个 FFmpeg 作业，适合本地机器；扩容或提高并发前必须评估 CPU、内存、磁盘和 OSS 带宽。使用真实本地服务时必须通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入凭据，先启动 media-service，再启动 course-service；不得读取或把凭据拼到命令行。

初始化脚本会把课程/媒体服务密钥单独保存到用户目录的 DPAPI 文件。已有本地环境可重跑 `Initialize-LocalEnvironment.ps1` 只补齐该文件；不会把密钥写入 `local.env`。course-service 和 media-service 必须加载同一密钥，且不能复用 IAM JWT 密钥。

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:SPRING_PROFILES_ACTIVE = 'local'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl media-service -am spring-boot:run
}
```

在另一个已初始化凭据的终端以相同包装方式启动 `course-service`，并确保 `MEDIA_SERVICE_BASE_URL` 指向 media-service。开启真实引用事件链路还需设置 `RABBITMQ_ENABLED=true`。

## 伪数据、清理与重新生成

T08A/T08A.1 都没有新增 Seed 或正式伪数据，也没有创建数据库。自动化测试只在进程内 H2 MySQL 模式的 `course_test`、`course_seed_test` 和 `media_test` 中生成最小随机 UUID 资产/课程数据；每个测试清表，进程结束即释放。T08A.1 绝未创建报名记录。

既有 T05 固定课程 Seed 仍默认关闭，不含媒体 ID。若人工验收使用该固定课程，`java-platform/course-service/src/main/resources/db/seed/cleanup-course-seed.sql` 已补齐该课程的课时媒体引用和课程 Outbox 定向清理；再次设置 `COURSE_SEED_ENABLED=true` 并重启可幂等重新生成无媒体绑定的 T05 Fixture。

真实媒体环境验收只允许固定测试 owner 和唯一 `t07-acceptance/<runId>/` OSS 前缀。测试最终按数据库记录的精确原件、HLS 清单、切片和临时清单键删除，并逐表断言专用 media dev Schema 无残留；不列举或清空 Bucket。

## 测试命令与实际结果

已执行：

- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify`：8 个 Reactor 模块全部成功；42 项 Java 测试中 41 项通过，1 项真实环境验收因未设置 `MEDIA_REAL_ACCEPTANCE=true` 按设计跳过。
- Course：12 项通过。新增覆盖封面绑定/替换/解除、历史 `cover_url` 读取、他人/错用途/未就绪/不存在媒体拒绝、目录媒体重复请求幂等、发布版本封面隔离。
- Media：10 项不依赖真实 OSS/FFmpeg/RabbitMQ 的测试通过。新增覆盖六种已评审课程用途与未开放枚举拒绝、绑定校验、HLS 清单/切片产物、短期授权重写、临时对象回收、处理失败重试和耗尽重试后的失败事件/DLQ 恢复路径；既有引用事件重复消费幂等继续通过。
- `pnpm.cmd --dir web-apps run typecheck`：OpenAPI 重新生成及全部 workspace 类型检查通过。
- `pnpm.cmd --dir web-apps run test`：沙箱内因 Vite/esbuild `spawn EPERM` 无法启动；按批准在沙箱外复跑，6 个文件 34 项全部通过。
- `pnpm.cmd --dir web-apps run build`：沙箱内因同一 `spawn EPERM` 失败；按批准在沙箱外复跑，学生、教师、管理三端生产构建全部通过。
- `pnpm.cmd --dir web-apps run check:api`：主提交后复跑通过，生成文件与 OpenAPI 0.7.0 一致且已被 Git 跟踪。
- `git diff --check`：文档完成后的最终结果在提交前复核。

T08A 分支开发时自动化曾跳过真实环境用例；随后用户于 2026-08-02 按教程完成并确认真实 OSS、RabbitMQ、FFmpeg HLS 验收通过。PR #13 已合并到 `main`，不能再保留“待合并/未验收”的旧状态。

### T08A.1 实际验证（2026-08-05）

- Java 完整 `clean verify`：8 个 Reactor 模块全部成功；13 个报告共 46 项，45 项通过、1 项真实环境用例按默认开关跳过，0 失败/错误。Gateway 10 项、Course 14 项、Media Fake 11 项；新增覆盖公开/试看/报名制、草稿 owner、当前/历史版本、课时资源错配、owner 预览、学生直调 owner 拒绝、伪造/过期/错 audience/访问者/mediaId/用途/变体、重放、非 READY、Gateway internal 路由隔离和 URL 不落库。
- 真实 `MediaRealEnvironmentAcceptanceTest`：通过安全环境包装脚本显式启用后 1 项通过。专用 `edu_platform_media_dev` 从 V2 向前迁移到 V3，真实 OSS 单对象/Multipart、FFmpeg HLS、owner 授权与服务令牌授权的清单/切片读取、RabbitMQ confirm/return/有限重试/DLQ/重投全部通过；结束后 OSS 精确对象和全部验收数据逐表归零。
- Frontend：`generate:api`、全 workspace `typecheck` 通过；Vitest 在沙箱内因 esbuild `spawn EPERM` 不能启动，获准沙箱外复跑后 6 文件 34 项通过；三端生产构建同样在沙箱外复跑后全部通过。`check:api` 在提交前按设计检测到生成文件相对 HEAD 的合法差异，提交后必须复跑通过。
- PowerShell：两个本地环境脚本通过解析器语法检查；实际升级路径成功只新增 DPAPI 服务凭据，没有覆盖现有 MySQL 凭据或 `local.env`。

## 人工演示步骤

1. 核对 `SELECT DATABASE()` 分别为 `edu_platform_media_dev`、`edu_platform_course_dev`，通过凭据包装脚本启动 RabbitMQ/OSS 已启用的 media-service 与 course-service。
2. 以教师 A 创建 `COURSE_COVER` 上传会话，直传并完成校验，等待资产为 `READY`；用该 `mediaId` 创建课程，确认课程响应只有稳定 `coverMediaId`，课程表没有 Object Key/签名 URL。
3. 用教师 B 的封面、错用途资产、仍为 `PROCESSING` 的资产和随机不存在 ID 分别尝试绑定，确认统一拒绝且课程事实不变。
4. 替换并解除封面，检查 course Outbox 产生 Bound/Released，media-service 引用台账最终一致；重放同一请求/事件不增加重复引用。
5. 上传最小视频并等待 `READY`，将 `LESSON_VIDEO` 绑定到视频课时；发布 v1 后编辑 v2 替换资源，确认学生课程详情仍读取 v1 的媒体 ID，直到显式发布 v2。
6. 请求 `hls-v1` 访问授权，读取返回的短期播放清单和至少一个切片；确认课程表、事件、日志和授权审计均没有保存签名 URL，到期维护任务将临时清单标记并精确删除。
7. 在专用验收环境执行 `MediaRealEnvironmentAcceptanceTest`，确认真实 OSS/RabbitMQ/FFmpeg 测试通过且测试 owner 数据与唯一 OSS 前缀被清理。
8. 登录学生账号，对 PUBLIC 或报名制试看课时调用课程域播放授权接口并播放返回清单；确认非试看报名制返回 403，浏览器直调 `/internal/**` 经 Gateway 为 404，直调 owner 媒体授权不能读取教师资产。
9. 登录课程 owner，从教师接口预览草稿视频；用其他教师、发布版本之外的旧资源行或课件/字幕资源调用同一播放接口，确认统一不可见。

## 注释覆盖与可读性检查

新增/修改的课程服务边界、绑定校验客户端、版本复制/引用切换、Outbox 发布、HLS Worker、FFmpeg 子进程、临时清单授权与回收均补充中文 Javadoc；复杂测试使用中文 Given/When/Then 说明业务前置、操作和预期。两个新建 V2、OpenAPI 和事件 Schema 对字段边界、状态、约束、失败与签名 URL 风险均有说明。已执行 V1 的补充说明改由持久化包级 Javadoc 承载，生成客户端未手工添加注释。

T08A.1 新增/触及的课程播放决策、只读查询投影、服务令牌签发/验证、内部 HTTP 边界、jti 防重放事务、授权审计与过期清理均补充中文 Javadoc；V3、配置和 OpenAPI 描述说明了输入、输出、权限、失败与敏感信息边界。复杂授权测试明确 Given/When/Then，生成 TypeScript 未手工补注释。人工检查未发现把业务逻辑逐行翻译、注释与实现不一致或注释泄密。

## 已知风险与后续依赖

- T08B 的真实媒体后端环境验收已通过，但本次未在浏览器中执行带真实教师凭据、Gateway、course-service 与专用课程 Fixture 的上传—绑定—学生 HLS 全链路；自动化和 Mock 结果不能替代该人工 E2E。
- T11 尚未实现报名与学习权限查询；报名制非试看固定返回 403。T08A.1 不伪造报名，也不把该临时边界解释为最终授权模型。
- 首期播放接口只接受 `LESSON_VIDEO`；封面继续使用展示投影，字幕/课件保持绑定但不堆积未评审的播放/下载授权实现。
- 当前 HLS 是单清晰度视频、显式关闭音频；多码率自适应、音轨/字幕打包、截图、DRM 和 CDN 发布策略需后续专项设计。
- 病毒、恶意文档与内容安全服务尚未接入；MIME/扩展名、OSS HEAD/SHA 与 FFmpeg 可解码校验不等价于生产内容安全。
- 绑定验证与课程事务不是分布式事务；READY 资产在验证后到提交前的极短竞态由引用事件、延迟回收和后续对账兜底，不宣称“恰好一次”。
- 单机 `Semaphore` 只限制单实例并发；多实例全局容量、作业租约、断点续转和运维重放仍需在部署设计中补齐。
- 真实 OSS/RabbitMQ/FFmpeg 验收仍需专用环境并显式启用；本次已实际通过，但日常 `clean verify` 默认跳过该用例，后续环境变更需再次运行，不能用 Fake 结果替代。

## 分支、提交与 PR

- T08A：分支 `codex/t08a-course-media-contract-binding`，关键提交 `2f2697882394a5194a48e1756c8d05cd1a3780ea`；[PR #13](https://github.com/chengchen-ccnu/ccnu-edulion/pull/13) 已合并，当前基线 merge commit 为 `55b30d52493e84038da59f7a6789e43b8de6e716`。
- T08A.1：分支 `codex/t08a1-course-media-playback-auth`，实现提交 `f2e1d972e828759089d2192227366ddb4985059b`；[PR #14](https://github.com/chengchen-ccnu/ccnu-edulion/pull/14) 已合并，主线 merge commit 为 `2f40b762d41f780509ab388928aff84614d6b62c`。

## T08B 前端实现（2026-08-05）

### 功能与前端边界

- 教师创建/编辑课程不再出现手填图片 URL。`MediaUploadField` 支持封面选择、本地 Blob 预览、真实字节进度、校验/处理状态、成功、失败、取消、有限重试、替换和移除；只有上传器轮询到 `READY` 才向课程表单回写 `coverMediaId`。
- 课程目录的 `VIDEO` 课时在首次保存、取得稳定 lesson ID 后可上传 `LESSON_VIDEO`。状态机按服务端 `SINGLE_PUT`/`MULTIPART` 选择协议，Multipart 使用返回的 `partSize`、每片单独申请授权、最多 3 路并发、最多 3 次传输尝试，并向完成接口提交 OSS 实际 ETag。
- SHA-256 使用 `@noble/hashes` 增量状态和 4 MiB `Blob.slice()` 计算，任一时刻不把整段视频读入内存。上传用 `XMLHttpRequest.upload` 的真实字节事件聚合进度，不显示模拟进度。
- 同一运行中重复点击复用一个 Promise；创建会话的 429/503 有限重试固定复用同一 `clientRequestId`。`sessionStorage` 检查点只保存 uploadId、幂等键、用途和不可变文件摘要等稳定字段，不保存签名 URL、授权头或 Token；刷新后查询服务端会话并明确要求重选同一 File。
- 目录写请求显式投影为 `mediaId`、`purpose`、`sequence`，UI 使用的 lesson/resource ID 与 `uiKey` 不会进入请求。新 READY 视频保存后取得课程域 resource ID，教师才可调用 owner 预览接口；发布版本不可变性继续由 T08A 后端保证。
- 学生只调用发布课程资源播放授权；教师只调用 owner 预览授权。共享 `HlsVideoPlayer` 优先使用浏览器原生 HLS，其他支持 MSE 的浏览器按需加载 `hls.js 1.6.16`，切换/卸载会销毁实例、移除 source、中止请求和清除定时器。
- 播放授权根据 `expiresAt` 最多提前 30 秒刷新。429 最多退避两次，刷新失败后停止自动循环并显示 401/403/404/409/429/503 中文提示；短期 URL 和授权头只在组件内存存在，不写持久化状态或日志。

### 环境、数据与清理

T08B 没有新增数据库迁移、业务表、Seed 或报名数据，也没有创建数据库。自动化继续只使用随 Maven 测试进程释放的 H2 Schema。真实验收通过凭据包装脚本连接既有专用 `edu_platform_media_dev`（已处于 V3），使用固定测试 owner 和唯一 `t07-acceptance/<runId>/` 前缀验证 OSS、RabbitMQ 与 FFmpeg；用例结束后按精确 Object Key 删除原件、HLS/临时清单和切片，定向清理测试消息与记录，并逐表断言验收数据为 0。没有操作 course/IAM Schema，也没有创建虚假报名。

浏览器直传环境必须允许教师 Web Origin 对专用 OSS Bucket 执行短期 `PUT`，允许签名要求的请求头，并通过 `Access-Control-Expose-Headers: ETag` 暴露分片实际 ETag。生产 Origin 必须使用精确白名单，不能用 `*` 扩大带凭证边界。

### 测试与实际结果

- `pnpm.cmd --dir web-apps install --frozen-lockfile`：通过，锁文件可复现。
- `generate:api`、`check:api`、全 workspace `typecheck`：通过；OpenAPI 保持 0.7.0，生成文件相对 HEAD 一致。
- Vitest：受限沙箱首次因已知 `esbuild spawn EPERM` 无法启动；获准沙箱外复跑后 8 个文件 48 项全部通过。新增覆盖单对象、Multipart 已上传分片恢复、实际 ETag、真实字节聚合、取消、增量哈希、固定幂等键有限重试、FAILED/REJECTED/超时、学生/教师路径隔离、授权刷新时机和 429 重试上限。
- 三端生产构建：获准沙箱外执行并通过；`hls.js` 只在非原生 HLS 浏览器按需加载。
- Java：`gateway-service,course-service,media-service -am verify` 的 5 个 Reactor 模块全部成功；Gateway 10 项、Course 14 项、Media 12 项（真实环境 1 项默认跳过）及 shared-kernel 1 项均无失败。
- 真实 `MediaRealEnvironmentAcceptanceTest`：通过 `Invoke-WithLocalEnvironment.ps1` 显式开启后 1 项通过，实际连接既有 `edu_platform_media_dev`、真实 OSS、RabbitMQ 和 FFmpeg，并完成精确清理。该后端用例没有经过 Vue 页面，不能记录为真实浏览器上传/播放 E2E。
- 浏览器响应式检查：指向同一 OpenAPI Mock 的学生课程详情在 1440×900 与 390×844 均无横向溢出，窄屏信息和目录正确单列，控制台无错误。Mock 详情没有课时资源，教师页又需要真实登录，因此没有把 Mock 页面描述成上传/HLS 播放通过。
- `git diff --check`、敏感信息与构建产物审计在提交前执行；未发现签名 URL 持久化、日志输出、AccessKey、密码或应提交的 dist/target。

### 人工演示步骤

1. 通过安全环境包装脚本依次启动 media-service、course-service、IAM 与 Gateway，确认只使用专用 dev Schema；启动 teacher/student Web，OSS CORS 精确允许本地 Origin 并暴露 ETag。
2. 教师创建课程，选择 JPG/PNG/WebP 封面，观察 SHA、真实上传、校验/处理到 READY；保存后刷新，确认展示来自课程 `coverUrl` 投影。替换和移除后再次保存，确认线上已发布版本未被直接改变。
3. 保存一个 VIDEO 课时取得稳定 ID，分别选择小视频和超过 Multipart 阈值的视频；检查分片进度、取消/恢复提示、处理状态和 READY 后的目录绑定。保存请求只应包含稳定媒体三字段。
4. 保存目录后在教师页 owner 预览；发布课程后，以已登录访问者在学生详情选择同一课程域资源。公开或试看课时可播放；报名制非试看在 T11 前显示 403 文案。
5. 等待授权刷新，确认到期前重新签发；模拟 429/503 或停止依赖，确认有限重试后停止。切换资源或离开页面，确认播放器、定时器、授权请求和 Blob URL 已清理。

### 注释覆盖、风险与发布

新增/修改的上传 API、上传状态机、增量哈希、OSS 直传、分片并发/重试/取消、会话检查点、播放授权刷新、HLS 生命周期和页面职责均补充中文 TSDoc/JSDoc/Vue 注释；生成客户端未手工修改。人工可读性检查未发现注释与实现不一致或注释泄露敏感信息。

剩余风险：完整生产内容安全仍未接入；HLS 仍为单清晰度且无音频/字幕/DRM；真实浏览器全链路需带专用教师/访问者账号和最小公开/试看课程再次人工验收；大文件哈希虽有固定内存上限，但当前在主线程增量执行，超大视频的耗时仍受终端 CPU 影响。

T08B 分支为 `codex/t08b-course-media-web`，基线为已合并 PR #14 的 `2f40b762d41f780509ab388928aff84614d6b62c`。提交与 Ready PR 在创建后回填，PR 不自行合并。
