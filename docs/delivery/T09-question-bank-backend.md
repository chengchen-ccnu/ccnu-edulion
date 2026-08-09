# T09 题库与知识点后端交付说明

## 1. 目标与边界

T09 在 `learning-service` 提供初中数学知识点、教师题库、不可变题目版本、题目媒体引用和 CSV/XLSX 异步导入，为 T10 教师题库页面和 T11 测评快照提供稳定契约。本任务不实现 Vue 页面、学生取题、报名、组卷、作答、判分、成绩、错题本或 Python Agent 检索。

知识点和题目只属于 learning Schema。服务不读取 IAM/课程数据库，不建立跨 Schema 外键；教师身份、角色和权限来自 Gateway 注入的可信请求头。`learning-service` 不引入 OSS SDK，不保存 Bucket、Object Key、永久地址或短期签名 URL。

## 2. 题库统计证据与导入适配约束

实施前对只读目录 `I:\大模型学习\edu_agent\智能体项目\data\题库` 做了逐文件流式聚合扫描，没有把完整 JSON、题干、答案或解析输出到终端/报告，也没有写扫描缓存或复制原始文件。扫描确认 44,441 个 JSON、84,063,347 字节，全部可解析；固定字段及类型稳定，`exerciseType` 分布为 `select_single` 19,587、`select_multiple` 12、`fillin` 10,047、`writeout` 14,795，`difficulty` 分布为 1:34,386、5:7,555、9:2,500。

聚合信号包括：空 `solution` 8,152、空 `queAns` 7、空 `subQues` 37,082；显式 `subQues` 项 18,316、最大 10 项且均为对象；HTML 34,757、LaTeX 18,141、图片引用 28,487、疑似隐式多问 3,434。选项标签可可靠识别 7,655，选择题中另有 11,787 个无法按标签可靠拆分、1,280 个歧义候选。路径教材信息与 JSON 年级存在冲突，有限样本中已验证“七年级目录、九年级字段”；因中文路径启发式统计未形成可靠冲突总数，本交付不虚构该数量。

人工只打开 5 个代表文件，分别覆盖单选、填空、普通解答、显式组合题和多选，并同时覆盖空解析、HTML、LaTeX、外部图片、年级冲突与题干内嵌选项。由此冻结适配原则：保留 Markdown/HTML/LaTeX 字符串；不从题干猜测选项或隐式拆题；外部/内嵌图片必须先上传为 `QUESTION_IMAGE`；无法表达的公式、图片、工作簿结构或字段映射必须产生明确行错误。真实题库样本和路径没有进入 Git 或测试 Fixture。

## 3. 架构与调用流程

1. 教师经 Gateway 查询知识点或管理本人题目；管理员知识点写操作必须同时具有 `ADMIN` 角色和 `administration:access`。
2. 创建题目以 `ownerId + clientRequestId` 和请求 SHA-256 指纹幂等；同 ID 同内容返回原题，不同内容返回 409。
3. 修改题目先校验 owner、期望 `revision`、知识点范围和媒体绑定，再插入新版本并以乐观锁移动聚合当前版本。历史版本不更新、不释放其媒体引用。
4. 发布/归档只改变聚合生命周期；`ARCHIVED` 不可再编辑或重新发布。T09 只暴露教师 API，列表投影不包含答案/解析，详情只对题目 owner 返回完整当前版本。
5. 媒体绑定通过 media-service 校验 owner、精确用途和 `READY`；题目事务写版本、绑定与 `MediaReferenceBound` Outbox。导入源到保留期后在本地事务中写 `MediaReferenceReleased`，由 media-service 决定对象生命周期。
6. 创建导入任务后，同一事务写任务、源引用 Bound 和 `QuestionImportRequested` Outbox。Outbox 使用 mandatory publisher confirm/return 与有限退避。
7. RabbitMQ 消费者通过短期读取授权流式下载源文件，严格解析 CSV/XLSX；每行以任务 ID + 行号生成确定性请求 ID，允许部分成功并保存安全的逐行错误。`eventId` 消费幂等防止消息重投重复创建题目，超限失败进入 DLQ。

## 4. REST 与事件契约

`docs/api/openapi.yaml` 由 0.7.0 前向更新为 0.8.0，新增：

- `GET /api/v1/teacher/knowledge-points`：教师只读知识点树。
- `POST /api/v1/admin/knowledge-points`、`PUT /api/v1/admin/knowledge-points/{knowledgePointId}`：管理员维护知识点。
- `GET/POST /api/v1/teacher/questions`：本人题目分页筛选与幂等创建。
- `GET/PUT /api/v1/teacher/questions/{questionId}`：本人题目详情与不可变新版本更新。
- `PUT /api/v1/teacher/questions/{questionId}/status`：`DRAFT`、`ACTIVE`、`ARCHIVED` 生命周期。
- `GET/POST /api/v1/teacher/question-imports`、`GET /api/v1/teacher/question-imports/{importTaskId}`：导入创建、分页与逐行结果。

题型为单选、多选、判断、填空、简答；难度为易、中、难；学段固定初中、学科固定数学、年级为七/八/九年级。版本输入显式携带选项、答案、解析、知识点和稳定媒体 ID，不接受存储地址。

`docs/events/learning-question-events.v1.yaml` 定义 `QuestionImportRequested` v1，使用标准事件信封且 payload 只含任务 ID、稳定源媒体 ID 和格式。`docs/events/README.md` 固结 learning 导入 Exchange/Queue/DLX/DLQ、三次总尝试与 `eventId` 幂等。题目和导入源的引用绑定/解除继续复用 `media-events.v1.yaml` 的 `MediaReferenceBound/Released`。

## 5. 数据库与迁移

向前 Flyway `V1__create_question_bank_tables.sql` 只创建 10 张 learning 域表：

- `knowledge_point`：稳定编码、树父节点、学段/年级/学科、排序、启用状态与 revision。
- `question_aggregate`：稳定题目 ID、owner、生命周期、当前版本和乐观锁 revision。
- `question_version`：只插入的不可变题干、选项、答案、解析及版本号。
- `question_version_knowledge_point`、`question_version_media`：版本级知识点和稳定媒体引用。
- `question_idempotency_request`：教师创建幂等请求与指纹。
- `question_import_task`、`question_import_row_error`：任务进度、成功/失败数、保留期限和安全行错误。
- `learning_outbox_event`、`learning_consumed_event`：可靠发布与持久消费幂等。

索引覆盖 owner 分页、状态、题型/难度/年级/知识点筛选、导入 owner 分页、保留期扫描和 Outbox 扫描。外键只存在于同一 learning Schema；没有报名、进度、试卷、作答、成绩或错题表。

## 6. 媒体用途策略

media-service 显式启用四种教师用途：

| 用途 | 扩展名 / MIME | 大小上限 | Multipart |
|---|---|---:|---|
| `QUESTION_IMAGE` | jpg/jpeg/png/webp；对应 image MIME | 10 MiB | 否 |
| `QUESTION_AUDIO` | mp3/m4a/wav/ogg；对应 audio MIME | 50 MiB | 是 |
| `QUESTION_ATTACHMENT` | pdf/docx；PDF/OOXML MIME | 50 MiB | 是 |
| `QUESTION_IMPORT_SOURCE` | csv/xlsx；CSV/OOXML MIME | 100 MiB | 是 |

题目版本仅接受前三种用途，导入仅接受第四种。错用途、他人资产、非 `READY` 均在数据库写入前失败。新版本替换媒体不会解除历史版本仍持有的引用；导入源在任务终态后的默认 7 天保留期届满才发布 Released，避免误删仍在审计/排错中的源对象。

## 7. 配置、启动与导入模板

配置、凭据包装脚本、非敏感变量、RabbitMQ 资源和严格导入表头见 `docs/development/local-environment.md`。本地顺序为 media-service、learning-service、Gateway；默认构建关闭 Flyway 和 RabbitMQ，因此不依赖真实基础设施。

CSV 必须为 UTF-8；CSV/XLSX 首行字段为 `type,difficulty,grade,stem,optionsJson,answersJson,analysis,knowledgePointCodesJson,mediaJson`。所有复合字段必须提供 JSON 数组。XLSX 只接受单工作表普通单元格；公式和嵌入图片不会求值或静默丢弃，而是记录行错误。

## 8. 测试数据与清理

没有创建或修改真实 MySQL Schema、OSS 对象或 RabbitMQ 消息，也没有创建正式 Seed 或持久伪数据。安全包装脚本只读确认本机 MySQL 可达且目标名为 `edu_platform_learning_dev`；显式验收随后在 Flyway 连接阶段因该数据库不存在而停止。按照仓库规则没有自动创建数据库，因此这次尝试未创建表或行，也没有其他数据需要清理。自动化使用随 JVM 退出销毁的 H2 `learning_test`，Fixture 使用固定测试 owner/管理员 UUID、合成知识点、合成题目、内存 CSV/XLSX 和 Mock media-service，不包含真实姓名、邮箱或原始题库文本；重新运行测试即可重建隔离数据。

若后续启用真实 local 环境，只允许既有的 `edu_platform_learning_dev`，命令必须经 `scripts/Invoke-WithLocalEnvironment.ps1`；本任务没有获得创建数据库或清空既有表的授权。导入源对象的实际删除由 media-service 根据 Released、引用计数和宽限期处理，learning-service 不执行 OSS 清理。

## 9. 验证结果

- 契约：OpenAPI 0.8.0、`media-events.v1.yaml` 和 `learning-question-events.v1.yaml` 均可解析；TypeScript 客户端由 OpenAPI 成功重新生成。`check:api` 在提交前会按设计报告生成文件相对 HEAD 的本任务差异，提交后复跑一致性门禁。
- Java：`mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify` 的 8 个 Reactor 模块全部成功（2 分 20 秒）；17 个报告共 65 项，63 项通过、0 失败、0 错误，media 真实环境和 learning MySQL 验收各 1 项按开关跳过。learning-service 14 项中 13 项 H2/Mock 通过。
- 覆盖：所有权、管理员权限、幂等、乐观锁、版本不可变、答案列表防泄漏、五种题型、生命周期、未知/缺失字段拒绝、媒体错用途/他人/非 READY、历史替换引用、保留期解除、导入进度与部分成功、XLSX 公式/嵌图行错误、重复事件、有限重试和 DLQ。
- Frontend：`generate:api` 与全 workspace `typecheck` 通过；Vitest 在受限沙箱内因既有 `esbuild spawn EPERM` 未启动，获准沙箱外复跑后 13 文件 91 项全部通过；三端 Vite 生产构建同样在沙箱外通过，只有既有 hls.js chunk 大小提示。本任务没有新增 Vue 页面。
- MySQL：安全包装脚本确认服务可达和目标名称正确，但专用 Schema 尚不存在；显式验收以 MySQL 1049 停止且没有产生外部状态。`LearningMysqlMigrationAcceptanceTest` 已提供，待环境管理员创建专用库后执行。
- 质量：最终差异检查、敏感信息审计和提交后的 `check:api` 在发布前完成；结果同步记录到 `docs/roadmap/verification.md`。

## 10. 人工演示步骤

1. 通过安全包装脚本启动 media-service、learning-service 和 Gateway；使用管理员令牌创建/调整一个初中数学知识点，教师令牌确认只读树。
2. 教师创建单选草稿，重放相同 `clientRequestId` 得到同一题；修改请求内容重放得到 409。再以当前 revision 新建版本，旧 revision 更新得到 409。
3. 查询教师列表，确认无答案和完整解析；查询本人详情可见当前版本；另一教师访问得到 404。发布后归档，确认归档题不可再更新。
4. 用 T10 或媒体 API 上传四类测试资产并等待 `READY`；绑定题目媒体，分别验证错用途、他人资产和未 READY 被拒绝。检查业务表只保存 mediaId/用途/顺序。
5. 上传合成 CSV/XLSX 导入源，创建任务并轮询至 `COMPLETED` 或 `PARTIAL_SUCCESS`；确认成功/失败计数、逐行错误、消息重投不重复创建题目，公式/嵌图/外部图片不被静默忽略。
6. 将测试环境保留期缩短后确认只发布源引用 Released，不由 learning-service 删除对象；按人工记录的精确题目、知识点和媒体 ID 清理测试数据。

## 11. 注释覆盖、风险与后续依赖

新增业务类、公共边界、权限判断、不可变版本、媒体校验、导入解析、Outbox、重试/DLQ、消费幂等、定时释放、SQL 约束、OpenAPI 与事件 Schema 均补充中文职责/输入/结果/失败副作用说明；生成的 TypeScript 客户端未手工补注释。

已知风险：当前导入模板是基于统计证据冻结的规范化 CSV/XLSX，不直接读取历史 JSON；历史题干内嵌选项、外部图片、组合题和年级冲突需要离线清洗映射，不能自动猜测。XLSX 解析支持普通单工作表，不支持公式计算、富文本、合并结构和嵌图；生产大批量容量与病毒/恶意文档扫描仍需真实环境压测和媒体安全能力。T10 负责教师上传、编辑、公式预览与导入结果页面；T11 负责测评快照和学生答案权限，不得复用教师详情作为学生取题接口。

分支为 `codex/t09-question-bank-backend`，基线为 `main@6839eab`。关键提交、Ready PR 和 CI 结果在创建后补充；本任务不会自行合并 PR。
