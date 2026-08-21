# T10B 数学知识图谱导入与题目知识点绑定交付说明

## 任务目标与边界

本任务在 T10A 当前分支基线上增加数学知识图谱的受控导入、有限邻域查询、历史题目 `tags` 到平台内部知识点 UUID 的绑定，以及教师题目编辑页的分页选择器。浏览器和脚本都只通过 Gateway；没有引入 Neo4j 运行时，没有修改 Python 智能体、只读源文件或 T10A 的 45 个 DryRun 任务/复核记录/检查点，也没有新增前端路由。

后续范围确认又补充题目来源边界：`SYSTEM` 是管理员持有、学生和教师可读但教师不可改写的公共题库，`TEACHER` 是创建教师本人可写的私有题库。教师列表展示公共题加本人题并按来源筛选；历史 JSON 的创建、批次、复核编排和精确清理从教师端移到管理员入口，正式题与外部图片均冻结为 `SYSTEM`。教师 CSV/XLSX 导入仍只创建本人 `TEACHER` 题，旧历史任务仅保留只读审计。

基线为 `codex/t10a-legacy-json-directory-upload-fix@df73fb0`；实施分支为 `codex/t10b-knowledge-graph-import`。本轮开始时只有用户已有的 `AGENTS.md` 修改，全程未覆盖、纳入或格式化该文件。T11 已预留 learning-service V4，因此图谱基础表使用 V5，确认语义使用 V6，学段开放策略使用向前 V7，题目作用域使用 V8；media-service 作用域使用向前 V4，未改写已有迁移。

## 契约与调用流程

OpenAPI 由 0.10.1 经 0.11.1、0.12.0 向前升级到 0.13.0。旧 `GET /api/v1/teacher/knowledge-points` 树接口保留且只返回管理员手工树，增加默认 `JUNIOR` 的学段筛选以兼容旧链接；图谱来源走分页接口，不会一次返回 3,671 个根节点。题目契约支持 `PRIMARY/JUNIOR` 与一至九年级，高中不进入题目写契约。

公共题库新增/调整的接口如下：

- `GET /api/v1/teacher/questions`：返回全部可见 `SYSTEM` 加本人 `TEACHER`，可选 `scope` 来源筛选，摘要带 `scope/editable`。
- 教师题目详情与历史版本读取允许公共 SYSTEM；更新版本和状态对 SYSTEM 固定返回 403 `QUESTION.SYSTEM_READ_ONLY`。
- `GET /api/v1/learning/system-questions`、`GET /api/v1/learning/system-questions/{questionId}`：任一已认证学生或教师读取 `ACTIVE SYSTEM`，专用投影不含答案、解析和历史来源。
- `POST /api/v1/admin/legacy-question-imports`、批次、任务/逐项读取及精确清理：要求 `administration:access`，任务产出冻结为 SYSTEM。
- 教师历史 JSON 创建、批次、解决和清理写契约已删除；教师导入页只创建 CSV/XLSX 私有任务，并只读展示旧任务。
- `POST /api/v1/media/external-image-transfers` 增加必填 `scope`；SYSTEM 必须携带 `administration:access`，仅有 ADMIN 角色名不足以授权。

- `POST /api/v1/admin/knowledge-graph-imports`：管理员创建幂等任务。
- `POST /api/v1/admin/knowledge-graph-imports/{taskId}/node-batches`：最多 500 个节点，必须先完成全部节点。
- `POST /api/v1/admin/knowledge-graph-imports/{taskId}/relation-batches`：最多 500 条显式 `prerequisite -> dependent` 关系，最终批次执行全图校验。
- `GET /api/v1/admin/knowledge-graph-imports/{taskId}`：只返回状态和聚合结果。
- `GET /api/v1/admin/knowledge-graph-imports/{taskId}/issues`：分页返回无内容问题投影。
- `GET /api/v1/teacher/knowledge-points/search`：按 `PRIMARY/JUNIOR`、类型和关键词分页；`SENIOR` 仍拒绝。
- `GET /api/v1/teacher/questions`：增加可选学段/年级筛选，摘要显式返回学段。
- `GET /api/v1/teacher/knowledge-points/{knowledgePointId}/graph`：返回直接先修、直接后续和 1～5 层有限邻域。

Gateway 已增加管理员图谱任务路由。管理员接口要求 `administration:access`，教师查询要求 `teaching:access`；角色字符串不能替代权限。节点批次按 `sourceSystem + sourceKnowId` 和内容摘要幂等，同键内容变化进入冲突；关系在服务层校验端点、同学科、同学段、自环和合并既有来源边后的环。正式写入只有在结构无问题时发生，小学/初中节点按当前平台策略启用，高中禁用，且不会删除任何旧节点或关系。

## 数据库与迁移

`V5__add_knowledge_graph_import.sql` 向前扩展 `knowledge_point`，保留内部 UUID，并增加 `source_system`、`source_know_id`、来源学科/学段、可空 `normalized_stage`、`source_parent_key`、可空 `taxonomy_parent_id`、来源类型/颜色/顺序/节点种类/状态/摘要等字段。唯一键是 `(source_system, source_know_id)`；名称、颜色、Neo4j identity/elementId 和来源 parent key 均不参与去重。

`V6__stage_confirmed_knowledge_graph_mappings.sql` 在 staging 节点增加 `normalized_stage` 与 `knowledge_type_name`，使确认后的映射参与任务内容摘要、恢复校验和正式写入。正式导入强制 `02=PRIMARY`、`03=JUNIOR`、`04=SENIOR`，并只接受“知识点”和“题型”两类正式名称；来源 `status` 必须原样为 `0`，但不据此推导平台启用状态。

`V7__enable_primary_and_junior_knowledge_points.sql` 只调整来源图谱节点的启用投影：小学、初中启用，高中禁用。它不写两个 parent 外键，也不新增、删除或重排 `knowledge_point_prerequisite`。

`V8__add_question_scope.sql` 为 `question_aggregate` 增加非空 `scope`，既有题默认迁移为 `TEACHER`；为 `question_import_task` 增加冻结的 `question_scope`，保证任务重试不能在公共/私有之间混写。`media-service` 的 `V4__add_media_asset_scope.sql` 同样为既有资产回填 `TEACHER`，新增 SYSTEM 图片保持管理员 owner，但允许任一已认证用户只读和申请短期授权。两个迁移都只向前增加列、约束和索引，没有改写既有迁移。

新增表：

- `knowledge_point_prerequisite`：同 Schema 双外键、唯一 `prerequisite_id + dependent_id + relation_type`、禁止自环，业务方向固定为 `PREREQUISITE`。
- `knowledge_graph_import_task`、`knowledge_graph_import_batch`：任务、批次幂等和安全聚合。
- `knowledge_graph_import_node`、`knowledge_graph_import_relation`：结构化 staging，不保存绝对路径或原始 JSON。
- `knowledge_graph_import_issue`：按安全指纹聚合问题，不保存完整来源 ID 列表。

`question_version_knowledge_point` 继续作为不可变题目版本绑定，新增 `priority`、`core_flag`、`exam_point_flag`、`last_exam_point_flag`、`source_tag_sort_index`。来源没有年级字段，因此图谱知识点的 `grade` 可以为空，表示经确认后适用于整个学段；题目校验仅在知识点年级非空时要求单一年级相等。

本任务没有创建数据库；真实验收在既有专用 `edu_platform_learning_dev` 上由 Flyway 向前应用 V5、V6、V7，创建本节列出的图谱任务、staging、问题和关系表并扩展既有表。自动化仍只使用 H2 内存 Schema 和最小合成 Fixture，进程结束自动清理；重复运行测试即可重建。

## 只读源结构复核

2026-08-19 对两个指定源文件做了只读聚合，没有修改、复制、提交或输出完整 ID/原始 JSON：

- 节点 3,671，`totalCount` 与实际一致；学段来源值分布为 `02=817`、`03=1,386`、`04=1,468`，六种来源类型合计一致。
- 空必要字段 0、重复 `knowId` 0；重复名称组 110。
- 关系 2,107，缺失端点 0、重复边 0、自环 0、跨学科 0、跨学段 0、环 0。
- 文件含义为“b 是 a 的先序知识点”，平台方向已复核为 `b -> a`；最长先修链按边数计 21，最大入度 11、最大出度 19。
- 参与边的节点 1,404，孤立节点 2,267。
- 479 个不同来源 parent key 均无法解析到节点业务 ID；代码只保存为 `source_parent_key`，不会生成父节点或写入任一自外键。

## 历史题目 tags 聚合与绑定行为

只读扫描 44,441 份历史 JSON，0 解析失败；37,082 个普通根题、7,359 个组合父题和 18,316 个子题分别统计。普通根题含 79,216 条 tag，组合父题含 34,336 条，当前子题没有独立 `tags`；62,757 个题/子题候选中 44,232 个带 tag。

全部 tag 涉及 1,260 个唯一 `knowId`，节点文件中未知 ID 为 0，类型冲突为 0；早期只读扫描按 tag 次数统计出 112,873 条 ID/名称/类型完全匹配、679 条名称冲突和 6,034 条题内重复 tag。确认 `03=JUNIOR` 并完成正式图谱导入后，又以新来源命名空间通过真实 Gateway 对 44,441 份文件执行了完整题目 DryRun；服务端按“题目来源记录”而不是 tag 次数给出最终可执行结论。

`LegacyQuestionJsonParser` 现在分别读取普通题、组合父题和每个子题自己的 tags。服务按 `sourceSystem + knowId` 查平台知识点，不按名称猜测；名称、类型、已确认学段和重复项分别产生 `KNOWLEDGE_POINT_NAME_CONFLICT`、`KNOWLEDGE_POINT_TYPE_CONFLICT`、`KNOWLEDGE_POINT_STAGE_MISMATCH`、`KNOWLEDGE_POINT_DUPLICATE` 等问题码。成功项按确定性顺序使用内部 UUID 创建不可变版本，并保留 priority、sortIndex 和三种 flag。

## 迁移脚本

脚本为 `scripts/Invoke-KnowledgeGraphMigration.ps1`。默认 `DryRun`，按业务 ID 确定性排序，先节点后关系；请求体使用 UTF-8 无 BOM，Token 只读当前进程 `EDU_MIGRATION_ACCESS_TOKEN`。检查点只保存来源/运行指纹、任务 ID、下一批位置和聚合计数，不保存令牌、绝对路径、节点列表或原始 JSON。401/其他 HTTP 失败只报告安全状态，不输出响应体。

示例见 `docs/development/local-environment.md`。正式 Import 必须同时显式给出 `ConfirmImport` 和 stage/status/type/parent 四项语义确认开关；脚本只接受已确认的三组学段映射和六组“来源学段 + 类型数量”签名，并将类型规范为“知识点”或“题型”。相同参数与检查点可安全恢复；来源分布变化会在发起 HTTP 请求前停止。

## 教师端呈现

题目新建/编辑页继续使用既有路由。列表支持 URL 可恢复的小学/初中及年级筛选；编辑器提供小学一至六年级、初中七至九年级，切换上下文会清除不匹配知识点，组合题子题继承父题学段/年级。知识点控件按当前学段分页查询，每页最多 20 项，支持关键词、类型名称、已选 Chip 和一层关系摘要；不会渲染 3,671 个 checkbox。高中不会出现在题目编辑器。

题库列表新增“全部来源 / 平台系统题 / 我的私有题”筛选和来源徽标；SYSTEM 详情显示公共只读状态并隐藏编辑、启用、归档按钮。即使直接访问编辑深链，也只显示只读说明并返回详情，不渲染可提交表单。导入页删除浏览器历史 JSON 提交、人工解决和清理按钮，保留 CSV/XLSX 私有导入与旧任务只读审计说明。

## 测试与实际结果

- Java 专项：`KnowledgeGraphIntegrationTest` 7/7 通过，覆盖严格 Jackson 请求绑定、DryRun、节点批次、关系方向、幂等、来源 parent 隔离、错误学段映射、缺失端点、自环、跨学段、环、学段启用策略、分页/有限邻域和 tags 元数据。
- Java 全量：8 个 Reactor 模块 `clean verify` 全部通过；learning-service 38 项执行、36 通过、2 个真实 MySQL 用例按开关跳过，media-service 20 项执行、18 通过、2 个真实环境用例跳过；Gateway 11/11 通过。H2 MySQL 模式成功应用 Learning V1/V2/V3/V5/V6/V7/V8 与 Media V1～V4。
- Pester：VALID-only/均衡样本专项 8/8 通过，覆盖 101 硬配额、稀缺类别全选、至少 50% 含图、主机白名单、正式确认、VALID-only 提交、计划无写入和 401 脱敏；图谱迁移既有专项 5/5 结果保持通过。
- Windows PowerShell 5.1：脚本语法解析通过；合成 HTTP Fixture 实际执行通过。
- OpenAPI 客户端生成和全 workspace typecheck：通过。
- Frontend：18 个测试文件、122 项全部通过；OpenAPI 客户端重新生成、全 workspace 类型检查及管理/学生/教师三端生产构建通过，仅有既有 hls.js 大 chunk 提示。Vitest/Vite 经批准在沙箱外复跑，避免 Windows `esbuild spawn EPERM`。
- `git diff --check` 通过；完整 Java `clean verify` 的 8 个 Reactor 模块全部成功。`check:api` 在提交前按设计检测到生成文件相对旧 HEAD 的待提交差异，提交后复跑作为最终契约一致性门禁。

## 真实环境状态、数据变化与后续边界

2026-08-19 完成图谱验收；2026-08-21 又使用当前代码的临时 IAM/Gateway/Media/Learning 实例和既有专用本地环境完成 SYSTEM 题库验收。四个实例使用独立 18081/18080/18083/18084 端口，结束后按精确 PID 全部停止，未干扰原 808x 实例。`edu_platform_learning_dev` 由 Flyway 向前到 V8，既有专用 Media 开发 Schema 向前到 V4；本任务未创建新数据库。需要凭据的启动、查询和登录均经 `Invoke-WithLocalEnvironment.ps1` 执行；DPAPI 凭据和短期令牌未读取、打印、复制或写入仓库。

首个 V1 DryRun 暴露并修复 PowerShell 字节数组发送缺陷；修复后 V1 完成，但服务端“重复名称”按超额节点计 117，与本地“重复名称组”110 不一致。保留该任务作为审计证据，修正服务端口径并使用新的 `KG_MATH_T10B_V2` 与独立检查点再次 DryRun。V2 最终状态为 `COMPLETED`：节点 3,671、关系 2,107、六类类型、三个学段分布、重复名称组 110、孤立节点 2,267、未解析 parent key 479、最大入度 11、最大出度 19、最长链 21，本地与服务端完全一致；缺失端点、重复边、自环、跨学科、跨学段、环、节点冲突和跳过关系均为 0。安全问题页只有 `SOURCE_PARENT_UNRESOLVED=479`。

真实数据库继续保留 V1/V2 两个 `dry_run=true` 图谱任务及其 staging、安全问题和聚合结果，以便审计。确认业务语义后使用独立 `KG_MATH_T10B_V3` 命名空间完成一次正式图谱 Import：3,671 个节点和 2,107 条关系全部新增，节点/关系冲突、缺失端点、重复边、自环、跨学科、跨学段和环均为 0；孤立节点 2,267、未解析来源 parent key 479、最长链 21。V7 应用后正式表为 `PRIMARY enabled=817`、`JUNIOR enabled=1,386`、`SENIOR enabled=0/disabled=1,468`；所有来源节点仍为 `grade=NULL`、两个 parent 外键均为 `NULL`、`source_status=0`。关系总数仍为 2,107，按来源分布小学 53、初中 2,054、高中 0。

已确认并执行的业务策略为：`02/03/04` 分别映射小学/初中/高中；无年级字段表示适用于整个学段；所有来源 `status=0` 原样保存但不推导启用；479 个 `parentId` 只作为外部分组键保存，不写 parent 外键；六组类型按已确认的学段与数量签名规范为“知识点”或“题型”；高中关系暂不完整并保持禁用。服务端会再次校验这些映射，任何来源分布或状态变化都会阻止正式写入。

T10A 的原 45 个 DryRun 任务、复核记录和检查点没有删除、重建或修改。新 V3 图谱命名空间下又完成 45 个历史题库 DryRun 任务，共处理 44,441 个来源：`VALID=27,687`（62.30%）、`NEEDS_REVIEW=16,754`（37.70%）、`FAILED=0`、`SKIPPED=0`，并确认未知知识点、类型冲突、学段不匹配均为 0。复核问题按来源记录计为：路径与 JSON 元数据冲突 5,793、子题答案缺失 4,827、知识点重复 3,567、隐式多小题 3,375、内嵌选项歧义 2,468、知识点名称冲突 406、字段无效 173、HTML 不安全 37。所有 DryRun 来源记录的 `question_id` 均为空，正式题目和题目知识点绑定仍为 0；必须先评审并处理这 16,754 条复核项，才可另行确认题目 Import。

管理员 SYSTEM 全量预检使用独立检查点和 45 个任务：44,441 条中 `VALID=27,687`、`NEEDS_REVIEW=16,754`、`FAILED/SKIPPED=0`。均衡计划选 650 条，计划含图题 483（源图出现 1,936 次）、纯文本 167；七/八/九年级 202/242/206，易/中/难 378/165/107，填空/组合/单选/简答/多选 149/111/273/110/7。来源没有小学题或判断题；多选题仅有 7 道可用，按“不足则全选”全部纳入。

最终正式任务处理 650 条：648 道管理员所有的 `ACTIVE SYSTEM` 根题创建成功，2 条因源图实际无法安全转存保持 `EXTERNAL_IMAGE_TRANSFER_FAILED` 复核，普通失败为 0。成功集合为七/八/九年级 202/240/206，易/中/难 378/164/106，填空/组合/单选/简答/多选 149/110/272/110/7，仍满足每个现有非稀缺分类至少 101。其中含图根题 481/648（74.23%），另有 362 道组合子题、1,910 个题目媒体引用和 1,208 个不同知识点。所有引用资产均为管理员 owner、`SYSTEM/READY`且媒体参考状态为 `BOUND`；缺失、错作用域、错 owner、非 READY、禁用/跨学段知识点绑定和题目字段/对象键中的外部主机残留均为 0。

首轮正式验收发现 2 道已创建题的答案 HTML 仍含外部图片 URL；根因是历史解析器只提取题干/解析图片。修复后新增答案图片专项测试，并按首轮任务精确清理 1,010 个聚合（648 根题＋362 子题）、650 个来源引用和 1,905 个媒体引用，再以新幂等指纹重导。旧媒体收到释放事件，按 24 小时宽限与回收器策略清理；未直接删库或手工改题。最终任务如需撤销，使用本地检查点 `.m2/migration-checkpoints/system-import-kg-math-t10b-v3-sample650-answer-images.json` 执行 `-Action Cleanup`；重建时按本地环境文档的同一 Plan/Import 命令执行。检查点在 `.m2` 且不进入 Git。

真实 API 复核：教师来源筛选返回 648 道 SYSTEM，详情 `editable=false`，写状态返回 403 `QUESTION.SYSTEM_READ_ONLY`；学生公共列表/详情返回 200 且不含 `answers/analysis`；教师与学生读 SYSTEM 媒体均为 200，学生短期访问授权为 201。本任务未创建伪数据；正式样本是用户明确批准的只读历史来源，用于教师来源筛选、学生公共读取、类别筛选、图片授权和知识点绑定验收。

## 注释覆盖与发布

新增 DTO、Repository、Service、Controller、迁移脚本和 Vue 组件均补充中文职责、输入、结果和失败/写入副作用说明；OpenAPI 与 Flyway 对来源键、关系方向、学段启用策略和敏感数据边界有说明；生成客户端未手工补注释。

分支：`codex/t10b-knowledge-graph-import`。PR 未创建，未推送，未合并；初始实现提交为 `a2aba056`，真实 DryRun 传输修复为 `359ce1fd`，确认映射与正式导入记录由本交付文档所在提交承载。
