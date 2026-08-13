# T10 教师题库前端交付说明

## 任务目标与功能边界

本任务在教师端实现页面规格冻结的六组题库路由：题库列表、新建题目、题目详情、编辑新版本、导入任务列表和导入任务详情。六组路由共同启用既有“题库”导航槽位并保持同一高亮组，没有改变全局导航标签、顺序或其他槽位。

实现覆盖本人题目筛选与分页、六种普通题型和一层有序组合题、不可变版本历史、真实生命周期、Markdown/LaTeX 安全预览、题目媒体、CSV/XLSX 导入源上传、浏览器显式选择的有界历史 JSON 导入、逐项人工结构化修正、确认跳过和任务级精确清理。没有实现组卷、学生作答、成绩、错题、T11/T12 页面，没有修改 Python 智能体、已合并数据库迁移、全局页面规格或后端契约，也没有读取或扫描 44,441 份历史题库。

## 主要架构与调用流程

1. `question-api.ts` 只通过 Gateway 和 OpenAPI 生成客户端调用 T09/T09A/T09B 契约；页面、表单和适配层复用生成类型，没有维护第二套 DTO。
2. 列表筛选、页码、历史版本页码/版本 ID、导入结果页码/结果筛选/复核项 ID 都写入 URL。刷新与深链接从 URL 和服务端安全投影恢复，不依赖内存假数据。
3. `QuestionFields.vue` 统一编辑父题与子题。组合题父题和每个有序子题都提交完整结构；子题类型由生成类型限制，不能继续嵌套组合题。
4. 编辑请求携带当前 `expectedRevision` 创建新版本。409 保留当前表单并要求显式重新加载；422 的 `fieldErrors` 按 `version.*` 或 `correctedVersion.*` 路径映射到对应输入项。
5. `SafeRichText.vue` 的唯一 `v-html` 输入来自 `marked`、KaTeX 和 DOMPurify 组成的清洗管线。脚本、事件属性、外链媒体、嵌入框架和表单元素被禁止；题目媒体另经授权访问组件渲染。
6. `MediaUploadField.vue` 小范围泛化到 `QUESTION_IMAGE`、`QUESTION_AUDIO`、`QUESTION_ATTACHMENT` 和 `QUESTION_IMPORT_SOURCE`，继续复用 T07 上传会话、直传和轮询。业务请求只保存 `mediaId`；页面卸载时中止请求/轮询并撤销临时 URL。
7. CSV/XLSX 先上传为 `QUESTION_IMPORT_SOURCE`，READY 后创建真实异步导入任务。历史 JSON 只读取浏览器显式选择的文件或目录，单文件上限 256 KiB、每批最多 20、每任务最多 1000，默认 dry-run；提交内容只含规范化相对路径和 SHA-256，不提交客户端绝对路径。
8. 导入详情只在 `COMPLETED` 或 `PARTIAL_SUCCESS` 允许解决 `NEEDS_REVIEW`。复核使用完整结构化修正或显式确认跳过；409 保留修正。清理先展示任务 ID 和影响范围，再经勾选与浏览器二次确认调用任务级精确清理端点。

## 使用的 API

本任务没有修改 `docs/api/openapi.yaml`。使用 OpenAPI 0.10.1 已冻结端点：

- `GET /api/v1/teacher/knowledge-points`
- `GET|POST /api/v1/teacher/questions`
- `GET|PUT /api/v1/teacher/questions/{questionId}`
- `POST /api/v1/teacher/questions/{questionId}/status`
- `GET /api/v1/teacher/questions/{questionId}/versions`
- `GET /api/v1/teacher/questions/{questionId}/versions/{versionId}`
- `GET|POST /api/v1/teacher/question-imports`
- `GET /api/v1/teacher/question-imports/{importTaskId}`
- `POST /api/v1/teacher/legacy-question-imports`
- `POST /api/v1/teacher/legacy-question-imports/{importTaskId}/batches`
- `GET /api/v1/teacher/question-imports/{importTaskId}/items`
- `GET /api/v1/teacher/question-imports/{importTaskId}/items/{itemId}`
- `POST /api/v1/teacher/question-imports/{importTaskId}/items/{itemId}/resolution`
- `DELETE /api/v1/teacher/legacy-question-imports/{importTaskId}/created-resources`
- T07 media-service 上传会话、状态查询和短期访问授权端点

## 数据库、迁移、环境变量与启动

T10 的生产代码是纯前端交付，没有创建或修改数据库、表、迁移、事件契约或运行时环境变量。教师端继续使用既有 `VITE_GATEWAY_BASE_URL`，浏览器不得直连 learning-service 或持有 OSS AccessKey。

本地前端启动命令：

```powershell
pnpm.cmd --dir web-apps --filter @edu/teacher-web run dev
```

真实环境验收通过 `scripts/Invoke-WithLocalEnvironment.ps1` 使用既有专用 `edu_platform_learning_dev` 与 `edu_platform_media_dev`，没有创建 Schema、表或 Seed。凭据没有写入仓库、文档或命令参数。

## 自动化与真实浏览器验收

- `pnpm.cmd --dir web-apps run generate:api` 与 `check:api`：通过，生成客户端无契约漂移。
- `pnpm.cmd --dir web-apps run typecheck`：通过，三端及共享包类型检查成功。
- `pnpm.cmd --dir web-apps run test`：18 个测试文件、115 项全部通过。
- `pnpm.cmd --dir web-apps run build`：学生端、教师端、管理端三个生产构建全部通过；只有既有大 chunk 警告。
- 六组路由的深链接、刷新、同组导航高亮、本人筛选、空态、partial-error、分页、版本历史、导入结果筛选和 820×1180 响应式通过真实 Codex 内置浏览器验收。
- 真实环境完成普通题创建/编辑、422 字段映射、两个标签制造的 409 本地输入保留、DRAFT→ACTIVE→ARCHIVED、归档只读、统一 404、一层结构化组合题、三种题目媒体、CSV/XLSX/LEGACY_JSON 导入和 401/403 权限验收。
- Markdown、LaTeX、上标和清洗预览通过；危险事件属性与不允许的嵌入图片没有进入渲染 DOM。

结论为**有条件通过**。剩余两项不是产品失败，但尚不能写成已通过：

1. 浏览器 offline：内置浏览器控制接口不提供网络离线仿真，本次收尾又在控制内核初始化前遇到本地运行时路径错误。Vitest 已覆盖 offline 分支，真实 503 与旧内容保留已通过，但真实离线动作仍未执行。
2. 键盘激活：Tab/Shift+Tab 聚焦和可见焦点环已通过；内置控制后端不能可靠派发 Enter/Space 激活事件，本次收尾同样无法取得浏览器控制，因此纯键盘激活动作仍未执行。

## 尚需人工补验的最短步骤

### 浏览器离线

1. 登录教师端并打开 `/teacher/questions`，等待至少一张题目卡加载完成。
2. 打开浏览器开发者工具，在 Network 的网络下拉框选择 **Offline**；不要离线后硬刷新。
3. 在关键词输入 `offline-check`，点击“应用筛选”。
4. 预期：URL 和输入值保留，原有题目卡不被清空，页面显示“当前处于离线状态，恢复网络后重试。”
5. 将网络恢复为 **No throttling/Online**，点击“重试”。预期：离线提示消失并按当前 URL 重新加载。

### 键盘激活

1. 在 `/teacher/questions` 只用 Tab 聚焦“新建题目”链接，确认有可见焦点环，按 Enter。预期进入 `/teacher/questions/new`，且“题库”导航仍高亮。
2. 返回列表，在关键词输入一个唯一值后只用 Tab 聚焦“应用筛选”按钮，按 Space。预期 URL 写入该关键词并出现 loading 后的新结果或空态。
3. 在新建页只用 Tab 聚焦“预览”按钮，按 Space。预期预览区域展开；再次按 Space 时按当前按钮语义收起或刷新预览。全程焦点不可无故丢失。

## 真实测试数据、媒体和精确清理

### 题目

- 人工普通题 `ab10bf99-ca6d-4be7-9f97-5da699e7a4fb`：`ARCHIVED`，v3/r4。
- 组合父题 `2a9134bc-5c96-407e-b507-1fa43deeaa35`：`ARCHIVED`，v1/r1。
- 组合子题 `01b8d285-35de-41a8-9106-ce8051b9e9cb` 与 `c584452a-2d08-4a23-a80d-e4a0a7c624fc`：作为父版本的内部有序组成保留为 `DRAFT` 子聚合，不进入根题列表；父题已归档只读。
- CSV 导入题 `eeae94bc-35c9-4de8-85a5-7c5e5f5a7d4d`：`ARCHIVED`。
- 修正 XLSX 导入题 `1a0c426a-30a1-4e2a-8974-709581bfea03`：`ARCHIVED`。

正式契约没有普通题/组合题删除端点，归档又必须保留不可变版本与媒体投影，因此这些题目不能安全精确删除。本轮没有跨库删行，也没有新增生产删除接口。

### 导入任务

- CSV 任务 `9e85cbf5-2d17-4b94-8a1c-7a743da3f0a5`：`COMPLETED`，1/1。
- 原 XLSX 任务 `0354b513-5148-421c-8e0e-00acd68ff030`：`COMPLETED`，0/0；原最小素材实际是一行 18 列，未产生数据行，未虚报导入题目。
- 修正 XLSX 任务 `a393f1c1-9097-4635-a3da-1d0d10c9b15b`：`COMPLETED`，1/1。
- LEGACY_JSON 任务 `ba0baf11-198f-4aa8-9f89-c037b7d54fba`：已调用正式任务级接口精确清理为 `CLEANED`；删除题目 1、来源引用 3、媒体引用 0。
- 文件名校验失败产生的空 LEGACY_JSON 任务 `e4e0276c-5d0b-48fd-a09e-0e4584592222`：已调用同一正式接口清理为 `CLEANED`；影响计数均为 0。

CSV/XLSX 契约没有任务删除端点，三个任务必须作为导入审计记录保留；对应的已导入题已归档。任务记录本身在源文件释放后仍保留。

### 媒体

- 归档组合题绑定：图片 `42d3afab-7091-4970-84a2-099d6b8b2ee9`、音频 `6416f94c-668a-43d3-b05c-5100708d8cdf`、附件 `28731cb3-6f8b-4f62-822e-0b0d100d61b6`。
- 导入源绑定：CSV `bb53f36f-f15c-48b1-af3c-bafa320dbeb5`、原 XLSX `fd1b7775-bfbd-4e1a-b8ce-61ba5b5c944a`、修正 XLSX `6cd3ca19-2f11-4305-a24b-483947f377ba`。
- 六个资产在收尾只读复核时均为 `READY/SUCCEEDED`，引用均为 `BOUND`；没有记录或输出 Bucket、Object Key、签名 URL。

题目三媒体绑定到不可变归档版本，当前没有解除题目版本媒体引用的正式业务动作，必须保留以保证只读详情可访问。三个 `QUESTION_IMPORT_SOURCE` 由 7 天任务保留期管理，本轮 `retainUntil` 为 2026-08-20；learning-service 默认每小时标记到期任务并发布精确 `MediaReferenceReleased`。media-service 在最后一个引用释放后设置 24 小时宽限期，默认每小时由回收器对账并删除精确对象、将资产标为 `DELETED`。

后续验证最终删除时应通过服务状态和只读数据核对：任务 `source_released_at` 已设置、媒体引用为 `RELEASED`、资产为 `DELETED` 且 `deleted_at` 非空；如需校验对象不存在，也必须复用 media-service 的回收/审计能力，不直接列举或删除 OSS 对象。

若后续本地验收要求“零持久残留”，需要单独实现仅 local/test 可启用的精确清理任务，而不是新增生产删除 API。该任务应只接受本轮 run ID 或显式 ID 白名单，校验专用开发/测试 Schema 和测试 owner，通过 learning-service 发布解除引用事件，等待 media-service 回收，再按依赖顺序清理精确题目图、任务和验收 Outbox，并对每个 ID 断言结果；不得清表、扫 Bucket 或清共享队列。本 T10 收尾没有擅自实现该能力。

## RabbitMQ、Outbox 与通知

- 与本轮六个绑定相关的 3 条 `QuestionImportRequested` 和 6 条 `MediaReferenceBound` 均为 `PUBLISHED`；六个媒体的 `MediaUploaded` 与 `MediaReady` 事件也均为 `PUBLISHED`。
- 收尾时只读查询六个既有 learning/media 主队列与 DLQ，`messages_ready` 和 `messages_unacknowledged` 均为 0；没有清空或 ACK 共享队列。
- 没有创建通知消息。
- `LegacyQuestionImportBatchProcessed` 事件 `8ed81ec4-4b96-49e4-9a31-ff5786f19c76` 当前为 `PENDING`、attempts=10。仓库事件说明明确 v2 当前没有业务消费者；源码审计也只发现 v2 Exchange 声明，没有 Queue、Binding 或 `@RabbitListener`，因此消息被 mandatory return，并未滞留 RabbitMQ 队列。
- Publisher 不是无限重试：默认 `max-publish-attempts=20`，失败采用最大 256 秒的有限退避，达到上限后 Outbox 转为 `FAILED` 并停止被 `pendingOutbox` 查询。当前服务已停止，所以该行停在第 10 次；下次持续启动 learning-service 后会继续至成功或第 20 次转 `FAILED`。
- 这属于当前“预留审计事件但尚无订阅者”设计的已知风险，不阻断 T10 前端：它不会造成 RabbitMQ 队列无限重投，但在订阅者建立前，每个历史批次都会产生一条最终失败的 Outbox 审计记录和有限告警。后续应在独立消息治理任务中决定是落地幂等审计消费者，还是调整无订阅阶段的发布策略；T10 不擅自扩展该业务。

## 注释覆盖、风险与后续依赖

新增页面、共享字段组件、API 适配、富文本清洗、媒体访问、有界历史导入和验收 Fixture 均补充了面向人工阅读的中文职责/边界注释；自动生成代码没有手工修改或补注释。

当前安全富文本是轻量 Markdown + KaTeX + DOMPurify，不是富文本编辑器；复杂所见即所得编辑不在 T10 范围。KaTeX 字体会增加教师端题库异步路由资源，但页面已按路由懒加载。发布前仍需人工完成本文件列出的真实 offline 与 Enter/Space 两项；完成前结论保持“有条件通过”。

## 分支、提交与 PR

- 分支：`codex/t10-question-bank-web`
- 基线：`main@82ca1fbe`，状态提交 `74b6b7c`
- 关键实现提交：`32371d0`
- 真实验收基线提交：`aaaaf73`
- Ready PR：[PR #22](https://github.com/chengchen-ccnu/ccnu-edulion/pull/22)；本任务只继续更新该 PR，不创建重复 PR，不自行合并
