# T10 教师题库前端交付说明

## 任务目标与功能边界

本任务在教师端实现页面规格冻结的六组题库路由：题库列表、新建题目、题目详情、编辑新版本、导入任务列表和导入任务详情。六组路由共同启用既有“题库”导航槽位并保持同一高亮组，没有改变全局导航标签、顺序或其他槽位。

实现覆盖本人题目筛选与分页、六种普通题型和一层有序组合题、不可变版本历史、真实生命周期、Markdown/LaTeX 安全预览、题目媒体、CSV/XLSX 导入源上传、浏览器显式选择的有界历史 JSON 导入、逐项人工结构化修正、确认跳过和任务级精确清理。没有实现组卷、学生作答、成绩、错题、T11/T12 页面，没有修改 Python 智能体、数据库迁移、全局页面规格或后端契约，也没有读取或扫描 44,441 份历史题库。

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

本任务是纯前端交付，没有创建或修改数据库、表、迁移、RabbitMQ 消息或 OSS 对象。没有新增运行时环境变量；教师端继续使用既有 `VITE_GATEWAY_BASE_URL`，浏览器不得直连 learning-service 或持有 OSS AccessKey。

本地启动：

```powershell
pnpm.cmd --dir web-apps --filter @edu/teacher-web run dev
```

需要真实环境时必须通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入既有本地配置。本次没有进行真实环境写入验收，因此没有调用凭据脚本，也没有创建需要清理的真实测试数据。

## Fixture 与清理结果

自动化和浏览器验收只使用 `web-apps/apps/teacher-web/test-fixtures/t10-gateway.mjs`：一个合成教师、一个合成知识点、一道单子题组合题及两个不可变版本、一个 LEGACY_JSON dry-run 任务和一个待复核项。Fixture 仅在 `127.0.0.1` 内存进程中返回安全投影，同时提供合成 403/404/409/422/429/5xx 场景；进程结束后没有持久化数据。

验收结束后，专用 Vite、Prism 和合成 Gateway 监听进程均已停止。没有创建数据库、表、Seed、真实题目、媒体上传会话、OSS 对象、RabbitMQ 消息或通知消息，因此数据库、媒体对象和消息的清理结果均为“无对象需要清理”。临时验收日志在提交前删除。

## 测试命令与实际结果

- `pnpm.cmd --dir web-apps run generate:api`：通过，OpenAPI 0.10.1 成功生成客户端。
- `pnpm.cmd --dir web-apps run check:api`：通过，已跟踪生成文件无契约漂移。
- `pnpm.cmd --dir web-apps run typecheck`：通过，三端及全部共享包类型检查成功。
- `pnpm.cmd --dir web-apps run test`：通过，18 个测试文件、115 项测试全部成功；T10 新增覆盖结构化父子题、生成客户端适配、历史 JSON 上限/相对路径、路由/导航和富文本 XSS 清洗。
- `pnpm.cmd --dir web-apps run build`：学生端、教师端、管理端三个生产构建全部通过。
- 浏览器验收：六组路由均以“题库”为当前导航；列表筛选与分页、历史版本、导入筛选和选中复核项可深链接/刷新恢复；桌面及 820×1180 平板无横向溢出；实际验证 403、404、409、422、429、5xx、KaTeX 公式、上标、危险 HTML 清洗、冲突输入保留和清理二次确认入口。401、刷新一次和权限重定向继续由共享 `@edu/auth` 27 项测试覆盖。

## 人工演示步骤

1. 以具备 `teaching:access` 的教师进入 `/teacher/questions`，组合筛选题型、难度、状态和知识点，翻页后刷新并确认 URL 与控件恢复；列表只显示题干摘要。
2. 打开 `/teacher/questions/new`，分别选择六种题型；为组合题填写父题和一个或多个有序子题，检查每层题干、选项、答案、解析、知识点、媒体和公式预览。
3. 打开题目详情，在当前版本和分页历史之间切换并刷新；确认历史快照不改变“当前 vN”和 revision。执行启用/归档，归档后只读。
4. 编辑同一题目并制造旧 revision，确认 409 提示要求重新加载且本地输入保留；提交字段错误时确认 422 落到对应输入项。
5. 在导入任务页上传最小合成 CSV/XLSX，确认上传 READY 后才可创建任务。另显式选择不超过 20 个最小 JSON，确认 dry-run 默认开启且不会显示或提交绝对路径。
6. 刷新导入详情，按 outcome 分页查看问题码；选中 `NEEDS_REVIEW` 后填写完整结构化修正或确认跳过。对未完成最终批次的任务确认按钮禁用/服务端 409；最后核对任务 ID、影响范围并二次确认精确清理。

## 注释覆盖、已知风险与后续依赖

新增页面、共享字段组件、API 适配、富文本清洗、媒体访问、有界历史导入和验收 Fixture 均补充了面向人工阅读的中文职责/边界注释；自动生成代码没有手工修改或补注释。

当前安全富文本是轻量 Markdown + KaTeX + DOMPurify，不是富文本编辑器；复杂所见即所得编辑不在 T10 范围。KaTeX 字体会增加教师端题库异步路由资源，但题库页面已按路由懒加载，不影响未进入题库的教师页面首屏。真实 OSS、RabbitMQ、CSV/XLSX 后端链路已由 T07/T09 系列验收，本次没有重复创建真实数据；发布前仍需在 T18 以受控最小数据完成跨服务回归。

## 分支、提交与 PR

- 分支：`codex/t10-question-bank-web`
- 基线：`main@82ca1fbe`，状态提交 `74b6b7c`
- 关键提交：提交后回填
- Ready PR：创建后回填；不得由本任务自行合并
