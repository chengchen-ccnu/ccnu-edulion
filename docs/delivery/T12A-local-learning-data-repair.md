# T12A 本地 student1 学习记录与课程封面修复

日期：2026-10-13。工作分支：`codex/t17c2-account-permissions-web`；实施开始时工作区干净。本修复是用户明确要求的本地演示数据维护，不是新增页面功能。2026-10-14 按用户提交合并要求归入 [PR #90](https://github.com/chengchen-ccnu/ccnu-edulion/pull/90)，CI 与合并状态以 PR 记录为准。

## 目标、范围与实际结果

- “七年级代数基础”固定课程 `00000000-0000-4000-8000-000000000201` 的早期 Seed 封面使用不可访问的 `cdn.example.test` 地址，现换为新制作的 PNG。
- student1 的固定 learner ID 为 `00000000-0000-4000-8000-000000000101`。截图中的课程 `00000000-0000-4000-8000-00000000f201` 在 Course 开发库确实不存在，其孤立报名 `0225ddc0-24fa-43d4-b5b6-ec3da741dd7f` 已精确删除。该课程没有此用户的进度、笔记、作答或图文完成事实。
- 其余两条 ACTIVE 报名保留：“七年级代数基础”与原有“线性代数”。没有改动“线性代数”的内容、媒体或版本。
- 不改前端页面、路由、契约、生产服务代码、迁移、账号权限或凭据。

## 封面制作与媒体调用流程

使用内置 imagegen，结果保存为 [`assets/grade7-algebra-cover.png`](assets/grade7-algebra-cover.png)，1536×1024，2,044,876 字节。

最终生成提示词：

```text
Use case: scientific-educational
Asset type: online course cover image, landscape 3:2 composition.
Primary request: an elegant illustrated cover for a seventh-grade introductory algebra course. Blue and pale turquoise background, softly dimensional notebook, pencil, ruler and simple geometric shapes, clear mathematical symbols x, y, +, −, =, and the valid equation 2x + 3 = 7. Central composition stays legible when cropped to square. Clean, polished, welcoming educational editorial illustration, modest detail, no people or logos.
Text (verbatim): "七年级代数基础" and smaller "从有理数到一元一次方程"
Constraints: crisp correct Chinese lettering, strong contrast, visual hierarchy, no watermarks.
```

操作者在本地统一登录页登录课程 owner `teacher1@example.invalid` 后，通过既有教师编辑页上传。浏览器经 Gateway 调用既有 Media 创建上传会话、短期直传和完成接口；Media 复核并经真实 RabbitMQ/处理 Worker 进入 READY。稳定媒体 ID 为 `96a3f373-607c-4351-a094-fb9391d67c8e`，用途 COURSE_COVER，owner 为固定测试教师。图片已经正常完成所有权/用途/状态绑定校验，不直接写媒体状态或接入第二套 OSS SDK。

正常保存生成本轮 v2 草稿 `f740b07d-1186-43f4-a843-8950a8b0d364`。尝试正常发布被既有校验拒绝：原 Seed 的 VIDEO 课时从未绑定视频。没有降低校验，也没有补造视频或改成图文课。随后仅对明确的本地旧 Seed 进行一次性维护，将 READY 封面绑定至原已发布 v1 `00000000-0000-4000-8000-000000000211`，清空其失效历史 cover_url，并撤掉本轮刚产生的草稿与克隆目录。已发布版本仍为 v1，版本 revision=1；没有把不符合发布条件的草稿标为 PUBLISHED。

封面绑定 v1、释放 v2 的两个 MediaReferenceBound/Released v1 事件与本域数据在同一 Course 事务写入 Outbox。由生产发布器经过 confirm 投递，Media 幂等消费后 v1 引用 BOUND、v2 引用 RELEASED。所有本轮 Course/Media 事件均 PUBLISHED，相关 PENDING=0；没有在数据库、事件或文档保存签名 URL、对象键或 Bucket。

**此脚本只用于已经存在的固定开发 Seed 修复，不能当作普通课程修改或生产发布方式。** 正常课程继续使用草稿与发布 API。

## 数据范围、清理与重复执行

未创建数据库、表、迁移、测试账号、课程或新的伪学习记录。仅复用现有 `edu_platform_course_dev`、`edu_platform_media_dev`、`edu_platform_learning_dev`：

- Course：修复固定 `course_version` 封面字段与 revision，清理本轮临时草稿及克隆 chapter/lesson、恢复 course 的无草稿指针，新增两条可靠媒体引用 Outbox；保留原 MediaReferenceBound 派发历史。
- Media：正常上传所产生的会话、资产、处理作业、已消费事件、引用与 Outbox 是此次封面替换的实际业务事实；保留 PNG 原件及其有效已发布引用。
- Learning：删除固定孤立 enrollment，既有 `notification_enrollment_interval` 本域外键级联清理相关区间。其他历史和编号/事件未做全域清扫。

可重复执行的维护命令：

```powershell
.\scripts\Remove-Student1OrphanEnrollment.ps1 -InspectOnly
.\scripts\Remove-Student1OrphanEnrollment.ps1
.\scripts\Repair-Student1AlgebraCover.ps1 -InspectOnly
.\scripts\Repair-Student1AlgebraCover.ps1
```

两脚本都只允许明确开发 Schema，凭据统一由 Invoke-WithLocalEnvironment.ps1 加载。第一个在课程存在或有历史学习事实时拒绝删除，重复删除为零行；第二个限定本轮上传媒体、教师、原版本、草稿与 revision，有差异则停止，已修复后为零写入。脚本包含中文边界/输入/结果/副作用注释，已人工复核。

若需要以后替换或移除新封面，使用正常教师编辑和发布流程；旧 Seed 必须先补齐真实课时视频才能正常发布。不要删除当前被 v1 引用的 OSS 对象。需重新制作封面时使用文档中的提示词，再走正常 Media 上传；本次脚本不负责创建另一套上传或恢复孤立报名。

## 环境与运行过程

现有 8080～8084 和 5173/5174 服务保持运行。原 Media/Course 进程未启用 RabbitMQ 消息开关，因此上传最初停在 PROCESSING/PENDING。检查确认本次之前 Course 无 PENDING、本次 Media 只有该图片的一个 PENDING，相关共享处理/引用队列为空后，通过安全包装器临时启动相同现有 classpath 的 Media 18083 与 Course 18082，运行真实生产发布/消费链路。没有 purge 队列、FLUSHDB 或操作旧验收队列。

临时进程在本轮事件处理完成后停止；原服务不重启。今后日常联调应通过包装器在启动进程中显式设置 `RABBITMQ_ENABLED=true`，否则下一次上传/媒体引用仍可能停在待处理。Course/Media 的非敏感消息开关可以配置在本地配置或启动环境；密码仍通过 DPAPI 提供。

没有修改 local.env 或任何凭据文件。开发流程全部结束前，local.env 中若仍有 OSS_ACCESS_KEY_ID、OSS_ACCESS_KEY_SECRET、OSS_SECURITY_TOKEN、验证码固定码或其他秘密，应迁入本机加密秘密管理并通过安全加载器注入，再删除明文项；正式部署用 Secret Manager/部署 Secret。地址、端口、Schema 等非敏感配置可以保留。

## 实际验证与演示

- 孤立报名脚本 InspectOnly：课程不存在、历史学习事实为零、匹配报名 1；Cleanup：删除 1 行、剩余 0；再次 Cleanup：删除 0 行、剩余 0。关联通知区间为 0，其余两报名仍 ACTIVE。
- 封面脚本 InspectOnly：固定教师 READY 封面及原 Seed/本轮草稿匹配；Apply：成功；再次执行：already repaired，零修改。
- 真正 Gateway 公共课程详情 GET 返回可用短期封面，访问原图 HTTP 200，验证 PNG 文件头及 1536×1024 尺寸；没有输出或保存签名 URL。
- 浏览器硬刷新教师课程管理，显示新封面、v1/r1、已发布，未遗留“有未发布修改”；图片 complete=true、naturalWidth=1536、naturalHeight=1024。
- PowerShell 解析与 `git diff --check` 通过。未改变 Java/Python/前端生产代码，未重跑全仓构建或页面全状态测试；没有用教师会话冒充 student1 UI 验收。

演示：用 student1 打开/刷新 `/my-learning`，应仅显示两门正常课程，“七年级代数基础”显示新封面，没有 f201 的失败卡片与对应部分失败提示。教师端课程管理也可确认封面及无临时草稿。浏览器截图位于忽略目录 `.m2/runtime-logs/student1-algebra-cover-repaired.png`。

## 已知限制

该早期演示课程依然没有可播放的视频。本轮只完成用户要求的封面和孤立学习记录维护，不宣称课程视频可播放或正常新版本发布可用。显式重新运行旧 T05 Seed 会重写其演示内容及旧封面，故不要用它重置已修复的课程。此问题应由后续演示 Seed 维护独立处理。
