# ADR-0004: OSS 媒体资产生命周期与阶段性占位收口

- 状态：Accepted
- 日期：2026-08-01

## 背景

T04～T06 为了先完成登录和课程纵向切片，部分契约使用了 `avatarUrl`、`coverUrl` 等可直接展示的 URL，教师端课程表单也暂时要求手工填写封面地址。这些字段只代表当前阶段的展示数据，并不表示最终允许用户把本地文件交给业务服务或长期保存 OSS 签名 URL。

平台后续还会出现课程封面、课时视频、视频海报和字幕、图文课件、题目图片/音频/附件、题库导入文件、AI 提问图片、用户头像、教师资质材料、运营 Banner/专题封面以及报表导出文件。如果各领域分别实现一套上传、签名、扫描和清理逻辑，会形成权限不一致、永久 URL 泄露、孤儿对象无法回收和重复 SDK 封装。

## 决策

### 1. 统一资产边界

- 生产二进制对象统一保存在阿里云 OSS；MySQL 只保存资产元数据、对象 Key、校验值、处理状态和业务引用，不保存文件内容。
- `media-service` 是上传会话、OSS SDK、对象 Key、处理状态、派生文件、临时访问授权和资产回收的唯一拥有者。其他服务不得自行接入 OSS SDK。
- Gateway 只转发创建会话、确认上传、查询状态和获取访问授权等小型 API，不代理图片、视频、附件、导入文件或导出文件的字节流。
- 浏览器只能获得服务端按用户、用途、对象 Key、大小、MIME 和有效期约束的短期直传授权；不得获得长期 AccessKey。
- 对外写契约使用稳定的 `mediaId`/`assetId` 绑定业务资源，禁止把 OSS Object Key、Bucket 名或短期签名 URL 当作业务事实保存。读取契约可返回经过授权的 `deliveryUrl`/`coverUrl` 作为展示投影。

### 2. 资产用途与领域归属

| 资产用途 | 业务引用归属 | 默认访问级别 | 收口任务 |
|---|---|---|---|
| `COURSE_COVER` | `course-service` 的课程草稿/版本 | 草稿私有，发布后可走版本化 CDN | T07 后端能力，T08 表单上传与课程绑定 |
| `LESSON_VIDEO`、`VIDEO_POSTER`、`SUBTITLE` | `course-service` 的课时资源 | 私有，播放前鉴权 | T07 转码/授权，T08 上传、绑定与播放 |
| `COURSEWARE`、`ARTICLE_ATTACHMENT` | `course-service` 的课时资源 | 按课程访问权限 | T07 通用附件，T08 绑定与下载 |
| `QUESTION_IMAGE`、`QUESTION_AUDIO`、`QUESTION_ATTACHMENT` | `learning-service` 的题目版本 | 按题目/测评权限 | T09 数据与绑定，T10 编辑上传与预览 |
| `QUESTION_IMPORT_SOURCE` | `learning-service` 的导入任务 | 教师私有、限期保留 | T09 导入任务，T10 上传与结果页 |
| `AI_QUESTION_IMAGE` | Java Agent Access 的会话消息 | 学生本人/授权教师可见、短期保留 | T14 鉴权转交，T15 图片提问 |
| `USER_AVATAR` | `iam-service` 的用户资料 | 审核后公开读取 | T17 资料页面与绑定，不在 T04 回填 |
| `TEACHER_QUALIFICATION` | `iam-service` 的教师审核记录 | 严格私有 | T16/T17 审核流程与管理端预览 |
| `BANNER_IMAGE`、`TOPIC_COVER` | `operation-service` 的运营配置 | 发布后公开 CDN | T16 数据/发布规则，T17 管理上传与学生展示 |
| `REPORT_EXPORT` | 发起导出的领域任务 | 仅发起人/授权管理员、到期删除 | T16/T17 导出任务与短期下载 |

前端构建产物、代码仓库资源和随应用发布的图标不进入上述业务上传链路；它们由 CI/CD 发布到静态托管或 CDN。用户填写的普通外部超链接也不是媒体资产，但必须经过协议白名单、消毒和安全跳转处理。

### 3. 上传与处理状态机

1. 领域页面经 Gateway 请求 `media-service` 创建上传会话，提交用途、原始文件名、声明 MIME、大小、哈希和一次性 `clientRequestId`。
2. `media-service` 按用途执行角色、所有者、数量、扩展名、MIME、大小和配额校验，自行生成不可猜测的对象 Key。
3. 小图片/小附件使用短期单对象直传；大视频和较大导入文件使用 OSS Multipart Upload。两者共用上传会话和幂等确认语义。
4. 浏览器直传 OSS 后提交 ETag/Part 列表和哈希；服务端通过 OSS 元数据复核大小和对象存在性，不能只相信客户端“上传成功”。
5. 原始对象先进入隔离前缀，经过病毒、恶意文档、图片/音视频内容安全和必要的转码/缩略图处理后才进入 `READY`。
6. 统一状态为 `CREATED → UPLOADING → UPLOADED → VERIFYING → PROCESSING → READY`，失败分支为 `REJECTED/FAILED/EXPIRED/CANCELED`，删除分支为 `DELETING/DELETED`。状态转换必须幂等且有审计记录。
7. 领域服务只能绑定 `READY` 且用途匹配、当前用户有权使用的资产；跨服务只传稳定 ID，不建立跨 Schema 外键。

领域服务在绑定/解除资产时通过本领域 Outbox 发布 `MediaReferenceBound`/`MediaReferenceReleased`，`media-service` 按 `eventId` 幂等维护引用台账；`MediaUploaded`、`MediaReady`、`MediaRejected`、`MediaFailed` 和 `MediaDeleted` 用于驱动处理状态和领域投影。资产回收必须设置宽限期并执行引用对账，不能只依赖一次可能丢失的解除事件。

### 4. 访问、替换与删除

- 公开课程封面、头像和运营图片只有在对应业务版本发布/审核通过后才能通过不可变版本 Key 和 CDN 公开读取；原始上传对象始终私有。
- 视频、字幕、课件、题目附件、AI 图片、资质材料、导入源文件和导出结果默认私有，访问时由后端签发短期 URL/Token，禁止在数据库、日志、事件或前端持久化缓存中保存签名 URL。
- 业务记录保存资产 ID；`media-service` 不决定课程、题目、AI 会话或审核记录是否可见，必须依赖调用方给出的可信主体和领域授权结果。
- 替换封面或附件采用“先上传并就绪、再原子切换业务引用”。旧对象解除引用后进入延迟回收，不在请求内立即物理删除。
- 未完成会话、未绑定资产、导入源文件、AI 临时图片和过期导出结果按用途配置保留期，由定时任务幂等清理；共享资产必须在确认无引用后再删除。

### 5. 阶段性字段迁移

- T06 的 `coverUrl` 输入和 T04 的 `avatarUrl` 示例保留为当前版本兼容字段，不能被误认为最终上传接口。
- T07 先冻结通用媒体 OpenAPI、事件和状态机，不直接改写课程、IAM、题库或运营表。
- T08 为课程域新增向前迁移的 `cover_media_id` 和课时资源引用；创建/编辑请求改为绑定 `coverMediaId`，读取响应仍可提供 `coverUrl` 展示投影。迁移期允许读取历史 `cover_url`，但新 UI 不再要求教师手填 URL；历史数据回填完成后再通过新版本契约移除旧写字段。
- T10、T15、T17 分别在本领域契约中增加资产 ID；不得提前把这些字段塞进 T07 的通用媒体表，也不得让媒体服务保存题目、AI、用户或运营业务实体。
- 任何已合并迁移只能向前追加；禁止为了替换 URL 字段重写 T05/T06 已发布迁移。

## 后果

- T07 必须被实现为可复用的通用媒体基础，而不是只支持视频的专用接口；T08 是第一个完整消费者，必须同时收口课程封面和课时视频占位。
- T09/T10、T14/T15、T16/T17 必须复用同一上传会话、资产状态和短期访问模型，但各自拥有业务绑定与可见性规则。
- 在没有用户明确授权的专用测试 Bucket 时，只能使用 OSS SDK Mock/契约测试验证；不得把本地目录或 MinIO 冒充真实 OSS 验收。
- 资产处理、内容安全、转码、孤儿回收和临时下载增加了异步状态与运维成本，但避免了永久 URL、跨域 OSS SDK 和无法治理的孤儿文件。

## T07 实施状态

T07 已按本 ADR 落地上传会话、单对象/Multipart 直传、私有原件、OSS HEAD 复核、统一资产与处理状态、短期访问授权、引用事件台账、Outbox/RabbitMQ 可靠事件、幂等回收和可替换 FFmpeg Worker，首期只启用 `COURSE_COVER` 与 `LESSON_VIDEO`。实现没有修改课程或 IAM 业务表，也没有把短期签名 URL 写入数据库或事件。

当前的安全复核只包含对象存在性、大小/MIME/SHA 元数据和视频可解码性；病毒、恶意文档与内容安全仍需接入外部服务，接入前不得把首期能力解释为完整生产级内容审核。

## T08A 实施状态

T08A 将课程写事实收口为版本级 `cover_media_id` 与 `lesson_media_resource.media_id`。绑定前，`course-service` 只通过 media-service 的版本化 JSON 契约核验上传者、精确用途与 `READY` 状态；两服务不跨库查表、不建跨 Schema 外键。创建草稿副本时复制并新建媒体引用，因此后续草稿替换不会解除或污染已发布版本的引用。

课程绑定、替换和解除与课程事务一起写入本域 Outbox，再以 `MediaReferenceBound`/`MediaReferenceReleased` v1 发布到既有媒体事件 Exchange。发布采用 mandatory、publisher confirm、有限退避与失败留痕；media-service 继续按 `eventId` 幂等维护引用台账。数据库和事件只保存稳定 ID，不保存 Bucket、Object Key 或签名 URL。

T08A 显式启用 `VIDEO_POSTER`、`SUBTITLE`、`COURSEWARE`、`ARTICLE_ATTACHMENT` 的教师上传策略；其他冻结枚举仍拒绝上传。`LESSON_VIDEO` 的派生由单机有界并发 FFmpeg Worker 输出私有 `hls-v1` 播放清单与切片。授权时 media-service 为每个已登记切片签发短期 GET，生成同期限临时清单并在到期后按精确 Object Key 清理；课程表仍只保存 `mediaId`。多码率、自适应音轨、DRM、病毒/内容安全与最终 Vue 播放器不属于 T08A。

T08A 已通过 PR #13 合并。2026-08-02 的人工验收已确认真实 OSS、RabbitMQ、FFmpeg HLS 与课程媒体绑定链路通过；验收使用专用开发 Schema 和唯一 OSS 前缀，结束后按精确记录清理。

## T08A.1 实施状态

T08A.1 将课程可见性判断与媒体签发连接成服务端闭环。浏览器只按课程、课时和课程域资源行申请播放授权，不能提交 `mediaId`、用途或 `allowAccess`。`course-service` 从当前发布版本的绑定关系解析 `LESSON_VIDEO`：`PUBLIC` 课程允许已登录访问者播放；`ENROLLMENT_REQUIRED` 在 T11 前只允许 `previewable=true` 的课时，非试看内容明确返回 403。草稿、未发布内容只通过教师 owner 路径预览，历史版本、错课时和错资源统一不可见。

课程域批准后签发只发往 media-service 的短时 HMAC JWT，绑定 issuer/audience、访问者、mediaId、用途、访问类型、变体、处置方式、有效期、traceId 与一次性 jti。首期精确允许两组课程读取投影：`LESSON_VIDEO`/`COURSE_PLAYBACK`/`hls-v1`/`INLINE` 用于课程视频，`COURSE_COVER`/`COURSE_COVER_DISPLAY`/`ORIGINAL`/`INLINE` 用于课程列表和详情的原图封面。Gateway 不路由 `/internal/**`；media-service 校验全部字段并在 `consumed_service_authorization` 以唯一 jti 原子拒绝重放，再独立复核资产用途和 `READY` 状态。密钥只由 DPAPI/CI Secret 注入，令牌和短期 URL 均不写数据库、事件或日志。

首期只完整开放 `LESSON_VIDEO` 播放；封面展示授权仅能由 course-service 在确认草稿 owner 或已发布课程可见后申请，浏览器不能凭 `mediaId` 直接调用内部接口。字幕与课件保持稳定绑定但不被播放接口当作视频授权。T08B 负责 Vue 播放器与授权刷新，T11 负责真实报名/学习权限；media-service 仍不查询课程或学习 Schema。
