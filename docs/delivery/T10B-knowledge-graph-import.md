# T10B 数学知识图谱导入与题目知识点绑定交付说明

## 任务目标与边界

本任务在 T10A 当前分支基线上增加数学知识图谱的受控导入、有限邻域查询、历史题目 `tags` 到平台内部知识点 UUID 的绑定，以及教师题目编辑页的分页选择器。浏览器和脚本都只通过 Gateway；没有引入 Neo4j 运行时，没有修改 Python 智能体、只读源文件或 T10A 的 45 个 DryRun 任务/复核记录/检查点，也没有新增前端路由。

开始时工作区干净，基线为 `codex/t10a-legacy-json-directory-upload-fix@df73fb0`；实施分支为 `codex/t10b-knowledge-graph-import`。T11 已预留 learning-service V4，因此本任务使用 V5，未修改 V1～V3。

## 契约与调用流程

OpenAPI 由 0.10.1 向前升级到 0.11.0。旧 `GET /api/v1/teacher/knowledge-points` 树接口保留且只返回管理员手工树，避免破坏既有客户端；图谱来源走新增分页接口，不会一次返回 3,671 个根节点。

- `POST /api/v1/admin/knowledge-graph-imports`：管理员创建幂等任务。
- `POST /api/v1/admin/knowledge-graph-imports/{taskId}/node-batches`：最多 500 个节点，必须先完成全部节点。
- `POST /api/v1/admin/knowledge-graph-imports/{taskId}/relation-batches`：最多 500 条显式 `prerequisite -> dependent` 关系，最终批次执行全图校验。
- `GET /api/v1/admin/knowledge-graph-imports/{taskId}`：只返回状态和聚合结果。
- `GET /api/v1/admin/knowledge-graph-imports/{taskId}/issues`：分页返回无内容问题投影。
- `GET /api/v1/teacher/knowledge-points/search`：按已确认 `normalizedStage`、类型和关键词分页；当前题库只接受 `JUNIOR`。
- `GET /api/v1/teacher/knowledge-points/{knowledgePointId}/graph`：返回直接先修、直接后续和 1～5 层有限邻域。

Gateway 已增加管理员图谱任务路由。管理员接口要求 `administration:access`，教师查询要求 `teaching:access`；角色字符串不能替代权限。节点批次按 `sourceSystem + sourceKnowId` 和内容摘要幂等，同键内容变化进入冲突；关系在服务层校验端点、同学科、同学段、自环和合并既有来源边后的环。正式写入只有在结构无问题时发生，节点仍默认禁用且不会删除任何旧节点或关系。

## 数据库与迁移

`V5__add_knowledge_graph_import.sql` 向前扩展 `knowledge_point`，保留内部 UUID，并增加 `source_system`、`source_know_id`、来源学科/学段、可空 `normalized_stage`、`source_parent_key`、可空 `taxonomy_parent_id`、来源类型/颜色/顺序/节点种类/状态/摘要等字段。唯一键是 `(source_system, source_know_id)`；名称、颜色、Neo4j identity/elementId 和来源 parent key 均不参与去重。

新增表：

- `knowledge_point_prerequisite`：同 Schema 双外键、唯一 `prerequisite_id + dependent_id + relation_type`、禁止自环，业务方向固定为 `PREREQUISITE`。
- `knowledge_graph_import_task`、`knowledge_graph_import_batch`：任务、批次幂等和安全聚合。
- `knowledge_graph_import_node`、`knowledge_graph_import_relation`：结构化 staging，不保存绝对路径或原始 JSON。
- `knowledge_graph_import_issue`：按安全指纹聚合问题，不保存完整来源 ID 列表。

`question_version_knowledge_point` 继续作为不可变题目版本绑定，新增 `priority`、`core_flag`、`exam_point_flag`、`last_exam_point_flag`、`source_tag_sort_index`。来源没有年级字段，因此图谱知识点的 `grade` 可以为空，表示经确认后适用于整个学段；题目校验仅在知识点年级非空时要求单一年级相等。

本任务没有创建数据库；真实验收在既有专用 `edu_platform_learning_dev` 上由 Flyway 向前应用 V5，创建本节列出的图谱任务、staging、问题和关系表并扩展既有表。自动化仍只使用 H2 内存 Schema 和最小合成 Fixture，进程结束自动清理；重复运行测试即可重建。

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

全部 tag 涉及 1,260 个唯一 `knowId`，节点文件中未知 ID 为 0，类型冲突为 0；按原始名称精确比较有 112,873 条 ID/名称/类型完全匹配、679 条名称冲突，且所有完全匹配项的来源学段值均为 `03`。题内重复 `knowId` 共 6,034 条。`03` 是否正式等于初中尚未确认，因此这些统计不是可导入结论，也没有据此填 `normalized_stage`。

`LegacyQuestionJsonParser` 现在分别读取普通题、组合父题和每个子题自己的 tags。服务按 `sourceSystem + knowId` 查平台知识点，不按名称猜测；名称、类型、已确认学段和重复项分别产生 `KNOWLEDGE_POINT_NAME_CONFLICT`、`KNOWLEDGE_POINT_TYPE_CONFLICT`、`KNOWLEDGE_POINT_STAGE_MISMATCH`、`KNOWLEDGE_POINT_DUPLICATE` 等问题码。成功项按确定性顺序使用内部 UUID 创建不可变版本，并保留 priority、sortIndex 和三种 flag。

## 迁移脚本

脚本为 `scripts/Invoke-KnowledgeGraphMigration.ps1`。默认 `DryRun`，按业务 ID 确定性排序，先节点后关系；请求体使用 UTF-8 无 BOM，Token 只读当前进程 `EDU_MIGRATION_ACCESS_TOKEN`。检查点只保存来源/运行指纹、任务 ID、下一批位置和聚合计数，不保存令牌、绝对路径、节点列表或原始 JSON。401/其他 HTTP 失败只报告安全状态，不输出响应体。

示例见 `docs/development/local-environment.md`。正式 Import 必须同时显式给出 `ConfirmImport` 和 stage/status/type/parent 四项语义确认开关；相同参数与检查点可安全恢复。

## 教师端呈现

题目新建/编辑页继续使用既有路由。知识点控件展开后才查询，每页最多 20 项，支持关键词、已确认类型名称筛选、上一页/下一页、已选 Chip 和一层关系摘要；不会渲染 3,671 个 checkbox。颜色不作为类型身份，类型名称未维护时明确显示“待维护”且节点正常情况下不会被启用。原生输入、按钮、details 和分页控件保持键盘可操作，并覆盖 loading、empty、401、403、422、offline 和 5xx 文案；请求在组件卸载时中止。

## 测试与实际结果

- Java 专项：`KnowledgeGraphIntegrationTest` 6/6 通过，覆盖严格 Jackson 请求绑定、DryRun、节点批次、关系方向、幂等、来源 parent 隔离、缺失端点、自环、跨学段、环、默认禁用、分页/有限邻域和 tags 元数据。
- Java 全量 learning-service：33 项执行，31 通过，2 个真实 MySQL 用例按开关跳过；V1/V2/V3/V5 在 H2 MySQL 模式成功迁移。
- Pester：T10B 专项 5/5 通过，覆盖多批次、`b -> a`、服务端直接收到可解析的 UTF-8 中文 JSON、Import 门禁、环、401 脱敏、最小检查点和不直连数据库。真实首轮发现 PowerShell 会把函数返回的 `byte[]` 展开成十进制数字文本；脚本改用 `MemoryStream` 原样发送 UTF-8 无 BOM，并复用白名单安全诊断器只显示状态、统一错误码和字段错误。
- Windows PowerShell 5.1：脚本语法解析通过；合成 HTTP Fixture 实际执行通过。
- OpenAPI 客户端生成和全 workspace typecheck：通过。
- Frontend：18 个测试文件、122 项全部通过；管理、学生、教师三端生产构建通过，仅有既有 hls.js 大 chunk 提示。沙箱内 Vitest/Vite 首次因 `esbuild spawn EPERM` 失败，批准后在沙箱外复跑成功。
- `git diff --check` 通过；完整 Java `clean verify` 的 8 个 Reactor 模块全部成功。`check:api` 在提交前按设计检测到生成文件相对旧 HEAD 的待提交差异，提交后复跑作为最终契约一致性门禁。

## 真实环境状态、数据变化与阻塞

2026-08-19 使用当前提交的临时 IAM/Gateway/Learning 实例和既有专用本地环境完成真实验收；三个实例使用独立 18081/18080/18084 端口，结束后全部停止，未干扰原 8081/8080/8084 实例。`edu_platform_learning_dev` 已由 Flyway 向前迁移到 V5。管理员密码只从当前 Windows 用户的 DPAPI 凭据读取，短期令牌只存在于进程环境并在结束时清除。

首个 V1 DryRun 暴露并修复 PowerShell 字节数组发送缺陷；修复后 V1 完成，但服务端“重复名称”按超额节点计 117，与本地“重复名称组”110 不一致。保留该任务作为审计证据，修正服务端口径并使用新的 `KG_MATH_T10B_V2` 与独立检查点再次 DryRun。V2 最终状态为 `COMPLETED`：节点 3,671、关系 2,107、六类类型、三个学段分布、重复名称组 110、孤立节点 2,267、未解析 parent key 479、最大入度 11、最大出度 19、最长链 21，本地与服务端完全一致；缺失端点、重复边、自环、跨学科、跨学段、环、节点冲突和跳过关系均为 0。安全问题页只有 `SOURCE_PARENT_UNRESOLVED=479`。

真实数据库保留 V1/V2 两个 `dry_run=true` 任务及其节点/关系 staging、安全问题和聚合结果，以便审计和确定性恢复；两个忽略型检查点均为 completed。DryRun 没有创建业务 `knowledge_point`、`knowledge_point_prerequisite` 或题目绑定，也没有修改来源文件。正常管理员登录产生本地 IAM 登录审计/刷新会话记录，已取得的访问令牌均已过期或从进程清除；DPAPI 管理员凭据仍保留在本机用户配置目录供后续确认后使用。

正式知识图谱 Import、新的历史题库 DryRun/Import 均未执行。原因是以下业务语义仍未确认：`status=0` 的平台启用含义、`02/03/04` 的正式学段映射、六类正式名称、479 个来源 parent key 的分类含义、高中无关系是否表示不完整，以及无年级字段是否按整个学段适用。代码原样保存来源值，默认不启用节点。

T10A 的 45 个 DryRun 任务、复核记录和检查点均未查询、删除、重建或修改。后续确认语义后，应先评审 V2 聚合，再决定是否以新正式 SourceSystem 执行 Import 和映射启用；最后使用新的 SourceSystem 与独立检查点重新执行历史题库 DryRun，确认 679 名称冲突和 6,034 重复 tag 的处理结果后才可考虑题目 Import。H2 Fixture 随测试进程销毁；若需要清理本次真实 DryRun，必须按两个精确任务 ID 删除对应 staging/问题/任务，不能清表或影响 T10A。

## 注释覆盖与发布

新增 DTO、Repository、Service、Controller、迁移脚本和 Vue 组件均补充中文职责、输入、结果和失败/写入副作用说明；OpenAPI 与 Flyway 对来源键、关系方向、默认禁用和敏感数据边界有说明；生成客户端未手工补注释。

分支：`codex/t10b-knowledge-graph-import`。PR 未创建，未推送，未合并；关键实现为本交付文档所在提交。
