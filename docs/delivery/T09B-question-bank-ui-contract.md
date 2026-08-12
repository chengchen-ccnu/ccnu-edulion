# T09B 题库前端契约闭环交付说明

## 任务目标与边界

本任务为 T10 六组教师题库稳定路由冻结可直接生成客户端的后端契约：普通题与有序组合题结构化新建/更新、只读不可变版本历史、CSV/XLSX 与历史 JSON 统一任务投影，以及历史 JSON 逐项分页、详情和显式人工解决。组合父题和每个子题均保留题型、题干、选项、答案、解析、知识点和稳定 `mediaId`；不会把子题压成自由文本。

本任务没有创建或启用 Vue 页面，没有实现组卷、学生作答、成绩、错题或 Python 智能体修改，没有引入 OSS SDK，也没有扩大每批 20 项、每任务 1000 项的历史迁移硬上限。44,441 份历史题库未全量导入。

## 主要架构与调用流程

1. `docs/api/openapi.yaml` 先升级到 0.10.0，随后由同一契约重新生成 `schema.d.ts`，Java DTO 和控制器按生成契约实现。
2. 根题目仍使用稳定 aggregate 和 revision。普通题写入一个不可变版本；组合题先校验父题及全部有序子题的内容、知识点和媒体，再在同一事务插入根聚合、隐藏子聚合、父子版本及媒体 Bound Outbox。
3. V3 关系同时保存 `child_question_id` 和精确 `child_question_version_id`。历史父版本始终读取当时的子版本，不跟随任何子聚合当前指针；子聚合不进入教师根题列表，也不能作为独立教师路由资源编辑。
4. 更新先验证 aggregate revision，再插入全新的父/子版本图并原子移动根题当前指针；冲突时事务整体回滚，历史版本和媒体引用不被覆盖或释放。
5. 导入列表/详情继续共用 `question_import_task`，新增 `maxItems`、当前未解决 `reviewCount` 和 `resolvedCount`。历史 JSON 每个处理项另写安全复核投影，只保留来源元数据、结果、问题码和 revision，不保存原始 JSON、题干、答案、路径明文或外部 URL。
6. 人工解决仅接受 `IMPORT_CORRECTED` 或 `CONFIRM_SKIP`。完整请求由 `clientRequestId` 和 SHA-256 指纹幂等，`expectedRevision` 防止并发覆盖；修正导入必须提交完整普通/组合题结构，确认跳过必须提交 `correctedVersion=null`。成功决定是单项终态，并同步统一任务计数。

## API 契约

沿用并扩展：

- `POST /api/v1/teacher/questions`：幂等创建普通题或结构化组合题。
- `PUT /api/v1/teacher/questions/{questionId}`：以 `expectedRevision` 创建新的普通/组合题不可变版本。
- `GET /api/v1/teacher/questions`：只列本人根题摘要，SQL 投影不读取答案或完整解析。
- `GET /api/v1/teacher/questions/{questionId}`：本人当前版本和完整有序子题；他人题目及隐藏子聚合返回 404。
- `GET /api/v1/teacher/questions/{questionId}/versions`：不可变版本历史分页。
- `GET /api/v1/teacher/questions/{questionId}/versions/{versionId}`：指定版本完整只读详情；不移动当前指针或 revision。
- `GET /api/v1/teacher/question-imports`、`GET /api/v1/teacher/question-imports/{importTaskId}`：CSV、XLSX、LEGACY_JSON 统一任务投影。
- `GET /api/v1/teacher/question-imports/{importTaskId}/items`：历史 JSON 项按结果可选筛选并分页。
- `GET /api/v1/teacher/question-imports/{importTaskId}/items/{itemId}`：可刷新恢复的单项详情与稳定问题码。
- `POST /api/v1/teacher/question-imports/{importTaskId}/items/{itemId}/resolution`：revision 幂等修正后导入或确认跳过。

原 T09A 批次提交、精确清理和 media-service 外部图片转存端点保持不变。接口仍从 Gateway 可信身份头取 owner，任何他人题目、版本、任务或复核项均按不存在处理。

## 数据库表与迁移

已合并的 `V1__create_question_bank_tables.sql`、`V2__add_legacy_json_question_import.sql` 未修改。新增 `V3__close_question_bank_ui_contract.sql`：

- `question_aggregate.parent_question_id`：标记组合子聚合并从根列表/独立路由隔离；V2 既有组合关系安全回填。
- `question_version_child.child_question_version_id`：冻结精确子版本并为 V2 数据回填当时当前版本。
- `question_legacy_review_item`：每个历史 JSON 提交项的安全分页投影、问题码、revision 和解决状态。
- `question_legacy_item_resolution`：owner 范围的 `clientRequestId`/指纹幂等记录，每个复核项仅允许一个人工终态决定。

全部外键仍位于 learning Schema 内。迁移不创建数据库、不创建 T11 表、不建立跨 Schema 外键；媒体仍只存稳定 `mediaId`。

## 环境变量与启动方式

T09B 没有新增环境变量。learning-service 仍通过 `MEDIA_SERVICE_BASE_URL` 调用 media-service，且必须使用 `scripts/Invoke-WithLocalEnvironment.ps1` 注入本地凭据。启动、RabbitMQ、媒体用途和显式 MySQL 验收命令见 `docs/development/local-environment.md`。

## Fixture、真实验收与清理

自动化只使用最小虚构知识点、普通题、单子题组合题和两个含糊选项的历史 JSON 对象；不含真实姓名、邮箱、手机号或原题库内容。H2 Fixture 随进程销毁。

真实验收复用用途明确的既有 `edu_platform_learning_dev`，应用没有创建数据库。用例只创建随机 owner 下的一个虚构知识点、一个两版本单子题组合题和一个待复核历史项；验证 V3、历史冻结、分页、问题码、revision 与确认跳过后，按本轮精确知识点 ID、根/子题 ID、任务 ID 和关联行逆序清理。断言该 owner 的题目、幂等键、任务、复核项和解决记录均为 0；只保留 V1/V2/V3 Schema。未连接 OSS、RabbitMQ 或历史题库目录，未创建媒体对象和持久 Seed。

## 测试命令与实际结果

- `pnpm.cmd --dir web-apps run generate:api`：通过，OpenAPI 0.10.0 成功生成 TypeScript 客户端。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl learning-service -am test`：通过；相关 Reactor 共 19 项，17 通过，2 个显式真实环境用例默认跳过。
- 通过本地凭据包装脚本显式运行 `LearningMysqlMigrationAcceptanceTest`：MySQL 8 将既有专用 Schema 从 V2 向前迁移到 V3；2 项中最小虚构 V3 用例通过，需真实历史目录的既有用例按条件跳过，Fixture 精确清理完成。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify`：8 个 Reactor 模块全部通过，耗时 2 分 29 秒；learning-service 19 项中 17 通过、2 项默认跳过。
- `pnpm.cmd --dir web-apps run typecheck`：生成客户端及全部 8 个 workspace 项目类型检查通过。
- `pnpm.cmd --dir web-apps run test`：沙箱内因既有 `esbuild spawn EPERM` 无法启动，经批准在沙箱外复跑后 13 个文件、91 项全部通过。
- `pnpm.cmd --dir web-apps run build`：三端生产构建通过；只有既有 `hls.js` 超过 500 kB 的提示，本任务没有新增前端 bundle。

提交后的 API 一致性、差异/敏感信息审计均通过；Ready PR #21 首轮 Java、Python、Frontend、OpenAPI 四项 CI 全绿。GitHub Actions 仅报告 action runtime 的 Node 20 平台级弃用提示，不影响本任务构建结果。

## 人工演示步骤

1. 以教师身份创建一个携带 `children` 的组合题，确认父题和子题均返回完整结构且题库列表只有根题。
2. 记录版本 1 ID，以 revision 0 更新父/子内容；分页查看版本 2、1，再读取版本 1，确认旧子题答案/解析不变且当前版本仍为 2。
3. 创建最多 2 项的历史 JSON dry-run，提交一个明确 `NEEDS_REVIEW` 小批次；刷新任务详情并分页读取问题码。
4. 对第一项携带 revision 0 提交完整 `IMPORT_CORRECTED`，重放相同请求确认题目和计数不重复；对第二项显式 `CONFIRM_SKIP`。
5. 使用旧 revision 或其他教师身份重试，分别确认 409 和 404；最后调用任务级精确清理，不进行 Schema 清空或全量迁移。

## 已知风险与后续依赖

- 当前结构化组合题只允许一层有序子题，不允许子题继续嵌套组合题；这是 T10 已冻结的页面边界。
- 每次组合题更新会创建新的隐藏子聚合以获得最直接的历史隔离；题目量和历史版本量需要在后续容量测试中观察，但不会改变每批/每任务导入上限。
- 复核投影有意不保存原始 JSON或题干，页面刷新可恢复问题与结果，但若教师需要重新参考原文件，必须由浏览器重新选择本地来源，服务端不能扫描客户端路径。
- T10 负责六组 Vue 路由、安全 Markdown/LaTeX 渲染、上传生命周期和交互状态；T11 才负责组卷、作答、成绩与学生答案权限。

## 注释覆盖与分支信息

结构化父子写入、精确子版本历史、根/子所有权、复核分页、幂等/revision 解决、任务计数、清理 SQL、V3 迁移和控制器公共边界均补充了中文 Javadoc/SQL/OpenAPI 说明；生成客户端未手工修改。

- 分支：`codex/t09b-question-bank-ui-contract`
- 基线：`main@c358c8e7dd971dd57c31f7040252b6715a1256ae`
- Ready PR：[#21](https://github.com/chengchen-ccnu/ccnu-edulion/pull/21)，保持打开且不由本任务合并。
- 关键实现提交：`86a9176`（`feat(question): close T09B UI contracts`）。
