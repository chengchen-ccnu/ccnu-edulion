# T08A 课程媒体契约、领域绑定与处理能力交付

## 任务目标与边界

T08A 将课程封面和课时资源的写事实从 URL 收口为稳定 `mediaId`，并把 T07 的最小视频派生演进为可授权播放的私有 HLS。课程服务只拥有课程版本与媒体引用，不接入 OSS SDK、不保存 Bucket、Object Key 或短期签名 URL；媒体服务只拥有上传、对象、处理和授权事实，不保存课程实体或决定课程可见性。

本任务完成 `COURSE_COVER`、`LESSON_VIDEO` 领域绑定，并显式启用 `VIDEO_POSTER`、`SUBTITLE`、`COURSEWARE`、`ARTICLE_ATTACHMENT` 上传策略。最终 Vue 上传器、处理状态交互和播放器属于 T08B；报名、学习进度、“我的学习”、题库和 Agent 均未实现。

## 架构与调用流程

1. 浏览器或后续 T08B 页面经 Gateway 使用 T07 上传会话把对象直传 OSS，并轮询到 `READY`。
2. 教师创建/编辑课程时只提交 `coverMediaId`；目录写入只提交课时资源的 `mediaId`、用途和顺序。
3. `course-service` 在本地写事务前调用 `POST /api/v1/media/{mediaId}/binding-validations`，media-service 校验上传者、精确用途和 `READY` 状态。失败时课程不落库；没有跨库查询。
4. 课程事务保存版本级 `cover_media_id` 或 `lesson_media_resource`，同时写本域 Outbox。绑定、替换和解除分别发布已有 `MediaReferenceBound`/`MediaReferenceReleased` v1；mandatory、publisher confirm、有限退避和失败留痕用于可靠补投。
5. media-service 按 `eventId` 幂等维护引用台账。已发布版本及其媒体引用保持不变；首次编辑已发布课程会复制新草稿及其独立引用，草稿替换不会污染线上版本。
6. `LESSON_VIDEO` 上传完成后进入有限并发 FFmpeg Worker。Worker 在随机临时目录下载原件，生成 `hls-v1` VOD 清单和切片，逐个上传私有对象并记录组件；成功、失败或超时都会清理本机临时目录，部分上传失败会按已知精确键补偿删除。
7. 请求 HLS 访问授权时，media-service 为登记切片逐个签发短期 GET，重写并上传同期限临时清单，再返回该清单的短期 URL。到期回收只删除记录中的精确临时 Object Key，不在业务表或日志中保存签名 URL。

## 契约与事件

OpenAPI 更新到 `0.6.0`：

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

## 数据库表与向前迁移

没有改写 T05/T07 已合并迁移，只新增两个 V2：

### course-service V2

- `course_version.cover_media_id`：可空、稳定 `COURSE_COVER` 资产 ID，无跨 Schema 外键。
- `course_version.cover_url`：放宽为可空，只供历史读取兼容，新写契约不接受该字段。
- `lesson_media_resource`：保存课时、稳定媒体 ID、限定用途和同用途连续顺序；只对本域 `lesson` 建外键。
- `outbox_event`：保存媒体引用事件信封、路由键、发布状态、次数、下次补投时间和失败摘要。

### media-service V2

- `media_variant_component`：记录 HLS 变体的切片名、顺序、精确 Object Key、MIME、大小和 ETag。
- `temporary_access_object`：记录短期授权生成的临时清单精确 Object Key、到期和删除时间；不保存清单内 URL。

应用不会创建数据库。只可将迁移运行在用途明确的 `edu_platform_course_dev`、`edu_platform_media_dev` 或相应 test Schema；本次 Codex 没有连接、创建或修改真实 MySQL Schema。

## 环境变量与启动

沿用 T07 的 MySQL、OSS、RabbitMQ 和 FFmpeg 配置，新增或明确以下非敏感参数：

- `MEDIA_SERVICE_BASE_URL`、`COURSE_MEDIA_REQUEST_TIMEOUT`
- `COURSE_MEDIA_OUTBOX_POLL_DELAY`、`COURSE_MEDIA_CONFIRM_TIMEOUT`、`COURSE_MEDIA_OUTBOX_MAX_ATTEMPTS`
- `MEDIA_FFMPEG_TIMEOUT`、`MEDIA_WORKER_MAX_CONCURRENT_JOBS`、`MEDIA_HLS_SEGMENT_DURATION`

默认单进程只运行一个 FFmpeg 作业，适合本地机器；扩容或提高并发前必须评估 CPU、内存、磁盘和 OSS 带宽。使用真实本地服务时必须通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入凭据，先启动 media-service，再启动 course-service；不得读取或把凭据拼到命令行。

```powershell
& .\scripts\Invoke-WithLocalEnvironment.ps1 -ScriptBlock {
    $env:SPRING_PROFILES_ACTIVE = 'local'
    mvn '-Dmaven.repo.local=.m2/repository' -f java-platform/pom.xml -pl media-service -am spring-boot:run
}
```

在另一个已初始化凭据的终端以相同包装方式启动 `course-service`，并确保 `MEDIA_SERVICE_BASE_URL` 指向 media-service。开启真实引用事件链路还需设置 `RABBITMQ_ENABLED=true`。

## 伪数据、清理与重新生成

本任务没有新增 Seed 或伪数据，也没有创建或修改任何真实开发数据库。自动化测试只在进程内 H2 MySQL 模式的 `course_test`、`course_seed_test` 和 `media_test` 中生成最小随机 UUID 资产/课程数据；每个测试清表，进程结束即释放。

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
- `pnpm.cmd --dir web-apps run check:api`：提交前会按设计报告生成文件相对 `HEAD` 有本任务差异；生成器本身成功且重复生成稳定。提交后必须复跑并记录最终一致性结果。
- `git diff --check`：文档完成后的最终结果在提交前复核。

真实 OSS/RabbitMQ/FFmpeg 验收本轮未执行，因为当前进程未显式提供 `MEDIA_REAL_ACCEPTANCE=true` 和专用外部环境授权；不能把跳过记为成功。用例已演进为实际验证 HLS 清单及切片、confirm/return、有限重试/DLQ、未 ACK 重投、Broker 不可用 Outbox 保留与精确清理，运行命令见本地环境文档。

## 人工演示步骤

1. 核对 `SELECT DATABASE()` 分别为 `edu_platform_media_dev`、`edu_platform_course_dev`，通过凭据包装脚本启动 RabbitMQ/OSS 已启用的 media-service 与 course-service。
2. 以教师 A 创建 `COURSE_COVER` 上传会话，直传并完成校验，等待资产为 `READY`；用该 `mediaId` 创建课程，确认课程响应只有稳定 `coverMediaId`，课程表没有 Object Key/签名 URL。
3. 用教师 B 的封面、错用途资产、仍为 `PROCESSING` 的资产和随机不存在 ID 分别尝试绑定，确认统一拒绝且课程事实不变。
4. 替换并解除封面，检查 course Outbox 产生 Bound/Released，media-service 引用台账最终一致；重放同一请求/事件不增加重复引用。
5. 上传最小视频并等待 `READY`，将 `LESSON_VIDEO` 绑定到视频课时；发布 v1 后编辑 v2 替换资源，确认学生课程详情仍读取 v1 的媒体 ID，直到显式发布 v2。
6. 请求 `hls-v1` 访问授权，读取返回的短期播放清单和至少一个切片；确认课程表、事件、日志和授权审计均没有保存签名 URL，到期维护任务将临时清单标记并精确删除。
7. 在专用验收环境执行 `MediaRealEnvironmentAcceptanceTest`，确认真实 OSS/RabbitMQ/FFmpeg 测试通过且测试 owner 数据与唯一 OSS 前缀被清理。

## 注释覆盖与可读性检查

新增/修改的课程服务边界、绑定校验客户端、版本复制/引用切换、Outbox 发布、HLS Worker、FFmpeg 子进程、临时清单授权与回收均补充中文 Javadoc；复杂测试使用中文 Given/When/Then 说明业务前置、操作和预期。两个 V2、OpenAPI 和事件 Schema 对字段边界、状态、约束、失败与签名 URL 风险均有说明。生成客户端未手工添加注释。

## 已知风险与后续依赖

- T08B 尚未实现最终上传页、状态轮询、资源编排控件、播放器和授权刷新；新上传媒体目前只能通过 API/验收脚本绑定和播放。
- 当前 HLS 是单清晰度视频、显式关闭音频；多码率自适应、音轨/字幕打包、截图、DRM 和 CDN 发布策略需后续专项设计。
- 病毒、恶意文档与内容安全服务尚未接入；MIME/扩展名、OSS HEAD/SHA 与 FFmpeg 可解码校验不等价于生产内容安全。
- 绑定验证与课程事务不是分布式事务；READY 资产在验证后到提交前的极短竞态由引用事件、延迟回收和后续对账兜底，不宣称“恰好一次”。
- 单机 `Semaphore` 只限制单实例并发；多实例全局容量、作业租约、断点续转和运维重放仍需在部署设计中补齐。
- 真实 OSS/RabbitMQ/FFmpeg 验收需要用户明确提供专用环境并显式启用，本次自动化只验证 Fake 适配器路径。

## 分支、提交与 PR

- 分支：`codex/t08a-course-media-contract-binding`
- 基线：`origin/main` 的 `eb17ed228efb1779ffc5761e18e43d1f2733f339`，包含已合并 PR #9、#11、#12。
- 关键提交：提交后补记。
- Ready PR：创建后补记；不得自行合并。
